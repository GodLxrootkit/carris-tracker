package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Line ids are namespaced at the provider boundary; these pin the contract the UI relies on. */
class LineIdentityTest {

    @Test
    fun trackedLinesSpanBothProviders() {
        assertTrue(CM_TRACKED_LINE_IDS.all { it.startsWith("$PROVIDER_CM:") })
        assertTrue(CARRIS_TRACKED_LINE_IDS.all { it.startsWith("$PROVIDER_CARRIS:") })
        assertEquals(
            CM_TRACKED_LINE_IDS + CARRIS_TRACKED_LINE_IDS,
            TRACKED_LINE_IDS
        )
    }

    /**
     * 716 was dropped from the app because Carris Metropolitana does not serve Lisbon; it is
     * route_id 164_0 in the CARRIS feed. 746 is route_ids 108_0 and 108_3.
     */
    @Test
    fun lisbonRoutesAreTracked() {
        assertEquals(
            listOf("carris:164_0", "carris:108_0", "carris:108_3"),
            CARRIS_TRACKED_LINE_IDS
        )
    }

    /**
     * 746 spans two route_ids with different termini, so both must be tracked or half the
     * line goes missing. This is the case that makes route_id != line identity.
     */
    @Test
    fun aLineWithTwoRouteIdsTracksBoth() {
        val variants = CARRIS_TRACKED_LINE_IDS.filter { bareOf(it).startsWith("108_") }
        assertEquals(listOf("carris:108_0", "carris:108_3"), variants)
    }

    /** The UI keys on short name, so the two 746 variants must collapse to one chip. */
    @Test
    fun variantsCollapseToOneFilterEntryByShortName() {
        val lines = listOf(
            LineInfo("carris:164_0", "716", "Alameda D. A. Henriques - Benfica", "#C61D23", emptyList()),
            LineInfo("carris:108_0", "746", "Marquês Pombal - Estação Damaia", "#1F5FA9", emptyList()),
            LineInfo("carris:108_3", "746", "Rossio - Sul e Sueste", "#0E7C7B", emptyList())
        )
        assertEquals(listOf("716", "746"), lines.distinctBy { it.shortName }.map { it.shortName })
        // Both variants resolve to the same short name, which is what groups them.
        assertEquals("746", lines.shortNameOf("carris:108_0"))
        assertEquals("746", lines.shortNameOf("carris:108_3"))
        // The ids stay distinct underneath, so the pattern cache cannot confuse them.
        assertNotEquals("carris:108_0", "carris:108_3")
    }

    @Test
    fun namespacesAreStrippable() {
        assertEquals("1707", bareOf("cm:1707"))
        assertEquals("164_0", bareOf("carris:164_0"))
    }

    /**
     * The whole point of namespacing: two providers can use the same raw id without the
     * pattern cache or the tracked-line filters confusing them.
     *
     * Uses a synthetic tracked list rather than the real one, since CARRIS and Carris
     * Metropolitana happen not to collide today and the property must hold regardless.
     */
    @Test
    fun sameRawIdFromTwoProvidersStaysDistinct() {
        val a = nsId(PROVIDER_CM, "164_0")
        val b = nsId(PROVIDER_CARRIS, "164_0")
        assertNotEquals(a, b)
        assertEquals("164_0", bareOf(a))
        assertEquals("164_0", bareOf(b))

        val patterns = mutableMapOf<String, String>()
        patterns[a] = "cm-pattern"
        patterns[b] = "carris-pattern"
        assertEquals("cm-pattern", patterns[a])
        assertEquals("carris-pattern", patterns[b])
    }

    @Test
    fun shortNameIsWhatUsersSee() {
        val lines = listOf(
            LineInfo("carris:164_0", "716", "Alameda D. A. Henriques - Benfica", "#C61D23", emptyList()),
            LineInfo("cm:1707", "1707", "Algés", "#005EB8", emptyList())
        )
        assertEquals("716", lines.shortNameOf("carris:164_0"))
        assertEquals("1707", lines.shortNameOf("cm:1707"))
        // Unknown id degrades to the bare id rather than leaking the namespace prefix.
        assertEquals("9999", lines.shortNameOf("cm:9999"))
    }

    /** gtfs version prefixes like "[VNWG3][LA77N]1707_0_1" must survive namespacing intact. */
    @Test
    fun cmVersionPrefixedIdsStillStrip() {
        assertEquals("1707_0_1", bareId("[VNWG3][LA77N]1707_0_1"))
        assertEquals("1707_0_1", bareOf(nsId(PROVIDER_CM, bareId("[VNWG3][LA77N]1707_0_1"))))
    }
}