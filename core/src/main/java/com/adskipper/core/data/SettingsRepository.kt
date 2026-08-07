package com.adskipper.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val masterEnabled: Boolean = true,
    val layer1Enabled: Boolean = true,
    val layer2Enabled: Boolean = true,
    // L3 runs the bundled SmolVLM2 256M model by default (no download needed).
    val layer3Enabled: Boolean = true,
    val keywords: Set<String> = DEFAULT_KEYWORDS,
    val whitelist: Set<String> = DEFAULT_WHITELIST,
    val vlmThreads: Int = 4,
    val vlmTimeoutMs: Long = 4000L,
    val debugOverlay: Boolean = false,
    // Allow the service to act on our own package (fake-ad test screen).
    val selfTest: Boolean = false,
    val activeModelId: String? = null,
) {
    companion object {
        val DEFAULT_KEYWORDS = setOf("跳过", "Skip", "关闭广告", "跳过广告", "skip_ad")

        /** System UI often contains "Skip" labels (setup wizard etc.) — tapping
         *  those is almost never what the user wants. */
        val DEFAULT_WHITELIST = setOf("com.android.systemui")
    }
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            masterEnabled = p[KEY_MASTER] ?: true,
            layer1Enabled = p[KEY_L1] ?: true,
            layer2Enabled = p[KEY_L2] ?: true,
            layer3Enabled = p[KEY_L3] ?: true,
            keywords = p[KEY_KEYWORDS] ?: AppSettings.DEFAULT_KEYWORDS,
            whitelist = p[KEY_WHITELIST] ?: AppSettings.DEFAULT_WHITELIST,
            vlmThreads = p[KEY_VLM_THREADS] ?: 4,
            vlmTimeoutMs = p[KEY_VLM_TIMEOUT] ?: 4000L,
            debugOverlay = p[KEY_DEBUG_OVERLAY] ?: false,
            selfTest = p[KEY_SELF_TEST] ?: false,
            activeModelId = p[KEY_ACTIVE_MODEL]?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun setMasterEnabled(v: Boolean) = edit { it[KEY_MASTER] = v }
    suspend fun setLayer1Enabled(v: Boolean) = edit { it[KEY_L1] = v }
    suspend fun setLayer2Enabled(v: Boolean) = edit { it[KEY_L2] = v }
    suspend fun setLayer3Enabled(v: Boolean) = edit { it[KEY_L3] = v }
    suspend fun setKeywords(v: Set<String>) = edit { it[KEY_KEYWORDS] = v }
    suspend fun setWhitelist(v: Set<String>) = edit { it[KEY_WHITELIST] = v }
    suspend fun setVlmThreads(v: Int) = edit { it[KEY_VLM_THREADS] = v }
    suspend fun setVlmTimeoutMs(v: Long) = edit { it[KEY_VLM_TIMEOUT] = v }
    suspend fun setDebugOverlay(v: Boolean) = edit { it[KEY_DEBUG_OVERLAY] = v }
    suspend fun setSelfTest(v: Boolean) = edit { it[KEY_SELF_TEST] = v }
    suspend fun setActiveModelId(v: String?) = edit { it[KEY_ACTIVE_MODEL] = v ?: "" }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val KEY_MASTER = booleanPreferencesKey("master_enabled")
        val KEY_L1 = booleanPreferencesKey("layer1_enabled")
        val KEY_L2 = booleanPreferencesKey("layer2_enabled")
        val KEY_L3 = booleanPreferencesKey("layer3_enabled")
        val KEY_KEYWORDS = stringSetPreferencesKey("keywords")
        val KEY_WHITELIST = stringSetPreferencesKey("whitelist")
        val KEY_VLM_THREADS = intPreferencesKey("vlm_threads")
        val KEY_VLM_TIMEOUT = longPreferencesKey("vlm_timeout_ms")
        val KEY_DEBUG_OVERLAY = booleanPreferencesKey("debug_overlay")
        val KEY_SELF_TEST = booleanPreferencesKey("self_test")
        val KEY_ACTIVE_MODEL = stringPreferencesKey("active_model_id")
    }
}
