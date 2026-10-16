package com.efkstudio.music

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    lateinit var web: WebView
        private set
    var controller: MediaController? = null
        private set

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val main = Handler(Looper.getMainLooper())
    private var pageReady = false
    private var pendingShare: String? = null
    private var pendingTab: String? = null
    private var pendingDeleteId: String? = null
    private var pendingEval: String? = null
    private var insetTop = 0
    private var insetBottom = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            syncLibrary()
        }

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val id = pendingDeleteId ?: return@registerForActivityResult
            pendingDeleteId = null
            if (result.resultCode == RESULT_OK) {
                val track = Library.get(id)
                // Android 10'da izin verildikten sonra silmeyi biz tekrar denemeliyiz.
                if (track != null && Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    try {
                        contentResolver.delete(Uri.parse(track.uri), null, null)
                    } catch (_: Exception) {
                    }
                }
                removeFromQueue(id)
                Library.remove(id)
                Events.toast("Şarkı silindi")
            }
        }

    private val positionTicker = object : Runnable {
        override fun run() {
            val c = controller
            if (c != null && c.isPlaying) {
                emitToWeb("pos", JSONObject().put("pos", c.currentPosition).put("dur", c.duration.coerceAtLeast(0)))
            }
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF050A16.toInt())
        web = WebView(this)
        web.setBackgroundColor(0xFF050A16.toInt())
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        setupWebView()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val density = resources.displayMetrics.density
            insetTop = (bars.top / density).toInt()
            insetBottom = if (ime.bottom > 0) 0 else (bars.bottom / density).toInt()
            web.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = if (ime.bottom > 0) ime.bottom else 0
            }
            pushInsets()
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!pageReady) {
                    moveTaskToBack(true)
                    return
                }
                web.evaluateJavascript("window.efk ? efk.back() : false") { handled ->
                    if (handled != "true") moveTaskToBack(true)
                }
            }
        })

        Events.sink = { type, json -> main.post { emitToWeb(type, json) } }
        connectController()
        askPermissions()
        handleIntent(intent)
    }

    private fun setupWebView() {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/covers/", WebViewAssetLoader.InternalStoragePathHandler(this, Library.coversDir))
            .build()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
        }
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == "appassets.androidplatform.net") return false
                openExternal(url.toString())
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pushInsets()
                pendingTab?.let { emitToWeb("navigate", JSONObject().put("tab", it)); pendingTab = null }
                pendingShare?.let { startShared(it); pendingShare = null }
                pendingEval?.let { web.evaluateJavascript(it, null); pendingEval = null }
            }
        }
        web.addJavascriptInterface(WebBridge(this), "EFKNative")
        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    private fun pushInsets() {
        if (!pageReady) return
        web.evaluateJavascript("window.efk && efk.setInsets($insetTop,$insetBottom)", null)
    }

    fun emitToWeb(type: String, json: String) {
        if (!pageReady) return
        web.evaluateJavascript("window.efk && efk.onEvent(${JSONObject.quote(type)},$json)", null)
    }

    private fun emitToWeb(type: String, data: JSONObject) = emitToWeb(type, data.toString())

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Yalnızca debug derlemede: otomatik emülatör testi arayüzü JS ile sürer.
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("efk_eval")?.let { js ->
                if (pageReady) web.evaluateJavascript(js, null) else pendingEval = js
                intent.removeExtra("efk_eval")
            }
        }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = YouTube.extractUrl(text)
            if (url == null) {
                Events.toast("Paylaşılan metinde bağlantı bulunamadı")
                return
            }
            if (pageReady) startShared(url) else pendingShare = url
            intent.action = null
            return
        }
        val tab = when {
            intent.getBooleanExtra("openPlayer", false) -> "player"
            else -> intent.getStringExtra("tab")
        } ?: return
        if (pageReady) emitToWeb("navigate", JSONObject().put("tab", tab)) else pendingTab = tab
    }

    private fun startShared(url: String) {
        if (DownloadQueue.enqueue(this, url)) {
            Events.toast("İndirme kuyruğa eklendi")
            emitToWeb("navigate", JSONObject().put("tab", "downloads"))
        }
    }

    // ---- İzinler ve kitaplık ----

    private fun askPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty() && !Settings.askedPermissions) {
            Settings.askedPermissions = true
            permissionLauncher.launch(wanted.toTypedArray())
        } else {
            syncLibrary()
        }
    }

    private fun syncLibrary() {
        appScope.launch(Dispatchers.IO) { Library.sync() }
    }

    fun deleteTrack(id: String) {
        val track = Library.get(id) ?: return
        val uri = Uri.parse(track.uri)
        try {
            contentResolver.delete(uri, null, null)
            removeFromQueue(id)
            Library.remove(id)
            Events.toast("Şarkı silindi")
        } catch (e: SecurityException) {
            // Uygulama yeniden kurulduysa dosya artık "bizim" değil; kullanıcıdan onay iste.
            val sender = when {
                Build.VERSION.SDK_INT >= 30 ->
                    MediaStore.createDeleteRequest(contentResolver, listOf(uri)).intentSender
                e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender == null) {
                Events.toast("Dosya silinemedi")
                return
            }
            pendingDeleteId = id
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    fun shareTrack(id: String) {
        val track = Library.get(id) ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/mpeg")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(track.uri))
            .putExtra(Intent.EXTRA_TITLE, track.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, track.title))
    }

    fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Events.toast("Bağlantı açılamadı")
        }
    }

    fun readClipboard(): String {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
    }

    // ---- Oynatıcı ----

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try {
                future.get()
            } catch (_: Exception) {
                return@addListener
            }
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    emitPlayer()
                }
            })
            emitPlayer()
        }, ContextCompat.getMainExecutor(this))
    }

    fun playerStateJson(): JSONObject {
        val c = controller ?: return JSONObject().put("id", "").put("queue", org.json.JSONArray())
        val queue = org.json.JSONArray()
        for (i in 0 until c.mediaItemCount) queue.put(c.getMediaItemAt(i).mediaId)
        val order = org.json.JSONArray()
        val timeline = c.currentTimeline
        if (!timeline.isEmpty) {
            var i = timeline.getFirstWindowIndex(c.shuffleModeEnabled)
            while (i != C.INDEX_UNSET && order.length() < timeline.windowCount) {
                order.put(i)
                i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            }
        }
        return JSONObject()
            .put("id", c.currentMediaItem?.mediaId ?: "")
            .put("playing", c.isPlaying)
            .put("buffering", c.playbackState == Player.STATE_BUFFERING)
            .put("ended", c.playbackState == Player.STATE_ENDED)
            .put("pos", c.currentPosition)
            .put("dur", c.duration.coerceAtLeast(0))
            .put("shuffle", c.shuffleModeEnabled)
            .put("repeat", c.repeatMode)
            .put("index", c.currentMediaItemIndex)
            .put("queue", queue)
            .put("order", order)
    }

    /** WebBridge'in herhangi bir iş parçacığından okuyabileceği son oynatıcı durumu. */
    @Volatile
    var playerSnapshot: String = "{}"
        private set

    private fun emitPlayer() {
        val json = playerStateJson().toString()
        playerSnapshot = json
        emitToWeb("player", json)
    }

    fun playIds(ids: List<String>, index: Int, shuffle: Boolean) {
        val c = controller ?: run { Events.toast("Oynatıcı hazırlanıyor, tekrar dene"); return }
        val items = ids.mapNotNull { Library.get(it)?.toMediaItem() }
        if (items.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle && index < 0) items.indices.random() else index.coerceIn(0, items.size - 1)
        c.setMediaItems(items, start, 0L)
        c.prepare()
        c.play()
    }

    fun enqueueTrack(id: String, next: Boolean) {
        val c = controller ?: return
        val item = Library.get(id)?.toMediaItem() ?: return
        if (c.mediaItemCount == 0) {
            playIds(listOf(id), 0, false)
            return
        }
        if (next) c.addMediaItem(c.currentMediaItemIndex + 1, item) else c.addMediaItem(item)
        Events.toast(if (next) "Sıradaki olarak eklendi" else "Sıraya eklendi")
    }

    fun playerCommand(cmd: String, arg: String) {
        val c = controller ?: return
        when (cmd) {
            "toggle" -> {
                if (c.isPlaying) c.pause()
                else {
                    if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0L)
                    if (c.playbackState == Player.STATE_IDLE) c.prepare()
                    c.play()
                }
            }
            "play" -> c.play()
            "pause" -> c.pause()
            "next" -> if (c.hasNextMediaItem()) c.seekToNextMediaItem()
            "prev" -> c.seekToPrevious()
            "seek" -> arg.toLongOrNull()?.let { c.seekTo(it) }
            "index" -> arg.toIntOrNull()?.let { if (it in 0 until c.mediaItemCount) { c.seekTo(it, 0L); c.play() } }
            "remove" -> arg.toIntOrNull()?.let { if (it in 0 until c.mediaItemCount) c.removeMediaItem(it) }
            "shuffle" -> c.shuffleModeEnabled = !c.shuffleModeEnabled
            "repeat" -> c.repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            "stop" -> { c.stop(); c.clearMediaItems() }
        }
        emitPlayer()
    }

    private fun removeFromQueue(id: String) {
        val c = controller ?: return
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (c.getMediaItemAt(i).mediaId == id) c.removeMediaItem(i)
        }
    }

    override fun onStart() {
        super.onStart()
        main.removeCallbacks(positionTicker)
        main.post(positionTicker)
        if (pageReady) {
            emitPlayer()
            Library.emit()
            Events.emit("downloads", DownloadQueue.toJson())
        }
    }

    override fun onStop() {
        main.removeCallbacks(positionTicker)
        super.onStop()
    }

    override fun onDestroy() {
        Events.sink = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        web.removeJavascriptInterface("EFKNative")
        web.destroy()
        super.onDestroy()
    }
}
