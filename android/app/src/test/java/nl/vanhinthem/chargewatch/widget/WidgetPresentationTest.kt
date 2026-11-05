package nl.vanhinthem.chargewatch.widget

import nl.vanhinthem.chargewatch.data.favorites.FavoriteLocation
import nl.vanhinthem.chargewatch.data.ndw.AvailabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WidgetPresentationTest {
    private val now = Instant.parse("2026-11-02T15:00:00Z").toEpochMilli()
    private fun snapshot(free: Int?, total: Int?, reliability: String = "Safe",
                         observed: String? = "2026-11-02T14:59:00Z",
                         sourceStale: Boolean? = false,
                         fetched: Long = now - 30_000) =
        WidgetSnapshot(free, total, reliability, observed, sourceStale, fetched)

    @Test fun validCountWithFreeEvseIsGreen() {
        val result = widgetRow("a", "Thuis", snapshot(2, 4), now - 30_000, now)
        assertEquals("2/4", result.label)
        assertEquals(AvailabilityStatus.Available, result.status)
        assertFalse(result.isLastKnown)
    }

    @Test fun validZeroAvailableIsRed() {
        val result = widgetRow("a", "Werk", snapshot(0, 3), now - 30_000, now)
        assertEquals("0/3", result.label)
        assertEquals(AvailabilityStatus.NoneAvailable, result.status)
    }

    @Test fun staleFeedRetainsKnownColorButIsMarkedLastKnown() {
        val result = widgetRow("a", "Werk",
            snapshot(1, 2, sourceStale = true), now - 30_000, now)
        assertEquals("1/2", result.label)
        assertEquals(AvailabilityStatus.Available, result.status)
        assertTrue(result.isLastKnown)
        assertTrue(result.freshness.contains("Laatst bekend"))
    }

    @Test fun ambiguousOrInvalidCountIsQuestionMark() {
        listOf(snapshot(null, null), snapshot(3, 2),
            snapshot(1, 2, reliability = "Ambiguous"), snapshot(0, 0)).forEach {
            val result = widgetRow("a", "Unknown", it, now - 30_000, now)
            assertEquals("?", result.label)
            assertEquals(AvailabilityStatus.Unknown, result.status)
        }
    }

    @Test fun partialRefreshDoesNotMakeOldCacheCurrent() {
        val lastFetch = now - 600_000
        val result = widgetRow("a", "Thuis",
            snapshot(2, 2, fetched = lastFetch), now, now)
        assertEquals("2/2", result.label)
        assertTrue(result.isLastKnown)
    }

    @Test fun missingTimestampCannotImplyFreshFeed() {
        val result = widgetRow("a", "Thuis",
            snapshot(2, 2, observed = null), now - 30_000, now)
        assertTrue(result.isLastKnown)
        assertEquals("2/2", result.label)
    }

    @Test fun responsiveLayoutPrioritizesLargerTextWithoutClippingRows() {
        assertEquals(1, visibleWidgetRows(150f, 140f))
        assertEquals(2, visibleWidgetRows(285f, 140f))
        assertEquals(3, visibleWidgetRows(285f, 215f))
        assertEquals(4, visibleWidgetRows(285f, 270f))
        assertEquals(5, visibleWidgetRows(285f, 320f))
    }

    @Test fun narrowTwoByTwoKeepsOneLegibleFavoriteEvenIfSeveralAreSelected() {
        assertEquals(1, visibleWidgetRows(150f, 140f))
        assertEquals(1, visibleWidgetRows(180f, 190f))
        assertEquals(1, visibleWidgetRows(220f, 320f))
        assertEquals(3, visibleWidgetRows(285f, 215f))
        // In the square layout the remaining selected favorites are
        // represented by +N meer instead of unreadably truncated names.
        val selected = listOf("Thuis", "Werk", "Familie")
        assertEquals(2, selected.size - visibleWidgetRows(150f, 140f))
    }

    @Test fun tinyOneUiSquareMustNotChooseWideSingleRowLayout() {
        // One UI may offer a 2x2 widget smaller than the previous 150x140dp
        // size. The new registered 100x62dp variant needs its own layout.
        assertTrue(isTinySquareWidget(100f, 62f))
        assertTrue(isTinySquareWidget(115f, 70f))
        assertFalse(isSingleRowWidget(100f, 62f))
        assertFalse(isTinySquareWidget(260f, 62f))
        assertTrue(isSingleRowWidget(260f, 62f))
        assertFalse(isTinySquareWidget(150f, 140f))
        assertFalse(isTinySquareWidget(285f, 140f))
    }

    @Test fun singleRowWidgetIsOnlyUsedForWideLowLauncherSizes() {
        assertTrue(isSingleRowWidget(260f, 62f))
        assertTrue(isSingleRowWidget(285f, 78f))
        assertFalse(isSingleRowWidget(285f, 140f))
        assertFalse(isSingleRowWidget(150f, 62f))
        assertEquals(1, visibleWidgetRows(260f, 62f))
        assertEquals(2, visibleWidgetRows(285f, 140f))
    }

    @Test fun compactWidgetAlwaysDistinguishesUnknownFromLastKnown() {
        val recent = widgetRow("a", "Thuis", snapshot(2, 2), now - 30_000, now)
        assertEquals("1 min", compactWidgetFreshness(recent))
        val lastKnown = widgetRow("a", "Thuis",
            snapshot(2, 2, fetched = now - 3_600_000), now, now)
        assertEquals("Laatst bekend", compactWidgetFreshness(lastKnown))
        assertEquals("Onbekend", compactWidgetFreshness(widgetRow("a", "Thuis", null, now, now)))
    }

    @Test fun narrowWidgetShowsActualCityButNeverGuessesStreet() {
        val stored = widgetRow("a", "Thuis", snapshot(2, 2), now - 30_000, now,
            subtitle = "Schietspoel 18, Oss", city = "Oss")
        assertEquals("Oss", compactWidgetSubtitle(stored))
        val missingCity = widgetRow("b", "Werk", snapshot(0, 2), now - 30_000, now,
            subtitle = "Mystraat 42")
        assertEquals(null, compactWidgetSubtitle(missingCity))
    }

    @Test fun wideHeaderShowsBriefAgeInsteadOfReservingFooterSpace() {
        val recent = widgetRow("a", "Thuis", snapshot(2, 2), now - 30_000, now)
        assertEquals("1 min geleden", widgetHeaderFreshness(listOf(recent), false))
        assertEquals("Verversen…", widgetHeaderFreshness(listOf(recent), true))
        assertEquals("", widgetHeaderFreshness(emptyList(), false))
    }

    @Test fun wideHeaderNeverMisrepresentsOldOrMixedAvailability() {
        val recent = widgetRow("a", "Thuis", snapshot(2, 2), now - 30_000, now)
        val old = widgetRow("b", "Werk",
            snapshot(0, 2, fetched = now - 600_000), now, now)
        val unknown = widgetRow("c", "Onbekend", null, now, now)
        val anotherTime = widgetRow("d", "Andere bron",
            snapshot(1, 2, observed = "2026-11-02T14:55:00Z"), now - 30_000, now)
        assertEquals("Laatst bekend", widgetHeaderFreshness(listOf(old), false))
        assertEquals("Laatst bekend", widgetHeaderFreshness(listOf(recent, old), false))
        assertEquals("Deels onbekend", widgetHeaderFreshness(listOf(recent, unknown), false))
        assertEquals("Onbekend", widgetHeaderFreshness(listOf(unknown), false))
        assertEquals("Bronmoment verschilt",
            widgetHeaderFreshness(listOf(recent, anotherTime), false))
    }

    @Test fun narrowWidgetFooterDoesNotHideUnavailableOrStaleData() {
        val recent = widgetRow("a", "Thuis", snapshot(2, 2), now - 30_000, now)
        val unknown = widgetRow("b", "Werk", null, now, now)
        assertEquals("1 min", compactSquareFooter(listOf(recent)))
        assertEquals("Deels onbekend", compactSquareFooter(listOf(recent, unknown)))
        assertEquals("Onbekend", compactSquareFooter(listOf(unknown)))
        val stale = widgetRow("c", "Familie", snapshot(1, 2, fetched = now - 600_000), now, now)
        assertEquals("Laatst bekend", compactSquareFooter(listOf(recent, stale)))
    }

    @Test fun manualRefreshDoesNotWaitForPeriodicSixtySecondThrottle() {
        assertTrue(shouldFetchWidgetAvailability(
            nowMillis = now, previousAttemptMillis = now - 3_000,
            force = true, needsInitialData = false))
        assertFalse(shouldFetchWidgetAvailability(
            nowMillis = now, previousAttemptMillis = now - 3_000,
            force = false, needsInitialData = false))
    }

    @Test fun newlySelectedFavoriteFetchesImmediatelyRegardlessOfOtherWidget() {
        assertTrue(shouldFetchWidgetAvailability(
            nowMillis = now, previousAttemptMillis = now - 3_000,
            force = false, needsInitialData = true))
        assertTrue(shouldFetchWidgetAvailability(
            nowMillis = now, previousAttemptMillis = 0L,
            force = false, needsInitialData = false))
    }

    @Test fun repeatedRapidTapsDoNotTriggerConcurrentExtraNetworkRequests() {
        assertFalse(shouldFetchWidgetAvailability(
            nowMillis = now, previousAttemptMillis = now - 300,
            force = true, needsInitialData = true))
        assertTrue(shouldFetchWidgetAvailability(
            nowMillis = now, previousAttemptMillis = now - 60_000,
            force = false, needsInitialData = false))
    }

    @Test fun loadingStateExpiresIfWorkerWasKilled() {
        val running = WidgetState(refreshingSinceMillis = now - 1_000)
        val interrupted = WidgetState(refreshingSinceMillis = now - 60_000)
        assertTrue(isWidgetRefreshing(running, now))
        assertFalse(isWidgetRefreshing(interrupted, now))
        assertFalse(isWidgetRefreshing(WidgetState(), now))
    }

    @Test fun cityIsTakenFromSavedAddressNotInferredFromCoordinates() {
        val home = favorite(alias = "Thuis", address = "Schietspoel 18, Oss")
        assertEquals("Thuis", widgetFavoriteName(home))
        assertEquals("Oss", widgetFavoriteCity(home))
        assertEquals("Schietspoel 18, Oss", widgetRow("a", widgetFavoriteName(home),
            snapshot(2, 2), now - 30_000, now, widgetFavoriteSubtitle(home)).subtitle)
    }

    @Test fun cityIsNotDuplicatedWhenNoAliasExists() {
        val street = favorite(alias = null, address = "Koetseland 4, Oss")
        assertEquals("Koetseland 4", widgetFavoriteName(street))
        assertEquals("Oss", widgetFavoriteCity(street))
    }

    @Test fun missingCityIsOmittedRatherThanGuessed() {
        val street = favorite(alias = "Thuis", address = "Schietspoel 18")
        assertEquals(null, widgetFavoriteCity(street))
        assertEquals("Thuis", widgetFavoriteName(street))
    }

    @Test fun postalCodeAndCountryAreNotPresentedAsCity() {
        val street = favorite(alias = null, address = "Schietspoel 18, 5343 AB Oss, Nederland")
        assertEquals("Oss", widgetFavoriteCity(street))
    }

    @Test fun aliasedFavoriteShowsAddressAndCityInSubtitle() {
        val item = favorite("Thuis", "Schietspoel 18")
        val metadata = WidgetAddress("Schietspoel 18", "Oss")
        assertEquals("Thuis", widgetFavoriteName(item, metadata))
        assertEquals("Schietspoel 18, Oss", widgetFavoriteSubtitle(item, metadata))
    }

    @Test fun favoriteWithoutAliasShowsStreetAndOnlyCityUnderneath() {
        val item = favorite(null, "Koetseland 4")
        val metadata = WidgetAddress("Koetseland 4", "Oss")
        assertEquals("Koetseland 4", widgetFavoriteName(item, metadata))
        assertEquals("Oss", widgetFavoriteSubtitle(item, metadata))
    }

    @Test fun addressAlreadyContainingCityIsNotDuplicated() {
        val item = favorite("Thuis", "Schietspoel 18, Oss")
        val metadata = WidgetAddress("Schietspoel 18, Oss", "Oss")
        assertEquals("Schietspoel 18, Oss", widgetFavoriteSubtitle(item, metadata))
    }

    @Test fun absentCityFallsBackToSavedStreetOnly() {
        val item = favorite("Thuis", "Schietspoel 18")
        assertEquals("Schietspoel 18", widgetFavoriteSubtitle(item))
        assertEquals(null, widgetFavoriteSubtitle(favorite(null, "Schietspoel 18")))
    }

    @Test fun reliableSnapshotPredicateRejectsUnknownOrMalformedCounts() {
        assertTrue(snapshot(2, 2).hasReliableCounts())
        assertTrue(snapshot(0, 2).hasReliableCounts())
        assertFalse(snapshot(2, 2, reliability = "Ambiguous").hasReliableCounts())
        assertFalse(snapshot(4, 2).hasReliableCounts())
        assertFalse(snapshot(null, 2).hasReliableCounts())
        assertFalse(snapshot(0, 0).hasReliableCounts())
    }

    @Test fun unknownNewResponsePreservesPriorGreenOrRedCounts() {
        val green = snapshot(2, 2, fetched = now - 600_000)
        val red = snapshot(0, 2, fetched = now - 600_000)
        val unknown = snapshot(null, null, reliability = "Ambiguous")
        assertEquals(green, mergeWidgetSnapshot(green, unknown))
        assertEquals(red, mergeWidgetSnapshot(red, unknown))
        assertTrue(widgetRow("a", "Thuis", mergeWidgetSnapshot(green, unknown), now, now)
            .isLastKnown)
    }

    @Test fun validNewResponseReplacesOldCacheWithoutInventingFreePorts() {
        val older = snapshot(2, 4, fetched = now - 600_000)
        val fresh = snapshot(0, 4)
        assertEquals(fresh, mergeWidgetSnapshot(older, fresh))
        assertEquals("0/4", widgetRow("a", "Werk", fresh, now - 30_000, now).label)
        assertEquals("?", widgetRow("a", "Werk",
            mergeWidgetSnapshot(null, snapshot(null, 2)), now, now).label)
    }

    @Test fun unknownCountsAreNotMislabelledAsLastKnown() {
        val unknown = widgetRow("a", "Thuis", null, now, now)
        assertEquals("Beschikbaarheid onbekend", widgetFooter(listOf(unknown)))
        val green = widgetRow("b", "Werk", snapshot(2, 2), now - 30_000, now)
        assertEquals("Niet alle aantallen betrouwbaar", widgetFooter(listOf(unknown, green)))
        val stale = widgetRow("c", "Familie", snapshot(2, 4, fetched = now - 600_000), now, now)
        assertEquals("Deels verouderd · laatst bekend", widgetFooter(listOf(stale)))
    }

    @Test fun addressMetadataIsRateLimitedAfterFailedAttempt() {
        assertTrue(shouldFetchWidgetAddress(null, null, now))
        assertFalse(shouldFetchWidgetAddress(null, now - 30_000, now))
        assertTrue(shouldFetchWidgetAddress(null, now - 3_600_000, now))
        assertFalse(shouldFetchWidgetAddress(WidgetAddress("Koetseland 4", "Oss"), null, now))
    }

    @Test fun batchDetailsProvideCanonicalAddressAndCityWithoutSecondaryApiCall() {
        val location = nl.vanhinthem.chargewatch.data.ndw.MapLocation(
            featureId = "NL-TST-ONE", longitude = 5.53, latitude = 51.76,
            title = "Koetseland 4", lastUpdated = null,
            availability = nl.vanhinthem.chargewatch.data.ndw.AvailabilitySummary(
                2, 2, nl.vanhinthem.chargewatch.data.ndw.CountReliability.Safe),
            address = "Koetseland 4"
        )
        assertEquals(WidgetAddress("Koetseland 4", "Oss"), widgetBatchAddress(location, "Oss"))
        assertEquals(null, widgetBatchAddress(location, null))
        assertEquals(null, widgetBatchAddress(location, ""))
        assertEquals(null, widgetBatchAddress(location.copy(address = null), "Oss"))
    }

    private fun favorite(alias: String?, address: String) = FavoriteLocation(
        featureId = "NL-NUO-A", title = address, latitude = 51.76,
        longitude = 5.53, address = address, alias = alias
    )
}
