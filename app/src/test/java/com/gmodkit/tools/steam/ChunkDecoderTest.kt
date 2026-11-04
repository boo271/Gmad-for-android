package com.gmodkit.tools.steam

import com.gmodkit.tools.network.WorkshopException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChunkDecoderTest {

    private val key = SteamFixtures.depotKey()

    @Test
    fun rebuildsEveryRecordedChunk() {
        val recorded = SteamFixtures.json("item.json").getJSONArray("chunks")
        for (i in 0 until recorded.length()) {
            val entry = recorded.getJSONObject(i)
            val sha1 = SteamFixtures.fromHex(entry.getString("sha1"))
            val plain = ChunkDecoder.decode(
                SteamFixtures.bytes(entry.getString("file")),
                key,
                sha1
            )
            assertEquals(entry.getLong("plainSize").toInt(), plain.size)
            assertArrayEquals(entry.getString("sha1"), sha1, SteamCrypto.sha1(plain))
        }
    }

    @Test
    fun readsTheLzmaAndZipContainersToo() {
        // GMod has only ever used VSZa chunks, so these two branches are covered
        // by containers the reference client itself can read rather than by real captures.
        val vectors = SteamFixtures.json("container-vectors.json")
        val expectedSha1 = SteamFixtures.fromHex(vectors.getString("sha1"))
        val plainSize = vectors.getLong("plainSize").toInt()
        val entries = vectors.getJSONArray("containers")

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val plain = ChunkDecoder.decode(SteamFixtures.bytes(entry.getString("file")), key, expectedSha1)
            assertEquals(entry.getString("magic"), plain.size, plainSize)
            assertEquals("GMAD", String(plain, 0, 4, Charsets.US_ASCII))
            assertArrayEquals(expectedSha1, SteamCrypto.sha1(plain))
        }
    }

    @Test
    fun theFirstChunkStartsWithAGmaHeader() {
        val sha1 = SteamFixtures.fromHex("c9b59fe291e42f36619554111dfe8c30abe324a3")
        val plain = ChunkDecoder.decode(
            SteamFixtures.bytes("chunk-c9b59fe291e42f36619554111dfe8c30abe324a3.bin"),
            key,
            sha1
        )
        assertEquals("GMAD", String(plain, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun rejectsAChunkThatDoesNotMatchItsManifestId() {
        val container = SteamFixtures.bytes("chunk-c9b59fe291e42f36619554111dfe8c30abe324a3.bin")
        val other = SteamFixtures.fromHex("6cb79788e0efef0660a1f1b37d2ca8b623067db1")

        assertThrows(WorkshopException::class.java) {
            ChunkDecoder.decode(container, key, other)
        }
    }

    @Test
    fun rejectsAContainerItDoesNotUnderstand() {
        val container = SteamCrypto.encrypt(
            "this is not a steam chunk container".toByteArray(),
            ChannelKeys(key, ByteArray(0), ByteArray(0))
        )
        assertThrows(WorkshopException::class.java) {
            ChunkDecoder.decode(container, key, SteamCrypto.sha1("whatever".toByteArray()))
        }
    }
}
