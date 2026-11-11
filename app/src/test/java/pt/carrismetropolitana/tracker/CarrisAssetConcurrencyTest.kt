package pt.carrismetropolitana.tracker

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The asset caches were check-then-act across a suspension point, with no lock.
 *
 * `refresh()` fans out one coroutine per saved stop and each reaches the 4.1 MB timetable;
 * `loadShapes()` fans out one per tracked line and each reaches the 2.7 MB shape file. With the
 * checks unguarded, every one of those saw a null cache and parsed the whole file again, on the
 * main thread, because only the file read was inside `Dispatchers.IO`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarrisAssetConcurrencyTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun concurrentEnsureLoadedAllSeeTheSameAsset() = runBlocking {
        // 16 concurrent callers, the shape of the problem: each would have passed a null check
        // before any of them finished reading.
        val results = coroutineScope {
            (1..16).map { async { CarrisGtfs.ensureLoaded(context); CarrisGtfs.feedVersion() } }.awaitAll()
        }
        assertTrue("the asset should have loaded", results.first() != null)
        assertEquals("callers disagreed about the asset", 1, results.distinct().size)
    }

    /**
     * Shape loading is fanned out one coroutine per tracked line, so this is the path that used to
     * re-read the file per line. All callers must get the same geometry and nothing may deadlock.
     */
    @Test
    fun concurrentShapeLoadsAgree() = runBlocking {
        CarrisGtfs.ensureLoaded(context)
        val shapeId = CarrisGtfs.patterns().mapNotNull { it.shapeId }.firstOrNull()
        assertNotNull("expected at least one pattern with a shape", shapeId)

        val results = coroutineScope {
            (1..12).map { async { CarrisGtfs.shape(context, shapeId!!) } }.awaitAll()
        }
        assertTrue("shape should have points", results.first().isNotEmpty())
        assertEquals("callers got different geometry", 1, results.distinct().size)
    }

    /**
     * `pattern()` is called once per pattern id by `loadPatterns`, and used to rebuild all 309
     * patterns on every call. The mapped list is cached, so repeat calls return the same instance.
     */
    @Test
    fun patternsAreCachedRatherThanRebuiltPerCall() = runBlocking {
        CarrisGtfs.ensureLoaded(context)
        val first = CarrisGtfs.patterns()
        val second = CarrisGtfs.patterns()
        assertSame("patterns() rebuilt the whole list instead of using the cache", first, second)

        // And per-id lookups must not rebuild it either.
        val id = bareOf(CarrisGtfs.patterns().first().id)
        CarrisGtfs.pattern(id)
        assertSame("pattern() invalidated the cache", first, CarrisGtfs.patterns())
    }

    /** The collapse must survive the cache: still one pattern per id. */
    @Test
    fun cachedPatternsAreStillCollapsed() = runBlocking {
        CarrisGtfs.ensureLoaded(context)
        repeat(3) { CarrisGtfs.patterns() }
        val ids = CarrisGtfs.patterns().map { it.id }
        assertEquals(ids.distinct().size, ids.size)
    }

    /**
     * The parse must not run on the caller's thread.
     *
     * Not asserted here on purpose: Robolectric runs everything on the test thread, so any
     * dispatcher assertion in a JVM test would pass whether or not the fix is present. The change
     * is structural - `withContext(Dispatchers.IO)` moved to wrap the parse and not just the file
     * read - and is worth confirming on a real device by watching a cold start.
     */
}