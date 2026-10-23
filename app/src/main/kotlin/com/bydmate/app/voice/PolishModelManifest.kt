package com.bydmate.app.voice

import java.io.File
import java.security.MessageDigest

enum class PolishModelKind { ASR, TTS }

/** One HTTPS fetch. [installPath] set: the bytes are one file, stored at that path under the
 *  package dir. [installPath] null: a .tar.bz2 archive whose single top-level directory is
 *  stripped when extracted into the package dir. */
data class PolishDownload(val url: String, val sizeBytes: Long, val sha256: String, val installPath: String?)

/** A file that must exist in the installed package dir with exactly this size and SHA-256. */
data class PolishModelFile(val relativePath: String, val sizeBytes: Long, val sha256: String)

/** A directory too large to hash file by file; only its file count is pinned. */
data class PolishRequiredTree(val relativePath: String, val fileCount: Int)

data class PolishModelPackage(
    val id: String,
    val kind: PolishModelKind,
    val downloads: List<PolishDownload>,
    val files: List<PolishModelFile>,
    val requiredTrees: List<PolishRequiredTree>,
    val licenseNotes: String,
    val licenseId: String = "",
    val attribution: String = "",
    val modifications: String = "",
)

data class PolishAsrSettings(
    val sampleRate: Int, val featureDim: Int, val modelType: String, val numThreads: Int,
    val provider: String, val decodingMethod: String,
    val encoder: String, val decoder: String, val joiner: String, val tokens: String,
)

sealed class PolishAsrResolution {
    data class Ready(val encoder: File, val decoder: File, val joiner: File, val tokens: File) : PolishAsrResolution()
    data class NotReady(val problems: List<String>) : PolishAsrResolution()
}

/** Pinned upstream assets for the Polish voice profile (Parakeet ASR and Gosia TTS), measured 2026-10-20 (see
 *  docs/voice/polish-native-feasibility.md). A package is usable only after every file in
 *  [PolishModelPackage.files] verifies; nothing here is fetched at runtime by this object. */
object PolishModelManifest {

    // Parakeet TDT 0.6B v3, int8, NeMo transducer: csukuangfj's sherpa-onnx export of NVIDIA's
    // model, pinned to a Hugging Face commit. The four files are used as-is (no archive). Earlier Whisper
    // tiny/small pins and results are historical evidence in docs/voice/polish-native-feasibility.md only.
    private const val PARAKEET_BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/" +
            "2bda32ec70b097a55adaa07d9a7173915b43cc78/"

    private const val PARAKEET_ENCODER_SHA = "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"
    private const val PARAKEET_DECODER_SHA = "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"
    private const val PARAKEET_JOINER_SHA = "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"
    private const val PARAKEET_TOKENS_SHA = "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"

    val PARAKEET_TDT_V3_INT8: PolishModelPackage = PolishModelPackage(
        id = "parakeet-tdt-0.6b-v3-int8",
        kind = PolishModelKind.ASR,
        downloads = listOf(
            PolishDownload(PARAKEET_BASE + "encoder.int8.onnx", 652_184_281, PARAKEET_ENCODER_SHA, "encoder.int8.onnx"),
            PolishDownload(PARAKEET_BASE + "decoder.int8.onnx", 11_845_275, PARAKEET_DECODER_SHA, "decoder.int8.onnx"),
            PolishDownload(PARAKEET_BASE + "joiner.int8.onnx", 6_355_277, PARAKEET_JOINER_SHA, "joiner.int8.onnx"),
            PolishDownload(PARAKEET_BASE + "tokens.txt", 93_939, PARAKEET_TOKENS_SHA, "tokens.txt"),
        ),
        files = listOf(
            PolishModelFile("encoder.int8.onnx", 652_184_281, PARAKEET_ENCODER_SHA),
            PolishModelFile("decoder.int8.onnx", 11_845_275, PARAKEET_DECODER_SHA),
            PolishModelFile("joiner.int8.onnx", 6_355_277, PARAKEET_JOINER_SHA),
            PolishModelFile("tokens.txt", 93_939, PARAKEET_TOKENS_SHA),
        ),
        requiredTrees = emptyList(),
        licenseNotes = "Upstream model card nvidia/parakeet-tdt-0.6b-v3 (commit " +
            "541d1f99c6b0c3cd0b11a95167540bb8edefd82b) sets license cc-by-4.0 and says use of the model is " +
            "governed by CC-BY-4.0. CC-BY-4.0 section 3(a) requires creator credit, a licence reference and " +
            "an indication of modification when sharing. Unresolved: the repack repo " +
            "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 has no README, licence file or licence " +
            "metadata, so any terms the converter adds are unstated; do not mirror these files. The model " +
            "detects the language itself; the NeMo transducer path has no language option.",
        licenseId = "CC-BY-4.0",
        attribution = "\"parakeet-tdt-0.6b-v3\" by NVIDIA, https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3, " +
            "licensed under CC BY 4.0, https://creativecommons.org/licenses/by/4.0/ . Modified (converted to " +
            "ONNX and int8-quantized); changes are not endorsed by NVIDIA.",
        modifications = "Converted from the NeMo checkpoint to ONNX (transducer branch only: encoder, decoder, " +
            "joiner) and dynamically quantized to int8 with onnxruntime quantize_dynamic (encoder QUInt8, " +
            "decoder and joiner QInt8); tokens.txt written from the joint vocabulary plus <blk>; metadata " +
            "added to the encoder. Source: sherpa-onnx scripts/nemo/parakeet-tdt-0.6b-v3/export_onnx.py.",
    )

