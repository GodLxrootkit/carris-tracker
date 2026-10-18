package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison behind the updater.
 *
 * Getting this wrong is costly in both directions: miss a real update and the user never gets
 * the fix, offer a non-update and they re-download what they already have, forever.
 */
class UpdateVersionTest {

    @Test
    fun tagPrefixIsOptional() {
        assertEquals(listOf(2, 2, 0), Update.versionTuple("v2.2.0"))
        assertEquals(listOf(2, 2, 0), Update.versionTuple("V2.2.0"))
        assertEquals(listOf(2, 2, 0), Update.versionTuple("2.2.0"))
        assertEquals(listOf(2, 2, 0), Update.versionTuple("  v2.2.0  "))
    }

    /**
     * Missing components are simply absent; [Update.isNewer] pads as it compares, so there is no
     * need to normalise here.
     */
    @Test
    fun missingComponentsAreAbsent() {
        assertEquals(listOf(2, 2), Update.versionTuple("2.2"))
        assertEquals(listOf(3), Update.versionTuple("3"))
        assertEquals(listOf(2, 2, 0), Update.versionTuple("2.2.0"))
    }

    /** The one that catches a naive string comparison. */
    @Test
    fun tenIsGreaterThanNine() {
        assertTrue(Update.isNewer("v2.10.0", "2.9.0"))
        assertFalse(Update.isNewer("2.9.0", "2.10.0"))
    }

    @Test
    fun sameVersionIsNotAnUpdate() {
        assertFalse(Update.isNewer("2.2.0", "2.2.0"))
        assertFalse(Update.isNewer("v2.2.0", "2.2.0"))
    }

    @Test
    fun patchAndMinorBothCount() {
        assertTrue(Update.isNewer("2.2.1", "2.2.0"))
        assertTrue(Update.isNewer("2.3.0", "2.2.9"))
        assertTrue(Update.isNewer("3.0.0", "2.99.99"))
    }

    /** A missing patch component must not read as newer than an explicit one. */
    @Test
    fun absentComponentIsZeroNotUnknown() {
        assertFalse(Update.isNewer("2.2", "2.2.0"))
        assertTrue(Update.isNewer("2.2.1", "2.2"))
    }

    /**
     * A tag we cannot read must never beat the installed version — otherwise a malformed release
     * would nag every user forever. It is treated as 0.
     */
    @Test
    fun unparseableTagsAreTreatedAsZero() {
        assertEquals(listOf(0), Update.versionTuple("nightly"))
        assertEquals(listOf(2, 0, 0), Update.versionTuple("2.x.0"))
        assertFalse(Update.isNewer("nightly", "2.2.0"))
        assertFalse(Update.isNewer("2.x.0", "2.2.0"))
    }

    /**
     * The mirror case: an installed version we cannot read sorts as 0, so a real release is
     * offered. Erring towards offering an update is the safe direction.
     */
    @Test
    fun unknownInstalledVersionAcceptsAnyRelease() {
        assertTrue(Update.isNewer("2.2.0", ""))
        assertFalse(Update.isNewer("", "2.2.0"))
    }
}
