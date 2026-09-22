package com.jjw.easygallery.feature.hidden

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.data.hidden.HiddenPin
import kotlinx.coroutines.delay

/**
 * PIN 게이트. 처음이면 설정, 아니면 입력. 잠겨 있으면 남은 시간을 1초마다 줄여 보여준다.
 * `docs/PHOTO_HIDING.md` §3.
 */
@Composable
internal fun HiddenPinGate(
    state: HiddenUiState.Locked,
    onSetPin: (pin: String, confirm: String) -> Unit,
    onVerify: (String) -> Unit,
    onForgot: () -> Unit,
    isResetting: Boolean,
    errorText: String?,
    modifier: Modifier = Modifier,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    // 남은 시간은 화면에서 직접 센다 — 저장소를 1초마다 깨우지 않는다.
    // 기준은 **절대 시각**이다. 남은 초를 키로 쓰면 상한(600초)에 닿았을 때 값이 안 바뀌어
    // 카운트다운이 0 에 멈춘 채로 남고, 잠겨 있는데 안 잠긴 것처럼 보인다.
    var remaining by remember(state.lockedUntilMillis) {
        mutableLongStateOf(secondsLeft(state.lockedUntilMillis))
    }
    LaunchedEffect(state.lockedUntilMillis) {
        while (secondsLeft(state.lockedUntilMillis) > 0) {
            delay(ONE_SECOND_MILLIS)
            remaining = secondsLeft(state.lockedUntilMillis)
        }
        remaining = 0
    }
    val locked = remaining > 0

    // 키보드가 올라오면 "한 번 더 입력" 과 확인 버튼이 가려진다(실기기 확인).
    // imePadding 으로 키보드만큼 띄우고, 그래도 모자라면 스크롤로 닿게 한다.
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                if (state.isSetup) R.string.hidden_pin_setup_title else R.string.hidden_pin_enter_title,
            ),
            style = MaterialTheme.typography.titleLarge,
        )
        if (state.isSetup) {
            Text(
                text = stringResource(
                    if (isResetting) R.string.hidden_pin_reset_hint else R.string.hidden_pin_setup_hint,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PinField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(HiddenPin.MAX_LENGTH) },
            labelRes = R.string.hidden_pin_label,
            enabled = !locked,
            imeAction = if (state.isSetup) ImeAction.Next else ImeAction.Done,
        )
        if (state.isSetup) {
            PinField(
                value = confirm,
                onValueChange = { confirm = it.filter(Char::isDigit).take(HiddenPin.MAX_LENGTH) },
                labelRes = R.string.hidden_pin_confirm_label,
                enabled = true,
                imeAction = ImeAction.Done,
            )
        }
        val message = gateMessage(remaining, locked, state.failedAttempts, errorText)
        if (message != null) {
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = { if (state.isSetup) onSetPin(pin, confirm) else onVerify(pin) },
            enabled = !locked && pin.length >= HiddenPin.MIN_LENGTH,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_confirm))
        }
        // 잊었을 때의 유일한 길. **잠긴 동안에도 눌려야 한다** —
        // 정작 잊어버린 사람이 10분을 기다려야 하면 복구 장치가 아니다.
        if (!state.isSetup) {
            TextButton(onClick = onForgot) {
                Text(stringResource(R.string.hidden_pin_forgot))
            }
        }
    }
}

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    enabled: Boolean,
    imeAction: ImeAction,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val ONE_SECOND_MILLIS = 1_000L

/** [lockedUntilMillis] 까지 남은 초(올림). 지났으면 0 */
private fun secondsLeft(lockedUntilMillis: Long): Long {
    val left = lockedUntilMillis - System.currentTimeMillis()
    return if (left <= 0) 0 else (left + ONE_SECOND_MILLIS - 1) / ONE_SECOND_MILLIS
}

/**
 * 게이트에 보여줄 한 줄. 잠김 > 실패 횟수 > 그 밖의 오류 순이다.
 *
 * 실패 횟수 분기가 [errorText] 보다 **앞**이어야 한다 — 뒤에 두면 영영 닿지 않는다(실기기 확인).
 * 5회까지 같은 문구만 보다가 6회째에 갑자기 잠기면 무슨 일인지 알 수 없다.
 */
@Composable
private fun gateMessage(remaining: Long, locked: Boolean, failedAttempts: Int, errorText: String?): String? = when {
    locked -> stringResource(R.string.hidden_pin_locked, remaining)
    errorText != null && failedAttempts > 0 ->
        stringResource(R.string.hidden_pin_wrong_with_attempts, failedAttempts)
    else -> errorText
}
