package com.jjw.easygallery.feature.hidden

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.data.hidden.VerifyResult

@Composable
fun HiddenRoute(
    onBackClick: () -> Unit,
    viewModel: HiddenViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var pinError by remember { mutableStateOf<String?>(null) }

    val badFormat = stringResource(R.string.hidden_pin_format_error)
    val mismatch = stringResource(R.string.hidden_pin_mismatch)
    val wrong = stringResource(R.string.hidden_pin_wrong)

    // 화면을 벗어나면 다시 잠근다 — 뒤로 갔다 오면 또 물어야 숨김이다.
    // ON_STOP 이 핵심이다. onDispose 만 두면 홈 버튼·앱 전환으로 나갈 때는 컴포지션이
    // 살아 있어 잠기지 않고, 앱에 돌아왔을 때 PIN 없이 숨긴 사진이 그대로 보인다.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.lock() }
    DisposableEffect(Unit) {
        onDispose { viewModel.lock() }
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is HiddenEvent.Unhidden -> snackbarHostState.showSnackbar(
                    resources.getQuantityString(R.plurals.hidden_unhidden_done, event.count, event.count),
                )
                is HiddenEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    HiddenScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        pinError = pinError,
        onBackClick = onBackClick,
        onSetPin = { pin, confirm ->
            viewModel.setPin(pin, confirm) { result ->
                pinError = when (result) {
                    HiddenViewModel.SetPinResult.Ok -> null
                    HiddenViewModel.SetPinResult.BadFormat -> badFormat
                    HiddenViewModel.SetPinResult.Mismatch -> mismatch
                }
            }
        },
        onVerify = { pin ->
            pinError = null
            viewModel.verify(pin) { result ->
                // 잠긴 동안에는 검사조차 하지 않는다. "틀렸다" 고 하면 맞는 PIN 을 넣은
                // 사용자에게 10분 내내 거짓말을 하게 된다 — 남은 시간은 게이트가 보여준다
                pinError = if (result == VerifyResult.Wrong) wrong else null
            }
        },
        onToggleSelection = viewModel::toggleSelection,
        onSelectionChange = viewModel::setSelection,
        onClearSelection = viewModel::clearSelection,
        onUnhideSelected = viewModel::unhideSelected,
    )
}
