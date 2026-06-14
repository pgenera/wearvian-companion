package org.fivesevenfive.wearvian.companion.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builders and parsers for the two Rivian GraphQL operations the companion runs
 * during enrollment: `getUserInfo` and `EnrollPhone`.
 *
 * Query strings are copied verbatim from the reference client
 * (`bretterer/rivian-python-client`, `rivian.py`: `get_user_information`,
 * `enroll_phone`). This module only shapes request bodies and reads responses;
 * the HTTP call + auth headers live in [org.fivesevenfive.wearvian.companion.net].
 * Framework-free (only org.json) for easy unit testing.
 *
 * NOTE: this is intentionally a copy of the watch app's `protocol/RivianGql.kt`
 * so this companion repo stays independent and open-sourceable on its own.
 */
object RivianGql {

    const val GATEWAY_URL = "https://rivian.com/api/gql/gateway/graphql"

    private const val VEHICLES_FRAGMENT =
        "vehicles { id vin name vas { __typename vasVehicleId vehiclePublicKey } roles state " +
            "createdAt updatedAt vehicle { __typename id vin modelYear make model " +
            "expectedBuildDate plannedBuildDate expectedGeneralAssemblyStartDate " +
            "actualGeneralAssemblyDate vehicleState { supportedFeatures { __typename name status } } } }"
    private const val PHONES_FRAGMENT =
        "enrolledPhones { __typename vas { __typename vasPhoneId publicKey } " +
            "enrolled { __typename deviceType deviceName keyDeviceSubtype vehicleId identityId shortName } }"

    fun getUserInfoBody(): String =
        JSONObject()
            .put("operationName", "getUserInfo")
            .put(
                "query",
                "query getUserInfo { currentUser { __typename id $VEHICLES_FRAGMENT $PHONES_FRAGMENT } }",
            )
            .put("variables", JSONObject.NULL)
            .toString()

    fun enrollPhoneBody(
        userId: String,
        vehicleId: String,
        publicKeyHex: String,
        deviceType: String,
        deviceName: String,
        // EnrollPhoneAttributes also accepts these (the official app leaves them absent). The decompile
        // shows keyDeviceSubtype is a nullable String and source an enum (MOBILE/WEB). For the
        // watch-key experiment we send keyDeviceSubtype="WATCH" + source="MOBILE"; null = omit.
        keyDeviceSubtype: String? = null,
        source: String? = null,
    ): String {
        val attrs = JSONObject()
            .put("userId", userId)
            .put("vehicleId", vehicleId)
            .put("publicKey", publicKeyHex)
            .put("type", deviceType)
            .put("name", deviceName)
        if (keyDeviceSubtype != null) attrs.put("keyDeviceSubtype", keyDeviceSubtype)
        if (source != null) attrs.put("source", source)
        return JSONObject()
            .put("operationName", "EnrollPhone")
            .put("variables", JSONObject().put("attrs", attrs))
            .put(
                "query",
                "mutation EnrollPhone(\$attrs: EnrollPhoneAttributes!) { enrollPhone(attrs: \$attrs) { __typename success } }",
            )
            .toString()
    }

    data class Vehicle(
        val vehicleId: String,
        val vin: String,
        val name: String,
        val vasVehicleId: String,
        val vehiclePublicKey: String,
    )

    data class UserInfo(val userId: String, val vehicles: List<Vehicle>)

    /** Enrolled-key fields looked up by our public key. deviceType/keyDeviceSubtype are the
     *  server's readback of how the key was classified — logged to verify the watch experiment. */
    data class EnrolledPhone(
        val vasPhoneId: String,
        val identityId: String,
        val deviceType: String = "",
        val keyDeviceSubtype: String = "",
    )

    fun parseUserInfo(responseJson: String): UserInfo {
        val user = JSONObject(responseJson).getJSONObject("data").getJSONObject("currentUser")
        val vehicles = mutableListOf<Vehicle>()
        val arr = user.optJSONArray("vehicles") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val vas = v.optJSONObject("vas") ?: continue
            vehicles.add(
                Vehicle(
                    vehicleId = v.optString("id"),
                    vin = v.optString("vin"),
                    name = v.optString("name"),
                    vasVehicleId = vas.optString("vasVehicleId"),
                    vehiclePublicKey = vas.optString("vehiclePublicKey"),
                ),
            )
        }
        return UserInfo(userId = user.optString("id"), vehicles = vehicles)
    }

    fun parseEnrollSuccess(responseJson: String): Boolean =
        JSONObject(responseJson)
            .optJSONObject("data")?.optJSONObject("enrollPhone")?.optBoolean("success", false)
            ?: false

    /**
     * Find the enrolled phone matching [publicKeyHex] and return its vasPhoneId +
     * identityId. Tolerant of `enrolledPhones` being either a list of
     * `{vas, enrolled}` objects or a single object with parallel `vas`/`enrolled`
     * arrays, since the schema is not formally documented.
     */
    fun findEnrolledPhone(responseJson: String, publicKeyHex: String): EnrolledPhone? {
        val user = JSONObject(responseJson).getJSONObject("data").getJSONObject("currentUser")
        val target = publicKeyHex.lowercase()

        fun match(vas: JSONObject?, enrolled: JSONObject?): EnrolledPhone? {
            if (vas == null) return null
            if (vas.optString("publicKey").lowercase() != target) return null
            return EnrolledPhone(
                vasPhoneId = vas.optString("vasPhoneId"),
                identityId = enrolled?.optString("identityId").orEmpty(),
                deviceType = enrolled?.optString("deviceType").orEmpty(),
                keyDeviceSubtype = enrolled?.optString("keyDeviceSubtype").orEmpty(),
            )
        }

        when (val ep = user.opt("enrolledPhones")) {
            is JSONArray -> {
                for (i in 0 until ep.length()) {
                    val obj = ep.optJSONObject(i) ?: continue
                    match(obj.optJSONObject("vas"), obj.optJSONObject("enrolled"))?.let { return it }
                }
            }
            is JSONObject -> {
                val vasArr = ep.optJSONArray("vas")
                val enrArr = ep.optJSONArray("enrolled")
                if (vasArr != null) {
                    for (i in 0 until vasArr.length()) {
                        val enr = enrArr?.optJSONObject(i)
                        match(vasArr.optJSONObject(i), enr)?.let { return it }
                    }
                } else {
                    match(ep.optJSONObject("vas"), ep.optJSONObject("enrolled"))?.let { return it }
                }
            }
        }
        return null
    }
}
