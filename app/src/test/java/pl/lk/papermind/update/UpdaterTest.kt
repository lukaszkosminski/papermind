package pl.lk.papermind.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun newerPatch() = assertTrue(Updater.isNewer("0.1.4", "0.1.3"))
    @Test fun numericNotLexical() = assertTrue(Updater.isNewer("0.1.10", "0.1.9"))
    @Test fun sameVersion() = assertFalse(Updater.isNewer("v0.1.3", "0.1.3"))
    @Test fun older() = assertFalse(Updater.isNewer("0.1.2", "0.1.3"))
    @Test fun majorBeatsMinor() = assertTrue(Updater.isNewer("1.0", "0.9.9"))
}
