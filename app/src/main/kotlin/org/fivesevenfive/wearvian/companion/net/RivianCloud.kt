package org.fivesevenfive.wearvian.companion.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fivesevenfive.wearvian.companion.auth.RivianAuthClient.SessionTokens
import org.fivesevenfive.wearvian.companion.protocol.RivianGql

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
            if (resp.code != 200) throw RivianCloudError("HTTP ${resp.code}: $payload")
            return payload
        }
    }

    fun getUserInfo(tokens: SessionTokens): RivianGql.UserInfo =
        RivianGql.parseUserInfo(authedPost(tokens, RivianGql.getUserInfoBody()))

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
    ): RivianGql.EnrolledPhone {
        val body = RivianGql.enrollPhoneBody(
            userId = userId,
            vehicleId = vehicleId,
            publicKeyHex = publicKeyHex,
            deviceType = "phone",
            deviceName = deviceName,
        )
        val ok = RivianGql.parseEnrollSuccess(authedPost(tokens, body))
        if (!ok) throw RivianCloudError("EnrollPhone returned success=false")
        val refreshed = authedPost(tokens, RivianGql.getUserInfoBody())
        return RivianGql.findEnrolledPhone(refreshed, publicKeyHex)
            ?: throw RivianCloudError("Enrolled phone not found in getUserInfo after EnrollPhone")
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val BASE_HEADERS = org.fivesevenfive.wearvian.companion.auth.RivianAuthClient.BASE_HEADERS
    }
}
