package com.app.aisheildme.namtran.feature.auth.verifyemail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.aisheildme.namtran.core.common.UiMessage
import com.app.aisheildme.namtran.R
import com.app.aisheildme.namtran.core.designsystem.AppColors
import com.app.aisheildme.namtran.core.designsystem.component.AppSnackbarHost
import com.app.aisheildme.namtran.core.designsystem.component.AuthHeader
import com.app.aisheildme.namtran.core.designsystem.component.CustomButton
import com.app.aisheildme.namtran.core.designsystem.component.MessageEffect
import com.app.aisheildme.namtran.core.designsystem.component.PinInput

@Composable
fun VerifyEmailScreen(
    onResetToken: (String) -> Unit,
    viewModel: VerifyEmailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is VerifyEmailEvent.ResetToken -> onResetToken(event.token)
            }
        }
    }

    MessageEffect(state.message, snackbarHostState, viewModel::onMessageShown)

    Scaffold(
        modifier = Modifier.paint(
            painter = painterResource(R.drawable.bg_main),
            contentScale = ContentScale.Crop,
        ),
        snackbarHost = {
            AppSnackbarHost(snackbarHostState, state.message?.type != UiMessage.Type.Success)
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                AuthHeader(
                    titleTop = "VERIFY",
                    titleBottom = "IT'S YOU",
                    subtitle = "Enter the 4-digit vault PIN you set on this device for ${state.email}.",
                )
                Spacer(Modifier.height(32.dp))

                PinInput(
                    value = state.pin,
                    onValueChange = viewModel::onPinChange,
                    length = VerifyEmailUiState.PIN_LENGTH,
                    onFilled = { viewModel.onContinue() },
                )
                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Your data never leaves this phone, so no code is emailed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextHint,
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                CustomButton(
                    title = "Continue",
                    onClick = viewModel::onContinue,
                    enabled = state.isValid,
                    isLoading = state.isLoading,
                    height = 44.dp,
                )
            }
        }
    }
}
