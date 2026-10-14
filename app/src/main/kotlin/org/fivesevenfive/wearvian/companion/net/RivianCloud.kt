package org.fivesevenfive.wearvian.companion.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fivesevenfive.wearvian.companion.auth.RivianAuthClient.SessionTokens
import org.fivesevenfive.wearvian.companion.protocol.RivianGql
import org.fivesevenfive.wearvian.companion.util.logi
import org.fivesevenfive.wearvian.companion.util.logw

/**
 * Authenticated Rivian GraphQL calls used during enrollment: `getUserInfo` and
 * `EnrollPhone`. Adds the three session headers (`Csrf-Token`, `A-Sess`,
 * `U-Sess`) required for authenticated operations.
 *
 * The [publicKeyHex] submitted to `EnrollPhone` is the WATCH's public key. This
 * app never generates key material.
 */
class RivianCloud(
    private val http: OkHttpClient = OkHttpClient(),
) {
    class RivianCloudError(message: String) : Exception(message)

    private fun authedPost(tokens: SessionTokens, body: String): String {
        val req = Request.Builder()
            .url(RivianGql.GATEWAY_URL)
            .apply { BASE_HEADERS.forEach { (k, v) -> header(k, v) } }
            .header("Csrf-Token", tokens.csrfToken)
            .header("A-Sess", tokens.appSessionToken)
            .header("U-Sess", tokens.userSessionToken)
            .post(body.toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            logi("authedPost <- HTTP ${resp.code} bytes=${payload.length}")
            if (resp.code != 200) throw RivianCloudError("HTTP ${resp.code}: $payload")
            return payload
        }
    }

    /**
     * Whether the gateway accepts the `deviceSlots` selection. Null until the first call settles
     * it. False disables only the key-limit pre-flight — never enrollment itself.
     */
    private var deviceSlotsSupported: Boolean? = null

    /**
     * Fetch `getUserInfo`, degrading to the reference client's verbatim query if the gateway
     * won't accept the added `deviceSlots` selection.
     *
     * One unknown field fails a whole GraphQL operation, so an unsupported selection would
     * otherwise take enrollment down with it. A failed or data-less extended response is retried
     * without the selection: if the retry succeeds, the selection was the problem and we latch it
     * off for this instance; if the retry fails too, the error was real and propagates unlatched.
     */
    private fun fetchUserInfoJson(tokens: SessionTokens): String {
        if (deviceSlotsSupported == false) {
            return authedPost(tokens, RivianGql.getUserInfoBody(includeDeviceSlots = false))
        }
        val attempt = runCatching { authedPost(tokens, RivianGql.getUserInfoBody(includeDeviceSlots = true)) }
        attempt.getOrNull()?.takeIf { RivianGql.hasUserInfoData(it) }?.let {
            if (deviceSlotsSupported == null) logi("getUserInfo: deviceSlots selection accepted")
            deviceSlotsSupported = true
            return it
        }
        logw(
            "getUserInfo: query with deviceSlots returned no usable data " +
                "(${attempt.exceptionOrNull()?.javaClass?.simpleName ?: "no data.currentUser"}) — " +
                "retrying without it",
        )
        // Not wrapped: if this throws, the failure was never about deviceSlots, and leaving
        // deviceSlotsSupported null means the next call tries the full query again.
        val base = authedPost(tokens, RivianGql.getUserInfoBody(includeDeviceSlots = false))
        deviceSlotsSupported = false
        logw("getUserInfo: gateway rejects deviceSlots — phone-key limit pre-flight disabled")
        return base
    }

    fun getUserInfo(tokens: SessionTokens): RivianGql.UserInfo {
        logi("getUserInfo: requesting")
        val info = RivianGql.parseUserInfo(fetchUserInfoJson(tokens))
        logi("getUserInfo: userId=${info.userId} vehicles=${info.vehicles.size} vins=${info.vehicles.map { it.vin }}")
        // Slot counts drive the pre-flight key-limit check — log them (counts only, no key material).
        info.vehicles.forEach { v ->
            val enrolled = info.enrolledKeys.count { it.vehicleId == v.vehicleId }
            logi("getUserInfo: vehicle=${v.label} phoneSlots max=${v.phoneSlotsMax} free=${v.phoneSlotsFree} enrolledKeysVisible=$enrolled")
        }
        return info
    }

    /**
     * Enroll [publicKeyHex] (the watch's public key) against [vehicleId], then
     * re-read getUserInfo to recover the resulting vasPhoneId + identityId.
     * Returns the enrolled-phone identifiers on success.
     */
    fun enrollPhone(
        tokens: SessionTokens,
        userId: String,
        vehicleId: String,
        publicKeyHex: String,
        deviceName: String,
        // When true, register as a WATCH by sending the optional EnrollPhoneAttributes fields the
        // official app leaves absent — keyDeviceSubtype="WATCH" and source="MOBILE" — instead of
        // overriding `type` (which the cloud ignored for device class). `type` stays "phone" either way.
        // CONFIRMED 2026-06-17 (on-vehicle): the server honours keyDeviceSubtype="WATCH" — the key then
        // shows up as a watch in the vehicle, and the car does NO passive lock/unlock for it (drive still
        // works). So asWatch=true → manual-lock/unlock-only key; asWatch=false → full proximity (phone).
        asWatch: Boolean,
    ): RivianGql.EnrolledPhone {
        val keyDeviceSubtype = if (asWatch) "WATCH" else null
        val source = if (asWatch) "MOBILE" else null
        val body = RivianGql.enrollPhoneBody(
            userId = userId,
            vehicleId = vehicleId,
            publicKeyHex = publicKeyHex,
            deviceType = "phone",
            deviceName = deviceName,
            keyDeviceSubtype = keyDeviceSubtype,
            source = source,
        )
        logi("enrollPhone: vehicleId=$vehicleId asWatch=$asWatch type=phone keyDeviceSubtype=$keyDeviceSubtype source=$source deviceName=$deviceName publicKeyLen=${publicKeyHex.length}")
        // Don't dump the raw request/response — they carry the public key + account identifiers.
        // The concise line above plus the parsed read-back below are enough for diagnostics.
        val resp = authedPost(tokens, body)
        logi("enrollPhone: response bytes=${resp.length}")
        val ok = RivianGql.parseEnrollSuccess(resp)
        logi("enrollPhone: success=$ok")
        if (!ok) {
            // Backstop for the pre-flight slot check in EnrollViewModel: the vehicle can fill up
            // between getUserInfo and here, or report no slot counts at all.
            if (RivianGql.isEnrollLimitError(resp)) {
                logw("enrollPhone: rejected — vehicle is at its phone-key limit")
                throw RivianCloudError(
                    "This vehicle has reached its phone-key limit. Remove a key in the Rivian app, " +
                        "then enroll the watch again.",
                )
            }
            throw RivianCloudError("EnrollPhone returned success=false")
        }
        val refreshed = fetchUserInfoJson(tokens)
        return RivianGql.findEnrolledPhone(refreshed, publicKeyHex)?.also {
            logi("enrollPhone: read back vasPhoneId=${it.vasPhoneId} identityId=${it.identityId} " +
                "deviceType='${it.deviceType}' keyDeviceSubtype='${it.keyDeviceSubtype}' (sent asWatch=$asWatch)")
        } ?: run {
            logw("enrollPhone: enrolled phone not found in getUserInfo after EnrollPhone")
            throw RivianCloudError("Enrolled phone not found in getUserInfo after EnrollPhone")
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val BASE_HEADERS = org.fivesevenfive.wearvian.companion.auth.RivianAuthClient.BASE_HEADERS
    }
}
