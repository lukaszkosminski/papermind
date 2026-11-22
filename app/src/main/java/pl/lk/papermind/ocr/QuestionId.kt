package pl.lk.papermind.ocr

import java.security.MessageDigest

/** Stabilne id pytania: skrócony SHA-1 znormalizowanej treści pytania + etykiet opcji. */
internal object QuestionId {
    fun of(stem: String, optionLabels: List<String>, optionTextsFallback: List<String> = emptyList(), salt: Int = 0): String {
        val sb = StringBuilder(TextNormalizer.squash(stem))
        sb.append('|').append(optionLabels.joinToString("") { TextNormalizer.squash(it) })
        // Bez treści pytania id opierałoby się tylko na literach — dołącz treść opcji.
        if (sb.length < 8 && optionTextsFallback.isNotEmpty()) {
            sb.append('|').append(optionTextsFallback.joinToString("|") { TextNormalizer.squash(it) })
        }
        if (salt > 0) sb.append('#').append(salt)
        val digest = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
