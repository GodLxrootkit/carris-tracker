package pt.carrismetropolitana.tracker

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/**
 * Every pattern id a line advertises must actually resolve.
 *
 * This exists because it silently did not, from the day CARRIS was added in 1.1.0 until 1.12.0.
 * `CarrisGtfs.lines()` returned bare pattern ids while every other id in the app is namespaced,
 * so `AppState.loadPattern` matched none of them and returned null. The failure was silent and
 * total for that provider:
 *
 * - no headsign, so tapping a 716 or 746 showed "em circulação" instead of its destination
 * - no pattern, so `Eta` had nothing to project onto: **no arrival predictions at all**
 * - `busStopHint` always answered `None`, so the tapped-bus card never showed a countdown
 * - no `shapeId`, so 716 and 746 drew no route polyline on the map
 *
 * A lookup that returns null is exactly the kind of thing that looks like "the feature is
 * missing" rather than "the id is wrong", which is why it survived so long. This asserts the
 * join instead of the outcome.
 *
 * Robolectric cannot run on a bionic JVM, so this runs in CI on ubuntu-latest, not locally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarrisPatternLookupTest {

    private val asset = runBlocking {
        CarrisGtfs.ensureLoaded(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun everyAdvertisedPatternIdResolves() {
        val lines = CarrisGtfs.lines()
        assertTrue("the bundled asset should carry routes", lines.isNotEmpty())

        // No guard on the prefix here: a bare id is exactly the bug, and filtering those out
        // first would make this pass vacuously.
        val unresolved = lines.flatMap { line ->
            line.patternIds.filter { pid -> CarrisGtfs.pattern(bareOf(pid))?.id != pid }
        }
        assertTrue(
            "pattern ids that do not resolve back to themselves: $unresolved",
            unresolved.isEmpty()
        )
    }

    @Test
    fun lineAndVehiclePatternIdsUseTheSameNamespace() {
        // A vehicle's pattern comes from patternFor() and a line's from the asset; both have to
        // be namespaced identically or the map and the ETA lookup disagree about the same bus.
        val namespaced = CarrisGtfs.lines().flatMap { it.patternIds }.toSet()
        assertTrue(
            "line pattern ids must be namespaced, found bare ones: " +
                namespaced.filterNot { it.startsWith("$PROVIDER_CARRIS:") },
            namespaced.none { !it.startsWith("$PROVIDER_CARRIS:") }
        )
    }

    @Test
    fun patternsCarryAHeadsignSoTheBusCardCanShowADestination() {
        val withoutHeadsign = CarrisGtfs.lines().flatMap { line ->
            line.patternIds.mapNotNull { pid ->
                CarrisGtfs.pattern(bareOf(pid))?.takeIf { it.headsign.isBlank() }?.let { pid }
            }
        }
        assertTrue("patterns with no headsign: $withoutHeadsign", withoutHeadsign.isEmpty())
    }
}