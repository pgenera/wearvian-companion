package org.fivesevenfive.wearvian.companion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fivesevenfive.wearvian.companion.auth.RivianAuthClient
import org.fivesevenfive.wearvian.companion.auth.RivianAuthClient.SessionTokens
import org.fivesevenfive.wearvian.companion.net.RivianCloud
import org.fivesevenfive.wearvian.companion.util.loge
import org.fivesevenfive.wearvian.companion.util.logi
import org.fivesevenfive.wearvian.companion.wear.EnrollmentContract
import org.fivesevenfive.wearvian.companion.wear.ImportContract
import org.fivesevenfive.wearvian.companion.wear.WearImportClient

/**
 * Drives the HA-key import: scan QR -> mint a fresh CSRF + use the QR's
 * user_session_token to resolve the vehicle crypto material from the cloud (no
 * login, no EnrollPhone — the key is already enrolled) -> push the key +
 * enrollment to the watch and await its ack.
 */
class ImportViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface UiState {
        data object Idle : UiState
        data class Working(val message: String) : UiState
        data class Done(val vin: String) : UiState
        data class Failed(val error: String) : UiState
    }

    private val auth = RivianAuthClient()
    private val cloud = RivianCloud()
    private val importClient = WearImportClient(app)

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    /** Called with the raw text decoded from the scanned QR. */
    fun onQrScanned(qrText: String) {
        val payload = runCatching { ImportContract.parseQr(qrText) }.getOrElse {
            logi("import: scanned QR rejected: ${it.message}")
            _state.value = UiState.Failed(it.message ?: "Not a wearvian import QR")
            return
        }
        logi("import: QR accepted for ${payload.username}")
        _state.value = UiState.Working("Resolving your vehicle with Rivian…")
        viewModelScope.launch {
            try {
                val ack = withContext(Dispatchers.IO) {
                    val csrf = auth.createCsrfToken()
                    val tokens = SessionTokens(csrf.csrfToken, csrf.appSessionToken, payload.userSessionToken)
                    val resolution = cloud.resolveImport(tokens, payload.publicKeyHex)
                    val vehicles = resolution.vehicles.map {
                        EnrollmentContract.VehicleResult(
                            vehicleId = it.vehicleId,
                            vin = it.vin,
                            vasVehicleId = it.vasVehicleId,
                            vehiclePublicKey = it.vehiclePublicKey,
                            vasPhoneId = it.vasPhoneId,
                            identityId = it.identityId,
                        )
                    }
                    importClient.pushKey(
                        privateKeyPemBase64 = payload.privateKeyPemBase64,
                        publicKeyHex = payload.publicKeyHex,
                        userId = resolution.userId,
                        asWatch = resolution.asWatch,
                        vehicles = vehicles,
                    )
                }
                if (ack.isOk) {
                    logi("import: watch acked ok vin=${ack.vin}")
                    _state.value = UiState.Done(ack.vin)
                } else {
                    _state.value = UiState.Failed(ack.error ?: "The watch rejected the import")
                }
            } catch (e: Exception) {
                loge("import failed: ${e.message}", e)
                _state.value = UiState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun reset() {
        _state.value = UiState.Idle
    }
}
