package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class PolishModelManifestTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pkg(
        files: List<PolishModelFile>,
        downloads: List<PolishDownload> = listOf(
            PolishDownload("https://example.test/a.bin", 1, "a".repeat(64), "a.bin"),
        ),
    ) = PolishModelPackage(
        id = "test", kind = PolishModelKind.ASR, downloads = downloads, files = files,
        requiredTrees = emptyList(), licenseNotes = "test",
    )

    private fun file(path: String = "a.bin", size: Long = 1, sha: String = "a".repeat(64)) =
        PolishModelFile(path, size, sha)

    // ---- shipped definitions -------------------------------------------------------------

    @Test fun `shipped manifest declares parakeet v3 int8 and gosia only`() {
        assertEquals(
            listOf("parakeet-tdt-0.6b-v3-int8", "gosia-medium-int8"),
            PolishModelManifest.ALL.map { it.id },
        )
        assertTrue(PolishModelManifest.ALL.none { it.id.contains("whisper") })
    }

    @Test fun `shipped manifest passes its own validation`() {
        PolishModelManifest.ALL.forEach { assertEquals(it.id, emptyList<String>(), PolishModelManifest.validate(it)) }
    }

    // ---- step 1i: pinned Parakeet TDT 0.6B v3 int8 (NeMo transducer) ---------------------

    private val parakeetCommit = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
    private val parakeetBase =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/$parakeetCommit/"
    private val parakeetExpected = mapOf(
        "encoder.int8.onnx" to Pair(652_184_281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
        "decoder.int8.onnx" to Pair(11_845_275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
        "joiner.int8.onnx" to Pair(6_355_277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
        "tokens.txt" to Pair(93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    )

    @Test fun `parakeet pins exact commit urls sizes and checksums for four files`() {
        val p = PolishModelManifest.PARAKEET_TDT_V3_INT8
        assertEquals("parakeet-tdt-0.6b-v3-int8", p.id)
        assertEquals(PolishModelKind.ASR, p.kind)
        assertEquals(parakeetExpected.keys, p.files.map { it.relativePath }.toSet())
        assertEquals(parakeetExpected.size, p.downloads.size)
        parakeetExpected.forEach { (path, expected) ->
            val file = p.files.single { it.relativePath == path }
            assertEquals(path, expected.first, file.sizeBytes)
            assertEquals(path, expected.second, file.sha256)
            val download = p.downloads.single { it.installPath == path }
            assertEquals(parakeetBase + path, download.url)
            assertEquals(expected.first, download.sizeBytes)
            assertEquals(expected.second, download.sha256)
        }
        assertTrue(p.downloads.none { it.url.contains("/main/") })
    }

    @Test fun `parakeet runtime settings are the measured nemo transducer configuration`() {
        val s = PolishModelManifest.PARAKEET_SETTINGS
        assertEquals(16_000, s.sampleRate)
        assertEquals(128, s.featureDim)
        assertEquals("nemo_transducer", s.modelType)
        assertEquals(4, s.numThreads)
        assertEquals("cpu", s.provider)
        assertEquals("greedy_search", s.decodingMethod)
        assertEquals("encoder.int8.onnx", s.encoder)
        assertEquals("decoder.int8.onnx", s.decoder)
        assertEquals("joiner.int8.onnx", s.joiner)
        assertEquals("tokens.txt", s.tokens)
    }

    @Test fun `parakeet shape has encoder decoder joiner and tokens`() {
        assertEquals(emptyList<String>(), PolishModelManifest.validateNemoTransducerShape(PolishModelManifest.PARAKEET_TDT_V3_INT8))
    }

    @Test fun `shape check rejects a package without a joiner`() {
        val noJoiner = PolishModelManifest.PARAKEET_TDT_V3_INT8.let { p ->
            p.copy(
                files = p.files.filterNot { it.relativePath == "joiner.int8.onnx" },
                downloads = p.downloads.filterNot { it.installPath == "joiner.int8.onnx" },
            )
        }
        val problems = PolishModelManifest.validateNemoTransducerShape(noJoiner)
        assertTrue(problems.toString(), problems.any { it.contains("joiner.int8.onnx") })
    }

    @Test fun `parakeet records licence attribution and supplied conversion changes`() {
        val p = PolishModelManifest.PARAKEET_TDT_V3_INT8
        assertEquals("CC-BY-4.0", p.licenseId)
        assertTrue(p.attribution, p.attribution.contains("NVIDIA") && p.attribution.contains("nvidia/parakeet-tdt-0.6b-v3"))
        assertTrue(p.attribution, p.attribution.contains("https://creativecommons.org/licenses/by/4.0/"))
        assertTrue(p.modifications, p.modifications.contains("int8") && p.modifications.contains("quantize_dynamic"))
        assertTrue(p.licenseNotes, p.licenseNotes.contains("Unresolved"))
    }

    @Test fun `corrupted parakeet tokens of the right size fail on sha256 only`() {
        val p = PolishModelManifest.PARAKEET_TDT_V3_INT8
        val root = tmp.newFolder()
        File(root, "tokens.txt").writeBytes(ByteArray(93_939))
        val problems = PolishModelManifest.verifyInstalled(root, p)
        assertTrue(problems.toString(), problems.any { it.contains("sha256 mismatch for tokens.txt") })
        assertFalse(problems.toString(), problems.any { it.contains("size") && it.contains("tokens.txt") })
    }

    @Test fun `truncated parakeet joiner is rejected by size`() {
        val root = tmp.newFolder()
        File(root, "joiner.int8.onnx").writeBytes(ByteArray(10))
        val problems = PolishModelManifest.verifyInstalled(root, PolishModelManifest.PARAKEET_TDT_V3_INT8)
        assertTrue(problems.toString(), problems.any { it.contains("size 10") && it.contains("joiner.int8.onnx") })
    }

    // ---- compatibility gate without provisioned assets (not the transcript-accuracy gate) ----

    @Test fun `compatibility resolution is not ready for an empty directory`() {
        val r = PolishModelManifest.resolveNemoTransducer(tmp.newFolder())
        assertTrue(r.toString(), r is PolishAsrResolution.NotReady)
        val problems = (r as PolishAsrResolution.NotReady).problems
        parakeetExpected.keys.forEach { f -> assertTrue("$f in $problems", problems.any { it.contains("missing $f") }) }
    }

    @Test fun `compatibility resolution is not ready when the directory does not exist`() {
        val r = PolishModelManifest.resolveNemoTransducer(File(tmp.root, "absent"))
        assertTrue(r.toString(), r is PolishAsrResolution.NotReady)
    }

    @Test fun `compatibility resolution reports a missing joiner`() {
        val root = tmp.newFolder()
        File(root, "encoder.int8.onnx").writeBytes(ByteArray(3))
        File(root, "decoder.int8.onnx").writeBytes(ByteArray(3))
        File(root, "tokens.txt").writeBytes(ByteArray(3))
        val r = PolishModelManifest.resolveNemoTransducer(root)
        assertTrue(r.toString(), r is PolishAsrResolution.NotReady)
        assertTrue((r as PolishAsrResolution.NotReady).problems.any { it.contains("missing joiner.int8.onnx") })
    }

    @Test fun `compatibility resolution is not ready for a corrupted file even when all four exist`() {
        val (root, p) = tinyNemoPackage()
        File(root, "joiner.int8.onnx").writeBytes("jello".toByteArray())
        val r = PolishModelManifest.resolveNemoTransducer(root, p)
        assertTrue(r.toString(), r is PolishAsrResolution.NotReady)
        assertTrue((r as PolishAsrResolution.NotReady).problems.any { it.contains("joiner.int8.onnx") })
    }

    @Test fun `compatibility resolution returns the four verified paths`() {
        val (root, p) = tinyNemoPackage()
        val r = PolishModelManifest.resolveNemoTransducer(root, p)
        assertTrue(r.toString(), r is PolishAsrResolution.Ready)
        r as PolishAsrResolution.Ready
        assertEquals(File(root, "encoder.int8.onnx"), r.encoder)
        assertEquals(File(root, "decoder.int8.onnx"), r.decoder)
        assertEquals(File(root, "joiner.int8.onnx"), r.joiner)
        assertEquals(File(root, "tokens.txt"), r.tokens)
    }

    /** A temp directory with four small files plus a package that pins them under the Parakeet file names. */
    private fun tinyNemoPackage(): Pair<File, PolishModelPackage> {
        val root = tmp.newFolder()
        val names = parakeetExpected.keys.toList()
        val bytes = names.associateWith { "content-of-$it".toByteArray() }
        bytes.forEach { (n, b) -> File(root, n).writeBytes(b) }
        val files = names.map { PolishModelFile(it, bytes.getValue(it).size.toLong(), sha256(bytes.getValue(it))) }
        val p = PolishModelManifest.PARAKEET_TDT_V3_INT8.copy(files = files, downloads = emptyList())
        return root to p
    }

    @Test fun `every shipped download uses https and a 64 hex sha256`() {
        PolishModelManifest.ALL.flatMap { it.downloads }.forEach {
            assertTrue(it.url, it.url.startsWith("https://"))
            assertTrue(it.sha256, Regex("[0-9a-f]{64}").matches(it.sha256))
            assertTrue(it.sizeBytes > 0)
        }
    }

    @Test fun `gosia package requires the espeak data tree and polish voice files`() {
        val gosia = PolishModelManifest.GOSIA_MEDIUM_INT8
        val paths = gosia.files.map { it.relativePath }
        assertTrue(paths.contains("pl_PL-gosia-medium.onnx"))
        assertTrue(paths.contains("tokens.txt"))
        assertTrue(paths.contains("espeak-ng-data/pl_dict"))
        assertTrue(gosia.requiredTrees.any { it.relativePath == "espeak-ng-data" && it.fileCount > 0 })
    }

    // ---- definition validation -----------------------------------------------------------

    @Test fun `validate rejects a non https download`() {
        val bad = pkg(listOf(file()), listOf(PolishDownload("http://example.test/a.bin", 1, "a".repeat(64), "a.bin")))
        assertTrue(PolishModelManifest.validate(bad).any { it.contains("https") })
    }

    @Test fun `validate rejects a malformed checksum`() {
        val bad = pkg(listOf(file(sha = "xyz")))
        assertTrue(PolishModelManifest.validate(bad).any { it.contains("sha256") })
    }

    @Test fun `validate rejects an empty file list`() {
        assertTrue(PolishModelManifest.validate(pkg(emptyList())).isNotEmpty())
    }

    @Test fun `validate rejects a zero size`() {
        assertTrue(PolishModelManifest.validate(pkg(listOf(file(size = 0)))).isNotEmpty())
    }

    @Test fun `validate rejects path traversal and absolute paths`() {
        assertTrue(PolishModelManifest.validate(pkg(listOf(file(path = "../x")))).isNotEmpty())
        assertTrue(PolishModelManifest.validate(pkg(listOf(file(path = "/etc/x")))).isNotEmpty())
    }

    @Test fun `validate rejects duplicate paths`() {
        assertTrue(PolishModelManifest.validate(pkg(listOf(file(), file()))).isNotEmpty())
    }

    // ---- installed-file verification -----------------------------------------------------

    @Test fun `verify reports a missing file`() {
        val root = tmp.newFolder()
        val problems = PolishModelManifest.verifyInstalled(root, pkg(listOf(file())))
        assertTrue(problems.any { it.contains("missing") && it.contains("a.bin") })
    }

    @Test fun `verify reports a changed hash`() {
        val root = tmp.newFolder()
        val original = "hello".toByteArray()
        File(root, "a.bin").writeBytes("jello".toByteArray())
        val p = pkg(listOf(file(size = original.size.toLong(), sha = sha256(original))))
        assertTrue(PolishModelManifest.verifyInstalled(root, p).any { it.contains("sha256") })
    }

    @Test fun `verify reports a size mismatch`() {
        val root = tmp.newFolder()
        val bytes = "hello".toByteArray()
        File(root, "a.bin").writeBytes(bytes)
        val p = pkg(listOf(file(size = bytes.size + 1L, sha = sha256(bytes))))
        assertTrue(PolishModelManifest.verifyInstalled(root, p).any { it.contains("size") })
    }

    @Test fun `verify accepts matching files`() {
        val root = tmp.newFolder()
        val bytes = "hello".toByteArray()
        File(root, "a.bin").writeBytes(bytes)
        val p = pkg(listOf(file(size = bytes.size.toLong(), sha = sha256(bytes))))
        assertEquals(emptyList<String>(), PolishModelManifest.verifyInstalled(root, p))
    }

    @Test fun `verify reports a short required tree`() {
        val root = tmp.newFolder()
        File(root, "tree").mkdirs()
        File(root, "tree/one").writeText("1")
        val p = pkg(emptyList()).copy(requiredTrees = listOf(PolishRequiredTree("tree", 2)))
        assertTrue(PolishModelManifest.verifyInstalled(root, p).any { it.contains("tree") })
        File(root, "tree/two").writeText("2")
        assertFalse(PolishModelManifest.verifyInstalled(root, p).any { it.contains("tree") })
    }
}
