package com.kzbs.kzbstube.downloader

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kzbs.kzbstube.KZBStubeApp
import com.kzbs.kzbstube.model.DownloadItem
import com.kzbs.kzbstube.model.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object DownloadManager {

    private const val PREFS_NAME = "kzbstube_prefs"
    private const val KEY_DOWNLOADS = "completed_downloads"
    const val KEY_CUSTOM_PATH = "custom_download_path"

    val activeDownloads = mutableListOf<DownloadItem>()
    val completedDownloads = mutableListOf<DownloadItem>()
    private val downloadJobs = mutableMapOf<String, Job>()

    var onProgressUpdate: (() -> Unit)? = null

    init {
        loadCompleted()
    }

    fun getDownloadDirectory(): File {
        val prefs = KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customPath = prefs.getString(KEY_CUSTOM_PATH, null)
        if (customPath != null) {
            val customDir = File(customPath)
            if (customDir.exists() && customDir.isDirectory) {
                return customDir
            }
        }
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    fun setCustomDownloadDirectory(path: String) {
        val prefs = KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_PATH, path).apply()
    }

    private fun loadCompleted() {
        val prefs = KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_DOWNLOADS, null) ?: return
        try {
            val type = object : TypeToken<List<DownloadItem>>() {}.type
            val list: List<DownloadItem> = Gson().fromJson(json, type)
            completedDownloads.clear()
            completedDownloads.addAll(list)
        } catch (_: Exception) {}
    }

    private fun saveCompleted() {
        val prefs = KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Gson().toJson(completedDownloads)
        prefs.edit().putString(KEY_DOWNLOADS, json).apply()
    }

    /**
     * Downloads real bytes from the streaming URL into device storage
     */
    fun startDownload(
        video: VideoItem,
        format: String,
        quality: String,
        streamUrl: String,
        totalSizeBytes: Long
    ) {
        val ext = if (format.contains("MP3", ignoreCase = true)) "mp3" else "mp4"
        val cleanTitle = video.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val downloadFolder = getDownloadDirectory()
        if (!downloadFolder.exists()) downloadFolder.mkdirs()

        val targetFile = File(downloadFolder, "$cleanTitle.$ext")
        val item = DownloadItem(
            id = "${video.id}_${System.currentTimeMillis()}",
            videoId = video.id,
            title = video.title,
            author = video.author,
            thumbnailUrl = video.thumbnailUrl,
            format = if (ext == "mp3") "MP3" else "MP4",
            quality = quality,
            filePath = targetFile.absolutePath,
            totalBytes = if (totalSizeBytes > 0) totalSizeBytes else 15 * 1024 * 1024L
        )

        activeDownloads.add(0, item)
        onProgressUpdate?.invoke()

        val job = CoroutineScope(Dispatchers.IO).launch {
            var input: InputStream? = null
            var output: FileOutputStream? = null
            try {
                if (streamUrl.startsWith("http")) {
                    val conn = URL(streamUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 15000
                    conn.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                    conn.connect()

                    val contentLength = conn.contentLengthLong
                    if (contentLength > 0) {
                        item.totalBytes = contentLength
                    }

                    input = conn.inputStream
                    output = FileOutputStream(targetFile)

                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var lastTime = System.currentTimeMillis()
                    var lastBytes = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (item.isPaused) {
                            while (item.isPaused) { kotlinx.coroutines.delay(500) }
                        }
                        output.write(buffer, 0, bytesRead)
                        item.downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        val diff = now - lastTime
                        if (diff >= 400) {
                            val byteDiff = item.downloadedBytes - lastBytes
                            item.speedBytesPerSec = (byteDiff / (diff / 1000.0))
                            lastBytes = item.downloadedBytes
                            lastTime = now
                            withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }
                        }
                    }

                    output.flush()
                }

                // Register with Android Media Scanner
                MediaScannerConnection.scanFile(
                    KZBStubeApp.appContext,
                    arrayOf(targetFile.absolutePath),
                    arrayOf(if (ext == "mp3") "audio/mpeg" else "video/mp4"),
                    null
                )

                item.isCompleted = true
                item.speedBytesPerSec = 0.0
                activeDownloads.remove(item)
                completedDownloads.add(0, item)
                saveCompleted()
                withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }
            } catch (e: Exception) {
                e.printStackTrace()
                activeDownloads.remove(item)
                withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }
            } finally {
                try { input?.close() } catch (_: Exception) {}
                try { output?.close() } catch (_: Exception) {}
            }
        }

        downloadJobs[item.id] = job
    }

    fun pauseDownload(id: String) {
        activeDownloads.find { it.id == id }?.let {
            it.isPaused = true
            onProgressUpdate?.invoke()
        }
    }

    fun resumeDownload(id: String) {
        activeDownloads.find { it.id == id }?.let {
            it.isPaused = false
            onProgressUpdate?.invoke()
        }
    }

    fun cancelDownload(id: String) {
        downloadJobs[id]?.cancel()
        downloadJobs.remove(id)
        activeDownloads.removeAll { it.id == id }
        onProgressUpdate?.invoke()
    }
}
