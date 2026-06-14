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

    fun getUserInfo(tokens: SessionTokens): RivianGql.UserInfo {
        logi("getUserInfo: requesting")
        val info = RivianGql.parseUserInfo(authedPost(tokens, RivianGql.getUserInfoBody()))
        logi("getUserInfo: userId=${info.userId} vehicles=${info.vehicles.size} vins=${info.vehicles.map { it.vin }}")
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
        // EXPERIMENT (see docs): when true, try to register as a watch by sending the optional
        // EnrollPhoneAttributes fields the official app leaves absent — keyDeviceSubtype="WATCH" and
        // source="MOBILE" — instead of overriding `type` (which the cloud ignored for device class).
        // `type` stays "phone" either way. Unconfirmed the server honors keyDeviceSubtype="WATCH";
        // we log the readback deviceType/keyDeviceSubtype so the result is visible in logcat.
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
        logi("enrollPhone: request body=$body")
        val resp = authedPost(tokens, body)
        logi("enrollPhone: response=$resp")
        val ok = RivianGql.parseEnrollSuccess(resp)
        logi("enrollPhone: success=$ok")
        if (!ok) throw RivianCloudError("EnrollPhone returned success=false")
        val refreshed = authedPost(tokens, RivianGql.getUserInfoBody())
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
