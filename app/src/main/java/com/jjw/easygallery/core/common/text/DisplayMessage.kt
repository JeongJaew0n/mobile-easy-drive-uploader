package com.jjw.easygallery.core.common.text

import android.content.res.Resources

/** [UiText] 를 지금 언어의 문장으로. 인자 속 [UiText] 도 풀어 넣는다 */
fun UiText.resolve(resources: Resources): String {
    val resolved = args.map { if (it is UiText) it.resolve(resources) else it }

    // 인자가 몇 개 없어 배열 복사 비용은 무시할 만하다 — getString 이 가변 인자만 받는다
    @Suppress("SpreadOperator")
    val text = if (resolved.isEmpty()) resources.getString(res) else resources.getString(res, *resolved.toTypedArray())
    return text
}

/**
 * 예외를 화면에 띄울 문장으로(`docs/plans/i18n/spec.md`). [LocalizedError] 면 그 문장을 지금 언어로 만들고,
 * 아니면(라이브러리·OS 가 던진 것) 그 메시지를 그대로 쓴다 — 번역은 없지만 원인은 보인다.
 * 원인 사슬도 본다: 코루틴·Retrofit 이 우리 예외를 감싸 다시 던지는 일이 있다.
 */
fun Throwable.displayMessage(resources: Resources): String {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        (current as? LocalizedError)?.uiText?.let { return it.resolve(resources) }
        current = current.cause
        depth++
    }
    return localizedMessage ?: javaClass.simpleName
}

private const val MAX_CAUSE_DEPTH = 5
