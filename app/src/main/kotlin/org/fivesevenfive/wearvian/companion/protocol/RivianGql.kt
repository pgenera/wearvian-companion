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

    // `deviceSlots` is the vehicle's own report of how many key slots it has and how many are
    // still free — the phone-key limit is per-vehicle and server-defined, NOT a constant we get
    // to assume. The official app selects the same field on the same `vehicles[].vehicle` type
    // (its `VehiclesAndEnrollments` query) and formats its "this vehicle can only have %d phone
    // keys" copy from it. `fob2` is the physical key-fob pool; we only care about `phone`.
    private const val DEVICE_SLOTS = "deviceSlots { phone { max free } fob2 { max free } }"

    /**
     * [includeDeviceSlots] is the only deviation from the reference client's verbatim query. A
     * GraphQL server rejects the WHOLE operation for one unknown field, which would break
     * enrollment outright — so callers must be able to re-issue this without the selection. See
     * [org.fivesevenfive.wearvian.companion.net.RivianCloud]'s fallback.
     */
    private fun vehiclesFragment(includeDeviceSlots: Boolean): String {
        val slots = if (includeDeviceSlots) "$DEVICE_SLOTS " else ""
        return "vehicles { id vin name vas { __typename vasVehicleId vehiclePublicKey } roles state " +
            "createdAt updatedAt vehicle { __typename id vin modelYear make model $slots" +
            "expectedBuildDate plannedBuildDate expectedGeneralAssemblyStartDate " +
            "actualGeneralAssemblyDate vehicleState { supportedFeatures { __typename name status } } } }"
    }
    private const val PHONES_FRAGMENT =
        "enrolledPhones { __typename vas { __typename vasPhoneId publicKey } " +
            "enrolled { __typename deviceType deviceName keyDeviceSubtype vehicleId identityId shortName } }"

    fun getUserInfoBody(includeDeviceSlots: Boolean = true): String =
        JSONObject()
            .put("operationName", "getUserInfo")
            .put(
                "query",
                "query getUserInfo { currentUser { __typename id " +
                    "${vehiclesFragment(includeDeviceSlots)} $PHONES_FRAGMENT } }",
            )
            .put("variables", JSONObject.NULL)
            .toString()

    /**
     * True when a `getUserInfo` response actually carries `data.currentUser`. A GraphQL
     * validation error (e.g. an unknown field in the selection) comes back with `errors` and a
     * null `data`, which this distinguishes from a usable response.
     */
    fun hasUserInfoData(responseJson: String): Boolean =
        runCatching {
            JSONObject(responseJson).optJSONObject("data")?.optJSONObject("currentUser") != null
        }.getOrDefault(false)

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
        /** Phone-key slots this vehicle supports, as reported by the server. Null = not reported. */
        val phoneSlotsMax: Int? = null,
        /** Phone-key slots still free on this vehicle. Null = not reported. */
        val phoneSlotsFree: Int? = null,
    ) {
        /** Human label for error copy: the vehicle's name, else the VIN, else the id. */
        val label: String get() = name.ifBlank { vin.ifBlank { vehicleId } }
    }

    /** One entry of `currentUser.enrolledPhones` — an existing key on the account. */
    data class EnrolledKey(
        val publicKey: String,
        val vehicleId: String,
        val deviceType: String,
        val deviceName: String,
        val keyDeviceSubtype: String,
    ) {
        /** True for keys the vehicle classifies as a watch rather than a phone. */
        val isWatch: Boolean get() = keyDeviceSubtype.equals("WATCH", ignoreCase = true)
    }

    data class UserInfo(
        val userId: String,
        val vehicles: List<Vehicle>,
        /** Every key already enrolled on the account, across all vehicles. */
        val enrolledKeys: List<EnrolledKey> = emptyList(),
    )

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
            // deviceSlots lives on the nested `vehicle` object and may be absent (older schema,
            // a vehicle that doesn't report slots). Absent stays null → "unknown", never 0.
            val phoneSlots = v.optJSONObject("vehicle")
                ?.optJSONObject("deviceSlots")
                ?.optJSONObject("phone")
            vehicles.add(
                Vehicle(
                    vehicleId = v.optString("id"),
                    vin = v.optString("vin"),
                    name = v.optString("name"),
                    vasVehicleId = vas.optString("vasVehicleId"),
                    vehiclePublicKey = vas.optString("vehiclePublicKey"),
                    phoneSlotsMax = phoneSlots?.optIntOrNull("max"),
                    phoneSlotsFree = phoneSlots?.optIntOrNull("free"),
                ),
            )
        }
        return UserInfo(
            userId = user.optString("id"),
            vehicles = vehicles,
            enrolledKeys = parseEnrolledKeys(user),
        )
    }

    /** `optInt` can't distinguish an absent field from 0, and 0 free slots is the case that matters. */
    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (isNull(name)) null else optString(name).toIntOrNull()

    /**
     * Read `currentUser.enrolledPhones` into a flat list. Tolerates the same two response shapes
     * [findEnrolledPhone] does (a list of `{vas, enrolled}`, or one object with parallel arrays).
     */
    private fun parseEnrolledKeys(user: JSONObject): List<EnrolledKey> {
        val out = mutableListOf<EnrolledKey>()

        fun add(vas: JSONObject?, enrolled: JSONObject?) {
            if (vas == null && enrolled == null) return
            out.add(
                EnrolledKey(
                    publicKey = vas?.optString("publicKey").orEmpty(),
                    vehicleId = enrolled?.optString("vehicleId").orEmpty(),
                    deviceType = enrolled?.optString("deviceType").orEmpty(),
                    deviceName = enrolled?.optString("deviceName").orEmpty(),
                    keyDeviceSubtype = enrolled?.optString("keyDeviceSubtype").orEmpty(),
                ),
            )
        }

        when (val ep = user.opt("enrolledPhones")) {
            is JSONArray -> for (i in 0 until ep.length()) {
                val obj = ep.optJSONObject(i) ?: continue
                add(obj.optJSONObject("vas"), obj.optJSONObject("enrolled"))
            }
            is JSONObject -> {
                val vasArr = ep.optJSONArray("vas")
                val enrArr = ep.optJSONArray("enrolled")
                if (vasArr != null) {
                    for (i in 0 until vasArr.length()) add(vasArr.optJSONObject(i), enrArr?.optJSONObject(i))
                } else {
                    add(ep.optJSONObject("vas"), ep.optJSONObject("enrolled"))
                }
            }
        }
        return out
    }

    /** Outcome of the pre-flight phone-key slot check for one vehicle. */
    sealed interface SlotCheck {
        /** There is room (or the vehicle says so). [free] is null when only a max/used count was available. */
        data class Ok(val free: Int?) : SlotCheck
        /** This exact public key is already enrolled here — a re-enroll, not a new slot. */
        data object AlreadyEnrolled : SlotCheck
        /** No slot available. [used] and [max] drive the error copy. */
        data class Full(val vehicleLabel: String, val used: Int, val max: Int) : SlotCheck
        /** The vehicle reported no slot information; let the server decide. */
        data object Unknown : SlotCheck
    }

    /**
     * Decide whether [publicKeyHex] can be enrolled on [vehicle] without hitting the vehicle's
     * phone-key limit.
     *
     * The limit is whatever the vehicle reports in `deviceSlots.phone` — it is not a fixed number,
     * so this never assumes one. Preference order:
     *  1. an already-enrolled copy of our own key → [SlotCheck.AlreadyEnrolled] (re-enrollment must
     *     not be blocked just because we occupy one of the slots),
     *  2. the vehicle's own `free` count — authoritative, and counts keys belonging to other drivers
     *     that this account cannot see in `enrolledPhones`,
     *  3. `max` vs. the keys we can see for this vehicle,
     *  4. nothing reported → [SlotCheck.Unknown]; proceed and let `EnrollPhone` reject it.
     */
    fun checkPhoneKeySlot(
        vehicle: Vehicle,
        enrolledKeys: List<EnrolledKey>,
        publicKeyHex: String,
    ): SlotCheck {
        val forVehicle = enrolledKeys.filter { it.vehicleId == vehicle.vehicleId }
        if (forVehicle.any { it.publicKey.equals(publicKeyHex, ignoreCase = true) }) {
            return SlotCheck.AlreadyEnrolled
        }
        val max = vehicle.phoneSlotsMax
        val free = vehicle.phoneSlotsFree
        if (free != null) {
            if (free > 0) return SlotCheck.Ok(free)
            // max can be absent even when free is present; fall back to what we can count.
            val used = max ?: forVehicle.size
            return SlotCheck.Full(vehicle.label, used = used, max = max ?: used)
        }
        if (max != null) {
            return if (forVehicle.size >= max) {
                SlotCheck.Full(vehicle.label, used = forVehicle.size, max = max)
            } else {
                SlotCheck.Ok(free = max - forVehicle.size)
            }
        }
        return SlotCheck.Unknown
    }

    /**
     * User-facing copy for a [SlotCheck.Full] result. Note each enrollment takes a *new* slot —
     * a previous wearvian key may well be one of the ones occupying them.
     */
    fun slotFullMessage(full: SlotCheck.Full): String =
        "${full.vehicleLabel} is at its limit of ${full.max} phone keys, so the watch can't be " +
            "added. Remove a key in the Rivian app (an older wearvian key, if you have one), " +
            "then enroll again."

    fun parseEnrollSuccess(responseJson: String): Boolean =
        JSONObject(responseJson)
            .optJSONObject("data")?.optJSONObject("enrollPhone")?.optBoolean("success", false)
            ?: false

    /**
     * The server's own over-limit rejection, used as a backstop when the pre-flight
     * [checkPhoneKeySlot] passed (stale slot counts, a key added by another driver between the
     * two calls, or a vehicle that reports no slots at all). GraphQL errors come back HTTP 200
     * with an `errors` array; the code appears inside the message text.
     */
    private const val ENROLL_LIMIT_CODE = "ENROLL_PHONE_LIMIT_REACHED"

    fun isEnrollLimitError(responseJson: String): Boolean =
        runCatching { JSONObject(responseJson).optJSONArray("errors")?.toString() }
            .getOrNull()
            ?.contains(ENROLL_LIMIT_CODE, ignoreCase = true) == true

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
