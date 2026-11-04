package com.gmodkit.tools.steam

import com.gmodkit.tools.network.WorkshopException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DepotManifestTest {

    private val manifest = DepotManifest.parse(
        SteamFixtures.bytes("manifest.bin"),
        SteamFixtures.depotKey()
    )

    @Test
    fun readsTheMetadataSection() {
        val item = SteamFixtures.json("item.json")
        val metadata = item.getJSONObject("manifest")
        assertEquals(item.getString("gid").toLong(), manifest.gid)
        assertEquals(metadata.getLong("depotId").toInt(), manifest.depotId)
        assertEquals(metadata.getLong("cbDiskOriginal"), manifest.totalUncompressed)
    }

    @Test
    fun readsTheAddonFileAndItsChunks() {
        val file = DepotManifest.pickContent(manifest, null)
        val expected = SteamFixtures.json("item.json").getJSONArray("files").getJSONObject(0)

        assertEquals(expected.getString("name"), file.name)
        assertEquals(expected.getLong("size"), file.size)
        assertEquals(expected.getLong("chunks").toInt(), file.chunks.size)
        assertTrue(!file.isDirectory)
        assertEquals(file.size, file.chunks.sumOf { it.originalSize.toLong() })
    }

    @Test
    fun keepsTheChunkTableExactlyAsSteamWroteIt() {
        val file = DepotManifest.pickContent(manifest, null)
        val bySha = file.chunks.associateBy { SteamFixtures.hex(it.sha1) }
        val recorded = SteamFixtures.json("item.json").getJSONArray("chunks")

        for (i in 0 until recorded.length()) {
            val expected = recorded.getJSONObject(i)
            val chunk = bySha.getValue(expected.getString("sha1"))
            assertEquals(expected.getLong("offset"), chunk.offset)
            assertEquals(expected.getLong("cbOriginal").toInt(), chunk.originalSize)
            assertEquals(expected.getLong("cbCompressed").toInt(), chunk.compressedSize)
            assertEquals(20, chunk.sha1.size)
        }

        // The manifest lists chunks in upload order; the file is assembled by offset.
        val lowest = file.chunks.minByOrNull { it.offset }
        assertTrue(lowest != null)
        assertEquals(0L, lowest!!.offset)
        assertEquals(
            "c9b59fe291e42f36619554111dfe8c30abe324a3",
            SteamFixtures.hex(lowest.sha1)
        )
    }

    @Test
    fun prefersTheNamedFileAndFallsBackToTheLargest() {
        val file = DepotManifest.pickContent(manifest, null)
        assertEquals(file, DepotManifest.pickContent(manifest, "some/dir/160250458.gma"))
        assertEquals(file, DepotManifest.pickContent(manifest, "160250458.GMA"))
        assertEquals(file, DepotManifest.pickContent(manifest, "not-in-this-addon.zip"))
    }

    @Test
    fun refusesDataThatIsNotAManifest() {
        val key = SteamFixtures.depotKey()
        assertThrows(WorkshopException::class.java) {
            DepotManifest.parse(ByteArray(0), key)
        }
        assertThrows(WorkshopException::class.java) {
            DepotManifest.parse("this is not a depot manifest at all".toByteArray(), key)
        }
    }
}
