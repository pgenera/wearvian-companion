package org.fivesevenfive.wearvian.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone-key slot accounting. The vehicle's limit is whatever `deviceSlots.phone` reports —
 * these tests pin that we never substitute a guessed constant for it.
 */
class RivianGqlSlotTest {

    private val ourKey = "04AABB"
    private val otherKey = "04CCDD"

    private fun vehicle(max: Int? = null, free: Int? = null) = RivianGql.Vehicle(
        vehicleId = "veh-1",
        vin = "7FCTGAAA1NN001234",
        name = "R1S",
        vasVehicleId = "vas-1",
        vehiclePublicKey = "04FFFF",
        phoneSlotsMax = max,
        phoneSlotsFree = free,
    )

    private fun key(publicKey: String, vehicleId: String = "veh-1", subtype: String = "") =
        RivianGql.EnrolledKey(
            publicKey = publicKey,
            vehicleId = vehicleId,
            deviceType = "phone",
            deviceName = "device",
            keyDeviceSubtype = subtype,
        )

    @Test
    fun `free slots reported means ok`() {
        val check = RivianGql.checkPhoneKeySlot(vehicle(max = 4, free = 2), emptyList(), ourKey)
        assertEquals(RivianGql.SlotCheck.Ok(free = 2), check)
    }

    @Test
    fun `zero free slots is full and reports the vehicle's own max`() {
        val enrolled = List(4) { key(publicKey = "04AA0$it") }
        val check = RivianGql.checkPhoneKeySlot(vehicle(max = 4, free = 0), enrolled, ourKey)
        assertEquals(RivianGql.SlotCheck.Full("R1S", used = 4, max = 4), check)
    }

    /** The limit is per-vehicle, not a constant — a vehicle reporting 6 must be treated as 6. */
    @Test
    fun `limit comes from the vehicle, not a hardcoded four`() {
        val enrolled = List(5) { key(publicKey = "04AA0$it") }
        val ok = RivianGql.checkPhoneKeySlot(vehicle(max = 6), enrolled, ourKey)
        assertEquals(RivianGql.SlotCheck.Ok(free = 1), ok)

        val full = RivianGql.checkPhoneKeySlot(vehicle(max = 2), enrolled.take(2), ourKey)
        assertEquals(RivianGql.SlotCheck.Full("R1S", used = 2, max = 2), full)
    }

    /** Re-enrolling the watch must not be blocked by the slot our own key already occupies. */
    @Test
    fun `our own already-enrolled key is a re-enroll, not a full vehicle`() {
        val enrolled = List(3) { key(publicKey = "04AA0$it") } + key(ourKey)
        val check = RivianGql.checkPhoneKeySlot(vehicle(max = 4, free = 0), enrolled, ourKey)
        assertEquals(RivianGql.SlotCheck.AlreadyEnrolled, check)
    }

    @Test
    fun `our key is matched case-insensitively`() {
        val check = RivianGql.checkPhoneKeySlot(
            vehicle(max = 4, free = 0),
            listOf(key(ourKey.lowercase())),
            ourKey.uppercase(),
        )
        assertEquals(RivianGql.SlotCheck.AlreadyEnrolled, check)
    }

    @Test
    fun `keys on other vehicles do not count against this one`() {
        val enrolled = List(4) { key(publicKey = "04AA0$it", vehicleId = "veh-2") }
        val check = RivianGql.checkPhoneKeySlot(vehicle(max = 4), enrolled, ourKey)
        assertEquals(RivianGql.SlotCheck.Ok(free = 4), check)
    }

    /** `free` is authoritative because it also counts keys this account cannot see. */
    @Test
    fun `free wins over the visible key count`() {
        val check = RivianGql.checkPhoneKeySlot(vehicle(max = 4, free = 0), listOf(key(otherKey)), ourKey)
        assertEquals(RivianGql.SlotCheck.Full("R1S", used = 4, max = 4), check)
    }

    @Test
    fun `no slot information means unknown, not blocked`() {
        val check = RivianGql.checkPhoneKeySlot(vehicle(), listOf(key(otherKey)), ourKey)
        assertEquals(RivianGql.SlotCheck.Unknown, check)
    }

