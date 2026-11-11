package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedStopPersistenceTest {

    @Test
    fun networksSurviveTheRoundTrip() {
        val stops = listOf(SavedStop("AM1", "Alameda", 38.737255, -9.134086, setOf("metro")))
        assertEquals(stops, stopsFromJson(stopsToJson(stops)))
    }

    @Test
    fun entriesWrittenBeforeNetworksExistedLoadAsEmpty() {
        val raw = """[{"id":"1707","name":"Paragem","lat":38.7,"lon":-9.1}]"""
        assertEquals(emptySet<String>(), stopsFromJson(raw).single().networks)
    }
}
