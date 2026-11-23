package pl.lk.papermind.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import pl.lk.papermind.ai.Http
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Aktualizacja aplikacji z GitHub Releases: sprawdzenie najnowszego wydania, pobranie APK, instalacja. */
object Updater {
    const val REPO = "lukaszkosminski/papermind"

    data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

    private val client by lazy {
        Http.sharedClient.newBuilder().callTimeout(10, TimeUnit.MINUTES).readTimeout(60, TimeUnit.SECONDS).build()
    }

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Najnowsze wydanie z załączonym APK; wyjątek [IOException] przy błędzie sieci/API. */
    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub zwrócił HTTP ${resp.code}")
            val obj = Json.parseToJsonElement(resp.body.string()).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.content ?: throw IOException("Brak tagu wydania")
            val assets: JsonArray = obj["assets"]?.jsonArray ?: JsonArray(emptyList())
            val apk: JsonObject = assets.map { it.jsonObject }
                .firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?: throw IOException("Wydanie $tag nie zawiera pliku APK")
            Release(
                version = tag.removePrefix("v"),
                notes = obj["body"]?.jsonPrimitive?.content.orEmpty(),
                apkUrl = apk["browser_download_url"]!!.jsonPrimitive.content,
                sizeBytes = apk["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        }
    }

    /** Czy [remote] jest nowsze niż [local] (porównanie numeryczne segmentów, np. 0.1.10 > 0.1.9). */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Pobiera APK do cache; [onProgress] 0..1 (lub -1 gdy rozmiar nieznany). */
    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "PaperMind-${release.version}.apk")
        client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Pobieranie nie powiodło się: HTTP ${resp.code}")
            val total = resp.body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            var done = 0L
            resp.body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
        }
        out
    }

    /** true gdy uruchomiono instalator; false gdy trzeba najpierw nadać zgodę „instaluj z tego źródła”. */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
