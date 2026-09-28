package com.jjw.easygallery.core.common.text

import androidx.annotation.StringRes

/**
 * 화면의 언어로 나중에 만들 문장(`docs/plans/i18n/spec.md`). 데이터 계층은 리소스를 모르므로 **문장 대신 이걸** 싣는다.
 * [args] 에 다른 [UiText] 를 넣으면 그것도 같은 언어로 풀린다 — "%1$s 실패" 의 "목록 조회" 같은 것.
 */
data class UiText(@param:StringRes @get:StringRes val res: Int, val args: List<Any> = emptyList()) {
    constructor(@StringRes res: Int, vararg args: Any) : this(res, args.toList())
}

/**
 * 사용자에게 보일 수 있는 예외. [uiText] 가 화면에 뜨는 문장이고, `message` 는 로그용 영어다.
 * 화면은 `Throwable.displayMessage` 로 띄운다.
 */
interface LocalizedError {
    /** null 이면 문장을 만들 수 없다는 뜻 — 원인 사슬을 더 보거나 `message` 로 물러선다 */
    val uiText: UiText?
}

/** 문장 하나만 싣는 예외. 데이터 계층이 아닌 곳(ViewModel)에서 사용자에게 알릴 때 */
class LocalizedException(override val uiText: UiText, message: String = "localized error") :
    Exception(message), LocalizedError
