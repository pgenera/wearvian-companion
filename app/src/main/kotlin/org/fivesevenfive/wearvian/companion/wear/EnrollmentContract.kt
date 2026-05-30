package org.fivesevenfive.wearvian.companion.wear

import org.fivesevenfive.wearvian.companion.auth.RivianAuthClient.SessionTokens
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire contract for the watch <-> phone Data Layer handoff. See PROTOCOL.md.
 * Keep paths/payload shapes in sync with the watch app.
 */
object EnrollmentContract {
    const val VERSION = 1

    // Capabilities (wear.xml on each side advertises one of these).
    const val CAP_PHONE = "wearvian_companion_enrollment"
    const val CAP_WATCH = "wearvian_watch"

    // Message paths.
    const val PATH_REQUEST = "/wearvian/enroll/request"
    const val PATH_RESULT = "/wearvian/enroll/result"

    /** Parsed watch -> phone enrollment request. */
    data class Request(
        val requestId: String,
        val publicKeyHex: String,
        val deviceName: String,
        val deviceType: String,
    ) {
        companion object {
            fun parse(bytes: ByteArray): Request {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                return Request(
                    requestId = o.getString("requestId"),
                    publicKeyHex = o.getString("publicKey"),
                    deviceName = o.optString("deviceName", "watch"),
                    deviceType = o.optString("deviceType", "watch"),
                )
            }
        }
    }

    data class VehicleResult(
        val vehicleId: String,
        val vin: String,
        val vasVehicleId: String,
        val vehiclePublicKey: String,
        val vasPhoneId: String,
        val identityId: String,
    )

    fun successResult(
        requestId: String,
        vehicles: List<VehicleResult>,
        userId: String,
        tokens: SessionTokens,
    ): ByteArray {
        val arr = JSONArray()
        vehicles.forEach { v ->
            arr.put(
                JSONObject()
                    .put("vehicleId", v.vehicleId)
                    .put("vin", v.vin)
                    .put("vasVehicleId", v.vasVehicleId)
                    .put("vehiclePublicKey", v.vehiclePublicKey)
                    .put("vasPhoneId", v.vasPhoneId)
                    .put("identityId", v.identityId),
            )
        }
        return JSONObject()
            .put("v", VERSION)
            .put("requestId", requestId)
            .put("status", "ok")
            .put("vehicles", arr)
            .put("userId", userId)
            .put(
                "sessionTokens",
                JSONObject()
                    .put("csrfToken", tokens.csrfToken)
                    .put("appSessionToken", tokens.appSessionToken)
                    .put("userSessionToken", tokens.userSessionToken),
            )
            .toString().toByteArray(Charsets.UTF_8)
    }

    fun errorResult(requestId: String, error: String): ByteArray =
        JSONObject()
            .put("v", VERSION)
            .put("requestId", requestId)
            .put("status", "error")
            .put("error", error)
            .toString().toByteArray(Charsets.UTF_8)
}
