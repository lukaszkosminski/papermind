package pl.lk.papermind.ocr

import java.security.MessageDigest

/**
 * Stabilne id pytania: skrócony SHA-1 kanonicznej treści pytania (bez numeracji i bez opcji) + liczby opcji.
 *
 * Kanonizacja ([TextNormalizer.ocrCanonical]) usuwa diakrytyki, spacje, interpunkcję i ujednolica znaki
 * mylone przez OCR (0/o, 1/l/i, 5/s, 8/b, rn/m), dzięki czemu drobny szum OCR między klatkami nie zmienia id.
 * Liczba opcji odróżnia pytanie zamknięte od otwartego o tej samej treści; [salt] rozróżnia identyczne
 * pytania powtórzone na jednej stronie.
 */
internal object QuestionId {
    fun of(stem: String, optionCount: Int, salt: Int = 0): String {
        val sb = StringBuilder(TextNormalizer.ocrCanonical(stem))
        sb.append('|').append(optionCount.coerceAtLeast(0))
        if (salt > 0) sb.append('#').append(salt)
        val digest = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