    @Test
    fun `full message names the vehicle and its limit`() {
        val msg = RivianGql.slotFullMessage(RivianGql.SlotCheck.Full("R1S", used = 4, max = 4))
        assertTrue(msg, msg.contains("R1S"))
        assertTrue(msg, msg.contains("4 phone keys"))
        assertTrue(msg, msg.contains("Rivian app"))

        // The limit in the copy is the vehicle's, not a constant.
        val six = RivianGql.slotFullMessage(RivianGql.SlotCheck.Full("R1T", used = 6, max = 6))
        assertTrue(six, six.contains("6 phone keys"))
        assertFalse(six, six.contains("4"))
    }

    @Test
    fun `vehicle label falls back from name to vin to id`() {
        assertEquals("R1S", vehicle().label)
        assertEquals(
            "7FCTGAAA1NN001234",
            vehicle().copy(name = "").label,
        )
        assertEquals("veh-1", vehicle().copy(name = "", vin = "").label)
    }

    @Test
    fun `getUserInfo query requests the phone slot counts`() {
        val body = RivianGql.getUserInfoBody()
        assertTrue(body, body.contains("deviceSlots"))
        assertTrue(body, body.contains("phone { max free }"))
    }

    /**
     * The fallback query must be the reference client's verbatim shape — everything the
     * enrollment flow needs, minus the one selection the gateway might not accept.
     */
    @Test
    fun `fallback query drops deviceSlots but keeps every field enrollment needs`() {
        val body = RivianGql.getUserInfoBody(includeDeviceSlots = false)
        assertFalse(body, body.contains("deviceSlots"))
        listOf(
            "vasVehicleId", "vehiclePublicKey", "enrolledPhones", "vasPhoneId",
            "publicKey", "identityId", "keyDeviceSubtype", "vehicleId",
        ).forEach { assertTrue("$it missing from $body", body.contains(it)) }
        // No double space where the selection was spliced out.
        assertFalse(body, body.contains("model  expectedBuildDate"))
    }

    @Test
    fun `hasUserInfoData distinguishes a usable response from a validation error`() {
        assertTrue(RivianGql.hasUserInfoData("""{"data":{"currentUser":{"id":"u1","vehicles":[]}}}"""))
        assertFalse(
            RivianGql.hasUserInfoData(
                """{"errors":[{"message":"Cannot query field \"deviceSlots\""}],"data":null}""",
            ),
        )
        assertFalse(RivianGql.hasUserInfoData("""{"data":{}}"""))
        assertFalse(RivianGql.hasUserInfoData("not json"))
    }

    /** Losing the slot counts must degrade to "let the server decide", never to "blocked". */
    @Test
    fun `a response without deviceSlots never blocks enrollment`() {
        val json = """
            {"data":{"currentUser":{"id":"user-1",
              "vehicles":[{"id":"veh-1","vin":"VIN1","name":"R1S",
                "vas":{"vasVehicleId":"vas-1","vehiclePublicKey":"04FFFF"},
                "vehicle":{"id":"veh-1"}}],
              "enrolledPhones":[
                {"vas":{"publicKey":"04AA01"},"enrolled":{"vehicleId":"veh-1"}},
                {"vas":{"publicKey":"04AA02"},"enrolled":{"vehicleId":"veh-1"}},
                {"vas":{"publicKey":"04AA03"},"enrolled":{"vehicleId":"veh-1"}},
                {"vas":{"publicKey":"04AA04"},"enrolled":{"vehicleId":"veh-1"}},
                {"vas":{"publicKey":"04AA05"},"enrolled":{"vehicleId":"veh-1"}}]}}}
        """.trimIndent()

        val info = RivianGql.parseUserInfo(json)
        // Five visible keys — more than any plausible limit — still must not be reported Full,
        // because without deviceSlots we have no limit to compare against.
        assertEquals(
            RivianGql.SlotCheck.Unknown,
            RivianGql.checkPhoneKeySlot(info.vehicles.single(), info.enrolledKeys, ourKey),
        )
    }

