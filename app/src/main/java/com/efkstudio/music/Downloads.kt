package com.efkstudio.music

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class DlJob(
    val id: String,
    val url: String,
    @Volatile var videoId: String?,
    @Volatile var title: String,
    @Volatile var artist: String,
    @Volatile var thumb: String,
) {
    /** queued | running | converting | done | error | canceled */
    @Volatile var status = "queued"
    @Volatile var progress = 0f
    @Volatile var eta = -1L
    @Volatile var error = ""
    @Volatile var note = ""
    @Volatile var cancelRequested = false
    val createdAt = System.currentTimeMillis()

    val active get() = status == "queued" || status == "running" || status == "converting"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("url", url)
        .put("videoId", videoId ?: "")
        .put("title", title)
        .put("artist", artist)
        .put("thumb", thumb)
        .put("status", status)
        .put("progress", progress.toDouble())
        .put("eta", eta)
        .put("error", error)
        .put("note", note)
        .put("createdAt", createdAt)
}

/** Sıralı indirme kuyruğu. Python süreci ağır olduğu için aynı anda tek iş çalışır. */
object DownloadQueue {
    private val jobs = ArrayList<DlJob>()
    private var running = false
    private var lastEmit = 0L
    private var lastNotify = 0L

    private const val CROP_TO_SQUARE =
        "ThumbnailsConvertor+FFmpeg_o:-c:v mjpeg -qmin 1 -q:v 2 -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\""
    private const val SPLIT_ARTIST =
        "%(artist&_A_|)s%(title)s:^(?!_A_)(?P<artist>.+?) - (?P<title>.+)$"
    private const val TITLE_NOISE =
        "(?i)\\s*[(\\[][^)\\]]*(official|video|audio|lyric|klip|clip|hd|4k|resmi|visualizer)[^)\\]]*[)\\]]"
    private const val META_TEMPLATE =
        "video:%(.{id,title,track,artist,uploader,channel,duration,album})j"

    @Synchronized
    fun toJson(): JSONArray {
        val arr = JSONArray()
        jobs.forEach { arr.put(it.toJson()) }
        return arr
    }

    @Synchronized
    fun hasActive() = jobs.any { it.active }

    @Synchronized
    fun activeCount() = jobs.count { it.active }

