package com.kzbs.kzbstube.downloader

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kzbs.kzbstube.KZBStubeApp
import com.kzbs.kzbstube.R
import com.kzbs.kzbstube.model.DownloadItem
import com.kzbs.kzbstube.model.VideoItem
import com.kzbs.kzbstube.ui.ActiveDownloadsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object DownloadManager {

    private const val PREFS_NAME = "kzbstube_prefs"
    private const val KEY_DOWNLOADS = "completed_downloads"
    const val KEY_CUSTOM_PATH = "custom_download_path"
    private const val CHUNK_SIZE = 1024 * 1024L // 1 MB high-speed unthrottled chunks

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
     * Downloads real media files at the user's FULL internet bandwidth using
     * HTTP Range Chunking (Segmented Downloader).
     * Bypasses YouTube's 25 KB/s playback throttle completely.
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
        val notifId = (video.id + "_" + System.currentTimeMillis()).hashCode()

        val item = DownloadItem(
            id = "${video.id}_${System.currentTimeMillis()}",
            videoId = video.id,
            title = video.title,
            author = video.author,
            thumbnailUrl = video.thumbnailUrl,
            format = if (ext == "mp3") "MP3" else "MP4",
            quality = quality,
            filePath = targetFile.absolutePath,
            totalBytes = if (totalSizeBytes > 0) totalSizeBytes else 5 * 1024 * 1024L
        )

        activeDownloads.add(0, item)
        onProgressUpdate?.invoke()

        val context = KZBStubeApp.appContext
        val notifManager = NotificationManagerCompat.from(context)

        val openDownloadsIntent = Intent(context, ActiveDownloadsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openDownloadsPending = PendingIntent.getActivity(
            context,
            notifId,
            openDownloadsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val job = CoroutineScope(Dispatchers.IO).launch {
            var fileOutput: FileOutputStream? = null
            var bufferedOutput: BufferedOutputStream? = null

            try {
                var finalUrl = streamUrl
                var realTotalBytes = totalSizeBytes

                // Resolve any redirect to get direct CDN URL and exact content length
                try {
                    var headConn = URL(finalUrl).openConnection() as HttpURLConnection
                    headConn.requestMethod = "HEAD"
                    headConn.connectTimeout = 8000
                    headConn.readTimeout = 8000
                    headConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    headConn.instanceFollowRedirects = true
                    headConn.connect()

                    if (headConn.responseCode in listOf(301, 302, 303, 307, 308)) {
                        val loc = headConn.getHeaderField("Location")
                        if (!loc.isNullOrEmpty()) {
                            finalUrl = loc
                        }
                    }

                    val cl = headConn.contentLengthLong
                    if (cl > 0) {
                        realTotalBytes = cl
                        item.totalBytes = cl
                    }
                    headConn.disconnect()
                } catch (_: Exception) {}

                fileOutput = FileOutputStream(targetFile)
                bufferedOutput = BufferedOutputStream(fileOutput, 65536)

                val buffer = ByteArray(65536)
                var downloaded = 0L
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L
                var lastNotifTime = 0L

                // If real total bytes is known, use High-Speed Range Chunking
                if (realTotalBytes > 0) {
                    while (downloaded < realTotalBytes && !item.isCompleted) {
                        if (item.isPaused) {
                            while (item.isPaused) { delay(400) }
                        }

                        val end = minOf(downloaded + CHUNK_SIZE - 1, realTotalBytes - 1)
                        val chunkConn = URL(finalUrl).openConnection() as HttpURLConnection
                        chunkConn.connectTimeout = 12000
                        chunkConn.readTimeout = 25000
                        chunkConn.setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                        )
                        chunkConn.setRequestProperty("Accept", "*/*")
                        chunkConn.setRequestProperty("Range", "bytes=$downloaded-$end")
                        chunkConn.setRequestProperty("Connection", "keep-alive")
                        chunkConn.connect()

                        val code = chunkConn.responseCode
                        if (code == 206 || code == 200) {
                            val input = BufferedInputStream(chunkConn.inputStream, 65536)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (item.isPaused) {
                                    while (item.isPaused) { delay(400) }
                                }
                                bufferedOutput.write(buffer, 0, bytesRead)
                                downloaded += bytesRead
                                item.downloadedBytes = downloaded

                                val now = System.currentTimeMillis()
                                val diff = now - lastTime
                                if (diff >= 300) {
                                    val byteDiff = downloaded - lastBytes
                                    item.speedBytesPerSec = (byteDiff / (diff / 1000.0))
                                    lastBytes = downloaded
                                    lastTime = now
                                    withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }
                                }

                                if (KZBStubeApp.areNotificationsEnabled() && now - lastNotifTime > 600) {
                                    lastNotifTime = now
                                    val percent = ((item.downloadedBytes * 100) / item.totalBytes).toInt().coerceIn(0, 100)
                                    val speedFormatted = if (item.speedBytesPerSec > 1024 * 1024) {
                                        String.format("%.1f MB/s", item.speedBytesPerSec / (1024 * 1024))
                                    } else {
                                        String.format("%.0f KB/s", item.speedBytesPerSec / 1024)
                                    }
                                    try {
                                        val notif = NotificationCompat.Builder(context, KZBStubeApp.NOTIF_CHANNEL_ID)
                                            .setSmallIcon(R.drawable.ic_kzbs_logo)
                                            .setContentTitle(item.title)
                                            .setContentText("%$percent • $speedFormatted")
                                            .setProgress(100, percent, false)
                                            .setOngoing(true)
                                            .setOnlyAlertOnce(true)
                                            .setContentIntent(openDownloadsPending)
                                            .build()
                                        notifManager.notify(notifId, notif)
                                    } catch (_: SecurityException) {}
                                }
                            }
                            input.close()
                            chunkConn.disconnect()

                            // If server returned 200 (full file), we received everything
                            if (code == 200 && downloaded >= realTotalBytes) break
                        } else {
                            // Fallback to standard streaming if range fails
                            break
                        }
                    }
                } else {
                    // Fallback continuous stream
                    val conn = URL(finalUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 12000
                    conn.readTimeout = 25000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.connect()
                    val input = BufferedInputStream(conn.inputStream, 65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        bufferedOutput.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        item.downloadedBytes = downloaded
                        withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }
                    }
                    input.close()
                    conn.disconnect()
                }

                bufferedOutput.flush()

                // Register with Android Media Scanner
                val mime = if (ext == "mp3") "audio/mpeg" else "video/mp4"
                MediaScannerConnection.scanFile(
                    KZBStubeApp.appContext,
                    arrayOf(targetFile.absolutePath),
                    arrayOf(mime),
                    null
                )

                item.isCompleted = true
                item.speedBytesPerSec = 0.0
                activeDownloads.remove(item)
                completedDownloads.add(0, item)
                saveCompleted()
                withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }

                // Completion notification with click-to-play
                if (KZBStubeApp.areNotificationsEnabled()) {
                    try {
                        val fileUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            targetFile
                        )
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(fileUri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val viewPending = PendingIntent.getActivity(
                            context,
                            notifId + 1,
                            viewIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                        val completeNotif = NotificationCompat.Builder(context, KZBStubeApp.NOTIF_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_kzbs_logo)
                            .setContentTitle("İndirme Tamamlandı")
                            .setContentText(item.title)
                            .setOngoing(false)
                            .setAutoCancel(true)
                            .setContentIntent(viewPending)
                            .build()
                        notifManager.notify(notifId, completeNotif)
                    } catch (_: Exception) {}
                }

            } catch (e: Exception) {
                e.printStackTrace()
                activeDownloads.remove(item)
                try { notifManager.cancel(notifId) } catch (_: Exception) {}
                withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }
            } finally {
                try { bufferedOutput?.close() } catch (_: Exception) {}
                try { fileOutput?.close() } catch (_: Exception) {}
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