    @Test
    fun `parseUserInfo reads slot counts and enrolled keys`() {
        val json = """
            {"data":{"currentUser":{"id":"user-1",
              "vehicles":[{"id":"veh-1","vin":"VIN1","name":"R1S",
                "vas":{"vasVehicleId":"vas-1","vehiclePublicKey":"04FFFF"},
                "vehicle":{"id":"veh-1","deviceSlots":{"phone":{"max":4,"free":1},
                                                       "fob2":{"max":2,"free":2}}}}],
              "enrolledPhones":[
                {"vas":{"vasPhoneId":"p1","publicKey":"04AABB"},
                 "enrolled":{"deviceType":"phone","deviceName":"Pixel","keyDeviceSubtype":"",
                             "vehicleId":"veh-1","identityId":"i1"}},
                {"vas":{"vasPhoneId":"p2","publicKey":"04CCDD"},
                 "enrolled":{"deviceType":"phone","deviceName":"Watch","keyDeviceSubtype":"WATCH",
                             "vehicleId":"veh-1","identityId":"i2"}}]}}}
        """.trimIndent()

        val info = RivianGql.parseUserInfo(json)
        assertEquals("user-1", info.userId)
        assertEquals(1, info.vehicles.size)
        assertEquals(4, info.vehicles[0].phoneSlotsMax)
        assertEquals(1, info.vehicles[0].phoneSlotsFree)
        assertEquals(2, info.enrolledKeys.size)
        assertEquals("veh-1", info.enrolledKeys[0].vehicleId)
        assertFalse(info.enrolledKeys[0].isWatch)
        assertTrue(info.enrolledKeys[1].isWatch)
    }

    /** Absent slot counts must stay null — 0 would wrongly read as "vehicle full". */
    @Test
    fun `missing deviceSlots parses as null, not zero`() {
        val json = """
            {"data":{"currentUser":{"id":"user-1",
              "vehicles":[{"id":"veh-1","vin":"VIN1","name":"R1S",
                "vas":{"vasVehicleId":"vas-1","vehiclePublicKey":"04FFFF"},
                "vehicle":{"id":"veh-1"}}]}}}
        """.trimIndent()

        val v = RivianGql.parseUserInfo(json).vehicles.single()
        assertNull(v.phoneSlotsMax)
        assertNull(v.phoneSlotsFree)
        assertEquals(RivianGql.SlotCheck.Unknown, RivianGql.checkPhoneKeySlot(v, emptyList(), ourKey))
    }

    @Test
    fun `explicit null slot counts parse as null`() {
        val json = """
            {"data":{"currentUser":{"id":"user-1",
              "vehicles":[{"id":"veh-1","vin":"VIN1","name":"R1S",
                "vas":{"vasVehicleId":"vas-1","vehiclePublicKey":"04FFFF"},
                "vehicle":{"id":"veh-1","deviceSlots":{"phone":{"max":null,"free":null}}}}]}}}
        """.trimIndent()

        val v = RivianGql.parseUserInfo(json).vehicles.single()
        assertNull(v.phoneSlotsMax)
        assertNull(v.phoneSlotsFree)
    }

    @Test
    fun `parseUserInfo tolerates the parallel-array enrolledPhones shape`() {
        val json = """
            {"data":{"currentUser":{"id":"user-1","vehicles":[],
              "enrolledPhones":{
                "vas":[{"vasPhoneId":"p1","publicKey":"04AABB"}],
                "enrolled":[{"deviceType":"phone","deviceName":"Pixel","vehicleId":"veh-1"}]}}}}
        """.trimIndent()

        val keys = RivianGql.parseUserInfo(json).enrolledKeys
        assertEquals(1, keys.size)
        assertEquals("04AABB", keys[0].publicKey)
        assertEquals("veh-1", keys[0].vehicleId)
    }

    @Test
    fun `server over-limit rejection is recognised`() {
        val err = """
            {"errors":[{"message":"Bad request: ENROLL_PHONE_LIMIT_REACHED","path":["enrollPhone"]}],
             "data":{"enrollPhone":null}}
        """.trimIndent()
        assertTrue(RivianGql.isEnrollLimitError(err))
        assertFalse(RivianGql.parseEnrollSuccess(err))
    }

    @Test
    fun `other errors are not treated as an over-limit rejection`() {
        val err = """{"errors":[{"message":"UNAUTHENTICATED"}],"data":null}"""
        assertFalse(RivianGql.isEnrollLimitError(err))
        assertFalse(RivianGql.isEnrollLimitError("""{"data":{"enrollPhone":{"success":true}}}"""))
        assertFalse(RivianGql.isEnrollLimitError("not json"))
    }
}
