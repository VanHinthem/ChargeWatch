package nl.vanhinthem.chargewatch.data.favorites

import org.junit.Assert.*
import org.junit.Test

class FavoriteLimitPolicyTest {
    @Test fun upToTwentyFavoritesCanBeAdded() {
        val nineteen=(1..19).map {
            FavoriteLocation("NL-TST-$it","Test $it",51.7,5.5)
        }
        assertTrue(mayToggleFavorite(nineteen,"NL-TST-20"))
    }

    @Test fun twentyFavoritesRejectNewAdditionButAllowRemoval() {
        val twenty=(1..20).map {
            FavoriteLocation("NL-TST-$it","Test $it",51.7,5.5)
        }
        assertFalse(mayToggleFavorite(twenty,"NL-TST-21"))
        assertTrue(mayToggleFavorite(twenty,"NL-TST-10"))
    }

    @Test fun previouslySavedMoreThanTwentyAreNotDeletedAndCanBeRemoved() {
        val existing=(1..25).map {
            FavoriteLocation("NL-TST-$it","Test $it",51.7,5.5)
        }
        assertTrue(mayToggleFavorite(existing,"NL-TST-25"))
        assertFalse(mayToggleFavorite(existing,"NL-TST-26"))
    }
}
