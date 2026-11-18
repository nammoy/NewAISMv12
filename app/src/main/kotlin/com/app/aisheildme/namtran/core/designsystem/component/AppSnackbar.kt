package com.app.aisheildme.namtran.core.designsystem.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.app.aisheildme.namtran.core.designsystem.AppColors
import com.app.aisheildme.namtran.core.common.UiMessage

@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, isError: Boolean) {
    // The app is edge-to-edge, so the keyboard doesn't resize the window. Lift the
    // snackbar above the keyboard; Scaffold already offsets it by the system bars.
    SnackbarHost(
        hostState = hostState,
        modifier = Modifier.windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars)),
    ) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = if (isError) AppColors.Error else AppColors.Success,
            contentColor = Color.White,
        )
    }
}

@Composable
fun MessageEffect(
    message: UiMessage?,
    hostState: SnackbarHostState,
    onShown: () -> Unit,
) {
    LaunchedEffect(message?.id) {
        val current = message ?: return@LaunchedEffect
        hostState.showSnackbar(current.text)
        onShown()
    }
}
