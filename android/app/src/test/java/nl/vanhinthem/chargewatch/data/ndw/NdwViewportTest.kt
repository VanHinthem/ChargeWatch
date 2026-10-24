package nl.vanhinthem.chargewatch.data.ndw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdwViewportTest {
    @Test
    fun bboxUsesLocaleIndependentDecimalFormat() {
        val viewport = NdwViewport(5.49, 51.74, 5.56, 51.80)
        assertEquals("5.490000,51.740000,5.560000,51.800000", viewport.asBboxParameter())
    }

    @Test
    fun oversizedViewportIsBoundedToOneSquareDegree() {
        val viewport = NdwViewport(3.0, 50.0, 7.0, 54.0)
        val bounded = viewport.bounded()

        assertTrue(bounded.areaSquareDegrees <= 1.0000001)
        assertEquals(5.0, (bounded.minLongitude + bounded.maxLongitude) / 2.0, 0.000001)
        assertEquals(52.0, (bounded.minLatitude + bounded.maxLatitude) / 2.0, 0.000001)
    }
}
