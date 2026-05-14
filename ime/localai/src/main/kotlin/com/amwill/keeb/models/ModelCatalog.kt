package com.amwill.keeb.models

enum class ModelSize { Tiny, Base, Small, Medium, Large, Quantized }
enum class ChecksumAlgorithm { SHA1, SHA256 }

data class WhisperModel(
    val id: String,
    val displayName: String,
    val size: ModelSize,
    val url: String,
    val bytes: Long,
    val checksum: String,
    val checksumAlgorithm: ChecksumAlgorithm = ChecksumAlgorithm.SHA256,
    val license: String = "MIT model/code lineage; verify exact artifact before distribution",
    val minRamMb: Int,
    val recommended: Boolean,
    val localPath: String? = null,
)

object ModelCatalog {
    val pinned: List<WhisperModel> = listOf(
        hfModel("tiny.en", "Tiny English", ModelSize.Tiny, "ggml-tiny.en.bin", 75, "c78c86eb1a8faa21b369bcd33207cc90d64ae9df", minRamMb = 2048, recommended = true),
        hfModel("tiny", "Tiny Multilingual", ModelSize.Tiny, "ggml-tiny.bin", 75, "bd577a113a864445d4c299885e0cb97d4ba92b5f", minRamMb = 2048, recommended = true),
        hfModel("tiny-q5_1", "Tiny Quantized Q5", ModelSize.Quantized, "ggml-tiny-q5_1.bin", 31, "2827a03e495b1ed3048ef28a6a4620537db4ee51", minRamMb = 1536, recommended = true),
        hfModel("tiny-q8_0", "Tiny Quantized Q8", ModelSize.Quantized, "ggml-tiny-q8_0.bin", 42, "19e8118f6652a650569f5a949d962154e01571d9", minRamMb = 1536, recommended = true),
        hfModel("base.en", "Base English", ModelSize.Base, "ggml-base.en.bin", 142, "137c40403d78fd54d454da0f9bd998f78703390c", minRamMb = 3072, recommended = true),
        hfModel("base", "Base Multilingual", ModelSize.Base, "ggml-base.bin", 142, "465707469ff3a37a2b9b8d8f89f2f99de7299dac", minRamMb = 3072, recommended = true),
        hfModel("base-q5_1", "Base Quantized Q5", ModelSize.Quantized, "ggml-base-q5_1.bin", 57, "a3733eda680ef76256db5fc5dd9de8629e62c5e7", minRamMb = 2048, recommended = true),
        hfModel("base-q8_0", "Base Quantized Q8", ModelSize.Quantized, "ggml-base-q8_0.bin", 78, "7bb89bb49ed6955013b166f1b6a6c04584a20fbe", minRamMb = 2048, recommended = true),
        hfModel("small", "Small Multilingual", ModelSize.Small, "ggml-small.bin", 466, "55356645c2b361a969dfd0ef2c5a50d530afd8d5", minRamMb = 4096, recommended = false),
        hfModel("small-q5_1", "Small Quantized Q5", ModelSize.Quantized, "ggml-small-q5_1.bin", 181, "6fe57ddcfdd1c6b07cdcc73aaf620810ce5fc771", minRamMb = 3072, recommended = true),
        hfModel("small-q8_0", "Small Quantized Q8", ModelSize.Quantized, "ggml-small-q8_0.bin", 252, "bcad8a2083f4e53d648d586b7dbc0cd673d8afad", minRamMb = 3072, recommended = false),
        hfModel("medium", "Medium Multilingual", ModelSize.Medium, "ggml-medium.bin", 1536, "fd9727b6e1217c2f614f9b698455c4ffd82463b4", minRamMb = 6144, recommended = false),
        hfModel("medium-q5_0", "Medium Quantized Q5", ModelSize.Quantized, "ggml-medium-q5_0.bin", 514, "7718d4c1ec62ca96998f058114db98236937490e", minRamMb = 4096, recommended = false),
        hfModel("medium-q8_0", "Medium Quantized Q8", ModelSize.Quantized, "ggml-medium-q8_0.bin", 785, "e66645948aff4bebbec71b3485c576f3d63af5d6", minRamMb = 6144, recommended = false),
        hfModel("large-v3-turbo", "Large v3 Turbo", ModelSize.Large, "ggml-large-v3-turbo.bin", 1536, "4af2b29d7ec73d781377bfd1758ca957a807e941", minRamMb = 8192, recommended = false),
        hfModel("large-v3-turbo-q5_0", "Large v3 Turbo Quantized Q5", ModelSize.Quantized, "ggml-large-v3-turbo-q5_0.bin", 547, "e050f7970618a659205450ad97eb95a18d69c9ee", minRamMb = 6144, recommended = false),
        hfModel("large-v3-turbo-q8_0", "Large v3 Turbo Quantized Q8", ModelSize.Quantized, "ggml-large-v3-turbo-q8_0.bin", 834, "01bf15bedffe9f39d65c1b6ff9b687ea91f59e0e", minRamMb = 8192, recommended = false),
    )

    private fun hfModel(
        id: String,
        displayName: String,
        size: ModelSize,
        fileName: String,
        mib: Long,
        sha: String,
        minRamMb: Int,
        recommended: Boolean,
    ) = WhisperModel(
        id = id,
        displayName = displayName,
        size = size,
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName",
        bytes = mib * 1024L * 1024L,
        checksum = sha,
        checksumAlgorithm = ChecksumAlgorithm.SHA1,
        minRamMb = minRamMb,
        recommended = recommended,
    )
}
