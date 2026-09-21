package com.jjw.easygallery.feature.hidden

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

    Column(
        modifier = modifier
            .fillMaxSize()
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
                text = stringResource(R.string.hidden_pin_setup_hint),
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
        val message = when {
            locked -> stringResource(R.string.hidden_pin_locked, remaining)
            errorText != null -> errorText
            // 5회까지는 같은 문구만 보다가 6회째에 갑자기 잠기면 당황스럽다
            state.failedAttempts > 0 ->
                stringResource(R.string.hidden_pin_wrong_with_attempts, state.failedAttempts)
            else -> null
        }
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
