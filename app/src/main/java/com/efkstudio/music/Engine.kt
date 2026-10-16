package com.efkstudio.music

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * yt-dlp (youtubedl-android) sarmalayıcısı. YouTube'dan arama, liste çözme ve
 * MP3 indirme işlerinin hepsi telefonda, uygulamanın içindeki Python + yt-dlp +
 * ffmpeg + QuickJS ile yapılır. Hiçbir aracı siteye bağımlı değildir.
 */
object Engine {
    @Volatile var ready = false
        private set
    @Volatile var initError: String? = null
        private set
    @Volatile var updating = false
        private set
    @Volatile var version: String = ""
        private set

    private val readySignal = CompletableDeferred<Boolean>()
    private lateinit var app: Application

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun init(app: Application) {
        this.app = app
        version = Settings.engineVersion
        appScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                ready = true
                readySignal.complete(true)
                emitState()
                if (version.isEmpty()) refreshVersion()
                if (Settings.autoUpdate &&
                    System.currentTimeMillis() - Settings.lastEngineUpdate > DAY_MS
                ) {
                    update(manual = false)
                }
            } catch (t: Throwable) {
                initError = t.message ?: t.toString()
                readySignal.complete(false)
                emitState()
            }
        }
    }

    suspend fun awaitReady(): Boolean = readySignal.await()

    fun stateJson(): JSONObject = JSONObject()
        .put("ready", ready)
        .put("error", initError ?: "")
        .put("updating", updating)
        .put("version", version)
        .put("lastUpdate", Settings.lastEngineUpdate)

    private fun emitState() = Events.emit("engine", stateJson())

    private fun refreshVersion() {
        try {
            val request = YoutubeDLRequest(emptyList<String>()).addOption("--version")
            val out = YoutubeDL.getInstance().execute(request, null, null).out.trim()
            if (out.isNotEmpty()) {
                version = out.lines().last().trim()
                Settings.engineVersion = version
            }
        } catch (_: Throwable) {
        }
        emitState()
    }

    /** yt-dlp'yi GitHub'daki son kararlı sürüme günceller (YouTube değiştikçe gerekli). */
    fun update(manual: Boolean) {
        if (updating) return
        updating = true
        emitState()
        appScope.launch(Dispatchers.IO) {
            try {
                if (!awaitReady()) {
                    if (manual) Events.toast("İndirme motoru başlatılamadı")
                    return@launch
                }
                val status = YoutubeDL.getInstance()
                    .updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE)
                Settings.lastEngineUpdate = System.currentTimeMillis()
                refreshVersion()
                if (manual) {
                    Events.toast(
                        if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "Motor zaten güncel ($version)"
                        else "Motor güncellendi: $version"
                    )
                }
            } catch (t: Throwable) {
                if (manual) Events.toast("Güncelleme başarısız: " + Errors.friendly(t.message))
            } finally {
                updating = false
                emitState()
            }
        }
    }

    /** YouTube'da arama yapar; sonuçlar hafif (düz) liste olarak gelir. */
    suspend fun search(query: String, limit: Int = 25): JSONArray = withContext(Dispatchers.IO) {
        if (!awaitReady()) throw IllegalStateException(initError ?: "İndirme motoru hazır değil")
        val request = YoutubeDLRequest("ytsearch$limit:$query")
            .addOption("--flat-playlist")
            .addOption("-J")
            .addOption("--no-warnings")
        val out = YoutubeDL.getInstance().execute(request, null, null).out
        entriesToJson(JSONObject(out))
    }

    /** Oynatma listesi / albüm bağlantısındaki şarkıları çıkarır. */
    suspend fun resolvePlaylist(url: String): Pair<String, JSONArray> = withContext(Dispatchers.IO) {
        if (!awaitReady()) throw IllegalStateException(initError ?: "İndirme motoru hazır değil")
        val request = YoutubeDLRequest(url)
            .addOption("--flat-playlist")
            .addOption("--yes-playlist")
            .addOption("-J")
            .addOption("--no-warnings")
        val root = JSONObject(YoutubeDL.getInstance().execute(request, null, null).out)
        root.optString("title") to entriesToJson(root)
    }

    private fun entriesToJson(root: JSONObject): JSONArray {
        val result = JSONArray()
        val entries = root.optJSONArray("entries") ?: return result
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val id = e.optString("id")
            if (!YouTube.isVideoId(id)) continue
            if (e.optString("live_status") == "is_live") continue
            val channel = Meta.cleanArtist(
                e.optString("channel").ifEmpty { e.optString("uploader") }
            )
            result.put(
                JSONObject()
                    .put("videoId", id)
                    .put("title", e.optString("title"))
                    .put("channel", channel)
                    .put("duration", e.optDouble("duration", 0.0).toInt())
                    .put("thumb", YouTube.thumb(id))
                    .put("url", YouTube.watchUrl(id))
            )
        }
        return result
    }
}

