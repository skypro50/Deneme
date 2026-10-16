package com.efkstudio.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject

/** Uygulama ömrü boyunca yaşayan arka plan işleri için kapsam. */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Yerel taraftan web arayüzüne olay köprüsü. MainActivity açıkken [sink]
 * olayları WebView'a iletir; kapalıyken olaylar düşer, arayüz açılışta
 * `getState()` ile her şeyi baştan okur.
 */
object Events {
    @Volatile
    var sink: ((type: String, json: String) -> Unit)? = null

    fun emit(type: String, data: Any) {
        sink?.invoke(type, data.toString())
    }

    fun toast(message: String) {
        emit("toast", JSONObject().put("msg", message))
    }
}

object Settings {
    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        prefs = context.getSharedPreferences("efk_settings", Context.MODE_PRIVATE)
    }

    /** Kullanıcı YouTube hesabıyla giriş yaptıysa yt-dlp'nin kullanacağı çerez dosyası. */
    val cookieFile: java.io.File? get() = LoginActivity.cookieFile(app).takeIf { it.exists() }

    /** MP3 bit hızı (kbps): "128", "192" veya "320". */
    var quality: String
        get() = prefs.getString("quality", "192") ?: "192"
        set(value) = prefs.edit().putString("quality", value).apply()

    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(value) = prefs.edit().putBoolean("autoUpdate", value).apply()

    var lastEngineUpdate: Long
        get() = prefs.getLong("lastEngineUpdate", 0L)
        set(value) = prefs.edit().putLong("lastEngineUpdate", value).apply()

    var engineVersion: String
        get() = prefs.getString("engineVersion", "") ?: ""
        set(value) = prefs.edit().putString("engineVersion", value).apply()

    var askedPermissions: Boolean
        get() = prefs.getBoolean("askedPermissions", false)
        set(value) = prefs.edit().putBoolean("askedPermissions", value).apply()

    fun toJson(): JSONObject = JSONObject()
        .put("quality", quality)
        .put("autoUpdate", autoUpdate)
        .put("cookies", cookieFile != null)

    fun set(key: String, value: String) {
        when (key) {
            "quality" -> if (value in setOf("128", "192", "320")) quality = value
            "autoUpdate" -> autoUpdate = value == "true"
        }
    }
}

object Notifications {
    const val CHANNEL_DOWNLOADS = "downloads"
    const val ID_DOWNLOADS = 1001

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                context.getString(R.string.channel_downloads),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }
}
