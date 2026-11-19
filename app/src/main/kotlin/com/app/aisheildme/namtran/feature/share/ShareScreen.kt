package com.app.aisheildme.namtran.feature.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.aisheildme.namtran.core.common.UiMessage
import com.app.aisheildme.namtran.core.designsystem.AppColors
import com.app.aisheildme.namtran.core.designsystem.component.AppSnackbarHost
import com.app.aisheildme.namtran.core.designsystem.component.CustomButton
import com.app.aisheildme.namtran.core.designsystem.component.MessageEffect
import com.app.aisheildme.namtran.core.designsystem.component.SimpleAppBar

@Composable
fun ShareScreen(
    onBack: () -> Unit,
    onShared: (String) -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.shared.collect(onShared)
    }

    MessageEffect(state.message, snackbarHostState, viewModel::onMessageShown)

    Scaffold(
        topBar = { SimpleAppBar(title = "Share", onBack = onBack) },
        snackbarHost = {
            AppSnackbarHost(snackbarHostState, state.message?.type != UiMessage.Type.Success)
        },
        containerColor = AppColors.LightBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Share outside your vault?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.BrandText,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.documentTitle.ifBlank { "This document" },
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.BrandBlue,
            )
            Spacer(Modifier.height(20.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AppColors.LightSurface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Point(Icons.Outlined.Lock, "In your vault this document is encrypted and never leaves this phone.")
                Point(Icons.Outlined.LockOpen, "Sharing makes an unencrypted copy for the app you choose next (email, messages, a printer…).")
                Point(Icons.Outlined.PhoneAndroid, "AISM doesn't send it anywhere itself, and this share is recorded in your vault's activity log.")
            }

            Spacer(Modifier.weight(1f))

            CustomButton(
                title = "Share outside the vault",
                onClick = { viewModel.share(context) },
                isLoading = state.isSharing,
                height = 46.dp,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppColors.BrandBlue,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.BlackText,
        )
    }
}
