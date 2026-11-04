package com.gmodkit.tools.steam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The protobuf layer is checked against request bodies captured from a working
 * session, so a field number that drifts fails here instead of on a phone.
 */
class ProtoWireTest {

    @Test
    fun writesTheLoggedOnRequestBody() {
        val body = ProtoWriter()
            .varint(1, PROTOCOL_VERSION)
            .varint(5, CLIENT_PACKAGE_VERSION)
            .toByteArray()
        assertArrayEquals(recorded("CMsgClientLogon"), body)
    }

    @Test
    fun writesTheManifestRequestCodeBody() {
        val body = ProtoWriter()
            .varint(1, APP_ID)
            .varint(2, APP_ID)
            .varint(3, MANIFEST_GID)
            .toByteArray()
        assertArrayEquals(recorded("CContentServerDirectory_GetManifestRequestCode_Request"), body)
    }

    @Test
    fun writesTheDepotKeyBody() {
        val body = ProtoWriter()
            .varint(1, APP_ID)
            .varint(2, APP_ID)
            .toByteArray()
        assertArrayEquals(recorded("CMsgClientGetDepotDecryptionKey"), body)
    }

    @Test
    fun readsBackEveryWireType() {
        val nested = ProtoWriter().varint(7, 9).toByteArray()
        val body = ProtoWriter()
            .varint(1, 300)
            .fixed64(2, -1L)
            .bytes(3, byteArrayOf(1, 2, 3))
            .string(4, "ContentServerDirectory.GetManifestRequestCode")
            .message(5, nested)
            .bool(6, true)
            .varint(24, 7)
            .toByteArray()

        assertEquals(300L, ProtoReader.varint(body, 1))
        assertEquals(-1L, ProtoReader.fixed64(body, 2))
        assertArrayEquals(byteArrayOf(1, 2, 3), ProtoReader.bytes(body, 3))
        assertEquals(
            "ContentServerDirectory.GetManifestRequestCode",
            ProtoReader.string(body, 4)
        )
        assertEquals(9L, ProtoReader.varint(ProtoReader.bytes(body, 5), 7))
        assertEquals(1L, ProtoReader.varint(body, 6))
        assertEquals(7L, ProtoReader.varint(body, 24))
    }

    @Test
    fun skipsProto3Defaults() {
        val body = ProtoWriter()
            .varint(1, 0)
            .bool(2, false)
            .string(3, "")
            .bytes(4, null)
            .varint(5, 1)
            .toByteArray()

        // Only field 5 survives, which is what Steam's own clients send.
        assertArrayEquals(byteArrayOf(0x28, 0x01), body)
        assertEquals(1L, ProtoReader.varint(body, 5))
        assertEquals(0L, ProtoReader.varint(body, 1))
        assertEquals("", ProtoReader.string(body, 3))
        assertTrue(ProtoReader.messages(body, 9).isEmpty())
    }

    @Test
    fun walksEveryFieldInOrder() {
        // Tag 0x1D is field 3 on the 32-bit wire; the writer has no fixed32 field
        // because nothing in this protocol sends one.
        val body = ProtoWriter()
            .varint(1, 5)
            .string(2, "hello")
            .toByteArray() + byteArrayOf(0x1D, 7, 0, 0, 0)

        val reader = ProtoReader(body)
        val numbers = ArrayList<Int>()
        val values = ArrayList<Any>()
        while (reader.hasNext()) {
            val field = reader.readField()
            numbers += field.number
            values += reader.readValue(field)
        }

        assertEquals(listOf(1, 2, 3), numbers)
        assertEquals(5L, values[0])
        assertArrayEquals("hello".toByteArray(), values[1] as ByteArray)
        assertEquals(7, values[2])
    }

    @Test
    fun printsUint64sWithoutASign() {
        assertEquals("0", unsignedToString(0))
        assertEquals("4000", unsignedToString(4000))
        assertEquals("9223372036854775807", unsignedToString(Long.MAX_VALUE))
        assertEquals("9223372036854775808", unsignedToString(Long.MIN_VALUE))
        assertEquals("18446744073709551615", unsignedToString(-1))
        // A request code seen on the wire, where Steam really means 13561283537753393759.
        assertEquals("13561283537753393759", unsignedToString(-4885460535956157857))
        assertEquals("13706163053230873407", unsignedToString(-4740581020478678209))
    }

    private fun recorded(name: String): ByteArray {
        val messages = SteamFixtures.json("proto-vectors.json").getJSONArray("messages")
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            if (message.getString("name") == name) {
                return SteamFixtures.fromHex(message.getString("hex"))
            }
        }
        throw IllegalStateException("No recorded request body for $name")
    }

    private companion object {
        const val PROTOCOL_VERSION = 65580L
        const val CLIENT_PACKAGE_VERSION = 1561159470L
        const val APP_ID = 4000L
        const val MANIFEST_GID = 629170255479464089L
    }
}
