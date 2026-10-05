package com.jjw.easygallery.core.ui.media

import com.jjw.easygallery.core.data.media.MediaAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** 휴지통 이동 뒤 스낵바(`docs/plans/ux-round2/spec.md` §4) */
class TrashFollowUpTest {

    private val trash = MediaAction.Trash(emptyList(), trashed = true)
    private val restore = MediaAction.Trash(emptyList(), trashed = false)

    @Test
    fun `권한이 있으면 실행 취소`() {
        assertEquals(TrashFollowUpKind.UNDO, trashFollowUpKind(trash, canManageMedia = true, offerManageMedia = true))
    }

    @Test
    fun `권한이 없으면 한 번만 권한 제안`() {
        assertEquals(
            TrashFollowUpKind.OFFER_MANAGE_MEDIA,
            trashFollowUpKind(trash, canManageMedia = false, offerManageMedia = true),
        )
        assertEquals(TrashFollowUpKind.NONE, trashFollowUpKind(trash, canManageMedia = false, offerManageMedia = false))
    }

    @Test
    fun `되돌리기·다른 동작에는 아무것도 붙이지 않는다`() {
        assertEquals(TrashFollowUpKind.NONE, trashFollowUpKind(restore, canManageMedia = true, offerManageMedia = true))
        assertEquals(
            TrashFollowUpKind.NONE,
            trashFollowUpKind(MediaAction.Delete(emptyList()), canManageMedia = true, offerManageMedia = true),
        )
    }
}
