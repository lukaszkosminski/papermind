package pl.lk.papermind.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bufor ostatnich zdarzeń diagnostycznych (OCR, stabilizator, silniki AI) do podglądu w Ustawieniach. Czysta logika JVM. */
object DiagLog {
    private const val MAX = 400
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    /** Opcjonalny odbiornik (np. logcat); ustawiany przez warstwę Androida. */
    @Volatile var sink: ((String) -> Unit)? = null

    fun log(tag: String, message: String) {
        val line = "${fmt.get()!!.format(Date())} [$tag] $message"
        sink?.invoke(line)
        while (true) {
            val cur = _lines.value
            val next = if (cur.size >= MAX) cur.drop(cur.size - MAX + 1) + line else cur + line
            if (_lines.compareAndSet(cur, next)) break
        }
    }

    fun clear() { _lines.value = emptyList() }

    fun dump(): String = _lines.value.joinToString("\n")
}
