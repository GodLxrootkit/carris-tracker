package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Settings search has to match what people type, not just what the feed says.
 *
 * The network publishes accented names like "Alcácer" and "Algács"; a phone keyboard in Portugal
 * usually drops the accent, and many people skip them entirely. Folding marks away is the
 * difference between search working and search appearing broken.
 */
class LineSearchTest {

    @Test
    fun normalizeFoldsEveryAccentKind() {
        assertEquals("alcacer", normalize("Alcácer"))
        assertEquals("estrela", normalize("Estrela"))
        assertEquals("sao", normalize("São"))
        // Combining marks, not precomposed letters: the NFD pass has to actually decompose.
        assertEquals("acao", normalize("a\u0307c\u0303a\u0303o"))
    }

    @Test
    fun normalizeLowercasesAndStripsMarks() {
        assertEquals("algacs", normalize("ALGÁCS"))
        // A cedilla and an acute in one pass.
        assertEquals("concao", normalize("Conção"))
    }

    /**
     * The property the search depends on: an unaccented query and an accented name agree once
     * folded, so typing either finds the line.
     */
    @Test
    fun accentedAndUnaccentedFormsAgree() {
        assertEquals(normalize("Alcácer do Sal"), normalize("alcacer do sal"))
        assertEquals(normalize("Algács"), normalize("algacs"))
        assertEquals(normalize("Conção"), normalize("concao"))
    }

    /** Names differing only by accents must not be distinguishable by search. */
    @Test
    fun accentIsNotADistinguishingCharacter() {
        assertEquals(normalize("Açores"), normalize("acores"))
    }

    @Test
    fun normalizeIsIdempotent() {
        val once = normalize("Alcácer")
        assertEquals(once, normalize(once))
    }
}
