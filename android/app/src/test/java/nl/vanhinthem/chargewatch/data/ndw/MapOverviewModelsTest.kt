package nl.vanhinthem.chargewatch.data.ndw

import org.junit.Assert.*
import org.junit.Test

class MapOverviewModelsTest {
    private val sample = """
      {"schemaVersion":1,"filterVersion":1,"dataVersion":null,
       "gridLevel":8,"coverage":{"complete":true,"gridLevel":8,
       "minCellX":131,"minCellY":84,"maxCellX":132,"maxCellY":84},
       "representedLocationCount":4,"feedIsStale":false,"feedLastObservedAt":"2026-11-04T12:00:00Z",
       "markers":[
         {"kind":"cluster","gridLevel":8,"gridX":131,"gridY":84,
         "longitude":4.9,"latitude":51.2,"locationCount":3,
         "locationsWithFree":2,"locationsWithNoFree":1,"locationsUnknown":0},
         {"kind":"location","gridLevel":8,"gridX":132,"gridY":84,
         "longitude":5.5,"latitude":51.7,"featureId":"NL-ABC-item","availableCount":0,
         "totalCount":0,"countQuality":"unknown"}]}
    """.trimIndent()

    @Test fun parsesCompleteAndDisjointClusters() {
        val data = parseOverview(sample)
        assertEquals(4L,data.representedLocationCount)
        assertEquals(2,data.markers.size)
        assertEquals(1,data.individualLocations().size)
        assertEquals("0/0",data.individualLocations().single().availability.label)
        assertEquals(AvailabilityStatus.Unknown,data.individualLocations().single().availability.status)
        val cluster = data.markers.first() as OverviewMarker.Cluster
        assertEquals(3,cluster.locationCount)
        assertEquals(2,cluster.locationsWithFree)
    }

    @Test fun refusesPartialOrIncorrectOverview() {
        assertFails { parseOverview(sample.replace("\"complete\":true","\"complete\":false")) }
        assertFails { parseOverview(sample.replace("\"representedLocationCount\":4","\"representedLocationCount\":3")) }
        assertFails { parseOverview(sample.replace("\"locationsWithNoFree\":1","\"locationsWithNoFree\":2")) }
        assertFails { parseOverview(sample.replace("\"gridX\":132","\"gridX\":140")) }
        assertFails { parseOverview(sample.replace("\"schemaVersion\":1","\"schemaVersion\":2")) }
    }

    @Test fun returnsStableWebMercatorCellBounds() {
        val cell=overviewCellBounds(8,132,84)
        assertTrue(cell.maxLongitude>cell.minLongitude)
        assertTrue(cell.maxLatitude>cell.minLatitude)
        assertEquals(5.625,cell.minLongitude,0.00001)
        assertEquals(7.03125,cell.maxLongitude,0.00001)
    }

    @Test fun parsesBoundedCellPages() {
        val cluster = OverviewMarker.Cluster(8,131,84,4.9,51.2,3,2,1,0)
        val json = """{"gridLevel":8,"gridX":131,"gridY":84,"offset":0,"limit":2,
            "totalCount":3,"hasMore":true,"items":[
            {"featureId":"NL-AAA-1","longitude":4.9,"latitude":51.2,
            "address":"Straat 1","operatorName":null,"availableCount":1,"totalCount":2},
            {"featureId":"NL-AAA-2","longitude":4.9,"latitude":51.2,
            "address":"Straat 2","operatorName":null,"availableCount":0,"totalCount":0}]}"""
        val page=parseOverviewCell(json,cluster,0,2)
        assertEquals(2,page.locations.size)
        assertTrue(page.hasMore)
        assertEquals("Straat 1",page.locations.first().title)
    }


    @Test fun pagesAcrossSeveralPhysicalCellsWithoutLosingLocations() {
        val a = OverviewMarker.Cluster(8,131,84,4.8,51.2,3,2,1,0)
        val b = OverviewMarker.Cluster(8,132,84,5.1,51.2,4,2,1,1)
        val c = OverviewMarker.Location(8,133,84,5.3,51.2,
            "NL-A-1",0,0,"unknown")
        val members=listOf(c,b,a) // intentionally shuffled; sort by canonical identity
        val p0=overviewGroupPageSlices(members,0,5)
        assertEquals(listOf(3,2),p0.map { it.limit })
        assertEquals(listOf(a,b),p0.map { it.marker })
        assertEquals(listOf(0,0),p0.map { it.localOffset })

        val p1=overviewGroupPageSlices(members,5,3)
        assertEquals(listOf(2,1),p1.map { it.limit })
        assertEquals(listOf(b,c),p1.map { it.marker })
        assertEquals(listOf(2,0),p1.map { it.localOffset })
        assertTrue(overviewGroupPageSlices(members,8,3).isEmpty())
    }

    @Test fun refusesRepeatedBackendCellsInGroup() {
        val a=OverviewMarker.Cluster(8,131,84,4.8,51.2,2,1,1,0)
        assertFails { overviewGroupPageSlices(listOf(a,a),0,10) }
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); fail("Expected strict contract validation to fail") }
        catch (_: IllegalArgumentException) {}
        catch (_: IllegalStateException) {}
    }
}
