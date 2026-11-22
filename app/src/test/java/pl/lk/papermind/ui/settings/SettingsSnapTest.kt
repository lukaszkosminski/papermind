package pl.lk.papermind.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSnapTest {

    @Test
    fun snapStability_roundsToNearestHundred() {
        assertEquals(700L, snapStability(680f))
        assertEquals(700L, snapStability(740f))
        assertEquals(800L, snapStability(750f))
        assertEquals(800L, snapStability(820f))
    }

    @Test
    fun snapStability_clampsToMinAndMax() {
        assertEquals(200L, snapStability(50f))
        assertEquals(200L, snapStability(190f))
        assertEquals(2000L, snapStability(2100f))
        assertEquals(2000L, snapStability(5000f))
    }

    @Test
    fun textInputs_trimmedProperly() {
        assertEquals("sk-test-key", "  sk-test-key \n".trim())
        assertEquals("http://192.168.1.10:8080", " http://192.168.1.10:8080 ".trim())
        assertEquals("gpt-4o", " gpt-4o".trim())
        assertEquals("admin", "admin ".trim())
    }
}