    /** The configuration measured in steps 1g and 1i; mirrors OfflineRecognizerConfig field for field.
     *  `nemo_transducer` has no language or task option. */
    val PARAKEET_SETTINGS = PolishAsrSettings(
        sampleRate = 16_000,
        featureDim = 128,
        modelType = "nemo_transducer",
        numThreads = 4,
        provider = "cpu",
        decodingMethod = "greedy_search",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        tokens = "tokens.txt",
    )

    // The sherpa-onnx `tts-models` release tag is mutable; the archive checksum pins the content.
    val GOSIA_MEDIUM_INT8: PolishModelPackage = PolishModelPackage(
        id = "gosia-medium-int8",
        kind = PolishModelKind.TTS,
        downloads = listOf(
            PolishDownload(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
                    "vits-piper-pl_PL-gosia-medium-int8.tar.bz2",
                21_109_262,
                "72acac4c4b031725c41a61b3af0314a3d30e1ec2cd83ee410ea5f9e6d2d9d4fb",
                null,
            ),
        ),
        files = listOf(
            PolishModelFile(
                "pl_PL-gosia-medium.onnx", 18_681_768,
                "1655002e59aa148cf1039b5fc652301fc0ad94372aee4cdc7954b03fb2a959ff",
            ),
            PolishModelFile(
                "pl_PL-gosia-medium.onnx.json", 4_814,
                "1aefb31a9d53ffe44a8163ff73ec833acb7a6253848f6bb0403d8a66f9c7510d",
            ),
            PolishModelFile(
                "tokens.txt", 907,
                "2619c1a9de1bcf928162f40c583caf39368cfd6b2340c7bcad51dc634411ec36",
            ),
            PolishModelFile(
                "MODEL_CARD", 275,
                "6b4de033dfff0da758bbe4d438fd87fbd917ab843bdd83f591fa0f32b8eb5780",
            ),
            PolishModelFile(
                "espeak-ng-data/phondata", 550_424,
                "4e0288957874029a8c3c9f41a8f517ad4bf18127046decbdd4b9d1d6807ce3a3",
            ),
            PolishModelFile(
                "espeak-ng-data/phonindex", 39_074,
                "3ca7b8fa3b42624e4b0f152707e7a39245fce569aa99ea47c055d9e622fcf0c4",
            ),
            PolishModelFile(
                "espeak-ng-data/phontab", 55_796,
                "886f3fa402cb0ba73d483aa8ad000af47a6b7cc06293c75a97913fba68a530f6",
            ),
            PolishModelFile(
                "espeak-ng-data/intonations", 2_040,
                "3f8af65fd3eda9759a10f021d61361c120871f463515229c925995c7f90918cc",
            ),
            PolishModelFile(
                "espeak-ng-data/phondata-manifest", 21_821,
                "7b387af0702c7cf0b61f0bead68feded0bd8e1620729b0b252e76acbc30d3813",
            ),
            PolishModelFile(
                "espeak-ng-data/pl_dict", 76_730,
                "0e0ce6cfe4eba41a1223362a046390dca359a3e597af8243adaac84d624ef18f",
            ),
            PolishModelFile(
                "espeak-ng-data/lang/zlw/pl", 38,
                "83c586a2b724b24b9f98d21ade5022112c465ac446ca09930286980ceae1c781",
            ),
        ),
        requiredTrees = listOf(PolishRequiredTree("espeak-ng-data", 355)),
        licenseNotes = "Voice data CC0 per the bundled MODEL_CARD (OHF-Voice/voice-datasets, fine-tuned " +
            "from the lessac US English voice). Unverified: Piper and espeak-ng licence notices " +
            "that must ship with espeak-ng-data; confirm before packaging or mirroring.",
    )

