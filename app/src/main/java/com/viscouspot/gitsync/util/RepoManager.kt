package com.viscouspot.gitsync.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class RepoManager internal constructor(context: Context) {
    private val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val settingsSharedPref = EncryptedSharedPreferences.create(
        context,
        "git_sync_repos",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getRepoIndex(): Int {
        return settingsSharedPref.getInt("repoIndex", 0)
    }

    fun setRepoIndex(index: Int) {
        with(settingsSharedPref.edit()) {
            putInt("repoIndex", index)
            apply()
        }
    }

    fun getRepoNames(): List<String> {
        val repoNames = settingsSharedPref.getString("repoNames", null) ?: return listOf("main")
        return repoNames.split(",")
    }

    fun setRepoNames(repoNames: List<String>) {
        with(settingsSharedPref.edit()) {
            putString("repoNames", repoNames.joinToString(","))
            apply()
        }
    }

    fun hasContributed(): Boolean {
        return settingsSharedPref.getBoolean("hasContributed", false)
    }

    fun setHasContributed() {
        with(settingsSharedPref.edit()) {
            putBoolean("hasContributed", true)
            apply()
        }
    }
}
