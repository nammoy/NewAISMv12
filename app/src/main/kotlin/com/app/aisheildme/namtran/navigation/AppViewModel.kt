package com.app.aisheildme.namtran.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.aisheildme.namtran.data.repository.AuthRepository
import com.app.aisheildme.namtran.data.repository.CheckInHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val checkInHistory: CheckInHistoryRepository,
) : ViewModel() {

    private val _sessionEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionEnded = _sessionEnded.asSharedFlow()

    init {
        viewModelScope.launch {
            var hadSession = false
            authRepository.user.collect { user ->
                val hasSession = user != null
                if (hadSession && !hasSession) _sessionEnded.tryEmit(Unit)
                if (!hadSession && hasSession) {
                    // One-time move of data the old build stored unencrypted.
                    runCatching { checkInHistory.importLegacySummaries() }
                }
                hadSession = hasSession
            }
        }
    }
}
