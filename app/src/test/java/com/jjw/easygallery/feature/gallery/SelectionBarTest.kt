package com.jjw.easygallery.feature.gallery

import com.jjw.easygallery.feature.gallery.SelectionMoreItem.CATEGORIES
import com.jjw.easygallery.feature.gallery.SelectionMoreItem.DELETE_FOREVER
import com.jjw.easygallery.feature.gallery.SelectionMoreItem.FAVORITE
import com.jjw.easygallery.feature.gallery.SelectionMoreItem.HIDE
import com.jjw.easygallery.feature.gallery.SelectionMoreItem.MOVE
import com.jjw.easygallery.feature.gallery.SelectionMoreItem.RENAME
import com.jjw.easygallery.feature.gallery.SelectionMoreItem.UPLOAD_TO_GUEST
import com.jjw.easygallery.feature.gallery.SelectionMoreItem.UPLOAD_TO_OTHER
import org.junit.Assert.assertEquals
import org.junit.Test

/** 더보기 시트 구성(`docs/plans/ux-round2/spec.md` §1) */
class SelectionBarTest {

    @Test
    fun `하나를 고르면 이름 변경이 있고 완전 삭제는 맨 아래`() {
        assertEquals(
            listOf(FAVORITE, HIDE, CATEGORIES, RENAME, MOVE, DELETE_FOREVER),
            selectionMoreItems(
                selectedCount = 1,
                supportsTrashAndFavorites = true,
                uploadTargetCount = 1,
                guestAvailable = false,
            ),
        )
    }

    @Test
    fun `여럿을 고르면 이름 변경이 없다`() {
        val items = selectionMoreItems(
            3,
            supportsTrashAndFavorites = true,
            uploadTargetCount = 1,
            guestAvailable = false,
        )
        assertEquals(false, RENAME in items)
    }

    @Test
    fun `올릴 곳이 둘 이상이거나 다른 계정이 되면 업로드 줄이 생긴다`() {
        val items = selectionMoreItems(
            2,
            supportsTrashAndFavorites = true,
            uploadTargetCount = 2,
            guestAvailable = true,
        )
        assertEquals(listOf(UPLOAD_TO_OTHER, UPLOAD_TO_GUEST, DELETE_FOREVER), items.takeLast(3))
    }

    @Test
    fun `휴지통이 없는 기기는 즐겨찾기·완전 삭제가 시트에 없다 - 하단바 휴지통 자리가 이미 삭제다`() {
        assertEquals(
            listOf(HIDE, CATEGORIES, MOVE),
            selectionMoreItems(2, supportsTrashAndFavorites = false, uploadTargetCount = 1, guestAvailable = false),
        )
    }
}
