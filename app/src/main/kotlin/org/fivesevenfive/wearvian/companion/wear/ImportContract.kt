package org.fivesevenfive.wearvian.companion.wear

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone -> watch *key import* contract (the HA-key flow), plus the QR payload the
 * `tools/ha_to_qr.py` generator emits. Distinct from the watch-initiated
 * [EnrollmentContract]: here the companion holds the private key + resolves the
 * vehicle crypto material from the cloud, then pushes the whole bundle to the
 * watch. Keep in sync with the watch app's `ImportContract`.
 */
object ImportContract {
    const val VERSION = 1
    const val QR_SCHEMA = "wearvian-ha-import"

    const val PATH_KEY = "/wearvian/import/key"
    const val PATH_RESULT = "/wearvian/import/result"

    /** The bits scanned from the QR generated off the HA config entry. */
    data class QrPayload(
        val privateKeyPemBase64: String,
        val publicKeyHex: String,
        val userSessionToken: String,
        val userId: String,
        val username: String,
    )

    /** Parse + validate a scanned QR string. Throws [IllegalArgumentException] if it isn't ours. */
    fun parseQr(text: String): QrPayload {
        val o = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("Not a wearvian import QR (not JSON)") }
        require(o.optString("t") == QR_SCHEMA) { "Not a wearvian import QR" }
        val priv = o.optString("priv")
        val pub = o.optString("pub")
        val usess = o.optString("usess")
        require(priv.isNotBlank() && pub.isNotBlank()) { "QR missing key material" }
        require(usess.isNotBlank()) { "QR missing session token" }
        return QrPayload(
            privateKeyPemBase64 = priv,
            publicKeyHex = pub,
            userSessionToken = usess,
            userId = o.optString("uid"),
            username = o.optString("user"),
        )
    }

    /** Build the phone -> watch key-import payload. */
    fun buildKey(
        requestId: String,
        privateKeyPemBase64: String,
        publicKeyHex: String,
        userId: String,
        asWatch: Boolean,
        vehicles: List<EnrollmentContract.VehicleResult>,
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
            .put("privateKey", privateKeyPemBase64)
            .put("publicKey", publicKeyHex)
            .put("userId", userId)
            .put("asWatch", asWatch)
            .put("vehicles", arr)
            .toString().toByteArray(Charsets.UTF_8)
    }

    data class Ack(val requestId: String, val status: String, val error: String?, val vin: String) {
        val isOk: Boolean get() = status == "ok"
    }

    fun parseAck(bytes: ByteArray): Ack {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        return Ack(
            requestId = o.optString("requestId"),
            status = o.optString("status").ifEmpty { "error" },
            error = o.optString("error").ifEmpty { null },
            vin = o.optString("vin"),
        )
    }
}
