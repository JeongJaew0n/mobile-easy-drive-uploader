package com.jjw.easygallery.core.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 앱 전체 모션 토큰. 화면 코드는 리터럴 대신 이 스펙만 쓴다.
 * [reduceMotion] 이면 모든 스펙이 즉시 전환(snap) 이 되어 시스템 "애니메이션 끄기" 를 존중한다.
 * 지속 시간은 짧게(150/250ms) 잡았다 — 두 화면이 동시에 살아 있는 시간과 재측정 프레임 수를 줄이기 위해.
 */
@Immutable
class MotionSpecs(val reduceMotion: Boolean) {

    /** 작은 요소(아이콘·배지·상단바 내용) 전환 */
    fun <T> quick(): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else tween(Durations.SHORT, easing = FastOutSlowInEasing)

    /** 화면 전환·큰 영역 */
    fun <T> standard(): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else tween(Durations.MEDIUM, easing = FastOutSlowInEasing)

    /** 손을 뗀 뒤 제자리로 돌아가는 움직임(선택 축소, 확대 보정) — 오버슈트 없음 */
    fun <T> settle(): FiniteAnimationSpec<T> =
        if (reduceMotion) {
            snap()
        } else {
            spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
        }

    /** 진행률처럼 주기적으로 갱신되는 값 — 갱신마다 짧게 1회만 움직인다(연속 트윈 금지) */
    fun <T> progress(): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else tween(Durations.PROGRESS, easing = LinearEasing)

    // ----- Enter / Exit 프리셋 -----

    fun enterFade(): EnterTransition = fadeIn(quick())
    fun exitFade(): ExitTransition = fadeOut(quick())

    /** 아래에서 올라옴(하단 바) */
    fun enterFromBottom(): EnterTransition = slideInVertically(standard()) { it } + fadeIn(quick())
    fun exitToBottom(): ExitTransition = slideOutVertically(standard()) { it } + fadeOut(quick())

    /** 위에서 내려옴(상단 바) */
    fun enterFromTop(): EnterTransition = slideInVertically(standard()) { -it } + fadeIn(quick())
    fun exitToTop(): ExitTransition = slideOutVertically(standard()) { -it } + fadeOut(quick())

    /** 높이가 펴지며 등장(배너). 아래 콘텐츠를 재측정하므로 짧게 */
    fun enterExpand(): EnterTransition = expandVertically(quick()) + fadeIn(quick())
    fun exitShrink(): ExitTransition = shrinkVertically(quick()) + fadeOut(quick())

    /** 제자리에서 커지며 등장(체크 아이콘·재생 버튼) */
    fun enterScale(): EnterTransition = scaleIn(quick(), initialScale = SCALE_FROM) + fadeIn(quick())
    fun exitScale(): ExitTransition = scaleOut(quick(), targetScale = SCALE_FROM) + fadeOut(quick())

    object Durations {
        const val SHORT = 150
        const val MEDIUM = 250
        const val PROGRESS = 200
    }

    private companion object {
        const val SCALE_FROM = 0.8f
    }
}

val LocalMotion = staticCompositionLocalOf { MotionSpecs(reduceMotion = false) }
