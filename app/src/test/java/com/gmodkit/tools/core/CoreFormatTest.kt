package com.gmodkit.tools.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CoreFormatTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val active = { true }

    // ---------- GMA ----------

    @Test
    fun gmaV3ReadsMetadataAndExtractsFiles() {
        val files = listOf(
            TestGmaFile("lua/autorun/test.lua", "print('hello')".toByteArray()),
            TestGmaFile("materials/example.vmt", "\"VertexLitGeneric\"".toByteArray()),
            TestGmaFile("models/empty.txt", ByteArray(0))
        )
        val bytes = TestFiles.buildGma(
            version = 3,
            requiredContent = listOf("team_fortress2", "hl2"),
            addonName = "My Addon",
            addonDescription = "Description",
            addonAuthor = "Author",
            addonVersion = 7,
            files = files
        )
        val file = folder.newFile("addon.gma").apply { writeBytes(bytes) }
        GmaArchive.open(FileSource(file)).use { archive ->
            val meta = archive.metadata
            assertEquals(3, meta.formatVersion)
            assertEquals(listOf("team_fortress2", "hl2"), meta.requiredContent)
            assertEquals("My Addon", meta.displayName)
            assertEquals("Description", meta.addonDescription)
            assertEquals("Author", meta.addonAuthor)
            assertEquals(7, meta.addonVersion)
            assertEquals(3, meta.files.size)
            assertEquals(32L, meta.totalContentSize)
            assertNotNull(meta.addonCrc)

            val extracted = extractGma(archive)
            assertEquals(setOf("lua/autorun/test.lua", "materials/example.vmt", "models/empty.txt"), extracted.keys)
            assertArrayEquals("print('hello')".toByteArray(), extracted["lua/autorun/test.lua"])
            assertArrayEquals("\"VertexLitGeneric\"".toByteArray(), extracted["materials/example.vmt"])
            assertArrayEquals(ByteArray(0), extracted["models/empty.txt"])
        }
    }

    @Test
    fun gmaV1HasNoRequiredContent() {
        val bytes = TestFiles.buildGma(
            version = 1,
            requiredContent = listOf("ignored"),
            addonName = "Old Addon",
            files = listOf(TestGmaFile("a.txt", "a".toByteArray()))
        )
        val file = folder.newFile("old.gma").apply { writeBytes(bytes) }
        GmaArchive.open(FileSource(file)).use { archive ->
            assertEquals(1, archive.metadata.formatVersion)
            assertTrue(archive.metadata.requiredContent.isEmpty())
            assertEquals("Old Addon", archive.metadata.addonName)
            assertEquals(1, archive.metadata.files.size)
        }
    }

    @Test
    fun gmaV2ReadsRequiredContent() {
        val bytes = TestFiles.buildGma(
            version = 2,
            requiredContent = listOf("csgo"),
            files = listOf(TestGmaFile("a.txt", "a".toByteArray()))
        )
        val file = folder.newFile("v2.gma").apply { writeBytes(bytes) }
        GmaArchive.open(FileSource(file)).use { archive ->
            assertEquals(2, archive.metadata.formatVersion)
            assertEquals(listOf("csgo"), archive.metadata.requiredContent)
        }
    }

    @Test
    fun gmaEmptyAddonIsValid() {
        val bytes = TestFiles.buildGma(files = emptyList())
        val file = folder.newFile("empty.gma").apply { writeBytes(bytes) }
        GmaArchive.open(FileSource(file)).use { archive ->
            assertEquals(0, archive.metadata.files.size)
            assertEquals(0L, archive.metadata.totalContentSize)
            val extracted = extractGma(archive)
            assertTrue(extracted.isEmpty())
        }
    }

    @Test
    fun gmaWithoutTrailerHasNoAddonCrc() {
        val bytes = TestFiles.buildGma(
            files = listOf(TestGmaFile("a.txt", "a".toByteArray())),
            includeAddonCrc = false
        )
        val file = folder.newFile("no-crc.gma").apply { writeBytes(bytes) }
        GmaArchive.open(FileSource(file)).use { archive ->
            assertNull(archive.metadata.addonCrc)
        }
    }

    @Test
    fun gmaSanitizesTraversalAndDuplicates() {
        val bytes = TestFiles.buildGma(
            files = listOf(
                TestGmaFile("../../evil.txt", "one".toByteArray()),
                TestGmaFile("../../evil.txt", "two".toByteArray()),
                TestGmaFile("C:\\Windows\\system32\\file.txt", "three".toByteArray()),
                TestGmaFile("a/./b/../c.txt", "four".toByteArray())
            )
        )
        val file = folder.newFile("names.gma").apply { writeBytes(bytes) }
        GmaArchive.open(FileSource(file)).use { archive ->
            val extracted = extractGma(archive)
            assertEquals(4, extracted.size)
            assertArrayEquals("one".toByteArray(), extracted["evil.txt"])
            assertArrayEquals("two".toByteArray(), extracted["evil~2.txt"])
            assertArrayEquals("three".toByteArray(), extracted["Windows/system32/file.txt"])
            assertArrayEquals("four".toByteArray(), extracted["a/b/c.txt"])
            assertFalse(extracted.keys.any { it.contains("..") })
        }
    }

    @Test
    fun gmaCountsCrcMismatches() {
        val bytes = TestFiles.buildGma(
            files = listOf(
                TestGmaFile("good.txt", "good".toByteArray()),
                TestGmaFile("bad.txt", "bad".toByteArray(), crcOverride = 1L)
            )
        )
        val file = folder.newFile("crc.gma").apply { writeBytes(bytes) }
        GmaArchive.open(FileSource(file)).use { archive ->
            val output = ByteArrayOutputStream()
            val stats = ZipOutputStream(output).use { zip ->
                archive.extract(zip, ProgressSink { _, _ -> }, active)
            }
            assertEquals(2, stats.entryCount)
            assertEquals(1, stats.crcMismatches)
        }
    }

    @Test(expected = GmaFormatException::class)
    fun gmaRejectsBadMagic() {
        val file = folder.newFile("bad.gma").apply { writeBytes("NOPE".toByteArray()) }
        GmaArchive.open(FileSource(file))
    }

    @Test(expected = GmaFormatException::class)
    fun gmaRejectsUnsupportedVersion() {
        val bytes = TestFiles.buildGma(version = 3)
        bytes[4] = 9
        val file = folder.newFile("v9.gma").apply { writeBytes(bytes) }
        GmaArchive.open(FileSource(file))
    }

    @Test(expected = GmaFormatException::class)
    fun gmaRejectsTruncatedContent() {
        val bytes = TestFiles.buildGma(
            files = listOf(TestGmaFile("large.txt", ByteArray(100) { 1 }))
        )
        val file = folder.newFile("truncated.gma").apply { writeBytes(bytes.copyOf(bytes.size - 20)) }
        GmaArchive.open(FileSource(file))
    }

    @Test
    fun gmaProgressIsReported() {
        val bytes = TestFiles.buildGma(
            files = listOf(TestGmaFile("a.txt", ByteArray(200_000) { 7 }))
        )
        val file = folder.newFile("progress.gma").apply { writeBytes(bytes) }
        var lastDone = 0L
        var lastTotal = 0L
        GmaArchive.open(FileSource(file)).use { archive ->
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                archive.extract(
                    zip,
                    ProgressSink { done, total ->
                        lastDone = done
                        lastTotal = total
                    },
                    active
                )
            }
        }
        assertEquals(200_000L, lastDone)
        assertEquals(200_000L, lastTotal)
    }

    // ---------- GMA containers ----------

    @Test
    fun gmaOpenerReadsPlainGma() {
        assertContainerWorks(TestFiles.buildGma(files = listOf(TestGmaFile("a.txt", "plain".toByteArray()))))
    }

    @Test
    fun gmaOpenerReadsLzmaAlone() {
        val gma = TestFiles.buildGma(files = listOf(TestGmaFile("a.txt", "lzma".toByteArray())))
        val out = ByteArrayOutputStream()
        LZMAOutputStream(out, LZMA2Options(), gma.size.toLong()).use { it.write(gma) }
        assertContainerWorks(out.toByteArray())
    }

    @Test
    fun gmaOpenerReadsXz() {
        val gma = TestFiles.buildGma(files = listOf(TestGmaFile("a.txt", "xz".toByteArray())))
        val out = ByteArrayOutputStream()
        XZOutputStream(out, LZMA2Options()).use { it.write(gma) }
        assertContainerWorks(out.toByteArray())
    }

    @Test
    fun gmaOpenerReadsGzip() {
        val gma = TestFiles.buildGma(files = listOf(TestGmaFile("a.txt", "gzip".toByteArray())))
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(gma) }
        assertContainerWorks(out.toByteArray())
    }

    @Test
    fun gmaOpenerReadsZip() {
        val gma = TestFiles.buildGma(files = listOf(TestGmaFile("a.txt", "zip".toByteArray())))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("nested/addon.gma"))
            zip.write(gma)
            zip.closeEntry()
        }
        assertContainerWorks(out.toByteArray())
    }

    @Test(expected = GmaFormatException::class)
    fun gmaOpenerRejectsZipWithoutGma() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        openContainer(out.toByteArray())
    }

    @Test(expected = GmaFormatException::class)
    fun gmaOpenerRejectsUnknownContainer() {
        openContainer("this is not an addon".toByteArray())
    }

    private fun assertContainerWorks(container: ByteArray) {
        openContainer(container).use { opened ->
            assertEquals("Test Addon", opened.metadata.addonName)
            val extracted = extractGma(opened)
            assertEquals(1, extracted.size)
        }
    }

    private fun openContainer(container: ByteArray): OpenedGma {
        val file = folder.newFile("container-${counter++}.bin").apply { writeBytes(container) }
        val cache = folder.newFolder("cache-${counter++}")
        return GmaOpener.open(FileSource(file), cache, ProgressSink { _, _ -> }, active)
    }

    private var counter = 0

    // ---------- VPK ----------

    @Test
    fun vpkV1EmbeddedReadsRootAndNestedFiles() {
        val root = "root contents".toByteArray()
        val model = "model contents".toByteArray()
        val bytes = TestFiles.buildVpk(
            version = 1,
            files = listOf(
                TestVpkFile(" ", "root", "txt", root),
                TestVpkFile("materials/models", "model", "txt", model)
            )
        )
        val file = folder.newFile("pak01_dir.vpk").apply { writeBytes(bytes) }
        VpkArchive.open(FileSource(file)).use { archive ->
            assertEquals(1, archive.metadata.version)
            assertEquals(2, archive.metadata.fileCount)
            assertEquals("pak01", archive.baseName)
            assertTrue(archive.metadata.externalArchives.isEmpty())
            val names = archive.metadata.entries.map { it.name }.toSet()
            assertEquals(setOf("root.txt", "materials/models/model.txt"), names)

            val extracted = extractVpk(archive, emptyMap())
            assertArrayEquals(root, extracted["root.txt"])
            assertArrayEquals(model, extracted["materials/models/model.txt"])
        }
    }

    @Test
    fun vpkV2WithPreloadAndSignedDirectory() {
        val preload = "PRE".toByteArray()
        val data = "DATA".toByteArray()
        val bytes = TestFiles.buildVpk(
            version = 2,
            files = listOf(
                TestVpkFile("sound", "beep", "wav", data, preload = preload)
            ),
            signed = true
        )
        val file = folder.newFile("signed_dir.vpk").apply { writeBytes(bytes) }
        VpkArchive.open(FileSource(file)).use { archive ->
            assertEquals(2, archive.metadata.version)
            assertTrue(archive.metadata.signed)
            val entry = archive.metadata.entries.single()
            assertArrayEquals(preload, entry.preload)
            assertEquals(7L, entry.totalSize)
            val extracted = extractVpk(archive, emptyMap())
            assertArrayEquals("PREDATA".toByteArray(), extracted["sound/beep.wav"])
        }
    }

    @Test
    fun vpkReadsExternalArchiveParts() {
        val first = "first part".toByteArray()
        val second = "second part".toByteArray()
        val bytes = TestFiles.buildVpk(
            version = 2,
            files = listOf(
                TestVpkFile("a", "one", "txt", first, archiveIndex = 1, offsetOverride = 0),
                TestVpkFile("a", "two", "txt", second, archiveIndex = 2, offsetOverride = 0)
            )
        )
        val dir = folder.newFile("pak02_dir.vpk").apply { writeBytes(bytes) }
        val part1 = folder.newFile("pak02_001.vpk").apply { writeBytes(first) }
        val part2 = folder.newFile("pak02_002.vpk").apply { writeBytes(second) }
        VpkArchive.open(FileSource(dir)).use { archive ->
            assertEquals(listOf(1, 2), archive.metadata.externalArchives)
            val parts = mapOf(
                1 to FileSource(part1),
                2 to FileSource(part2)
            )
            try {
                val extracted = extractVpk(archive, parts)
                assertArrayEquals(first, extracted["a/one.txt"])
                assertArrayEquals(second, extracted["a/two.txt"])
            } finally {
                parts.values.forEach { it.close() }
            }
        }
    }

    @Test
    fun vpkReportsMissingExternalPart() {
        val bytes = TestFiles.buildVpk(
            version = 2,
            files = listOf(
                TestVpkFile("a", "one", "txt", "data".toByteArray(), archiveIndex = 3, offsetOverride = 0)
            )
        )
        val dir = folder.newFile("pak03_dir.vpk").apply { writeBytes(bytes) }
        VpkArchive.open(FileSource(dir)).use { archive ->
            try {
                extractVpk(archive, emptyMap())
                throw AssertionError("Expected VpkMissingArchiveException")
            } catch (e: VpkMissingArchiveException) {
                assertEquals("pak03_003.vpk", e.archiveName)
            }
        }
    }

    @Test
    fun vpkPreloadOnlyExternalEntryNeedsNoPart() {
        val bytes = TestFiles.buildVpk(
            version = 2,
            files = listOf(
                TestVpkFile(
                    "config",
                    "settings",
                    "cfg",
                    ByteArray(0),
                    preload = "preload-only".toByteArray(),
                    archiveIndex = 4
                )
            )
        )
        val dir = folder.newFile("pak04_dir.vpk").apply { writeBytes(bytes) }
        VpkArchive.open(FileSource(dir)).use { archive ->
            assertTrue(archive.metadata.externalArchives.isEmpty())
            val extracted = extractVpk(archive, emptyMap())
            assertArrayEquals("preload-only".toByteArray(), extracted["config/settings.cfg"])
        }
    }

    @Test
    fun vpkSanitizesTraversalPathsAndCountsCrcMismatch() {
        val bytes = TestFiles.buildVpk(
            version = 1,
            files = listOf(
                TestVpkFile("..", "evil", "txt", "bad".toByteArray(), crcOverride = 1L),
                TestVpkFile(" ", "good", "txt", "good".toByteArray())
            )
        )
        val file = folder.newFile("names_dir.vpk").apply { writeBytes(bytes) }
        VpkArchive.open(FileSource(file)).use { archive ->
            val output = ByteArrayOutputStream()
            val stats = ZipOutputStream(output).use { zip ->
                archive.extract(zip, emptyMap(), ProgressSink { _, _ -> }, active)
            }
            assertEquals(2, stats.entryCount)
            assertEquals(1, stats.crcMismatches)
            val extracted = unzip(output.toByteArray())
            assertArrayEquals("bad".toByteArray(), extracted["evil.txt"])
            assertArrayEquals("good".toByteArray(), extracted["good.txt"])
        }
    }

    @Test(expected = VpkFormatException::class)
    fun vpkRejectsBadMagic() {
        val file = folder.newFile("bad.vpk").apply { writeBytes(ByteArray(64)) }
        VpkArchive.open(FileSource(file))
    }

    @Test(expected = VpkFormatException::class)
    fun vpkRejectsUnsupportedVersion() {
        val bytes = TestFiles.buildVpk(version = 2, files = emptyList())
        bytes[4] = 3
        val file = folder.newFile("v3.vpk").apply { writeBytes(bytes) }
        VpkArchive.open(FileSource(file))
    }

    @Test(expected = VpkFormatException::class)
    fun vpkRejectsTreeOutsideFile() {
        val bytes = TestFiles.buildVpk(version = 2, files = emptyList())
        bytes[8] = 0xFF.toByte()
        bytes[9] = 0xFF.toByte()
        bytes[10] = 0xFF.toByte()
        bytes[11] = 0x7F
        val file = folder.newFile("bad-tree.vpk").apply { writeBytes(bytes) }
        VpkArchive.open(FileSource(file))
    }

    @Test
    fun vpkBaseNameAndPartNames() {
        assertEquals("pak01", VpkArchive.baseNameFor("pak01_dir.vpk"))
        assertEquals("pak01", VpkArchive.baseNameFor("pak01_000.vpk"))
        assertEquals("pak01", VpkArchive.baseNameFor("folder/pak01.vpk"))
        assertEquals("pak01_000.vpk", VpkArchive.archiveFileName("pak01", 0))
        assertEquals("pak01_012.vpk", VpkArchive.archiveFileName("pak01", 12))
        assertEquals("pak01_dir.vpk", VpkArchive.dirFileName("pak01"))
        assertEquals(0, VpkArchive.numberedIndex("pak01_000.vpk"))
        assertEquals(12, VpkArchive.numberedIndex("pak01_012.vpk"))
        assertNull(VpkArchive.numberedIndex("pak01_dir.vpk"))
    }

    // ---------- helpers ----------

    private fun extractGma(archive: GmaArchive): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            archive.extract(zip, ProgressSink { _, _ -> }, active)
        }
        return unzip(output.toByteArray())
    }

    private fun extractGma(opened: OpenedGma): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            opened.extract(zip, ProgressSink { _, _ -> }, active)
        }
        return unzip(output.toByteArray())
    }

    private fun extractVpk(
        archive: VpkArchive,
        parts: Map<Int, SeekableSource>
    ): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            archive.extract(zip, parts, ProgressSink { _, _ -> }, active)
        }
        return unzip(output.toByteArray())
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                result[entry.name] = input.readBytes()
                input.closeEntry()
            }
        }
        return result
    }
}
