package com.jjw.easygallery.feature.hidden

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
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
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.data.hidden.VerifyResult
import com.jjw.easygallery.core.domain.model.MediaItem

@Composable
fun HiddenRoute(
    onBackClick: () -> Unit,
    onOpenItem: (MediaItem) -> Unit,
    viewModel: HiddenViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var pinError by remember { mutableStateOf<String?>(null) }

    val badFormat = stringResource(R.string.hidden_pin_format_error)
    val mismatch = stringResource(R.string.hidden_pin_mismatch)
    val wrong = stringResource(R.string.hidden_pin_wrong)

    // 앱을 벗어나면 다시 잠근다. 뒤로가기로 이 화면을 떠나면 NavEntry 와 함께 ViewModel 이
    // 죽어 자연히 잠긴다.
    //
    // **액티비티 생명주기를 직접 본다.** `LifecycleEventEffect` 는 Navigation 3 가 엔트리마다
    // 주는 생명주기를 따르므로 상세보기를 열기만 해도 ON_STOP 이 떠서, 사진 한 장 볼 때마다
    // PIN 을 다시 묻게 된다(실기기 확인). 단일 액티비티라 액티비티의 ON_STOP 은
    // "앱이 백그라운드로 갔다" 와 같다.
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.lock()
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
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
        onOpenItem = onOpenItem,
    )
}
