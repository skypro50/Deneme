package com.efkstudio.music

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class Track(
    val id: String,
    var title: String,
    var artist: String,
    var duration: Int,
    var uri: String,
    var size: Long,
    val addedAt: Long,
    var fav: Boolean = false,
    var plays: Int = 0,
    var lastPlayed: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("duration", duration)
        .put("uri", uri)
        .put("size", size)
        .put("addedAt", addedAt)
        .put("fav", fav)
        .put("plays", plays)
        .put("lastPlayed", lastPlayed)

    companion object {
        fun fromJson(o: JSONObject) = Track(
            id = o.getString("id"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            duration = o.optInt("duration"),
            uri = o.getString("uri"),
            size = o.optLong("size"),
            addedAt = o.optLong("addedAt"),
            fav = o.optBoolean("fav"),
            plays = o.optInt("plays"),
            lastPlayed = o.optLong("lastPlayed"),
        )
    }
}

/**
 * İndirilen şarkıların listesi. MP3 dosyaları telefonun ortak
 * "Müzik/EFK Music" klasöründe (MediaStore) durur; böylece uygulama silinse
 * bile şarkılar kaybolmaz ve başka müzik uygulamalarında da görünür.
 * Kapak resimleri ve beğeni / dinlenme bilgisi uygulamanın kendi klasöründe.
 */
object Library {
    val RELATIVE_DIR = Environment.DIRECTORY_MUSIC + "/EFK Music"

    private lateinit var app: Context
    private val tracks = LinkedHashMap<String, Track>()
    private val file get() = File(app.filesDir, "library.json")
    lateinit var coversDir: File
        private set

    fun init(context: Context) {
        app = context.applicationContext
        coversDir = File(app.filesDir, "covers").apply { mkdirs() }
        load()
    }

    @Synchronized
    private fun load() {
        tracks.clear()
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val t = Track.fromJson(arr.getJSONObject(i))
                tracks[t.id] = t
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        tracks.values.forEach { arr.put(it.toJson()) }
        val tmp = File(app.filesDir, "library.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }

    @Synchronized
    fun get(id: String): Track? = tracks[id]

    @Synchronized
    fun has(id: String) = tracks.containsKey(id)

    fun coverFile(id: String): File? = File(coversDir, "$id.jpg").takeIf { it.exists() }

    @Synchronized
    fun toJson(): JSONArray {
        val arr = JSONArray()
        tracks.values.forEach { t ->
            val o = t.toJson()
            coverFile(t.id)?.let {
                o.put("cover", "https://appassets.androidplatform.net/covers/${t.id}.jpg?v=${it.lastModified()}")
            }
            arr.put(o)
        }
        return arr
    }

    fun emit() = Events.emit("library", toJson())

    fun toggleFav(id: String) {
        synchronized(this) {
            val t = tracks[id] ?: return
            t.fav = !t.fav
            save()
        }
        emit()
    }

    fun markPlayed(id: String) {
        synchronized(this) {
            val t = tracks[id] ?: return
            t.plays += 1
            t.lastPlayed = System.currentTimeMillis()
            save()
        }
        emit()
    }

    /** Kitaplıktan çıkarır (dosya silme işi çağırana aittir). */
    fun remove(id: String) {
        synchronized(this) {
            tracks.remove(id) ?: return
            File(coversDir, "$id.jpg").delete()
            save()
        }
        emit()
    }

    /** yt-dlp'nin ürettiği MP3'ü Müzik/EFK Music klasörüne taşır ve kitaplığa ekler. */
    fun import(mp3: File, cover: File?, meta: JSONObject?, job: DlJob): Track {
        val id = meta?.optString("id")?.takeIf { it.isNotEmpty() } ?: job.videoId
            ?: mp3.nameWithoutExtension
        val rawTitle = meta?.optString("track")?.takeIf { it.isNotBlank() }
            ?: meta?.optString("title")?.takeIf { it.isNotBlank() } ?: job.title
        val rawArtist = meta?.optString("artist")?.takeIf { it.isNotBlank() } ?: ""
        var (title, artist) = Meta.split(rawTitle, rawArtist)
        if (artist.isEmpty()) {
            artist = Meta.cleanArtist(
                meta?.optString("uploader")?.takeIf { it.isNotBlank() }
                    ?: meta?.optString("channel") ?: job.artist
            )
        }
        val duration = meta?.optDouble("duration", 0.0)?.toInt() ?: 0
        val baseName = safeFileName(if (artist.isNotEmpty()) "$artist - $title" else title)
        val uri = saveToMusic(mp3, "$baseName [$id].mp3")

        if (cover != null && cover.exists()) {
            cover.copyTo(File(coversDir, "$id.jpg"), overwrite = true)
        }

        val track = Track(
            id = id,
            title = title,
            artist = artist,
            duration = duration,
            uri = uri.toString(),
            size = mp3.length(),
            addedAt = System.currentTimeMillis(),
        )
        synchronized(this) {
            tracks.remove(id)?.let { old -> if (old.uri != track.uri) deleteQuietly(Uri.parse(old.uri)) }
            tracks[id] = track
            save()
        }
        emit()
        return track
    }

    private fun safeFileName(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(120)
        .ifEmpty { "sarki" }

    private fun saveToMusic(src: File, displayName: String): Uri {
        val resolver = app.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Müzik klasörüne yazılamadı")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Müzik klasörüne yazılamadı")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw e
        }
        return uri
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            app.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    private fun exists(uri: Uri): Boolean = try {
        app.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: FileNotFoundException) {
        false
    } catch (_: SecurityException) {
        true
    } catch (_: Exception) {
        false
    }

    private val idInName = Regex("\\[([A-Za-z0-9_-]{11})]\\.mp3$", RegexOption.IGNORE_CASE)

    /**
     * Kitaplığı Müzik/EFK Music klasörüyle eşitler: dışarıdan silinen
     * şarkıları çıkarır, uygulama yeniden kurulduysa klasördeki MP3'leri
     * geri ekler.
     */
    fun sync() {
        val resolver = app.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        class Row(val uri: Uri, val name: String, val title: String, val artist: String,
                  val durationMs: Long, val size: Long, val added: Long)

        val rows = ArrayList<Row>()
        try {
            resolver.query(
                collection, projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$RELATIVE_DIR%"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val artist = c.getString(3) ?: ""
                    rows += Row(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        name = c.getString(1) ?: "",
                        title = c.getString(2) ?: "",
                        artist = if (artist == "<unknown>") "" else artist,
                        durationMs = c.getLong(4),
                        size = c.getLong(5),
                        added = c.getLong(6) * 1000,
                    )
                }
            }
        } catch (_: Exception) {
            return
        }

        val newcomers = ArrayList<Track>()
        var changed = false
        synchronized(this) {
            val knownUris = tracks.values.associateBy { it.uri }
            val foundUris = rows.map { it.uri.toString() }.toHashSet()
            val gone = tracks.values.filter { it.uri !in foundUris && !exists(Uri.parse(it.uri)) }
            gone.forEach { tracks.remove(it.id); File(coversDir, "${it.id}.jpg").delete() }
            changed = gone.isNotEmpty()

            for (row in rows) {
                if (knownUris.containsKey(row.uri.toString())) continue
                val videoId = idInName.find(row.name)?.groupValues?.get(1)
                val id = videoId ?: "ms${ContentUris.parseId(row.uri)}"
                val existing = tracks[id]
                if (existing != null) {
                    if (!exists(Uri.parse(existing.uri))) {
                        existing.uri = row.uri.toString()
                        changed = true
                    }
                    continue
                }
                val (title, artist) = Meta.split(row.title.ifEmpty { row.name.removeSuffix(".mp3") }, "")
                val track = Track(
                    id = id,
                    title = title,
                    artist = artist.ifEmpty { Meta.cleanArtist(row.artist) },
                    duration = (row.durationMs / 1000).toInt(),
                    uri = row.uri.toString(),
                    size = row.size,
                    addedAt = row.added,
                )
                tracks[id] = track
                newcomers += track
                changed = true
            }
            if (changed) save()
        }
        // Yeniden kurulumda kapakları MP3'ün içine gömülü resimden çıkar.
        for (t in newcomers) {
            if (coverFile(t.id) != null) continue
            try {
                val bmp = resolver.loadThumbnail(Uri.parse(t.uri), Size(544, 544), null)
                File(coversDir, "${t.id}.jpg").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
                }
            } catch (_: Exception) {
            }
        }
        if (changed) emit()
    }
}
