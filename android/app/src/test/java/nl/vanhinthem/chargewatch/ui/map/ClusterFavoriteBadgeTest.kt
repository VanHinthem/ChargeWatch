package nl.vanhinthem.chargewatch.ui.map

import nl.vanhinthem.chargewatch.data.favorites.FavoriteLocation
import nl.vanhinthem.chargewatch.data.ndw.AvailabilitySummary
import nl.vanhinthem.chargewatch.data.ndw.CountReliability
import nl.vanhinthem.chargewatch.data.ndw.MapLocation
import nl.vanhinthem.chargewatch.data.ndw.OverviewMarker
import org.junit.Assert.*
import org.junit.Test

class ClusterFavoriteBadgeTest {
    private val saved = FavoriteLocation(
        featureId="NL-AAA-fav",title="Favoriet Oss",latitude=51.765,longitude=5.525
    )
    private val other = FavoriteLocation(
        featureId="NL-BBB-fav",title="Favoriet elders",latitude=52.1,longitude=6.4
    )

    @Test fun canonicalWebMercatorCellMatchesBackendOssExample() {
        assertEquals(131L to 84L,favoriteGridCell(5.525,51.765,8))
        assertEquals(527L to 339L,favoriteGridCell(5.525,51.765,10))
        assertEquals(16886L to 10858L,favoriteGridCell(5.525,51.765,15))
        assertEquals(0L to 0L,favoriteGridCell(0.0,0.0,0))
        assertNull(favoriteGridCell(5.5,90.0,8))
        assertNull(favoriteGridCell(Double.NaN,51.765,8))
        assertNull(favoriteGridCell(5.525,51.765,23))
    }

    @Test fun localClusterMarksWhenAnySiteIsFavorite() {
        val siteA=MapLocation(
            "NL-AAA-fav",5.525,51.765,"A",null,
            AvailabilitySummary(1,2,CountReliability.Safe)
        )
        val siteB=siteA.copy(featureId="NL-NOT-A-FAV",longitude=5.526)
        val local=clusterLocationsVisually(listOf(
            ProjectedLocationMarker(siteA,100.0,100.0),
            ProjectedLocationMarker(siteB,102.0,101.0)
        ),144.0).single()
        assertEquals(2,local.locationCount)
        assertTrue(localGroupHasFavorite(local,setOf(saved.featureId)))
        assertFalse(localGroupHasFavorite(local,setOf(other.featureId)))
        assertFalse(localGroupHasFavorite(local,emptySet()))
    }

    @Test fun nationalAggregateMarksSavedFavoriteInUnderlyingCell() {
        val aggregate=OverviewMarker.Cluster(8,131,84,5.5,51.7,7,5,2,0)
        val visual=clusterOverviewVisually(listOf(
            ProjectedOverviewMarker(aggregate,100.0,100.0)
        ),144.0).single()
        assertEquals(7,visual.locationCount)
        assertTrue(overviewGroupHasFavorite(visual,listOf(saved),false))
        assertFalse(overviewGroupHasFavorite(visual,listOf(other),false))
        assertFalse(overviewGroupHasFavorite(visual,emptyList(),false))
    }

    @Test fun favoritesInOtherBackendCellsOfSameVisualGroupAlsoCount() {
        val a=OverviewMarker.Cluster(8,130,84,4.6,51.7,3,1,1,1)
        val b=OverviewMarker.Cluster(8,131,84,5.5,51.7,7,5,2,0)
        val visual=clusterOverviewVisually(listOf(
            ProjectedOverviewMarker(a,100.0,100.0),
            ProjectedOverviewMarker(b,102.0,101.0)
        ),144.0).single()
        assertTrue(overviewGroupHasFavorite(visual,listOf(saved),false))
        assertEquals(10,visual.locationCount)
    }

    @Test fun filteredOverviewNeverClaimsUnverifiedAggregateFavorite() {
        val aggregate=OverviewMarker.Cluster(8,131,84,5.5,51.7,7,5,2,0)
        val group=clusterOverviewVisually(listOf(
            ProjectedOverviewMarker(aggregate,100.0,100.0)
        ),144.0).single()
        assertFalse(overviewGroupHasFavorite(group,listOf(saved),true))
    }

    @Test fun exactSingletonIdentityIsValidEvenWithFilters() {
        val singleton=OverviewMarker.Location(8,131,84,5.525,51.765,
            "NL-AAA-fav",1,2,"known")
        val group=clusterOverviewVisually(listOf(
            ProjectedOverviewMarker(singleton,100.0,100.0)
        ),144.0).single()
        assertTrue(overviewGroupHasFavorite(group,listOf(saved),true))
        assertFalse(overviewGroupHasFavorite(group,listOf(other),true))
    }
}
