package com.omnivault.media.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilsTest {

    @Test
    fun `isRemoteNewer compares semantic versions and prefixes safely`() {
        assertTrue(VersionUtils.isRemoteNewer("v2.5.62", "2.5.61"))
        assertTrue(VersionUtils.isRemoteNewer("2.6.0", "2.5.61"))
        assertFalse(VersionUtils.isRemoteNewer("2.5.61", "2.5.61"))
        assertFalse(VersionUtils.isRemoteNewer("2.5.60", "2.5.61"))
        assertTrue(VersionUtils.isRemoteNewer("2.5.62-beta1", "2.5.61"))
        assertFalse(VersionUtils.isRemoteNewer("", "2.5.61"))
    }
}
