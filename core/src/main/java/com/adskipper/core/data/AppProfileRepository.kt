package com.adskipper.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.profileStore by preferencesDataStore(name = "app_profiles")

/**
 * Per-app splash history: how many consecutive launch sessions ended with no
 * ad evidence and no skip. Apps that provably never show splash ads get
 * downgraded to the cheap keyword layer (no screenshots, no OCR) — see the
 * service's poller. Counters reset on any ad sighting and on app updates
 * (an update can introduce ads).
 */
class AppProfileRepository(private val context: Context) {

    /** Consecutive ad-free splash sessions for [pkg]. */
    suspend fun barrenSessions(pkg: String): Int =
        context.profileStore.data.first()[intPreferencesKey(KEY_BARREN + pkg)] ?: 0

    /** Record one finished splash session. [adSeen] = any evidence or a
     *  successful skip this session. */
    suspend fun recordSession(pkg: String, adSeen: Boolean, versionCode: Long) {
        context.profileStore.edit { p ->
            val verKey = longPreferencesKey(KEY_VERSION + pkg)
            val barrenKey = intPreferencesKey(KEY_BARREN + pkg)
            val sameVersion = p[verKey] == versionCode
            p[verKey] = versionCode
            p[barrenKey] = when {
                adSeen -> 0
                !sameVersion -> 1 // version changed (or first sighting): restart the streak
                else -> (p[barrenKey] ?: 0) + 1
            }
        }
    }

    companion object {
        private const val KEY_BARREN = "barren_"
        private const val KEY_VERSION = "ver_"
    }
}
