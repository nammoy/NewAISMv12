package com.app.aisheildme.namtran.feature.share

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.app.aisheildme.namtran.core.common.AppException
import com.app.aisheildme.namtran.core.common.UiMessage
import com.app.aisheildme.namtran.core.util.LocalFiles
import com.app.aisheildme.namtran.data.repository.DocumentRepository
import com.app.aisheildme.namtran.navigation.ShareRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShareUiState(
    val documentTitle: String = "",
    val isSharing: Boolean = false,
    val message: UiMessage? = null,
)

/**
 * Sharing a document is the moment data leaves the vault, so it is an explicit,
 * consented step: the user sees what will leave, confirms, and then chooses the
 * destination in Android's share sheet. The Negotiator records it as a Share.
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val args: ShareRoute = savedStateHandle.toRoute()

    private val _uiState = MutableStateFlow(ShareUiState(documentTitle = args.title))
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    private val _shared = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val shared = _shared.asSharedFlow()

    fun onMessageShown() = _uiState.update { it.copy(message = null) }

    fun share(context: Context) {
        if (_uiState.value.isSharing) return
        if (args.documentId < 0) {
            _uiState.update { it.copy(message = UiMessage.error("Document not available to share.")) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSharing = true) }
            try {
                val (item, file) = documentRepository.decryptForSharing(args.documentId)
                val error = LocalFiles.share(context, file, item.fileType, item.title.ifBlank { file.name })
                if (error != null) {
                    _uiState.update { it.copy(message = UiMessage.error(error)) }
                } else {
                    _shared.tryEmit("Choose where to send \"${item.title}\".")
                }
            } catch (e: AppException) {
                _uiState.update { it.copy(message = UiMessage.error(e.message)) }
            } finally {
                _uiState.update { it.copy(isSharing = false) }
            }
        }
    }
}