    val ALL: List<PolishModelPackage> = listOf(PARAKEET_TDT_V3_INT8, GOSIA_MEDIUM_INT8)

    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    /** Problems with the definition itself; empty means well formed. */
    fun validate(pkg: PolishModelPackage): List<String> {
        val problems = mutableListOf<String>()
        if (pkg.downloads.isEmpty()) problems += "${pkg.id}: no downloads"
        if (pkg.files.isEmpty()) problems += "${pkg.id}: no files"
        pkg.downloads.forEach { problems += validateDownload(pkg.id, it) }
        pkg.files.forEach { problems += validateEntry(pkg.id, it.relativePath, it.sha256, it.sizeBytes) }
        pkg.requiredTrees.forEach { t ->
            if (!safeRelative(t.relativePath)) problems += "${pkg.id}: unsafe tree path ${t.relativePath}"
            if (t.fileCount <= 0) problems += "${pkg.id}: non-positive file count for ${t.relativePath}"
        }
        pkg.files.groupBy { it.relativePath }.filterValues { it.size > 1 }.keys
            .forEach { problems += "${pkg.id}: duplicate path $it" }
        return problems
    }

    private fun validateDownload(id: String, d: PolishDownload): List<String> {
        val problems = mutableListOf<String>()
        if (!d.url.startsWith("https://")) problems += "$id: download is not https: ${d.url}"
        if (!SHA256_HEX.matches(d.sha256)) problems += "$id: bad sha256 for ${d.url}"
        if (d.sizeBytes <= 0) problems += "$id: non-positive size for ${d.url}"
        d.installPath?.let { if (!safeRelative(it)) problems += "$id: unsafe install path $it" }
        return problems
    }

    private fun validateEntry(id: String, path: String, sha256: String, size: Long): List<String> {
        val problems = mutableListOf<String>()
        if (!safeRelative(path)) problems += "$id: unsafe path $path"
        if (!SHA256_HEX.matches(sha256)) problems += "$id: bad sha256 for $path"
        if (size <= 0) problems += "$id: non-positive size for $path"
        return problems
    }

    /** Checks [root] (the package directory) against [pkg]; empty means every file is present
     *  with the pinned size and SHA-256 and every tree has its pinned file count. */
    fun verifyInstalled(root: File, pkg: PolishModelPackage): List<String> {
        val problems = mutableListOf<String>()
        pkg.files.forEach { f ->
            val file = File(root, f.relativePath)
            when {
                !file.isFile -> problems += "${pkg.id}: missing ${f.relativePath}"
                file.length() != f.sizeBytes ->
                    problems += "${pkg.id}: size ${file.length()} != ${f.sizeBytes} for ${f.relativePath}"
                sha256(file) != f.sha256 -> problems += "${pkg.id}: sha256 mismatch for ${f.relativePath}"
            }
        }
        pkg.requiredTrees.forEach { t ->
            val count = File(root, t.relativePath).walkTopDown().count { it.isFile }
            if (count != t.fileCount) {
                problems += "${pkg.id}: tree ${t.relativePath} has $count files, expected ${t.fileCount}"
            }
        }
        return problems
    }

    /** Problems with the encoder/decoder/joiner/tokens role files in [pkg]; empty means the package defines all four. */
    fun validateNemoTransducerShape(pkg: PolishModelPackage): List<String> {
        val s = PARAKEET_SETTINGS
        val have = pkg.files.map { it.relativePath }.toSet()
        return listOf(s.encoder, s.decoder, s.joiner, s.tokens)
            .filter { it !in have }
            .map { "${pkg.id}: package does not define $it" }
    }

    /** Compatibility gate: [PolishAsrResolution.Ready] only when the package defines all four NeMo
     *  transducer files and each verifies in [root]. It says the assets are loadable, not that the
     *  recognizer is accurate. */
    fun resolveNemoTransducer(root: File, pkg: PolishModelPackage = PARAKEET_TDT_V3_INT8): PolishAsrResolution {
        val problems = validateNemoTransducerShape(pkg) + verifyInstalled(root, pkg)
        if (problems.isNotEmpty()) return PolishAsrResolution.NotReady(problems)
        val s = PARAKEET_SETTINGS
        return PolishAsrResolution.Ready(
            File(root, s.encoder), File(root, s.decoder), File(root, s.joiner), File(root, s.tokens),
        )
    }

    private fun safeRelative(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("/") && !path.split('/').any { it == ".." || it.isEmpty() }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
