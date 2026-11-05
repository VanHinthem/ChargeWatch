package nl.vanhinthem.chargewatch.data.favorites

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class FavoriteBatchClientTest {
    private val one="NL-TST-ONE"
    private val missing="NL-TST-MISSING"

    private val detail = """
        {"id":12,"countryCode":"NL","partyId":"TST","sourceId":"ONE",
         "address":"Teststraat 1","operatorName":"ChargeWatch","city":"Oss",
         "latitude":51.765,"longitude":5.525,"isStale":false,
         "feedLastObservedAt":"2026-11-05T09:00:00Z","feedIsStale":false,
         "evses":[{"id":101,"sourceUid":"EVSE-A","status":"AVAILABLE",
             "connectors":[{"id":11,"sourceId":"C1","standard":"IEC_62196_T2",
                 "powerType":"AC_3_PHASE","maxElectricPower":22000,
                 "tariffOptions":[]}]}]}
    """.trimIndent()
    private val statistics = """
        {"locationId":12,"isExperimental":true,
         "probabilityInterpretation":"UNVALIDATED_SNAPSHOT_EWMA",
         "slots":[{"isoWeekday":1,"hour":9,"availableProbability":0.8,"sampleCount":7}],
         "evses":[]}
    """.trimIndent()

    private fun response(id: String = one, history: String = statistics) = """
        {"locations":[
          {"featureId":"$id","status":"FOUND","detail":$detail,"statistics":$history},
          {"featureId":"$missing","status":"NOT_FOUND","detail":null,"statistics":null}
        ]}
    """.trimIndent()

    @Test fun completeBatchProjectsMapAvailabilityDetailsTariffsAndHistory() {
        val parsed=parseFavoriteBatchResponse(response(),listOf(one,missing),includeStatistics=true)
        assertEquals(setOf(one),parsed.locations.keys)
        assertEquals(setOf(missing),parsed.missingIds)
        val location=parsed.locations.getValue(one)
        assertEquals("Teststraat 1",location.mapLocation.title)
        assertEquals("Oss",location.city)
        assertEquals("ChargeWatch",location.mapLocation.operatorName)
        assertEquals("1/1",location.mapLocation.availability.label)
        assertEquals(false,location.mapLocation.feedIsStale)
        assertEquals(1,location.mapLocation.connectorCategories.size)
        assertEquals(1,location.statistics!!.size)
        assertEquals(0.8,location.statistics.single().share,0.0001)
        assertTrue(location.enrichment.statisticsAvailable)
    }

    @Test fun malformedOrIncompleteBatchIsNeverCached() {
        assertThrows(IllegalArgumentException::class.java) {
            parseFavoriteBatchResponse(response(),listOf(missing,one),includeStatistics=true)
        }
        assertThrows(RuntimeException::class.java) {
            parseFavoriteBatchResponse(
                """{"locations":[{"featureId":"$one","status":"FOUND","detail":$detail,"statistics":null}]}""",
                listOf(one),includeStatistics=true
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseFavoriteBatchResponse(response(),listOf(one),includeStatistics=true)
        }
    }

    @Test fun mismatchedNestedIdentityIsRejected() {
        val wrongDetail=detail.replace("\"sourceId\":\"ONE\"",
            "\"sourceId\":\"WRONG\"")
        assertThrows(IllegalArgumentException::class.java) {
            parseFavoriteBatchResponse(
                """{"locations":[{"featureId":"$one","status":"FOUND",
                   "detail":$wrongDetail,"statistics":$statistics}]}""",
                listOf(one),includeStatistics=true
            )
        }
        val wrongStatistics=statistics.replace("\"locationId\":12", "\"locationId\":77")
        assertThrows(IllegalArgumentException::class.java) {
            parseFavoriteBatchResponse(
                """{"locations":[{"featureId":"$one","status":"FOUND",
                   "detail":$detail,"statistics":$wrongStatistics}]}""",
                listOf(one),includeStatistics=true
            )
        }
    }

    @Test fun onlyIdsAreSentToBatchAndHistoryIsOptIn() = runBlocking {
        val requests=mutableListOf<String>()
        val bodies=mutableListOf<String>()
        val http=OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req=chain.request()
            requests+=req.method+" "+req.url.encodedPath
            val buffer=okio.Buffer()
            req.body?.writeTo(buffer)
            bodies+=buffer.readUtf8()
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(response(history="null").toResponseBody()).build()
        }).build()
        val result=FavoriteBatchClient("https://chargewatch.test",http).load(listOf(one,missing))
        assertTrue(result is FavoriteBatchResult.Success)
        assertEquals(listOf("POST /api/v1/locations/by-ndw-id/batch"),requests)
        assertEquals(true,bodies.single().contains("\"includeStatistics\":false"))
        assertEquals(true,bodies.single().contains("\"featureIds\""))
        assertFalse(bodies.single().contains("Teststraat"))
        assertFalse(bodies.single().contains("alias"))
    }

    @Test fun defaultBatchAcceptsNullHistoryAndDoesNotSetHistoryAvailable() {
        val parsed=parseFavoriteBatchResponse(
            response(history="null"),
            listOf(one,missing)
        )
        val value=parsed.locations.getValue(one)
        assertNull(value.statistics)
        assertFalse(value.enrichment.statisticsAvailable)
        assertEquals("1/1",value.mapLocation.availability.label)
    }

    @Test fun explicitHistoryStillRequestsAndParsesCompleteStatistics() = runBlocking {
        val bodies=mutableListOf<String>()
        val http=OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val buffer=okio.Buffer()
            chain.request().body?.writeTo(buffer)
            bodies+=buffer.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(response().toResponseBody()).build()
        }).build()
        val result=FavoriteBatchClient("https://chargewatch.test",http)
            .load(listOf(one,missing),includeStatistics=true)
        assertTrue(result is FavoriteBatchResult.Success)
        assertEquals(1,(result as FavoriteBatchResult.Success).locations.getValue(one).statistics!!.size)
        assertTrue(bodies.single().contains("\"includeStatistics\":true"))
    }

    @Test fun oldApiReturnsExplicitUnavailableInsteadOfCallingViewports() = runBlocking {
        val http=OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(404).message("Not found").body("{}".toResponseBody()).build()
        }).build()
        val result=FavoriteBatchClient("https://chargewatch.test",http).load(listOf(one))
        assertTrue(result is FavoriteBatchResult.Unavailable)
    }
}
