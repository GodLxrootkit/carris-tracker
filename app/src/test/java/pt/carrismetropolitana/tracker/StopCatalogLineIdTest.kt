package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stop catalog's line ids have to be namespaced like everything else.
 *
 * Regression from 1.1.0: `Api.allStops()` returns bare Carris Metropolitana ids ('1707') while
 * `CarrisGtfs.stops()` returns namespaced ones ('carris:164_0'), and both `nearestStops` and the
 * stop picker compare against namespaced `TRACKED_LINE_IDS`. So every Carris Metropolitana stop
 * failed the tracked-line test — nearest-stop search returned nothing and search results showed
 * no line badges. The CARRIS lines worked, which is what made it look like one provider was
 * broken rather than one path through the app.
 */
class StopCatalogLineIdTest {

    /** Mirrors what ensureAllStops does when it merges the two catalogs. */
    private fun namespaced(lineId: String) = nsId(PROVIDER_CM, lineId)

    @Test
    fun cmCatalogStopsMatchTheTrackedList() {
        val catalog = listOf(
            StopInfo("030105", "Algés", 38.7561, -9.1677, listOf(namespaced("1707"), namespaced("1717"))),
            StopInfo("030299", "Alvalade", 38.7519, -9.1416, listOf(namespaced("1720"), namespaced("1721")))
        )
        val tracked = TRACKED_LINE_IDS.toSet()

        assertTrue(catalog.all { stop -> stop.lineIds.any { it in tracked } })
    }

    /** A stop on no tracked line must still be excluded. */
    @Test
    fun untrackedStopsAreExcluded() {
        val stop = StopInfo("010001", "Outro", 38.7, -9.1, listOf(namespaced("4001")))
        assertTrue(stop.lineIds.none { it in TRACKED_LINE_IDS.toSet() })
    }

    /** The namespace is what stops a bare CM id matching, and the bug was precisely that. */
    @Test
    fun bareIdsDoNotMatchTheTrackedList() {
        val bare = StopInfo("030105", "Algés", 38.7561, -9.1677, listOf("1707"))
        assertTrue(bare.lineIds.none { it in TRACKED_LINE_IDS.toSet() })

        val fixed = bare.copy(lineIds = bare.lineIds.map(::namespaced))
        assertTrue(fixed.lineIds.any { it in TRACKED_LINE_IDS.toSet() })
    }

    @Test
    fun shortNameResolvesForBothProviders() {
        val lines = listOf(
            LineInfo(nsId(PROVIDER_CM, "1707"), "1707", "Algés", "#005EB8", emptyList()),
            LineInfo(nsId(PROVIDER_CARRIS, "164_0"), "716", "Alameda D. A. Henriques - Benfica", "#C61D23", emptyList())
        )
        assertEquals("1707", lines.shortNameOf(namespaced("1707")))
        assertEquals("716", lines.shortNameOf(nsId(PROVIDER_CARRIS, "164_0")))
    }

    /**
     * The on-disk cache format stays bare — `parseCatalogLine` is a straight mirror of the
     * cache line, and `ensureAllStops` namespaces on the way out. That is what lets a catalog
     * written by an older build fix itself on read instead of staying broken for 7 days.
     */
    @Test
    fun cacheFormatStaysBareAndIsNamespacedOnTheWayOut() {
        val line = parseCatalogLine("030105\tAlgés\t38.7561\t-9.1677\t1707,1717")
        assertEquals(listOf("1707", "1717"), line?.lineIds)
        assertTrue(line!!.lineIds.none { it in TRACKED_LINE_IDS.toSet() })

        val composed = line.copy(lineIds = line.lineIds.map(::namespaced))
        assertEquals(listOf("cm:1707", "cm:1717"), composed.lineIds)
        assertTrue(composed.lineIds.any { it in TRACKED_LINE_IDS.toSet() })
    }
}
