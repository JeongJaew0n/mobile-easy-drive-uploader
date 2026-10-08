package com.jjw.easygallery.core.common.text

import android.content.res.Resources
import com.jjw.easygallery.R
import com.jjw.easygallery.core.data.remote.RemoteStorageException
import com.jjw.easygallery.core.data.remote.remoteFailureText
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class DisplayMessageTest {

    private val resources: Resources = mockk {
        every { getString(R.string.op_list) } returns "목록 조회"
        every { getString(R.string.error_op_failed, *anyVararg()) } answers {
            val args = secondArg<Array<Any>>()
            "${args[0]} 실패 (${args[1]})"
        }
        every { getString(R.string.error_folder_exists) } returns "이미 같은 이름의 폴더가 있습니다"
        every { getString(R.string.error_offline) } returns "인터넷에 연결할 수 없습니다"
    }

    @Test
    fun `인터넷이 끊겼으면 원문 대신 우리 문장 - 감싸여 있어도`() {
        val offline = UnknownHostException("Unable to resolve host \"www.googleapis.com\"")
        assertEquals("인터넷에 연결할 수 없습니다", offline.displayMessage(resources))
        val wrapped = RemoteStorageException("io", uiText = UiText(R.string.error_folder_exists), cause = offline)
        assertEquals("인터넷에 연결할 수 없습니다", IOException("wrapped", wrapped).displayMessage(resources))
    }

    @Test
    fun `작업 이름도 지금 언어로 풀어 넣는다`() {
        val e = RemoteStorageException("list failed (403)", 403, uiText = remoteFailureText(R.string.op_list, 403))
        assertEquals("목록 조회 실패 (403)", e.displayMessage(resources))
    }

    @Test
    fun `감싼 예외 안의 문장을 찾는다`() {
        val inner = RemoteStorageException("exists", uiText = UiText(R.string.error_folder_exists))
        assertEquals("이미 같은 이름의 폴더가 있습니다", IOException("wrapped", inner).displayMessage(resources))
    }

    @Test
    fun `문장이 없으면 메시지를 그대로 쓴다`() {
        assertEquals("offline", IOException("offline").displayMessage(resources))
        assertEquals("IllegalStateException", IllegalStateException().displayMessage(resources))
    }
}
