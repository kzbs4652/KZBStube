package com.kzbs.kzbstube

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

class KZBStubeApp : Application() {

    companion object {
        lateinit var appContext: Context
            private set

        const val PREFS_SETTINGS = "kzbstube_prefs"
        const val KEY_THEME_MODE = "theme_mode" // 0: Auto (System), 1: Dark, 2: Light
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val NOTIF_CHANNEL_ID = "kzbstube_downloads"

        fun applyTheme(mode: Int) {
            val nightMode = when (mode) {
                1 -> AppCompatDelegate.MODE_NIGHT_YES
                2 -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }

        fun areNotificationsEnabled(): Boolean {
            val prefs = appContext.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_NOTIFICATIONS, true)
        }

        fun setNotificationsEnabled(enabled: Boolean) {
            val prefs = appContext.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply()
        }

        fun getSavedThemeMode(): Int {
            val prefs = appContext.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_THEME_MODE, 0)
        }

        fun saveThemeMode(mode: Int) {
            val prefs = appContext.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
            applyTheme(mode)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // Initialize user-selected theme (Default: 0 - Follow System)
        val mode = getSavedThemeMode()
        applyTheme(mode)

        // Create notification channel for downloads
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "KZBStube İndirmeler",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "İndirme durumu ve tamamlama bildirimleri"
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
