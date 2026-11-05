package nl.vanhinthem.chargewatch.data.enrichment

import kotlinx.coroutines.runBlocking
import nl.vanhinthem.chargewatch.data.favorites.FavoriteBatchClient
import nl.vanhinthem.chargewatch.data.favorites.FavoriteBatchResult
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LocationDetailsCacheTest {
    private val id = "NL-TST-ONE"
    private val detail = """
        {"id":12,"countryCode":"NL","partyId":"TST","sourceId":"ONE",
         "address":"Teststraat 1","city":"Oss","latitude":51.765,"longitude":5.525,
         "evses":[{"id":101,"sourceUid":"E1","status":"AVAILABLE",
                   "connectors":[{"id":201,"sourceId":"C1","standard":"IEC_62196_T2",
                       "powerType":"AC_3_PHASE","maxElectricPower":22000,
                       "tariffOptions":[]}]}]}
    """.trimIndent()
    private val batch = """
        {"locations":[{"featureId":"NL-TST-ONE","status":"FOUND",
            "detail":$detail,"statistics":null}]}
    """.trimIndent()
    private val statistics = """
        {"locationId":12,"slots":[{"isoWeekday":1,"hour":10,
             "availableProbability":0.7,"sampleCount":9}],"evses":[]}
    """.trimIndent()

    @Test fun detailsAndHistoryHaveIndependentFreshnessAndShareAcrossEntryPoints() = runBlocking {
        var clock = Instant.parse("2026-11-05T09:00:00Z")
        val requests = mutableListOf<Pair<String,String>>()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            requests += request.url.encodedPath to buffer.readUtf8()
            val body = if (request.url.encodedPath.endsWith("/statistics")) statistics else batch
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }).build()
        val cache = LocationDetailsCache(
            FavoriteBatchClient("https://chargewatch.test",http),
            LocationEnrichmentClient("https://chargewatch.test",http),
            now = { clock }
        )
        val first=cache.loadDetails(listOf(id))
        assertTrue(first is FavoriteBatchResult.Success)
        assertEquals("1/1",cache.peekDetails(id)?.mapLocation?.availability?.label)
        assertNull(cache.peekHistory(id))
        assertTrue(requests.single().second.contains("\"includeStatistics\":false"))

        cache.loadDetails(listOf(id))
        assertEquals(1,requests.size)
        assertEquals(1,cache.loadHistory(id)!!.size)
        assertEquals(1,cache.loadHistory(id)!!.size)
        assertEquals(2,requests.size)

        clock = clock.plusSeconds(119)
        assertNotNull(cache.peekDetails(id))
        assertNotNull(cache.peekHistory(id))
        clock = clock.plusSeconds(2)
        assertNull(cache.peekDetails(id))
        assertNotNull(cache.peekHistory(id))
        cache.loadDetails(listOf(id))
        assertEquals(3,requests.size)
        assertNotNull(cache.peekHistory(id))

        clock = clock.plusSeconds(15*60)
        assertNull(cache.peekHistory(id))
        assertEquals(1,cache.loadHistory(id)!!.size)
        assertEquals(4,requests.size)
        assertEquals(2,requests.count { it.first.endsWith("/statistics") })
    }

    @Test fun forceRefreshBypassesDetailTtlButNotHistoryTtl() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(batch.toResponseBody()).build()
        }).build()
        val cache = LocationDetailsCache(FavoriteBatchClient("https://chargewatch.test",http))
        cache.loadDetails(listOf(id))
        cache.loadDetails(listOf(id))
        assertEquals(1,calls)
        cache.loadDetails(listOf(id),force=true)
        assertEquals(2,calls)
    }

    @Test fun missingLocationIsCachedNegativelyAndFailureIsRetried() = runBlocking {
        var calls = 0
        var fail = true
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            calls++
            val body = """{"locations":[{"featureId":"NL-TST-ONE",
                "status":"NOT_FOUND","detail":null,"statistics":null}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (fail) 503 else 200).message("test")
                .body(body.toResponseBody()).build()
        }).build()
        val cache = LocationDetailsCache(FavoriteBatchClient("https://chargewatch.test",http))
        assertTrue(cache.loadDetails(listOf(id)) is FavoriteBatchResult.Unavailable)
        fail = false
        val success = cache.loadDetails(listOf(id))
        assertTrue(success is FavoriteBatchResult.Success)
        assertEquals(setOf(id),(success as FavoriteBatchResult.Success).missingIds)
        cache.loadDetails(listOf(id))
        assertEquals(2,calls)
    }
}
