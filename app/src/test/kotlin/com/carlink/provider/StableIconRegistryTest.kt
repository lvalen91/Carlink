package com.carlink.provider

import com.carlink.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Key stability is the whole point of the stable-URI cluster icon path (rev 156): the same
 * PNG bytes must always yield the same key/URI, a different image a different one.
 * Robolectric runner for android.net.Uri.
 */
@RunWith(RobolectricTestRunner::class)
class StableIconRegistryTest {
    @Test
    fun sameBytesSameKeyAndUri() {
        val a = byteArrayOf(1, 2, 3, 4, 5)
        val b = byteArrayOf(1, 2, 3, 4, 5)
        val keyA = StableIconRegistry.register(a)
        val keyB = StableIconRegistry.register(b)
        assertEquals(keyA, keyB)
        assertEquals(StableIconRegistry.uriFor(keyA), StableIconRegistry.uriFor(keyB))
        assertEquals(40, keyA.length) // SHA-1 hex
    }

    @Test
    fun differentBytesDifferentKey() {
        val keyA = StableIconRegistry.register(byteArrayOf(1, 2, 3))
        val keyB = StableIconRegistry.register(byteArrayOf(1, 2, 4))
        assertNotEquals(keyA, keyB)
    }

    @Test
    fun registeredBytesAreServedBack() {
        val png = byteArrayOf(9, 8, 7)
        val key = StableIconRegistry.register(png)
        val served = StableIconRegistry.get(key)
        assertNotNull(served)
        assertSame(png, served)
    }

    @Test
    fun uriUsesShimAuthorityAndStablePath() {
        val key = StableIconRegistry.keyFor(byteArrayOf(42))
        val uri = StableIconRegistry.uriFor(key)
        assertEquals("content", uri.scheme)
        assertEquals(BuildConfig.CLUSTER_ICON_AUTHORITY, uri.authority)
        assertEquals(listOf(StableIconRegistry.PATH_SEGMENT, key), uri.pathSegments)
    }
}
