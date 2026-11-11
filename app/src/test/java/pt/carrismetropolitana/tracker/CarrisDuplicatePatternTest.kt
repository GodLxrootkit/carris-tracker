package pt.carrismetropolitana.tracker

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The CARRIS asset carries two patterns under a handful of ids.
 *
 * Routes split into a full run and a short turn share one `shape_id`, so the generator hands both
 * the same id while their stop lists differ. GTFS-RT reports only route and direction, so the two
 * have to collapse to one representative - and every lookup that can reach a pattern has to
 * collapse it to the *same* one. They did not: the live-feed lookup took the last match and the
 * by-id lookup took the first, and they disagreed for exactly the four route/direction pairs that
 * have duplicates. A bus was therefore measured against a different geometry depending on which
 * one asked, and its ETAs were quietly wrong for those lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarrisDuplicatePatternTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun load() = runBlocking { CarrisGtfs.ensureLoaded(context) }

    /** The routes the shipped asset splits, with the direction each is duplicated on. */
    private val duplicated = listOf("75_0" to 0, "75_0" to 1, "171_3" to 0, "208_2" to 1)

    /**
     * The property that matters, stated through the public API.
     *
     * A duplicate surviving here would mean two patterns share an id, and `AppState.patterns` is a
     * map keyed on it, so the second would silently overwrite the first and half the line's
     * geometry would be lost.
     */
    @Test
    fun patternIdsAreUniqueAfterLoading() {
        load()
        val ids = CarrisGtfs.patterns().map { it.id }
        assertEquals(
            "duplicate pattern ids survived the load",
            ids.distinct().size,
            ids.size
        )
    }

    /**
     * The two lookups must return the same pattern. The live feed places a bus through
     * [CarrisGtfs.patternFor]; everything else goes through [CarrisGtfs.pattern]. When they
     * disagreed the map and the ETA disagreed about the geometry of the same bus.
     */
    @Test
    fun liveLookupAndByIdLookupAgree() {
        load()
        val mismatches = mutableListOf<String>()
        for ((route, direction) in duplicated) {
            val live = CarrisGtfs.patternFor(route, direction)
            assertNotNull("$route dir $direction did not resolve from the live path", live)
            val byId = CarrisGtfs.pattern(live!!)
            assertNotNull("$route: live path returned $live but by-id could not find it", byId)
            // patternFor answers in bare ids and pattern() namespaces its result; the live
            // caller namespaces too, so the comparison has to be on the bare id.
            if (bareOf(byId!!.id) != live) {
                mismatches += "$route dir $direction: live=$live byId=${byId.id}"
            }
        }
        assertTrue("pattern lookups disagree: $mismatches", mismatches.isEmpty())
    }

    /**
     * They must also agree for every line, not just the duplicated four, so a future asset with a
     * different set of duplicates is caught too.
     */
    @Test
    fun everyRoutesLiveLookupResolvesToAKnownPattern() {
        load()
        val unresolved = CarrisGtfs.allRoutes().flatMap { line ->
            val route = bareOf(line.id)
            listOf(0, 1).mapNotNull { direction ->
                val live = CarrisGtfs.patternFor(route, direction) ?: return@mapNotNull null
                if (CarrisGtfs.pattern(live) == null) "$route dir $direction -> $live" else null
            }
        }
        assertTrue("live lookups pointing at unknown patterns: $unresolved", unresolved.isEmpty())
    }

    /**
     * The representative has to be the full run, which the raw asset is the only place to check -
     * after the collapse the short turn is gone, by design.
     *
     * Picking it is only safe because the short turn's stops are a subset of the full run's: a bus
     * that turns early still has a distance for every stop it serves. Picking the other way would
     * leave the majority of buses with no ETA at all.
     */
    @Test
    fun duplicatedRoutesResolveToTheirFullRun() {
        load()

        // Raw patterns for the same id, straight out of the asset.
        val raw = JSONObject(
            context.resources.openRawResource(R.raw.carris_lines)
                .bufferedReader().use { it.readText() }
        ).getJSONArray("patterns")
        val byId = (0 until raw.length())
            .map { raw.getJSONObject(it) }
            .groupBy { it.getString("id") }

        for ((route, direction) in duplicated) {
            val chosen = CarrisGtfs.pattern(CarrisGtfs.patternFor(route, direction)!!)!!
            val variants = byId[bareOf(chosen.id)]
            assertNotNull("no raw variants for ${chosen.id}", variants)
            val fullest = variants!!.maxByOrNull { it.getInt("tripCount") }!!
            val expectedStops = fullest.getJSONArray("stops").length()
            assertEquals(
                "$route dir $direction: resolved to ${chosen.stops.size} stops, but the " +
                    "max-trip variant has $expectedStops",
                expectedStops,
                chosen.stops.size
            )
            // And the subset property that makes the choice safe.
            if (variants.size > 1) {
                val stopIds = { o: JSONObject ->
                    val a = o.getJSONArray("stops")
                    (0 until a.length()).map { a.getJSONObject(it).getString("stopId") }.toSet()
                }
                val short = variants.minByOrNull { it.getInt("tripCount") }!!
                assertTrue(
                    "$route: the short turn serves stops the full run does not",
                    stopIds(fullest).containsAll(stopIds(short))
                )
            }
        }
    }

    /**
     * Ids stay shared on purpose. The timetable in carris_times.json is keyed on the same id, so
     * renaming the duplicates would have dropped the short turn's departures from the Paragens tab.
     * This pins that the collapse happened without inventing new ids.
     */
    @Test
    fun collapsingDoesNotInventNewIds() {
        load()
        val ids = CarrisGtfs.patterns().map { bareOf(it.id) }.toSet()
        assertTrue(
            "the collapse must keep the shipped ids, found neither: missing " +
                setOf("75_0_ASC_shp", "75_0_DESC_shp", "171_3_ASC_shp", "208_2_DESC_shp")
                    .filterNot { it in ids },
            setOf("75_0_ASC_shp", "75_0_DESC_shp", "171_3_ASC_shp", "208_2_DESC_shp").all { it in ids }
        )
    }
}