package pl.lk.papermind.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.lk.papermind.model.EngineId
import java.util.Collections
import java.util.concurrent.Executors

class DataStoreSettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private class RecordingCodec : SecretCodec {
        val threads: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun encrypt(plain: String): String { threads += Thread.currentThread().name; return "enc:" + plain.reversed() }
        override fun decrypt(encoded: String): String { threads += Thread.currentThread().name; return encoded.removePrefix("enc:").reversed() }
    }

    @Test fun `#3 crypto runs on the io dispatcher and secrets are not stored in plain text`() = runTest {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "keystore-io-test") }
        val io = executor.asCoroutineDispatcher()
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("s.preferences_pb") }
            val codec = RecordingCodec()
            val repo = DataStoreSettingsRepository(store, codec, io)

            repo.update { it.copy(engine = EngineId.CLOUD, cloudApiKey = "sk-secret", openCodePassword = "hasło", stabilityMs = 900) }
            val s = repo.settings.first()

            assertEquals(EngineId.CLOUD, s.engine)
            assertEquals("sk-secret", s.cloudApiKey)
            assertEquals("hasło", s.openCodePassword)
            assertEquals(900L, s.stabilityMs)
            assertTrue(codec.threads.isNotEmpty())
            assertTrue(codec.threads.toString(), codec.threads.all { it.startsWith("keystore-io-test") })
            val raw = store.data.first().asMap().values.map { it.toString() }
            assertFalse(raw.any { it.contains("sk-secret") || it.contains("hasło") })
        } finally {
            executor.shutdown()
        }
    }

    @Test fun defaultsWhenEmpty() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("e.preferences_pb") }
        val repo = DataStoreSettingsRepository(store, RecordingCodec())
        assertEquals(AppSettings(), repo.settings.first())
    }
}
