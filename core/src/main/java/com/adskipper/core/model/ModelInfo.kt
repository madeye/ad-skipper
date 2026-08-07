package com.adskipper.core.model

/** A downloadable GGUF file with multiple source URLs (ModelScope first,
 *  hf-mirror as fallback — huggingface.co is unreachable from CN).
 *  All repos/files below are verified to exist on ModelScope (2026-08). */
data class ModelFile(
    val name: String,
    val urls: List<String>,
)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val files: List<ModelFile>,
    /** True if the GGUF files ship inside the APK assets (assets/bundled_model/)
     *  and are extracted to filesDir on first use. */
    val bundled: Boolean = false,
) {
    val modelFile: ModelFile get() = files.first()
    val mmprojFile: ModelFile? get() = files.getOrNull(1)
}

object ModelCatalog {

    private fun ms(repo: String, file: String) =
        "https://modelscope.cn/models/$repo/resolve/master/$file"

    private fun hfm(repo: String, file: String) =
        "https://hf-mirror.com/$repo/resolve/main/$file"

    val qwen25vl3b = ModelInfo(
        id = "qwen25-vl-3b-q4km",
        displayName = "Qwen2.5-VL 3B (Q4_K_M)",
        description = "约 3.3GB（模型 1.9GB + mmproj 1.3GB），定位能力较强，推荐首选",
        files = listOf(
            ModelFile(
                "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
                listOf(
                    ms("ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"),
                    hfm("ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"),
                ),
            ),
            ModelFile(
                "mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf",
                listOf(
                    ms("ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf"),
                    hfm("ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf"),
                ),
            ),
        ),
    )

    val qwen2vl2b = ModelInfo(
        id = "qwen2-vl-2b-q4km",
        displayName = "Qwen2-VL 2B (Q4_K_M)",
        description = "约 1.9GB，体积最小、延迟最低，适合低配机型",
        files = listOf(
            ModelFile(
                "Qwen2-VL-2B-Instruct-Q4_K_M.gguf",
                listOf(
                    ms("bartowski/Qwen2-VL-2B-Instruct-GGUF", "Qwen2-VL-2B-Instruct-Q4_K_M.gguf"),
                    hfm("bartowski/Qwen2-VL-2B-Instruct-GGUF", "Qwen2-VL-2B-Instruct-Q4_K_M.gguf"),
                ),
            ),
            ModelFile(
                "mmproj-Qwen2-VL-2B-Instruct-f16.gguf",
                listOf(
                    ms("bartowski/Qwen2-VL-2B-Instruct-GGUF", "mmproj-Qwen2-VL-2B-Instruct-f16.gguf"),
                    hfm("bartowski/Qwen2-VL-2B-Instruct-GGUF", "mmproj-Qwen2-VL-2B-Instruct-f16.gguf"),
                ),
            ),
        ),
    )

    val minicpmv26 = ModelInfo(
        id = "minicpm-v-2_6-q4km",
        displayName = "MiniCPM-V 2.6 (Q4_K_M)",
        description = "约 5.4GB，效果最强但内存与延迟最高，仅建议旗舰机",
        files = listOf(
            ModelFile(
                "ggml-model-Q4_K_M.gguf",
                listOf(
                    ms("OpenBMB/MiniCPM-V-2_6-gguf", "ggml-model-Q4_K_M.gguf"),
                    hfm("openbmb/MiniCPM-V-2_6-gguf", "ggml-model-Q4_K_M.gguf"),
                ),
            ),
            ModelFile(
                "mmproj-model-f16.gguf",
                listOf(
                    ms("OpenBMB/MiniCPM-V-2_6-gguf", "mmproj-model-f16.gguf"),
                    hfm("openbmb/MiniCPM-V-2_6-gguf", "mmproj-model-f16.gguf"),
                ),
            ),
        ),
    )

    val smolvlm256m = ModelInfo(
        id = "smolvlm2-256m-q8",
        displayName = "SmolVLM2 256M (Q8_0)",
        description = "仅约 266MB，已内置在 App 中，开箱即用；定位能力有限",
        bundled = true,
        files = listOf(
            ModelFile(
                "SmolVLM2-256M-Video-Instruct-Q8_0.gguf",
                listOf(
                    ms("ggml-org/SmolVLM2-256M-Video-Instruct-GGUF", "SmolVLM2-256M-Video-Instruct-Q8_0.gguf"),
                    hfm("ggml-org/SmolVLM2-256M-Video-Instruct-GGUF", "SmolVLM2-256M-Video-Instruct-Q8_0.gguf"),
                ),
            ),
            ModelFile(
                "mmproj-SmolVLM2-256M-Video-Instruct-Q8_0.gguf",
                listOf(
                    ms("ggml-org/SmolVLM2-256M-Video-Instruct-GGUF", "mmproj-SmolVLM2-256M-Video-Instruct-Q8_0.gguf"),
                    hfm("ggml-org/SmolVLM2-256M-Video-Instruct-GGUF", "mmproj-SmolVLM2-256M-Video-Instruct-Q8_0.gguf"),
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

    val all: List<ModelInfo> = listOf(qwen25vl3b, qwen2vl2b, smolvlm256m, minicpmv26, custom)

    val default: ModelInfo get() = smolvlm256m

    fun byId(id: String?): ModelInfo? = all.firstOrNull { it.id == id }
}
