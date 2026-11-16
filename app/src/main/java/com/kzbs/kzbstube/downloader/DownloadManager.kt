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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

object DownloadManager {

    private const val PREFS_NAME = "kzbstube_prefs"
    private const val KEY_DOWNLOADS = "completed_downloads"
    const val KEY_CUSTOM_PATH = "custom_download_path"
    private const val BUFFER_SIZE = 128 * 1024
    private const val MAX_PARALLEL_RANGES = 3
    private const val MIN_PARALLEL_BYTES = 3 * 1024 * 1024L
    private const val MIN_RANGE_BYTES = 1024 * 1024L
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36"

    val activeDownloads = mutableListOf<DownloadItem>()
    val completedDownloads = mutableListOf<DownloadItem>()
    private val downloadJobs = mutableMapOf<String, Job>()

    var onProgressUpdate: (() -> Unit)? = null

    private data class ByteRange(val start: Long, val end: Long)

    private class ProgressTracker {
        var lastSampleTime = System.currentTimeMillis()
        var lastSampleBytes = 0L
        var lastNotificationTime = 0L
    }

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
        val itemId = "${video.id}_${System.currentTimeMillis()}"
        val tempFile = File(downloadFolder, ".$cleanTitle-${itemId.hashCode()}.$ext.part")
        val notifId = (video.id + "_" + System.currentTimeMillis()).hashCode()

        val item = DownloadItem(
            id = itemId,
            videoId = video.id,
            title = video.title,
            author = video.author,
            thumbnailUrl = video.thumbnailUrl,
            format = if (ext == "mp3") "MP3" else "MP4",
            quality = quality,
            filePath = targetFile.absolutePath,
            totalBytes = totalSizeBytes.coerceAtLeast(0L)
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
            val tracker = ProgressTracker()

            suspend fun reportProgress(bytes: Long) {
                val now = System.currentTimeMillis()
                var updateUi = false
                var updateNotification = false
                var percent = 0
                var speedText = ""

                synchronized(tracker) {
                    item.downloadedBytes = maxOf(item.downloadedBytes, bytes)
                    val elapsed = now - tracker.lastSampleTime
                    if (elapsed >= 300) {
                        val diff = item.downloadedBytes - tracker.lastSampleBytes
                        item.speedBytesPerSec = diff / (elapsed / 1000.0)
                        tracker.lastSampleBytes = item.downloadedBytes
                        tracker.lastSampleTime = now
                        updateUi = true
                    }
                    if (KZBStubeApp.areNotificationsEnabled() && now - tracker.lastNotificationTime > 700) {
                        tracker.lastNotificationTime = now
                        percent = if (item.totalBytes > 0) {
                            ((item.downloadedBytes * 100) / item.totalBytes).toInt().coerceIn(0, 100)
                        } else 0
                        speedText = item.speedFormatted
                        updateNotification = true
                    }
                }

                if (updateUi) withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }
                if (updateNotification) {
                    try {
                        val notification = NotificationCompat.Builder(context, KZBStubeApp.NOTIF_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_kzbs_logo)
                            .setContentTitle(item.title)
                            .setContentText("%$percent • $speedText")
                            .setProgress(100, percent, false)
                            .setOngoing(true)
                            .setOnlyAlertOnce(true)
                            .setContentIntent(openDownloadsPending)
                            .build()
                        notifManager.notify(notifId, notification)
                    } catch (_: SecurityException) {}
                }
            }

            fun resetProgress() {
                synchronized(tracker) {
                    item.downloadedBytes = 0L
                    item.speedBytesPerSec = 0.0
                    tracker.lastSampleBytes = 0L
                    tracker.lastSampleTime = System.currentTimeMillis()
                }
            }

            try {
                val downloadedBytes = downloadMedia(
                    item = item,
                    sourceUrl = streamUrl,
                    providedTotalBytes = totalSizeBytes,
                    tempFile = tempFile,
                    resetProgress = ::resetProgress,
                    reportProgress = ::reportProgress
                )

                if (downloadedBytes <= 0L || (item.totalBytes > 0 && downloadedBytes != item.totalBytes)) {
                    throw IOException("İndirme tamamlanmadan bağlantı kapandı")
                }
                if (item.totalBytes <= 0L) item.totalBytes = downloadedBytes
                if (tempFile.length() != downloadedBytes) {
                    throw IOException("İndirilen dosyanın boyutu doğrulanamadı")
                }

                if (targetFile.exists() && !targetFile.delete()) {
                    throw IOException("Eski dosya değiştirilemedi")
                }
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }

