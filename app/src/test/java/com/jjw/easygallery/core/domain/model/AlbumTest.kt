package com.jjw.easygallery.core.domain.model

import android.net.Uri
import com.jjw.easygallery.feature.gallery.normalizeDisplayName
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumTest {

    @Test
    fun `albumsFrom groups by relative path and skips blank paths`() {
        val items = listOf(
            item(1, "DCIM/Camera/", "Camera"),
            item(2, "DCIM/Camera/", "Camera"),
            item(3, "Pictures/Screenshots/", "Screenshots"),
            item(4, "", ""),
        )

        val albums = albumsFrom(items)

        assertEquals(listOf("Camera", "Screenshots"), albums.map { it.name })
        assertEquals(2, albums.first { it.name == "Camera" }.itemCount)
        assertEquals("Pictures/Screenshots/", albums.first { it.name == "Screenshots" }.relativePath)
    }

    @Test
    fun `normalizeDisplayName keeps extension and strips slashes`() {
        assertEquals("trip.jpg", normalizeDisplayName("trip", "jpg"))
        assertEquals("trip.png", normalizeDisplayName("trip.png", "jpg"))
        assertEquals("a_b.jpg", normalizeDisplayName(" a/b ", "jpg"))
        assertNull(normalizeDisplayName("   ", "jpg"))
        assertNull(normalizeDisplayName(".", "jpg"))
    }

    private fun item(id: Long, path: String, bucket: String) = MediaItem(
        id = id,
        uri = mockk<Uri>(relaxed = true),
        displayName = "IMG_$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 1,
        dateTakenMillis = 0,
        bucketId = path.hashCode().toLong(),
        bucketName = bucket,
        relativePath = path,
    )
}
