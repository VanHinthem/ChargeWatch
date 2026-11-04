package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.ndw.OverviewMarker
import org.junit.Assert.*
import org.junit.Test

class OverviewVisualClusteringTest {
    private val cellA=OverviewMarker.Cluster(8,132,84,5.10,51.70,3,2,1,0)
    private val cellB=OverviewMarker.Cluster(8,133,84,5.13,51.73,4,1,2,1)
    private val single=OverviewMarker.Location(8,133,85,5.22,51.76,
        "NL-XYZ-001",0,0,"unknown")

    @Test fun adjacentCellsMakeOneNaturalClusterWithExactWeightedCounts() {
        val groups=clusterOverviewVisually(listOf(
            ProjectedOverviewMarker(cellA,50.0,60.0),
            ProjectedOverviewMarker(cellB,60.0,67.0),
            ProjectedOverviewMarker(single,65.0,72.0)
        ),144.0)
        assertEquals(1,groups.size)
        val cluster=groups.single()
        assertEquals(8,cluster.locationCount)
        assertEquals(3,cluster.locationsWithFree)
        assertEquals(3,cluster.locationsWithNoFree)
        assertEquals(2,cluster.locationsUnknown)
        assertEquals(3,cluster.members.size)
        assertEquals((5.10*3+5.13*4+5.22)/8,cluster.longitude,0.00000001)
        assertEquals((51.70*3+51.73*4+51.76)/8,cluster.latitude,0.00000001)
    }

    @Test fun widelySeparatedLocationsStayIndependent() {
        val results=clusterOverviewVisually(listOf(
            ProjectedOverviewMarker(cellA,10.0,10.0),
            ProjectedOverviewMarker(cellB,400.0,350.0),
            ProjectedOverviewMarker(single,850.0,600.0)
        ),144.0)
        assertEquals(3,results.size)
        assertTrue(results.any { it.isIndividualLocation })
        assertEquals(8,results.sumOf { it.locationCount })
        assertEquals(3,results.sumOf { it.locationsWithFree })
    }

    @Test fun groupingIsStableWhenBackendOrderChanges() {
        val points=listOf(
            ProjectedOverviewMarker(cellA,80.0,60.0),
            ProjectedOverviewMarker(cellB,95.0,61.0),
            ProjectedOverviewMarker(single,800.0,900.0)
        )
        val first=clusterOverviewVisually(points,144.0)
        val second=clusterOverviewVisually(points.reversed(),144.0)
        assertEquals(first.map { it.members.map { p -> p.gridX to p.gridY }.toSet() }.toSet(),
            second.map { it.members.map { p -> p.gridX to p.gridY }.toSet() }.toSet())
    }

    @Test fun extremeZoomCanRetainSeparatelySelectableCanonicalCells() {
        val points=listOf(
            ProjectedOverviewMarker(cellA,10.0,10.0),
            ProjectedOverviewMarker(cellB,11.0,11.0),
            ProjectedOverviewMarker(single,12.0,12.0)
        )
        val result=clusterOverviewVisually(points,144.0,group=false)
        assertEquals(3,result.size)
        assertEquals(8,result.sumOf { it.locationCount })
        assertEquals(1,result.count { it.isIndividualLocation })
    }

    @Test fun coincidentSitesDoNotDisappear() {
        val a=OverviewMarker.Location(15,16490,10801,5.52,51.76,
            "NL-A-1",1,2,"known")
        val b=OverviewMarker.Location(15,16490,10802,5.52,51.76,
            "NL-A-2",0,2,"known")
        val result=clusterOverviewVisually(listOf(
            ProjectedOverviewMarker(a,300.0,300.0),
            ProjectedOverviewMarker(b,300.0,300.0)
        ),144.0)
        assertEquals(1,result.size)
        assertEquals(2,result.single().locationCount)
        assertEquals(1,result.single().locationsWithFree)
        assertEquals(1,result.single().locationsWithNoFree)
    }
}
