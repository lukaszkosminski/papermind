package pl.lk.papermind.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.paperMindStore: DataStore<Preferences> by preferencesDataStore(name = "papermind_settings")

/** Ustawienia w Preferences DataStore; klucz API i hasło OpenCode szyfrowane Android Keystore (AES/GCM). */
class DataStoreSettingsRepository internal constructor(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCodec,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SettingsRepository {

    constructor(context: Context) : this(context.applicationContext.paperMindStore, KeystoreCodec(KeystoreCipher()))

    /** Deszyfrowanie (Keystore/Binder IPC) poza wątkiem głównym. */
    override val settings: Flow<AppSettings> = store.data.map { it.toSettings() }.flowOn(io)

    /** Szyfrowanie (Keystore/Binder IPC) poza wątkiem głównym — także gdy wołający jest na Main. */
    override suspend fun update(transform: (AppSettings) -> AppSettings) = withContext(io) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[ENGINE] = next.engine.name
            prefs[FALLBACK] = next.fallbackEnabled
            prefs[OC_URL] = next.openCodeUrl
            prefs[OC_USER] = next.openCodeUser
            prefs[OC_PASS] = cipher.encrypt(next.openCodePassword)
            prefs[OC_MODEL] = next.openCodeModel
            prefs[CLOUD_PROVIDER] = next.cloudProvider.name
            prefs[CLOUD_KEY] = cipher.encrypt(next.cloudApiKey)
            prefs[CLOUD_MODEL] = next.cloudModel
            prefs[LANGUAGE] = next.language
            prefs[EXPLAIN] = next.showExplanations
            prefs[STABILITY] = next.stabilityMs
        }
        Unit
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            engine = enumOr(this[ENGINE], d.engine),
            fallbackEnabled = this[FALLBACK] ?: d.fallbackEnabled,
            openCodeUrl = this[OC_URL] ?: d.openCodeUrl,
            openCodeUser = this[OC_USER] ?: d.openCodeUser,
            openCodePassword = this[OC_PASS]?.let(cipher::decrypt) ?: d.openCodePassword,
            openCodeModel = this[OC_MODEL] ?: d.openCodeModel,
            cloudProvider = enumOr(this[CLOUD_PROVIDER], d.cloudProvider),
            cloudApiKey = this[CLOUD_KEY]?.let(cipher::decrypt) ?: d.cloudApiKey,
            cloudModel = this[CLOUD_MODEL] ?: d.cloudModel,
            language = this[LANGUAGE] ?: d.language,
            showExplanations = this[EXPLAIN] ?: d.showExplanations,
            stabilityMs = this[STABILITY] ?: d.stabilityMs,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private companion object {
        val ENGINE = stringPreferencesKey("engine")
        val FALLBACK = booleanPreferencesKey("fallback_enabled")
        val OC_URL = stringPreferencesKey("opencode_url")
        val OC_USER = stringPreferencesKey("opencode_user")
        val OC_PASS = stringPreferencesKey("opencode_password_enc")
        val OC_MODEL = stringPreferencesKey("opencode_model")
        val CLOUD_PROVIDER = stringPreferencesKey("cloud_provider")
        val CLOUD_KEY = stringPreferencesKey("cloud_api_key_enc")
        val CLOUD_MODEL = stringPreferencesKey("cloud_model")
        val LANGUAGE = stringPreferencesKey("language")
        val EXPLAIN = booleanPreferencesKey("show_explanations")
        val STABILITY = longPreferencesKey("stability_ms")
    }
}

/** Szyfr sekretów (wydzielony, by repozytorium dało się testować na JVM). */
internal interface SecretCodec {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
}

private class KeystoreCodec(private val c: KeystoreCipher) : SecretCodec {
    override fun encrypt(plain: String) = c.encrypt(plain)
    override fun decrypt(encoded: String) = c.decrypt(encoded)
}
