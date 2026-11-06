package nl.vanhinthem.chargewatch.data.enrichment

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LocationEnrichmentLazyStatisticsTest {
    private val id = "NL-AAA-1"
    private val detailPath = "/api/v1/locations/by-ndw-id/${id}"
    private val statsPath = "/api/v1/locations/by-ndw-id/${id}/statistics"

    private fun client(requests: MutableList<String>, failStatistics: Boolean = false):
        LocationEnrichmentClient {
        val interceptor = Interceptor { chain ->
            val path = chain.request().url.encodedPath
            requests += path
            val isHistory = path.endsWith("/statistics")
            val json = if (isHistory) {
                """{"isExperimental":true,"probabilityInterpretation":"UNVALIDATED_SNAPSHOT_EWMA",
                "slots":[{"isoWeekday":1,"hour":10,"availableProbability":0.65,"sampleCount":7}]}"""
            } else {
                """{"evses":[{"connectors":[]}]}"""
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(if (isHistory && failStatistics) 503 else 200)
                .message(if (isHistory && failStatistics) "Unavailable" else "OK")
                .body(json.toResponseBody())
                .build()
        }
        return LocationEnrichmentClient(
            baseUrl = "https://chargewatch.test",
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
    }

    @Test fun compactDetailsNeverFetchHistoryImplicitly() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client(requests)
        val detail = client.load(id)
        assertTrue(detail is EnrichmentResult.Found)
        assertEquals(listOf(detailPath), requests)

        val history = client.loadStatistics(id)
        assertNotNull(history)
        assertEquals(1,history!!.size)
        assertEquals(listOf(detailPath,statsPath),requests)
        assertEquals(0.65,history.single().share!!,0.0001)
    }

    @Test fun historyFailureNeverInvalidatesSuccessfullyLoadedDetail() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client(requests,failStatistics=true)
        assertTrue(client.load(id) is EnrichmentResult.Found)
        assertNull(client.loadStatistics(id))
        assertEquals(listOf(detailPath,statsPath),requests)
    }

    @Test fun invalidIdDoesNotInitiateAnyNetworkRequest() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client(requests)
        assertEquals(EnrichmentResult.NotFound,client.load("invalid id"))
        assertNull(client.loadStatistics("invalid id"))
        assertTrue(requests.isEmpty())
    }
}