object YouTube {
    private val idPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val urlIdPattern =
        Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/|/live/|/v/)([A-Za-z0-9_-]{11})")
    private val urlPattern = Regex("https?://\\S+")

    fun isVideoId(s: String?) = s != null && idPattern.matches(s)

    fun videoIdFrom(url: String): String? = urlIdPattern.find(url)?.groupValues?.get(1)

    /** "youtube.com/playlist?list=..." veya YouTube Music albüm sayfası mı? */
    fun isPlaylistUrl(url: String): Boolean {
        if (videoIdFrom(url) != null) return false
        return url.contains("list=") || url.contains("/browse/") || url.contains("/channel/") ||
            url.contains("/@")
    }

    /** Paylaşılan metnin içinden ilk bağlantıyı çıkarır. */
    fun extractUrl(text: String): String? = urlPattern.find(text)?.value?.trimEnd('.', ',', ')')

    fun thumb(id: String) = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
}

/** Başlık / sanatçı temizliği ("(Official Video)" vb. ekleri atar). */
object Meta {
    private val noise = Regex(
        "\\s*[(\\[][^)\\]]*(official|video|audio|lyric|klip|clip|\\bhd\\b|4k|resmi|visualizer|m/v|\\bmv\\b)[^)\\]]*[)\\]]",
        RegexOption.IGNORE_CASE
    )
    private val pipeNoise = Regex("\\s*[|｜].*$")

    fun cleanTitle(title: String): String {
        val t = noise.replace(title, "").replace(pipeNoise, "").trim()
        return t.ifEmpty { title.trim() }
    }

    fun cleanArtist(artist: String): String = artist
        .removeSuffix(" - Topic")
        .replace(Regex("VEVO$"), "")
        .replace(Regex("\\s*,.*$"), "")
        .trim()

    /** "Sanatçı - Şarkı" biçimindeki başlığı ayırır; sanatçı yoksa kullanılır. */
    fun split(title: String, artist: String): Pair<String, String> {
        val clean = cleanTitle(title)
        if (artist.isNotBlank()) return clean to cleanArtist(artist)
        val idx = clean.indexOf(" - ")
        if (idx > 0) return clean.substring(idx + 3).trim() to clean.substring(0, idx).trim()
        return clean to ""
    }
}

object Errors {
    fun friendly(raw: String?): String {
        val text = raw ?: return "Bilinmeyen hata"
        val line = text.lines().lastOrNull { it.startsWith("ERROR:") }
            ?: text.lines().lastOrNull { it.isNotBlank() } ?: text
        val l = line.lowercase()
        return when {
            "sign in to confirm" in l || "not a bot" in l ->
                "YouTube bot doğrulaması istedi. Biraz bekleyip tekrar deneyin veya Ayarlar'dan motoru güncelleyin."
            "private video" in l -> "Bu video gizli."
            "video unavailable" in l || "not available" in l -> "Video kullanılamıyor (kaldırılmış veya bölgede engelli)."
            "confirm your age" in l || "age-restricted" in l || "inappropriate" in l ->
                "Yaş doğrulaması gereken videolar indirilemiyor."
            "http error 403" in l || "403: forbidden" in l ->
                "YouTube erişimi reddetti (403). Motoru güncelleyip tekrar deneyin."
            "http error 429" in l || "too many requests" in l ->
                "Çok fazla istek gönderildi. Bir süre bekleyip tekrar deneyin."
            "unable to resolve host" in l || "failed to resolve" in l || "getaddrinfo" in l ||
                "network is unreachable" in l || "no address associated" in l || "timed out" in l ->
                "İnternet bağlantısı yok veya çok yavaş."
            "is not a valid url" in l || "unsupported url" in l -> "Bu bağlantı desteklenmiyor."
            "live event" in l || "is live" in l -> "Canlı yayınlar indirilemez."
            else -> line.removePrefix("ERROR:").trim()
                .replace(Regex("^\\[[^\\]]+\\]\\s*[A-Za-z0-9_-]*:\\s*"), "")
                .take(220)
        }
    }
}
