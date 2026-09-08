package com.jjw.easygallery.core.domain.model

import android.net.Uri
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateGroupTest {

    @Test
    fun `items sharing a hash form a group, singles are ignored`() {
        val items = listOf(
            item(1, 100, added = 10),
            item(2, 100, added = 20),
            item(3, 100, added = 30),
            item(4, 50, added = 5),
        )
        val hashes = mapOf(1L to "a", 2L to "a", 3L to "b", 4L to "c")

        val groups = findDuplicateGroups(items, hashes)

        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L), groups[0].items.map { it.id })
        assertEquals(1, groups[0].duplicateCount)
        assertEquals(100L, groups[0].wastedBytes)
    }

    @Test
    fun `keep prefers the uploaded copy, then the oldest`() {
        val items = listOf(item(1, 100, added = 30), item(2, 100, added = 10), item(3, 100, added = 20))
        val hashes = mapOf(1L to "a", 2L to "a", 3L to "a")

        val oldestKept = findDuplicateGroups(items, hashes).single()
        assertEquals(2L, oldestKept.keepId)
        assertEquals(listOf(1L, 3L).sorted(), oldestKept.removeIds.sorted())

        val uploadedKept = findDuplicateGroups(items, hashes, uploadedIds = setOf(3L)).single()
        assertEquals(3L, uploadedKept.keepId)
        assertEquals(3L, uploadedKept.items.first().id)
    }

    @Test
    fun `items without a cached hash are skipped and groups sort by wasted bytes`() {
        val items = listOf(
            item(1, 10, added = 1),
            item(2, 10, added = 2),
            item(3, 500, added = 3),
            item(4, 500, added = 4),
            item(5, 999, added = 5),
        )
        val hashes = mapOf(1L to "small", 2L to "small", 3L to "big", 4L to "big")

        val groups = findDuplicateGroups(items, hashes)

        assertEquals(listOf("big", "small"), groups.map { it.sha256 })
        assertTrue(groups.none { g -> g.items.any { it.id == 5L } })
    }

    private fun item(id: Long, size: Long, added: Long) = MediaItem(
        id = id,
        uri = mockk<Uri>(relaxed = true),
        displayName = "IMG_$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = size,
        dateTakenMillis = added * 1_000,
        dateAddedSeconds = added,
        bucketId = 1,
        bucketName = "Camera",
    )
}
