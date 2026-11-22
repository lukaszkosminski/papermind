package pl.lk.papermind.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Błąd silnika z komunikatem czytelnym dla użytkownika. */
class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Odpowiedź HTTP z już wczytanym body. */
internal class HttpResult(val code: Int, val body: String, val contentType: String?) {
    val ok get() = code in 200..299
}

/** Wykonuje [Call] jako suspend; anulowanie korutyny anuluje żądanie. Body czytane w wątku OkHttp-dispatchera wywołującego. */
internal suspend fun Call.await(): HttpResult = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { HttpResult(it.code, it.body.string(), it.header("Content-Type")) }
            } catch (e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
                return
            }
            if (cont.isActive) cont.resume(result)
        }
    })
}

/** Jeden współdzielony klient (pula połączeń + dispatcher); silniki tworzą z niego warianty przez `newBuilder()`. */
object Http {
    val sharedClient: OkHttpClient by lazy { OkHttpClient.Builder().build() }
}
