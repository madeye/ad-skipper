package com.adskipper.core.model

/** A downloadable GGUF file with multiple source URLs (ModelScope first,
 *  hf-mirror as fallback — huggingface.co is unreachable from CN).
 *  All repos/files below are verified to exist on ModelScope (2026-08). */
data class ModelFile(
    val name: String,
    val urls: List<String>,
)

/** Coordinate system a model uses in its grounding answers. */
enum class CoordSpace {
    /** Normalized coordinates; magnitude (0-1 / 0-100 / 0-1000) auto-detected.
     *  InternVL3 and MiniCPM-V answer in 0-1000. */
    NORM,

    /** Absolute pixels relative to the image the model was given
     *  (Qwen2.5-VL convention). */
    PIXELS,
}

data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val files: List<ModelFile>,
    /** True if the GGUF files ship inside the APK assets (assets/bundled_model/)
     *  and are extracted to filesDir on first use. */
    val bundled: Boolean = false,
    /** How this model expresses bounding-box coordinates. */
    val coordSpace: CoordSpace = CoordSpace.NORM,
    /** Screenshot max dimension fed to the model. Benchmarked sweet spots
     *  (vlm-bench 2026-08): InternVL3-2B peaks at 896, Qwen2.5-VL-3B at 672;
     *  448 is too small for any model to see the skip button. */
    val maxDim: Int = 896,
) {
    val modelFile: ModelFile get() = files.first()
    val mmprojFile: ModelFile? get() = files.getOrNull(1)
}

object ModelCatalog {

    private fun ms(repo: String, file: String) =
        "https://modelscope.cn/models/$repo/resolve/master/$file"

    private fun hfm(repo: String, file: String) =
        "https://hf-mirror.com/$repo/resolve/main/$file"

    /** Benchmark winner (16/20 grounding hits @896px, 0 CTA misclicks,
     *  smallest capable model — see vlm-bench/REPORT.md). Too large to bundle
     *  in the APK; offered as the recommended download. */
    val internvl3v2b = ModelInfo(
        id = "internvl3-2b-q4km",
        displayName = "InternVL3 2B (Q4_K_M)",
        description = "约 1.5GB，定位准确率最佳，推荐首选",
        coordSpace = CoordSpace.NORM,
        maxDim = 896,
        files = listOf(
            ModelFile(
                "InternVL3-2B-Instruct-Q4_K_M.gguf",
                listOf(
                    ms("ggml-org/InternVL3-2B-Instruct-GGUF", "InternVL3-2B-Instruct-Q4_K_M.gguf"),
                    hfm("ggml-org/InternVL3-2B-Instruct-GGUF", "InternVL3-2B-Instruct-Q4_K_M.gguf"),
                ),
            ),
            ModelFile(
                "mmproj-InternVL3-2B-Instruct-Q8_0.gguf",
                listOf(
                    ms("ggml-org/InternVL3-2B-Instruct-GGUF", "mmproj-InternVL3-2B-Instruct-Q8_0.gguf"),
                    hfm("ggml-org/InternVL3-2B-Instruct-GGUF", "mmproj-InternVL3-2B-Instruct-Q8_0.gguf"),
                ),
            ),
        ),
    )

    val qwen25vl3b = ModelInfo(
        id = "qwen25-vl-3b-q4km",
        displayName = "Qwen2.5-VL 3B (Q4_K_M)",
        description = "约 2.8GB，定位能力与内置模型相当，可作备选",
        coordSpace = CoordSpace.PIXELS,
        maxDim = 672,
        files = listOf(
            ModelFile(
                "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
                listOf(
                    ms("ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"),
                    hfm("ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"),
                ),
            ),
            ModelFile(
                "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf",
                listOf(
                    ms("ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf"),
                    hfm("ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf"),
                ),
            ),
        ),
    )

    /** User-sideloaded model (files imported via SAF into models/custom/). */
    val custom = ModelInfo(
        id = "custom",
        displayName = "自定义模型",
        description = "手动导入的 GGUF 模型 + mmproj",
        files = listOf(
            ModelFile("model.gguf", emptyList()),
            ModelFile("mmproj.gguf", emptyList()),
        ),
    )

    val all: List<ModelInfo> = listOf(internvl3v2b, qwen25vl3b, custom)

    val default: ModelInfo get() = internvl3v2b

    fun byId(id: String?): ModelInfo? = all.firstOrNull { it.id == id }
}
