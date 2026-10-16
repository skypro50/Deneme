package com.efkstudio.music

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.File

/**
 * YouTube "bot olmadığını doğrula" uyarısı verirse kullanıcı burada kendi
 * hesabıyla giriş yapar; çerezler yt-dlp'nin okuyacağı cookies.txt'ye yazılır.
 */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF050A16.toInt())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
        }
        val title = TextView(this).apply {
            text = "YouTube'a giriş yap, sonra Kaydet'e bas"
            setTextColor(0xFFF2F6FF.toInt())
            textSize = 15f
        }
        val save = Button(this).apply {
            text = "Kaydet"
            setOnClickListener { saveCookies() }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(save)
        web = WebView(this)
        root.addView(bar)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Google, gömülü tarayıcı imzası taşıyan oturum açmaları reddedebiliyor.
        web.settings.userAgentString = web.settings.userAgentString
            .replace("; wv", "")
            .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
        web.webViewClient = WebViewClient()
        web.loadUrl(LOGIN_URL)
    }

    private fun saveCookies() {
        val cm = CookieManager.getInstance()
        cm.flush()
        val raw = cm.getCookie("https://www.youtube.com").orEmpty()
        val pairs = raw.split(";").map { it.trim() }.filter { it.contains('=') }
        val names = pairs.map { it.substringBefore('=') }.toSet()
        if (names.none { it in LOGIN_MARKERS }) {
            Toast.makeText(this, "Giriş algılanmadı. Önce YouTube hesabına giriş yap.", Toast.LENGTH_LONG).show()
            return
        }
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val text = buildString {
            append("# Netscape HTTP Cookie File\n")
            for (p in pairs) {
                val name = p.substringBefore('=')
                val value = p.substringAfter('=')
                append(".youtube.com\tTRUE\t/\tTRUE\t$expiry\t$name\t$value\n")
            }
        }
        cookieFile(this).writeText(text)
        Events.emit("settings", Settings.toJson())
        Events.toast("YouTube hesabı bağlandı")
        finish()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=https%3A%2F%2Fm.youtube.com%2F"
        private val LOGIN_MARKERS = setOf("SID", "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO", "SAPISID")

        fun cookieFile(context: android.content.Context) = File(context.filesDir, "youtube-cookies.txt")

        fun logout(context: android.content.Context) {
            cookieFile(context).delete()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }
}
