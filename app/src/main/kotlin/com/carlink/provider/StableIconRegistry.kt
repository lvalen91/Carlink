package com.carlink.provider

import android.graphics.Bitmap
import android.net.Uri
import com.carlink.BuildConfig
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Collections

/**
 * Process-global store of maneuver-icon PNGs addressable by a CONTENT-HASH key, served by
 * [ClusterIconShimProvider] at `content://<CLUSTER_ICON_AUTHORITY>/stable/<key>`.
 *
 * Why (rev 156, EXPERIMENTAL): Templates Host gives every bitmap-type CarIcon a RANDOM cache
 * id (`UUID.randomUUID().hashCode()` in NavigationStateConverterImpl.hash), so each
 * `updateTrip` — once a second during a route — re-encodes the identical 512x512 composed
 * bitmap to PNG (~100 ms on the gminfo37 Atom), re-inserts it into the shim, and makes GM
 * re-upload the image to the cluster ECU (2,150 uploads per drive vs 8 with stable ids).
 * Resource- and URI-type icons are hashed by content (`CarIcon.hashCode()`, which for
 * `IconCompat.TYPE_URI` hashes the Uri), so handing the host a URI whose string is a pure
 * function of the image bytes gives it a STABLE id: one encode per unique icon, and the
 * cluster upload only happens when the maneuver image actually changes.
 *
 * Keys are the SHA-1 of the PNG bytes (a content hash, not a security primitive — same
 * approach as [com.carlink.media.AlbumArtCache]). Identical bytes ⇒ identical key ⇒ identical
 * URI across routes and across process restarts; a different image ⇒ a different URI.
 *
 * Retention: bounded access-order LRU ([MAX_ENTRIES]). Entries are NEVER cleared explicitly
 * (not on route end, not on reroute) because the host may re-fetch a URI it has already
 * seen whenever ITS cache (the shim's 20-entry `iconCache`) evicted that id — and a fetch
 * that fails is believed to trip the host's session-wide `skipIcons` latch. At the
 * ~4 KB/PNG measured on the radio, 384 entries ≈ 1.5 MB worst case; a long route has
 * ≤ ~120 unique composed icons (rev 139 dedup), so eviction of a live key is not expected.
 *
 * Thread safety: all methods are safe from any thread (synchronized map). Callers:
 * ComposedIconStore's IconComposer worker (register), NavigationStateManager on the USB
 * read thread (register, no encode — AA bytes are already PNG), ClusterIconShimProvider
 * binder threads (get).
 */
object StableIconRegistry {
    /** Path segment that routes a URI to this registry inside [ClusterIconShimProvider.openFile]. */
    const val PATH_SEGMENT = "stable"

    private const val MAX_ENTRIES = 384

    private val entries: MutableMap<String, ByteArray> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > MAX_ENTRIES
            },
        )

    /**
     * Served when a key is requested that is no longer (or never was) registered. A 1x1
     * transparent PNG rather than a null/exception: the host latches `skipIcons` on a failed
     * icon load, which would kill every cluster icon for the rest of the session — a single
     * blank glyph, logged at WARN by the provider, is the lesser failure.
     */
    val placeholderPng: ByteArray by lazy {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        out.toByteArray()
    }

    /** Content-hash key for [png]; the same bytes always map to the same key. */
    fun keyFor(png: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(png)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }

    /** Store [png] (idempotent for identical bytes) and return its key. */
    fun register(png: ByteArray): String {
        val key = keyFor(png)
        entries[key] = png // put (not putIfAbsent) so the access-order LRU refreshes recency
        return key
    }

    /** PNG bytes for [key], or null when unknown/evicted. */
    fun get(key: String): ByteArray? = entries[key]

    fun size(): Int = entries.size

    /**
     * The URI handed to Templates Host inside a `CarIcon`. Built on the authority our shim is
     * registered under for this flavor (BuildConfig.CLUSTER_ICON_AUTHORITY — the GM literal on
     * sideload and the owner's play build), which is exported with no read permission, so the
     * host's `IconCompat.loadDrawable` → `ContentResolver.openInputStream` lands on
     * [ClusterIconShimProvider.openFile] without any grant.
     */
    fun uriFor(key: String): Uri = Uri.parse("content://${BuildConfig.CLUSTER_ICON_AUTHORITY}/$PATH_SEGMENT/$key")
}
