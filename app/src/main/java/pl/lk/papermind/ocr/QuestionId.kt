package pl.lk.papermind.ocr

import pl.lk.papermind.model.Direction
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
    fun of(stem: String, optionCount: Int, salt: Int = 0): String = hash(
        buildString {
            append(TextNormalizer.ocrCanonical(stem))
            append('|').append(optionCount.coerceAtLeast(0))
            appendSalt(salt)
        },
    )

    /**
     * Id hasła krzyżówki: kierunek + numer + kanoniczna treść (numer i kierunek są częścią tożsamości hasła,
     * bo ta sama treść może wystąpić w obu kierunkach).
     */
    fun forClue(direction: Direction, number: Int, text: String, salt: Int = 0): String = hash(
        buildString {
            append("clue|").append(direction.name).append('|').append(number)
            append('|').append(TextNormalizer.ocrCanonical(text))
            appendSalt(salt)
        },
    )

    private fun StringBuilder.appendSalt(salt: Int) {
        if (salt > 0) append('#').append(salt)
    }

    private fun hash(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
