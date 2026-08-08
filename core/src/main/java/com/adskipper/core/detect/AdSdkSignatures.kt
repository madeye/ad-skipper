package com.adskipper.core.detect

/**
 * Fingerprints of the ad SDKs that serve virtually all CN splash ads.
 * Seeing one of these class names (window state events, node classes) or
 * view-id markers in the tree is strong evidence the current screen is an
 * ad, regardless of what OCR can read off it.
 */
object AdSdkSignatures {

    /** Package prefixes of splash-ad SDK activities/views. Kept to vendor
     *  namespaces — an app's own `SplashActivity` is usually just a logo
     *  screen and must NOT match. */
    private val CLASS_PREFIXES = listOf(
        "com.bytedance.sdk.openadsdk", // 穿山甲 (Pangle)
        "com.qq.e.",                   // 优量汇 (GDT)
        "com.kwad.sdk",                // 快手联盟
        "com.baidu.mobads",            // 百度百青藤
        "com.sigmob.sdk",
        "com.mbridge.msdk",            // Mintegral
        "com.huawei.openalliance.ad",  // 华为 Ads
        "com.miui.zeus.mimo",          // 小米营销
        "com.heytap.msp.mobad",        // OPPO
        "com.vivo.mobilead",           // vivo
        "com.beizi.fusion",            // 倍孜
        "com.octopus.ad",
    )

    /** Substrings of view ids the same SDKs give their splash containers
     *  and skip buttons. */
    private val VIEW_ID_MARKERS = listOf(
        "tt_splash",
        "splash_ad",
        "ad_splash",
        "ksad_",
        "gdt_",
        "mimo_",
        "ad_skip",
        "skip_ad",
    )

    fun matchesClassName(className: String?): Boolean {
        className ?: return false
        return CLASS_PREFIXES.any { className.startsWith(it) }
    }

    fun matchesViewId(viewId: String?): Boolean {
        viewId ?: return false
        return VIEW_ID_MARKERS.any { viewId.contains(it) }
    }
}