    private fun emit(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmit < 300) return
        lastEmit = now
        Events.emit("downloads", toJson())
    }

    /** Kuyruğa yeni bağlantı ekler. Aynı şarkı zaten varsa / kuyruktaysa eklemez. */
    fun enqueue(
        context: Context,
        url: String,
        title: String = "",
        artist: String = "",
        thumb: String = "",
        quiet: Boolean = false,
    ): Boolean {
        val videoId = YouTube.videoIdFrom(url)
        val normalizedUrl = if (videoId != null) YouTube.watchUrl(videoId) else url
        synchronized(this) {
            if (videoId != null) {
                if (Library.has(videoId)) {
                    if (!quiet) Events.toast("Bu şarkı zaten kitaplığında")
                    return false
                }
                if (jobs.any { it.videoId == videoId && it.active }) {
                    if (!quiet) Events.toast("Bu şarkı zaten indiriliyor")
                    return false
                }
            }
            jobs.removeAll { it.videoId != null && it.videoId == videoId && !it.active }
            jobs += DlJob(
                id = UUID.randomUUID().toString().take(12),
                url = normalizedUrl,
                videoId = videoId,
                title = title.ifEmpty { if (YouTube.isPlaylistUrl(url)) "Oynatma listesi" else "Bilgiler alınıyor…" },
                artist = artist,
                thumb = thumb.ifEmpty { videoId?.let { YouTube.thumb(it) } ?: "" },
            )
        }
        emit()
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        return true
    }

    fun cancel(jobId: String) {
        val job = synchronized(this) { jobs.firstOrNull { it.id == jobId } } ?: return
        job.cancelRequested = true
        if (job.status == "queued") job.status = "canceled"
        else if (job.active) YoutubeDL.getInstance().destroyProcessById(job.id)
        emit()
    }

    fun retry(context: Context, jobId: String) {
        val job = synchronized(this) { jobs.firstOrNull { it.id == jobId } } ?: return
        if (job.active) return
        synchronized(this) { jobs.remove(job) }
        enqueue(context, job.url, job.title.takeUnless { it.startsWith("Bilgiler") } ?: "", job.artist, job.thumb)
    }

    fun remove(jobId: String) {
        synchronized(this) { jobs.removeAll { it.id == jobId && !it.active } }
        emit()
    }

    fun clearFinished() {
        synchronized(this) { jobs.removeAll { !it.active } }
        emit()
    }

    /** DownloadService tarafından çağrılır; kuyruk boşalana kadar işler. */
    fun ensureRunning(service: DownloadService) {
        synchronized(this) {
            if (running) return
            running = true
        }
        appScope.launch(Dispatchers.IO) {
            while (true) {
                val job = synchronized(this@DownloadQueue) {
                    jobs.firstOrNull { it.status == "queued" }.also { if (it == null) running = false }
                } ?: break
                process(service, job)
            }
            service.onQueueIdle()
        }
    }

    private suspend fun process(service: DownloadService, job: DlJob) {
        if (!Engine.ready) {
            job.note = "İndirme motoru hazırlanıyor…"
            emit()
        }
        if (Engine.updating) {
            job.note = "Motor güncelleniyor, birazdan başlayacak…"
            emit()
        }
        if (!Engine.awaitIdle()) {
            fail(job, "İndirme motoru başlatılamadı: " + (Engine.initError ?: ""))
            return
        }
        if (job.cancelRequested) {
            job.status = "canceled"; emit(); return
        }
        if (YouTube.isPlaylistUrl(job.url)) {
            expandPlaylist(service, job)
            return
        }
        job.status = "running"
        job.note = ""
        emit()
        service.updateNotification(job)

        val work = File(service.cacheDir, "dl/${job.id}")
        try {
            work.deleteRecursively()
            work.mkdirs()
            try {
                runYtDlp(service, job, work, full = true)
            } catch (e: YoutubeDLException) {
                if (job.cancelRequested || !isPostProcessingError(e.message)) throw e
                // Kapak/etiket işlemede sorun olduysa sade ayarlarla bir kez daha dene.
                work.deleteRecursively()
                work.mkdirs()
                runYtDlp(service, job, work, full = false)
            }
            val files = work.listFiles().orEmpty()
            val mp3 = files.firstOrNull { it.extension.equals("mp3", true) }
                ?: throw IllegalStateException("MP3 dosyası oluşturulamadı")
            val cover = files.firstOrNull { it.extension.lowercase() in setOf("jpg", "jpeg") }
            val meta = readMeta(File(work, "meta.json"))
            job.status = "converting"
            job.note = "Müzik klasörüne kaydediliyor…"
            emit()
            val track = Library.import(mp3, cover, meta, job)
            job.videoId = track.id
            job.title = track.title
            job.artist = track.artist
            job.status = "done"
            job.progress = 100f
            job.note = ""
            Events.emit("downloaded", track.toJson())
        } catch (e: YoutubeDL.CanceledException) {
            job.status = "canceled"
        } catch (e: Throwable) {
            if (job.cancelRequested) job.status = "canceled"
            else fail(job, Errors.friendly(e.message), emitNow = false)
        } finally {
            work.deleteRecursively()
            emit()
        }
    }

    private fun runYtDlp(service: DownloadService, job: DlJob, work: File, full: Boolean) {
        val metaFile = File(work, "meta.json")
        val request = YoutubeDLRequest(job.url)
            .addOption("--no-playlist")
            .addOption("--newline")
            .addOption("--no-mtime")
            .addOption("--cache-dir", Engine.cacheDir())
            .addOption("-f", "bestaudio/best")
            .addOption("-x")
            .addOption("--audio-format", "mp3")
            .addOption("--audio-quality", Settings.quality + "K")
            .addOption("-o", File(work, "%(id)s.%(ext)s").absolutePath)
        if (full) {
            request
                .addOption("--embed-metadata")
                .addOption("--embed-thumbnail")
                .addOption("--write-thumbnail")
                .addOption("--convert-thumbnails", "jpg")
                .addOption("--ppa", CROP_TO_SQUARE)
                .addOption("--parse-metadata", SPLIT_ARTIST)
                .addCommands(listOf("--replace-in-metadata", "title", TITLE_NOISE, ""))
        }
        request.addCommands(listOf("--print-to-file", META_TEMPLATE, metaFile.absolutePath))

        var metaApplied = false
        val callback: (Float, Long, String) -> Unit = { progress, eta, line ->
            if (!metaApplied && metaFile.exists()) {
                metaApplied = applyMeta(job, readMeta(metaFile))
            }
            when {
                line.startsWith("[ExtractAudio]") || line.startsWith("[EmbedThumbnail]") ||
                    line.startsWith("[Metadata]") -> {
                    job.status = "converting"
                    job.note = "MP3'e dönüştürülüyor…"
                }
                line.startsWith("[download]") && progress >= 0 -> {
                    job.status = "running"
                    job.progress = progress
                    job.eta = eta
                }
            }
            emit(force = false)
            service.updateNotification(job, throttle = true)
        }
        YoutubeDL.getInstance().execute(request, job.id, callback)
        if (!metaApplied) applyMeta(job, readMeta(metaFile))
    }

    private fun readMeta(file: File): JSONObject? = try {
        if (file.exists()) file.readLines().lastOrNull { it.isNotBlank() }?.let { JSONObject(it) } else null
    } catch (_: Exception) {
        null
    }

    private fun applyMeta(job: DlJob, meta: JSONObject?): Boolean {
        meta ?: return false
        val id = meta.optString("id")
        if (YouTube.isVideoId(id)) {
            job.videoId = id
            if (job.thumb.isEmpty()) job.thumb = YouTube.thumb(id)
        }
        val raw = meta.optString("track").ifBlank { meta.optString("title") }
        if (raw.isNotBlank()) {
            val (title, artist) = Meta.split(raw, meta.optString("artist"))
            job.title = title
            job.artist = artist.ifEmpty {
                Meta.cleanArtist(meta.optString("uploader").ifBlank { meta.optString("channel") })
            }
        }
        emit()
        return true
    }

    private fun isPostProcessingError(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return "postprocessing" in m || "ffmpeg" in m || "thumbnail" in m || "conversion failed" in m ||
            "metadataparser" in m || "embedthumbnail" in m
    }

    private suspend fun expandPlaylist(service: DownloadService, job: DlJob) {
        job.status = "running"
        job.note = "Liste çözülüyor…"
        emit()
        try {
            val (name, entries) = Engine.resolvePlaylist(job.url)
            var added = 0
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val ok = enqueue(
                    service, e.getString("url"), e.optString("title"), e.optString("channel"),
                    e.optString("thumb"), quiet = true
                )
                if (ok) added++
            }
            job.title = name.ifEmpty { "Oynatma listesi" }
            job.status = "done"
            job.progress = 100f
            job.note = "$added şarkı kuyruğa eklendi"
        } catch (e: Throwable) {
            fail(job, Errors.friendly(e.message), emitNow = false)
        }
        emit()
    }

    private fun fail(job: DlJob, message: String, emitNow: Boolean = true) {
        job.status = "error"
        job.error = message
        job.note = ""
        if (emitNow) emit()
    }

    internal fun shouldNotify(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return false
        lastNotify = now
        return true
    }
}

