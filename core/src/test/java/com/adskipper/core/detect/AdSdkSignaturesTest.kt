package com.adskipper.core.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdSdkSignaturesTest {

    @Test
    fun `ad sdk activity class names match`() {
        assertTrue(AdSdkSignatures.matchesClassName(
            "com.bytedance.sdk.openadsdk.activity.TTAppOpenAdActivity"))
        assertTrue(AdSdkSignatures.matchesClassName("com.qq.e.ads.PortraitADActivity"))
        assertTrue(AdSdkSignatures.matchesClassName("com.kwad.sdk.api.proxy.app.KsSplashActivity"))
    }

    @Test
    fun `an app's own splash activity does not match`() {
        assertFalse(AdSdkSignatures.matchesClassName("com.douban.frodo.SplashActivity"))
        assertFalse(AdSdkSignatures.matchesClassName("android.widget.FrameLayout"))
        assertFalse(AdSdkSignatures.matchesClassName(null))
    }

    @Test
    fun `ad sdk view ids match`() {
        assertTrue(AdSdkSignatures.matchesViewId("com.hupu.games:id/tt_splash_skip_btn"))
        assertTrue(AdSdkSignatures.matchesViewId("com.foo:id/ksad_skip_view"))
        assertTrue(AdSdkSignatures.matchesViewId("com.foo:id/splash_ad_container"))
    }

    @Test
    fun `ordinary view ids do not match`() {
        assertFalse(AdSdkSignatures.matchesViewId("com.foo:id/recycler_view"))
        assertFalse(AdSdkSignatures.matchesViewId("com.foo:id/skip"))
        assertFalse(AdSdkSignatures.matchesViewId(null))
    }
}
