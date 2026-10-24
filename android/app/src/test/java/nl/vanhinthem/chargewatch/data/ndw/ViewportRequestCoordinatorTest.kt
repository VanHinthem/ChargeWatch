package nl.vanhinthem.chargewatch.data.ndw

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewportRequestCoordinatorTest {
    @Test
    fun newerViewportCancelsPendingDebouncedRequest() = runTest {
        val loaded = mutableListOf<NdwViewport>()
        val coordinator = ViewportRequestCoordinator(
            scope = this,
            debounceMillis = 500,
            loader = { loaded += it }
        )

        val first = NdwViewport(5.49, 51.74, 5.56, 51.80)
        val second = NdwViewport(5.50, 51.75, 5.57, 51.81)

        coordinator.submit(first)
        advanceTimeBy(250)
        coordinator.submit(second)
        advanceUntilIdle()

        assertEquals(listOf(second), loaded)
    }

    @Test
    fun identicalViewportIsNotLoadedTwice() = runTest {
        val loaded = mutableListOf<NdwViewport>()
        val coordinator = ViewportRequestCoordinator(
            scope = this,
            debounceMillis = 10,
            loader = { loaded += it }
        )
        val viewport = NdwViewport(5.49, 51.74, 5.56, 51.80)

        coordinator.submit(viewport)
        advanceUntilIdle()
        coordinator.submit(viewport)
        advanceUntilIdle()

        assertEquals(1, loaded.size)
    }
}
