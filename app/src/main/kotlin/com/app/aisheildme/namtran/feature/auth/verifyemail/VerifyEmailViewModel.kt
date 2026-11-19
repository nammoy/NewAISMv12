package com.app.aisheildme.namtran.feature.auth.verifyemail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.app.aisheildme.namtran.core.common.AppException
import com.app.aisheildme.namtran.core.common.UiMessage
import com.app.aisheildme.namtran.data.repository.AuthRepository
import com.app.aisheildme.namtran.navigation.VerifyEmailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Offline password recovery: with no server to email a code, the user proves who they are
 * with the vault PIN they set on this device.
 */
data class VerifyEmailUiState(
    val email: String = "",
    val pin: String = "",
    val isLoading: Boolean = false,
    val message: UiMessage? = null,
) {
    val isValid: Boolean get() = pin.length == PIN_LENGTH

    companion object {
        const val PIN_LENGTH = 4
    }
}

sealed interface VerifyEmailEvent {
    data class ResetToken(val token: String) : VerifyEmailEvent
}

@HiltViewModel
class VerifyEmailViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val args: VerifyEmailRoute = savedStateHandle.toRoute()

    private val _uiState = MutableStateFlow(VerifyEmailUiState(email = args.email))
    val uiState: StateFlow<VerifyEmailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<VerifyEmailEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    fun onPinChange(value: String) = _uiState.update { it.copy(pin = value) }

    fun onMessageShown() = _uiState.update { it.copy(message = null) }

    fun onContinue() {
        val s = _uiState.value
        if (s.isLoading) return
        if (!s.isValid) {
            _uiState.update { it.copy(message = UiMessage.error("Please enter your 4-digit vault PIN.")) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val token = authRepository.verifyPinForReset(s.email, s.pin)
                _events.tryEmit(VerifyEmailEvent.ResetToken(token))
            } catch (e: AppException) {
                _uiState.update { it.copy(pin = "", message = UiMessage.error(e.message)) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}