/** İndirmeler sürerken uygulama arka plana alınsa da işin bitmesini sağlar. */
class DownloadService : Service() {
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        ServiceCompat.startForeground(
            this,
            Notifications.ID_DOWNLOADS,
            buildNotification(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        DownloadQueue.ensureRunning(this)
        return START_NOT_STICKY
    }

    // Android 15: dataSync servislerinin günlük süre sınırı dolduğunda çağrılır.
    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun onQueueIdle() {
        ContextCompat.getMainExecutor(this).execute {
            if (!DownloadQueue.hasActive()) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(lastStartId)
            }
        }
    }

    fun updateNotification(job: DlJob, throttle: Boolean = false) {
        if (throttle && !DownloadQueue.shouldNotify()) return
        try {
            NotificationManagerCompat.from(this).notify(Notifications.ID_DOWNLOADS, buildNotification(job))
        } catch (_: SecurityException) {
        }
    }

    private fun buildNotification(job: DlJob?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra("tab", "downloads"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val remaining = DownloadQueue.activeCount()
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF2D7CFF.toInt())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        if (job == null) {
            builder.setContentTitle("MP3 indiriliyor").setProgress(0, 0, true)
        } else {
            val title = job.title.ifEmpty { "MP3 indiriliyor" }
            builder.setContentTitle(title)
            if (job.status == "converting") {
                builder.setContentText("MP3'e dönüştürülüyor…").setProgress(0, 0, true)
            } else {
                builder.setContentText("%${job.progress.toInt()}" + if (remaining > 1) " • sırada ${remaining - 1} şarkı" else "")
                    .setProgress(100, job.progress.toInt(), job.progress <= 0f)
            }
        }
        return builder.build()
    }
}
