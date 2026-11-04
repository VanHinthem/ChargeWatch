package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.ndw.AvailabilitySummary
import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus
import nl.vanhinthem.chargewatch.data.ndw.CountReliability
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import nl.vanhinthem.chargewatch.data.ndw.OverviewMarker
import org.junit.Assert.*
import org.junit.Test

/** Both data routes must have identical visual and interaction semantics. */
class ConsistentMapClusteringTest {
    private fun location(
        id: String, lon: Double, lat: Double, available: Int, total: Int,
        reliability: CountReliability = CountReliability.Safe
    ) = MapLocation(
        id,lon,lat,"Straat $id",null,
        AvailabilitySummary(available,total,reliability)
    )

    private val sites = listOf(
        location("NL-A-1",5.510,51.75,2,4),
        location("NL-A-2",5.511,51.75,0,3),
        location("NL-A-3",5.512,51.75,0,0),
        location("NL-A-4",5.513,51.75,1,2,CountReliability.Ambiguous)
    )

    @Test fun localClusterShowsNumberOfSitesNotSumOfPlugs() {
        val clusters = clusterLocationsVisually(sites.mapIndexed { i, site ->
            ProjectedLocationMarker(site,100.0+i*5,210.0+i*6)
        },144.0)
        val group = clusters.single()
        assertEquals(4,group.locationCount) // NOT e.g. '3/9'
        assertEquals(1,group.locationsWithFree)
        assertEquals(1,group.locationsWithNoFree)
        assertEquals(2,group.locationsUnknown)
        assertEquals(4,group.members.size)
        assertEquals(AvailabilityStatus.Unknown,sites.last().availability.status)
    }

    @Test fun sameInputProducesSameGroupsFromLocalAndOverview() {
        val sitePoints=sites.mapIndexed { i, site ->
            ProjectedLocationMarker(site,100.0+i*5,210.0+i*6)
        }
        val overviewPoints=sites.mapIndexed { i, site ->
            val marker=OverviewMarker.Location(
                gridLevel=15,gridX=16500+i.toLong(),gridY=10740,
                longitude=site.longitude,latitude=site.latitude,
                featureId=site.featureId,
                availableCount=if (site.availability.status==AvailabilityStatus.Available) 1 else 0,
                totalCount=if (site.availability.status==AvailabilityStatus.Unknown) 0 else 1,
                countQuality=if (site.availability.status==AvailabilityStatus.Unknown) "unknown" else "known"
            )
            ProjectedOverviewMarker(marker,100.0+i*5,210.0+i*6)
        }
        val local=clusterLocationsVisually(sitePoints,144.0).single()
        val remote=clusterOverviewVisually(overviewPoints,144.0).single()
        assertEquals(local.locationCount,remote.locationCount)
        assertEquals(local.locationsWithFree,remote.locationsWithFree)
        assertEquals(local.locationsWithNoFree,remote.locationsWithNoFree)
        assertEquals(local.locationsUnknown,remote.locationsUnknown)
        assertEquals(local.longitude,remote.longitude,1e-9)
        assertEquals(local.latitude,remote.latitude,1e-9)
    }

    @Test fun separateSitesRemainIndividualWithOriginalFreeTotalLabels() {
        val original=sites[0]
        val groups=clusterLocationsVisually(listOf(ProjectedLocationMarker(original,10.0,10.0)),144.0)
        assertEquals(1,groups.single().locationCount)
        assertEquals("2/4",groups.single().members.single().availability.label)
        assertEquals(1,groups.single().locationsWithFree)
    }

    @Test fun localClusterAndNationalClusterHaveSameTapPolicy() {
        // Both MapLibre click paths use this policy with the same max zoom.
        assertEquals(MarkerTapAction.ZoomCluster,markerTapAction(4,12.0,18.0))
        assertEquals(MarkerTapAction.ZoomCluster,markerTapAction(4,17.0,18.0))
        assertEquals(MarkerTapAction.SelectClusterLocations,markerTapAction(4,18.0,18.0))
        assertEquals(MarkerTapAction.SelectLocation,markerTapAction(1,18.0,18.0))
    }

    @Test fun localGroupingIsDeterministicAcrossInputOrder() {
        val points=sites.mapIndexed { i, site ->
            ProjectedLocationMarker(site,80.0+i*3,120.0+i*4)
        } + listOf(
            ProjectedLocationMarker(location("NL-A-5",5.8,51.9,1,2),800.0,900.0)
        )
        val expected=clusterLocationsVisually(points,144.0)
        val actual=clusterLocationsVisually(points.reversed(),144.0)
        assertEquals(
            expected.map { it.members.map(MapLocation::featureId).toSet() }.toSet(),
            actual.map { it.members.map(MapLocation::featureId).toSet() }.toSet()
        )
        assertEquals(5,actual.sumOf { it.locationCount })
    }
}
