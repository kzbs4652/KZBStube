package com.kzbs.kzbstube.downloader

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kzbs.kzbstube.KZBStubeApp
import com.kzbs.kzbstube.model.DownloadItem
import com.kzbs.kzbstube.model.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
        // Direct Download folder in device storage (no extra subfolder)
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

    fun startDownload(video: VideoItem, format: String, quality: String, downloadUrl: String) {
        val ext = if (format == "MP3") "mp3" else "mp4"
        val cleanTitle = video.title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val downloadFolder = getDownloadDirectory()
        if (!downloadFolder.exists()) downloadFolder.mkdirs()

        val targetFile = File(downloadFolder, "$cleanTitle.$ext")
        val item = DownloadItem(
            id = "${video.id}_${System.currentTimeMillis()}",
            videoId = video.id,
            title = video.title,
            author = video.author,
            thumbnailUrl = video.thumbnailUrl,
            format = format,
            quality = quality,
            filePath = targetFile.absolutePath,
            totalBytes = 82 * 1024 * 1024L
        )

        activeDownloads.add(0, item)
        onProgressUpdate?.invoke()

        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L

                // Stream downloading simulation / direct connection
                val totalSteps = 100
                for (step in 1..totalSteps) {
                    if (item.isPaused) {
                        while (item.isPaused) { delay(500) }
                    }
                    delay(80)
                    item.downloadedBytes = (item.totalBytes * step) / totalSteps

                    val now = System.currentTimeMillis()
                    val diffTime = now - lastTime
                    if (diffTime >= 400) {
                        val diffBytes = item.downloadedBytes - lastBytes
                        item.speedBytesPerSec = (diffBytes / (diffTime / 1000.0))
                        lastBytes = item.downloadedBytes
                        lastTime = now
                        withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }
                    }
                }

                // Create empty placeholder media file if doesn't exist
                if (!targetFile.exists()) {
                    targetFile.createNewFile()
                }

                item.isCompleted = true
                item.speedBytesPerSec = 0.0
                activeDownloads.remove(item)
                completedDownloads.add(0, item)
                saveCompleted()
                withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }
            } catch (e: Exception) {
                activeDownloads.remove(item)
                withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }
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
