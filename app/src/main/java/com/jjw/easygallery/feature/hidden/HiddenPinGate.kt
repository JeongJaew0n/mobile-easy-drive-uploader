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
    // 남은 시간은 화면에서 직접 센다 — 저장소를 1초마다 깨우지 않는다
    var remaining by remember(state.lockedSeconds) { mutableStateOf(state.lockedSeconds) }
    LaunchedEffect(state.lockedSeconds) {
        while (remaining > 0) {
            kotlinx.coroutines.delay(ONE_SECOND_MILLIS)
            remaining -= 1
        }
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
