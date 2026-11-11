package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matching a tapped bus against the user's saved stops.
 *
 * Fixtures use the real stops from the reported set: 030605 R Elias Garcia 103 (CM), and the
 * CARRIS directional pairs 1503/1504 (Pç. Espanha / Av. Berna, 716), 10903/10904 (Igreja
 * Benfica, 716 + 746) and 1501/1502 (B. Azul / Gulbenkian, 746).
 */
class BusStopHintTest {

    private val now = 1_700_000_000L

    /** 716 towards Benfica. 1503 is at sequence 8, 10904 at 23, 1501 at 5. */
    private val asc716 = PatternInfo(
        id = "carris:164_0_ASC_shp",
        lineId = "carris:164_0",
        headsign = "Benfica - Al. Pe. Álvaro Proença",
        shapeId = "164_0_ASC_shp",
        stops = listOf(
            PatternStop("10701", 1, 0.0),
            PatternStop("1501", 5, 0.9),
            PatternStop("1503", 8, 1.6),
            PatternStop("10904", 23, 5.2),
            PatternStop("1505", 30, 7.1)
        )
    )

    /** 716 the other way: 1504 at 18, 10903 at 3. */
    private val desc716 = PatternInfo(
        id = "carris:164_0_DESC_shp",
        lineId = "carris:164_0",
        headsign = "Alameda D. A. Henriques",
        shapeId = "164_0_DESC_shp",
        stops = listOf(
            PatternStop("1502", 1, 0.0),
            PatternStop("10903", 3, 0.7),
            PatternStop("1504", 18, 4.3),
            PatternStop("1505", 26, 6.2)
        )
    )

    private fun bus(
        patternId: String,
        stopId: String?,
        speed: Int = 30,
        status: String = "IN_TRANSIT_TO",
        timestampMs: Long = now * 1000
    ) = Vehicle(
        id = "2214",
        lineId = "carris:164_0",
        patternId = patternId,
        stopId = stopId,
        lat = 38.74,
        lon = -9.16,
        speed = speed,
        status = status,
        timestampMs = timestampMs
    )

    private fun saved(id: String, name: String) = SavedStop(id, name)

    private fun hint(bus: Vehicle, vararg saved: SavedStop) =
        busStopHint(bus, listOf(asc716, desc716), saved.toList(), now)

    @Test
    fun approachingASavedStopGivesAnArrival() {
        val h = hint(bus("carris:164_0_ASC_shp", "1501"), saved("1503", "Pç. Espanha / Av. Berna"))
        assertTrue(h is BusStopHint.Arriving)
        assertEquals("Pç. Espanha / Av. Berna", (h as BusStopHint.Arriving).stopName)
        assertTrue("should be minutes, not milliseconds", h.seconds in 1..600)
    }

    /** The soonest saved stop ahead wins, not the first in pattern order. */
    @Test
    fun theClosestSavedStopAheadWins() {
        val h = hint(
            bus("carris:164_0_ASC_shp", "1501"),
            saved("10904", "Igreja Benfica"),
            saved("1503", "Pç. Espanha / Av. Berna")
        )
        assertEquals("Pç. Espanha / Av. Berna", (h as BusStopHint.Arriving).stopName)
    }

    @Test
    fun theFurthestSavedStopIsChosenWhenOnlyItIsAhead() {
        val h = hint(bus("carris:164_0_ASC_shp", "1501"), saved("10904", "Igreja Benfica"))
        assertEquals("Igreja Benfica", (h as BusStopHint.Arriving).stopName)
    }

    /**
     * A bus that has already called at a saved stop has nothing to announce about it — not an
     * arrival, and not an other-direction claim either, since it did go the right way.
     */
    @Test
    fun aStopAlreadyPassedGivesNoHint() {
        assertEquals(
            BusStopHint.None,
            hint(
                bus("carris:164_0_ASC_shp", "1503"),
                saved("1501", "B. Azul (Gulbenkian)")
            )
        )
    }

    /**
     * A bus running the opposite pattern never calls at the other pattern's stop ids, so there
     * is nothing to announce. The card's header carries the direction instead.
     */
    @Test
    fun oppositeDirectionGivesNoArrival() {
        assertEquals(
            BusStopHint.None,
            hint(
                bus("carris:164_0_DESC_shp", "10903"),
                saved("1503", "Pç. Espanha / Av. Berna")
            )
        )
    }

    @Test
    fun aStopNeverOnThisLineGivesNoHint() {
        val h = hint(
            bus("carris:164_0_ASC_shp", "1501"),
            saved("060041", "Igreja Benfica (CM)")
        )
        assertEquals(BusStopHint.None, h)
    }

    /** Names must match across the pair, so a saved CM stop must not match a CARRIS bus. */
    @Test
    fun stopsOnOtherNetworksAreIgnored() {
        val h = hint(
            bus("carris:164_0_ASC_shp", "1501"),
            saved("030605", "R Elias Garcia 103")
        )
        assertEquals(BusStopHint.None, h)
    }

    @Test
    fun aBusWithNoStopIdGivesNoHint() {
        assertEquals(
            BusStopHint.None,
            hint(bus("carris:164_0_ASC_shp", null), saved("1503", "Pç. Espanha / Av. Berna"))
        )
    }

    @Test
    fun aBusOnAnUnknownPatternGivesNoHint() {
        assertEquals(
            BusStopHint.None,
            hint(bus("carris:nope", "1501"), saved("1503", "Pç. Espanha / Av. Berna"))
        )
    }

    /** Stale positions are dropped, as everywhere else in the ETA pipeline. */
    @Test
    fun staleVehiclesGiveNoHint() {
        val old = bus("carris:164_0_ASC_shp", "1501", timestampMs = (now - 600) * 1000)
        assertEquals(BusStopHint.None, hint(old, saved("1503", "Pç. Espanha / Av. Berna")))
    }

    @Test
    fun noSavedStopsGivesNoHint() {
        assertEquals(BusStopHint.None, hint(bus("carris:164_0_ASC_shp", "1501")))
    }

    /** A bus standing at the stop itself reports it as imminent, not absent. */
    @Test
    fun stoppedAtTheStopStillReports() {
        val h = hint(
            bus("carris:164_0_ASC_shp", "1503", status = "STOPPED_AT"),
            saved("1505", "Pç. Espanha")
        )
        assertTrue("expected an arrival at the very next stop, got $h", h is BusStopHint.Arriving)
    }

    /**
 * 746 is two route_ids under one short name, and a saved stop can be on either variant's
 * pattern. This pins the 746 pattern shape itself, not the sibling lookup that used to exist.
 */
    @Test
    fun aStopOnTheVehiclesOwnPatternMatches() {
        val asc746 = asc716.copy(
            id = "carris:108_0_ASC_shp",
            lineId = "carris:108_0",
            headsign = "Estação Damaia",
            stops = listOf(PatternStop("1501", 1, 0.0), PatternStop("10904", 20, 4.0))
        )
        val bus = bus("carris:108_0_ASC_shp", "1501", speed = 30)
        val h = busStopHint(bus, listOf(asc746), listOf(saved("10904", "Igreja Benfica")), now)
        assertTrue("expected an arrival on the vehicle's own pattern, got $h", h is BusStopHint.Arriving)
        assertEquals("Igreja Benfica", (h as BusStopHint.Arriving).stopName)
    }
}