                val mime = if (ext == "mp3") "audio/mpeg" else "video/mp4"
                MediaScannerConnection.scanFile(
                    KZBStubeApp.appContext,
                    arrayOf(targetFile.absolutePath),
                    arrayOf(mime),
                    null
                )

                item.isCompleted = true
                item.downloadedBytes = downloadedBytes
                item.speedBytesPerSec = 0.0
                activeDownloads.remove(item)
                completedDownloads.add(0, item)
                saveCompleted()
                withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }

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
                        val completeNotification = NotificationCompat.Builder(context, KZBStubeApp.NOTIF_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_kzbs_logo)
                            .setContentTitle("İndirme Tamamlandı")
                            .setContentText(item.title)
                            .setOngoing(false)
                            .setAutoCancel(true)
                            .setContentIntent(viewPending)
                            .build()
                        notifManager.notify(notifId, completeNotification)
                    } catch (_: Exception) {}
                }
            } catch (e: CancellationException) {
                tempFile.delete()
                activeDownloads.remove(item)
                try { notifManager.cancel(notifId) } catch (_: Exception) {}
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                tempFile.delete()
                activeDownloads.remove(item)
                try { notifManager.cancel(notifId) } catch (_: Exception) {}
                withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }
            } finally {
                downloadJobs.remove(item.id)
            }
        }

        downloadJobs[item.id] = job
    }

    private suspend fun downloadMedia(
        item: DownloadItem,
        sourceUrl: String,
        providedTotalBytes: Long,
        tempFile: File,
        resetProgress: () -> Unit,
        reportProgress: suspend (Long) -> Unit
    ): Long {
        var probe: HttpURLConnection? = null
        try {
            probe = createConnection(sourceUrl, "bytes=0-0")
            val responseCode = probe.responseCode

            if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                val finalUrl = probe.url.toString()
                val rangeTotal = contentRangeTotal(probe.getHeaderField("Content-Range"))
                val totalBytes = rangeTotal.takeIf { it > 0L } ?: providedTotalBytes
                if (totalBytes > 0L) item.totalBytes = totalBytes
                try { probe.inputStream.close() } catch (_: Exception) {}
                probe.disconnect()
                probe = null

                if (totalBytes >= MIN_PARALLEL_BYTES) {
                    try {
                        return downloadParallelRanges(finalUrl, totalBytes, tempFile, item, reportProgress)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Some CDNs accept the initial range probe but reject concurrent ranges.
                        tempFile.delete()
                        resetProgress()
                        return downloadSequential(sourceUrl, totalBytes, tempFile, item, reportProgress)
                    }
                }
                return downloadSequential(sourceUrl, totalBytes, tempFile, item, reportProgress)
            }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseTotal = probe.contentLengthLong.takeIf { it > 0L } ?: providedTotalBytes
                if (responseTotal > 0L) item.totalBytes = responseTotal
                val input = BufferedInputStream(probe.inputStream, BUFFER_SIZE)
                return copyToFile(input, tempFile, item, reportProgress)
            }

            probe.disconnect()
            probe = null
            return downloadSequential(sourceUrl, providedTotalBytes, tempFile, item, reportProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            tempFile.delete()
            resetProgress()
            return downloadSequential(sourceUrl, providedTotalBytes, tempFile, item, reportProgress)
        } finally {
            probe?.disconnect()
        }
    }

    private suspend fun downloadParallelRanges(
        url: String,
        totalBytes: Long,
        tempFile: File,
        item: DownloadItem,
        reportProgress: suspend (Long) -> Unit
    ): Long {
        val workerCount = minOf(
            MAX_PARALLEL_RANGES,
            ((totalBytes + MIN_RANGE_BYTES - 1) / MIN_RANGE_BYTES).toInt().coerceAtLeast(1)
        )
        val segmentSize = (totalBytes + workerCount - 1) / workerCount
        val ranges = mutableListOf<ByteRange>()
        var start = 0L
        while (start < totalBytes) {
            val end = minOf(start + segmentSize - 1, totalBytes - 1)
            ranges.add(ByteRange(start, end))
            start = end + 1
        }

        RandomAccessFile(tempFile, "rw").use {
            it.setLength(0L)
            it.setLength(totalBytes)
        }
        val downloaded = AtomicLong(0L)

        coroutineScope {
            ranges.map { range ->
                async(Dispatchers.IO) {
                    val connection = createConnection(url, "bytes=${range.start}-${range.end}")
                    try {
                        if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                            throw IOException("Sunucu paralel aralık isteğini kabul etmedi")
                        }
                        validateContentRange(connection.getHeaderField("Content-Range"), range, totalBytes)

                        val input = BufferedInputStream(connection.inputStream, BUFFER_SIZE)
                        val randomFile = RandomAccessFile(tempFile, "rw")
                        try {
                            val buffer = ByteArray(BUFFER_SIZE)
                            var position = range.start
                            var remaining = range.end - range.start + 1
                            while (remaining > 0L) {
                                currentCoroutineContext().ensureActive()
                                while (item.isPaused) {
                                    delay(250)
                                    currentCoroutineContext().ensureActive()
                                }
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) throw IOException("Aralık eksik indirildi")
                                randomFile.seek(position)
                                randomFile.write(buffer, 0, count)
                                position += count
                                remaining -= count
                                reportProgress(downloaded.addAndGet(count.toLong()))
                            }
                        } finally {
                            input.close()
                            randomFile.close()
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
            }.awaitAll()
        }

        val bytes = downloaded.get()
        if (bytes != totalBytes) throw IOException("Paralel indirme eksik kaldı")
        return bytes
    }

    private suspend fun downloadSequential(
        url: String,
        providedTotalBytes: Long,
        tempFile: File,
        item: DownloadItem,
        reportProgress: suspend (Long) -> Unit
    ): Long {
        val connection = createConnection(url)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("İndirme sunucusu HTTP $responseCode yanıtı verdi")
            }
            val totalBytes = contentRangeTotal(connection.getHeaderField("Content-Range"))
                .takeIf { it > 0L }
                ?: connection.contentLengthLong.takeIf { it > 0L }
                ?: providedTotalBytes
            if (totalBytes > 0L) item.totalBytes = totalBytes
            val input = BufferedInputStream(connection.inputStream, BUFFER_SIZE)
            return copyToFile(input, tempFile, item, reportProgress)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun copyToFile(
        input: BufferedInputStream,
        tempFile: File,
        item: DownloadItem,
        reportProgress: suspend (Long) -> Unit
    ): Long {
        var downloaded = 0L
        val output = BufferedOutputStream(FileOutputStream(tempFile, false), BUFFER_SIZE)
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                while (item.isPaused) {
                    delay(250)
                    currentCoroutineContext().ensureActive()
                }
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                downloaded += count
                reportProgress(downloaded)
            }
            output.flush()
            return downloaded
        } finally {
            try { input.close() } catch (_: Exception) {}
            output.close()
        }
    }

    private fun createConnection(url: String, rangeHeader: String? = null): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 25000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "*/*")
        if (rangeHeader != null) connection.setRequestProperty("Range", rangeHeader)
        return connection
    }

    private fun contentRangeTotal(header: String?): Long {
        if (header.isNullOrBlank()) return 0L
        return header.substringAfterLast('/', "").toLongOrNull() ?: 0L
    }

    private fun validateContentRange(header: String?, expected: ByteRange, totalBytes: Long) {
        if (header.isNullOrBlank()) throw IOException("Sunucu Content-Range başlığı döndürmedi")
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE).find(header)
            ?: throw IOException("Sunucu geçersiz Content-Range başlığı döndürdü")
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLong()
        val total = match.groupValues[3].toLong()
        if (start != expected.start || end != expected.end || total != totalBytes) {
            throw IOException("Sunucu farklı bir veri aralığı döndürdü")
        }
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
