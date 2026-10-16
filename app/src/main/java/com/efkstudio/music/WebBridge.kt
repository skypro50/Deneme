package com.efkstudio.music

import android.webkit.JavascriptInterface
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Web arayüzünün (assets/web) çağırdığı yerel fonksiyonlar. JavaScript
 * tarafında `window.EFKNative` olarak görünür. Bu metotlar WebView'ın arka
 * plan iş parçacığında çalışır; arayüz işleri ana iş parçacığına aktarılır.
 */
class WebBridge(private val activity: MainActivity) {

    private fun ui(block: () -> Unit) = activity.runOnUiThread(block)

    @JavascriptInterface
    fun getState(): String {
        return JSONObject()
            .put("library", Library.toJson())
            .put("downloads", DownloadQueue.toJson())
            .put("engine", Engine.stateJson())
            .put("settings", Settings.toJson())
            .put("app", JSONObject()
                .put("version", BuildConfig.VERSION_NAME)
                .put("build", BuildConfig.VERSION_CODE))
            .put("player", JSONObject(activity.playerSnapshot))
            .toString()
    }

    @JavascriptInterface
    fun search(reqId: String, query: String) {
        appScope.launch {
            val payload = JSONObject().put("reqId", reqId)
            try {
                payload.put("results", Engine.search(query))
            } catch (t: Throwable) {
                payload.put("error", Errors.friendly(t.message))
            }
            Events.emit("search", payload)
        }
    }

    @JavascriptInterface
    fun download(url: String, metaJson: String) {
        val meta = try { JSONObject(metaJson) } catch (_: Exception) { JSONObject() }
        val clean = YouTube.extractUrl(url) ?: url.trim()
        if (!clean.startsWith("http")) {
            Events.toast("Geçerli bir YouTube bağlantısı yapıştır")
            return
        }
        ui {
            DownloadQueue.enqueue(
                activity, clean,
                meta.optString("title"), meta.optString("artist"), meta.optString("thumb")
            )
        }
    }

    @JavascriptInterface
    fun cancelDownload(id: String) = DownloadQueue.cancel(id)

    @JavascriptInterface
    fun retryDownload(id: String) = ui { DownloadQueue.retry(activity, id) }

    @JavascriptInterface
    fun removeDownload(id: String) = DownloadQueue.remove(id)

    @JavascriptInterface
    fun clearDownloads() = DownloadQueue.clearFinished()

    @JavascriptInterface
    fun play(idsJson: String, index: Int, shuffle: Boolean) {
        val arr = JSONArray(idsJson)
        val ids = List(arr.length()) { arr.getString(it) }
        ui { activity.playIds(ids, index, shuffle) }
    }

    @JavascriptInterface
    fun queue(id: String, next: Boolean) = ui { activity.enqueueTrack(id, next) }

    @JavascriptInterface
    fun player(cmd: String, arg: String) = ui { activity.playerCommand(cmd, arg) }

    @JavascriptInterface
    fun toggleFav(id: String) = Library.toggleFav(id)

    @JavascriptInterface
    fun deleteTrack(id: String) = ui { activity.deleteTrack(id) }

    @JavascriptInterface
    fun shareTrack(id: String) = ui { activity.shareTrack(id) }

    @JavascriptInterface
    fun setSetting(key: String, value: String) {
        Settings.set(key, value)
        Events.emit("settings", Settings.toJson())
    }

    @JavascriptInterface
    fun updateEngine() = Engine.update(manual = true)

    @JavascriptInterface
    fun readClipboard(): String {
        var text = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        ui {
            text = try { activity.readClipboard() } catch (_: Exception) { "" }
            latch.countDown()
        }
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        return text
    }

    @JavascriptInterface
    fun openUrl(url: String) = ui { activity.openExternal(url) }

    @JavascriptInterface
    fun rescan() {
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            Library.sync()
            Events.toast("Müzik klasörü tarandı")
        }
    }

    /** GitHub'daki son APK sürümünü kontrol eder (uygulama güncellemesi için). */
    @JavascriptInterface
    fun checkAppUpdate(manual: Boolean) {
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name")
                val build = tag.substringAfterLast('.').toIntOrNull() ?: 0
                var apk = ""
                val assets = json.optJSONArray("assets") ?: JSONArray()
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == "EFK-Music.apk") apk = a.optString("browser_download_url")
                }
                val newer = build > BuildConfig.VERSION_CODE
                Events.emit("appUpdate", JSONObject()
                    .put("available", newer)
                    .put("version", tag)
                    .put("url", apk.ifEmpty { json.optString("html_url") })
                    .put("notes", json.optString("body").take(600)))
                if (manual && !newer) Events.toast("Uygulama güncel (${BuildConfig.VERSION_NAME})")
            } catch (t: Throwable) {
                if (manual) Events.toast("Güncelleme kontrol edilemedi")
            }
        }
    }
}
