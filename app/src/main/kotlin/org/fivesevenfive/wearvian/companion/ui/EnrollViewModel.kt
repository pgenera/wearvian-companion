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
import org.fivesevenfive.wearvian.companion.net.RivianCloud
import org.fivesevenfive.wearvian.companion.store.SessionStore
import org.fivesevenfive.wearvian.companion.wear.EnrollmentContract
import org.fivesevenfive.wearvian.companion.util.loge
import org.fivesevenfive.wearvian.companion.util.logi
import org.fivesevenfive.wearvian.companion.util.logw
import org.fivesevenfive.wearvian.companion.wear.PendingEnrollment
import org.fivesevenfive.wearvian.companion.wear.WearTransport

/**
 * Drives the interactive enrollment: Rivian login (+ MFA) -> getUserInfo ->
 * EnrollPhone(watch public key) per vehicle -> send result back to the watch.
 */
class EnrollViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface UiState {
        /** No watch request pending. */
        data object Waiting : UiState
        /** A watch request is pending; collect Rivian credentials. [rememberedEmail] pre-fills the form. */
        data class NeedCredentials(val watchName: String, val rememberedEmail: String) : UiState
        data class MfaRequired(val email: String) : UiState
        data class Working(val message: String) : UiState
        data class Done(val vehicleCount: Int) : UiState
        data class Failed(val error: String) : UiState
    }

    private val auth = RivianAuthClient()
    private val cloud = RivianCloud()
    private val transport = WearTransport(app)
    private val store = SessionStore(app)

    private val _state = MutableStateFlow<UiState>(UiState.Waiting)
    val state: StateFlow<UiState> = _state

    // Carried across the MFA step.
    private var csrf: RivianAuthClient.CsrfTokens? = null
    private var otpToken: String? = null
    private var pendingEmail: String? = null
    // From the login screen's "Enroll as watch key" checkbox: true → pass the watch-supplied
    // deviceType ("watch") through to the cloud; false → override to "phone" (the prior behavior).
    private var enrollAsWatch: Boolean = true

    init {
        viewModelScope.launch {
            PendingEnrollment.request.collect { req ->
                logi("pending request changed: ${req?.requestId} (state=${_state.value::class.simpleName})")
                if (req != null && _state.value is UiState.Waiting) {
                    _state.value = UiState.NeedCredentials(req.deviceName, store.loadEmail())
                }
            }
        }
    }

    fun submitCredentials(email: String, password: String, enrollAsWatch: Boolean) {
        val req = PendingEnrollment.request.value ?: run {
            logw("submitCredentials but no pending request"); return
        }
        logi("submitCredentials: email=$email requestId=${req.requestId} enrollAsWatch=$enrollAsWatch")
        this.enrollAsWatch = enrollAsWatch // remembered across a possible MFA step
        pendingEmail = email
        store.saveEmail(email)
        _state.value = UiState.Working("Signing in to Rivian…")
        viewModelScope.launch {
            try {
                val tokens = withContext(Dispatchers.IO) {
                    val c = auth.createCsrfToken().also { csrf = it }
                    when (val r = auth.login(c, email, password)) {
                        is RivianAuthClient.LoginResult.Success -> r.tokens
                        is RivianAuthClient.LoginResult.MfaRequired -> {
                            otpToken = r.otpToken
                            null
                        }
                    }
                }
                if (tokens == null) {
                    logi("submitCredentials: -> MFA required")
                    _state.value = UiState.MfaRequired(email)
                } else {
                    finishEnrollment(tokens, req)
                }
            } catch (e: Exception) {
                fail(req.requestId, e)
            }
        }
    }

    fun submitOtp(otpCode: String) {
        logi("submitOtp")
        val req = PendingEnrollment.request.value ?: return
        val c = csrf ?: return
        val token = otpToken ?: return
        val email = pendingEmail ?: return
        _state.value = UiState.Working("Verifying code…")
        viewModelScope.launch {
            try {
                val tokens = withContext(Dispatchers.IO) {
                    auth.loginWithOtp(c, email, otpCode, token)
                }
                finishEnrollment(tokens, req)
            } catch (e: Exception) {
                fail(req.requestId, e)
            }
        }
    }

    private suspend fun finishEnrollment(
        tokens: RivianAuthClient.SessionTokens,
        req: EnrollmentContract.Request,
    ) {
        _state.value = UiState.Working("Enrolling watch key with your vehicles…")
        val results = withContext(Dispatchers.IO) {
            store.saveTokens(tokens)
            val info = cloud.getUserInfo(tokens)
            info.vehicles.map { v ->
                // "Enroll as watch key" on → pass the watch-supplied type ("watch"); off → "phone"
                // (the original behavior). Cloud acceptance of "watch" is unconfirmed.
                val deviceType = if (enrollAsWatch) req.deviceType else "phone"
                val enrolled = cloud.enrollPhone(
                    tokens = tokens,
                    userId = info.userId,
                    vehicleId = v.vehicleId,
                    publicKeyHex = req.publicKeyHex,
                    deviceName = req.deviceName,
                    deviceType = deviceType,
                )
                EnrollmentContract.VehicleResult(
                    vehicleId = v.vehicleId,
                    vin = v.vin,
                    vasVehicleId = v.vasVehicleId,
                    vehiclePublicKey = v.vehiclePublicKey,
                    vasPhoneId = enrolled.vasPhoneId,
                    identityId = enrolled.identityId,
                ) to info.userId
            }
        }
        val userId = results.firstOrNull()?.second.orEmpty()
        val vehicles = results.map { it.first }
        val payload = EnrollmentContract.successResult(req.requestId, vehicles, userId, tokens)
        val node = PendingEnrollment.sourceNodeId
        logi("finishEnrollment: enrolled ${vehicles.size} vehicle(s); sending result to node=$node")
        node?.let { withContext(Dispatchers.IO) { transport.sendResult(it, payload) } }
        PendingEnrollment.clear()
        _state.value = UiState.Done(vehicles.size)
        logi("finishEnrollment: done")
    }

    private suspend fun fail(requestId: String, e: Exception) {
        val msg = e.message ?: e.javaClass.simpleName
        loge("enrollment failed: $msg", e)
        PendingEnrollment.sourceNodeId?.let { node ->
            runCatching {
                withContext(Dispatchers.IO) {
                    transport.sendResult(node, EnrollmentContract.errorResult(requestId, msg))
                }
            }
        }
        _state.value = UiState.Failed(msg)
    }

    fun reset() {
        csrf = null; otpToken = null; pendingEmail = null
        _state.value = UiState.Waiting
    }
}
