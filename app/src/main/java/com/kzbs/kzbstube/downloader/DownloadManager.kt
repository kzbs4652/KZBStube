package com.kzbs.kzbstube.downloader

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kzbs.kzbstube.KZBStubeApp
import com.kzbs.kzbstube.R
import com.kzbs.kzbstube.model.DownloadItem
import com.kzbs.kzbstube.model.VideoItem
import com.kzbs.kzbstube.ui.ActiveDownloadsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

object DownloadManager {

    private const val PREFS_NAME = "kzbstube_prefs"
    private const val KEY_DOWNLOADS = "completed_downloads"
    private const val KEY_PAUSED_PREFIX = "paused_"
    const val KEY_CUSTOM_PATH = "custom_download_path"
    private const val WORK_TAG_PREFIX = "kzbstube_download_"
    private const val BUFFER_SIZE = 128 * 1024
    private const val MAX_PARALLEL_RANGES = 3
    private const val MIN_PARALLEL_BYTES = 3 * 1024 * 1024L
    private const val MIN_RANGE_BYTES = 1024 * 1024L
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36"

    val activeDownloads = CopyOnWriteArrayList<DownloadItem>()
    val completedDownloads = CopyOnWriteArrayList<DownloadItem>()

    var onProgressUpdate: (() -> Unit)? = null
    var onDownloadFailure: ((String, String) -> Unit)? = null

    private data class ByteRange(val start: Long, val end: Long)

    private class ProgressTracker {
        var lastSampleTime = System.currentTimeMillis()
        var lastSampleBytes = 0L
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

    fun getDownloadDirectoryLabel(): String {
        val prefs = KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customPath = prefs.getString(KEY_CUSTOM_PATH, null)
        if (customPath != null && customPath.startsWith("content://")) {
            val name = DocumentFile.fromTreeUri(KZBStubeApp.appContext, Uri.parse(customPath))?.name
            if (!name.isNullOrBlank()) return "Seçilen klasör: $name"
        }
        if (customPath != null) {
            val customDir = File(customPath)
            if (customDir.exists() && customDir.isDirectory) return customDir.absolutePath
        }
        return "İndirilenler/KZBStube"
    }

    fun setCustomDownloadDirectory(path: String) {
        val prefs = KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_PATH, path).apply()
    }

    fun clearCustomDownloadDirectory() {
        KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_CUSTOM_PATH).apply()
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
        val ext = extensionFor(format)
        val context = KZBStubeApp.appContext
        val downloadFolder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, ".kzbstube-work")
        if (!downloadFolder.exists()) downloadFolder.mkdirs()

        val itemId = "${video.id}_${System.currentTimeMillis()}"
        val targetFile = File(downloadFolder, "$itemId.$ext")

        val item = DownloadItem(
            id = itemId,
            videoId = video.id,
            title = video.title,
            author = video.author,
            thumbnailUrl = video.thumbnailUrl,
            format = format,
            quality = quality,
            filePath = targetFile.absolutePath,
            totalBytes = totalSizeBytes.coerceAtLeast(0L)
        )

        activeDownloads.add(0, item)
        onProgressUpdate?.invoke()

        val data = Data.Builder()
            .putString(MediaDownloadWorker.KEY_ID, item.id)
            .putString(MediaDownloadWorker.KEY_VIDEO_ID, item.videoId)
            .putString(MediaDownloadWorker.KEY_TITLE, item.title)
            .putString(MediaDownloadWorker.KEY_AUTHOR, item.author)
            .putString(MediaDownloadWorker.KEY_THUMBNAIL, item.thumbnailUrl)
            .putString(MediaDownloadWorker.KEY_FORMAT, item.format)
            .putString(MediaDownloadWorker.KEY_QUALITY, item.quality)
            .putString(MediaDownloadWorker.KEY_FILE_PATH, item.filePath)
            .putString(MediaDownloadWorker.KEY_STREAM_URL, streamUrl)
            .putLong(MediaDownloadWorker.KEY_TOTAL_BYTES, item.totalBytes)
            .build()
        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(WORK_TAG_PREFIX + item.id)
            .build()

        WorkManager.getInstance(KZBStubeApp.appContext)
            .enqueueUniqueWork(item.id, ExistingWorkPolicy.KEEP, request)
    }

    fun restoreWorkItem(data: Data): DownloadItem? {
        val id = data.getString(MediaDownloadWorker.KEY_ID) ?: return null
        activeDownloads.firstOrNull { it.id == id }?.let { return it }
        val prefs = KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val item = DownloadItem(
            id = id,
            videoId = data.getString(MediaDownloadWorker.KEY_VIDEO_ID) ?: return null,
            title = data.getString(MediaDownloadWorker.KEY_TITLE) ?: "Video",
            author = data.getString(MediaDownloadWorker.KEY_AUTHOR) ?: "YouTube",
            thumbnailUrl = data.getString(MediaDownloadWorker.KEY_THUMBNAIL) ?: "",
            format = data.getString(MediaDownloadWorker.KEY_FORMAT) ?: "MP4",
            quality = data.getString(MediaDownloadWorker.KEY_QUALITY) ?: "",
            filePath = data.getString(MediaDownloadWorker.KEY_FILE_PATH) ?: return null,
            totalBytes = data.getLong(MediaDownloadWorker.KEY_TOTAL_BYTES, 0L),
            isPaused = prefs.getBoolean(KEY_PAUSED_PREFIX + id, false)
        )
        activeDownloads.add(0, item)
        return item
    }

    suspend fun performDownload(
        item: DownloadItem,
        streamUrl: String,
        onWorkerProgress: suspend (DownloadItem) -> Unit
    ) {
        val targetFile = File(item.filePath)
        val tempFile = File(targetFile.parentFile ?: KZBStubeApp.appContext.cacheDir, ".${item.id.hashCode()}.${extensionFor(item.format)}.part")
        val notifId = notificationId(item.id)
        val context = KZBStubeApp.appContext
        val notifManager = NotificationManagerCompat.from(context)
        val tracker = ProgressTracker()

        suspend fun reportProgress(bytes: Long) {
            var updateUi = false
            synchronized(tracker) {
                item.downloadedBytes = maxOf(item.downloadedBytes, bytes)
                val now = System.currentTimeMillis()
                val elapsed = now - tracker.lastSampleTime
                if (elapsed >= 300) {
                    val diff = item.downloadedBytes - tracker.lastSampleBytes
                    item.speedBytesPerSec = diff / (elapsed / 1000.0)
                    tracker.lastSampleBytes = item.downloadedBytes
                    tracker.lastSampleTime = now
                    updateUi = true
                }
            }
            if (updateUi) {
                withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }
                onWorkerProgress(item)
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
            targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            tempFile.delete()
            val downloadedBytes = try {
                downloadMedia(item, streamUrl, item.totalBytes, tempFile, ::resetProgress, ::reportProgress)
            } catch (error: DownloadHttpException) {
                if (error.statusCode !in setOf(403, 410)) throw error
                YouTubeHelper.clearStreamCache(item.videoId)
                val refreshed = YouTubeHelper.resolveStreams(
                    item.videoId,
                    requiredFormat = item.format,
                    forceRefresh = true
                ).options
                val replacement = refreshed.firstOrNull { it.format == item.format && it.quality == item.quality }
                    ?: refreshed.firstOrNull { it.format == item.format }
                    ?: throw error
                tempFile.delete()
                resetProgress()
                item.totalBytes = replacement.sizeBytes.coerceAtLeast(0L)
                downloadMedia(item, replacement.streamUrl, replacement.sizeBytes, tempFile, ::resetProgress, ::reportProgress)
            }

            if (downloadedBytes <= 0L || (item.totalBytes > 0 && downloadedBytes != item.totalBytes)) {
                throw IOException("İndirme tamamlanmadan bağlantı kapandı")
            }
            if (item.totalBytes <= 0L) item.totalBytes = downloadedBytes
            if (tempFile.length() != downloadedBytes) throw IOException("İndirilen dosyanın boyutu doğrulanamadı")

            val mime = mimeTypeFor(item.format)
            val cleanTitle = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { item.videoId }
            item.filePath = publishDownloadedFile(tempFile, "$cleanTitle.${extensionFor(item.format)}", mime)
            tempFile.delete()

            item.isCompleted = true
            item.downloadedBytes = downloadedBytes
            item.speedBytesPerSec = 0.0
            activeDownloads.remove(item)
            completedDownloads.add(0, item)
            clearPaused(item.id)
            saveCompleted()
            withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }

            if (KZBStubeApp.areNotificationsEnabled()) {
                try {
                    val storedUri = Uri.parse(item.filePath)
                    val fileUri = if (storedUri.scheme == "content") storedUri else {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(item.filePath))
                    }
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val viewPending = PendingIntent.getActivity(
                        context, notifId + 1, viewIntent,
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
        } catch (error: CancellationException) {
            tempFile.delete()
            activeDownloads.remove(item)
            clearPaused(item.id)
            try { notifManager.cancel(notifId) } catch (_: Exception) {}
            withContext(Dispatchers.Main) { onProgressUpdate?.invoke() }
            throw error
        } catch (error: Exception) {
            tempFile.delete()
            throw error
        }
    }

    suspend fun markDownloadFailed(item: DownloadItem, error: Throwable) {
        activeDownloads.remove(item)
        clearPaused(item.id)
        val message = when (error) {
            is DownloadHttpException -> "İndirme sunucusu HTTP ${error.statusCode} yanıtı verdi."
            is IOException -> error.message?.takeIf { it.isNotBlank() } ?: "Bağlantı kesildi. Tekrar deneyin."
            else -> "İndirme tamamlanamadı. Lütfen tekrar deneyin."
        }
        val context = KZBStubeApp.appContext
        if (KZBStubeApp.areNotificationsEnabled()) {
            try {
                val notification = NotificationCompat.Builder(context, KZBStubeApp.NOTIF_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_kzbs_logo)
                    .setContentTitle("İndirme başarısız")
                    .setContentText("${item.title}: $message")
                    .setAutoCancel(true)
                    .build()
                NotificationManagerCompat.from(context).notify(notificationId(item.id), notification)
            } catch (_: Exception) {}
        }
        withContext(Dispatchers.Main) {
            onProgressUpdate?.invoke()
            onDownloadFailure?.invoke(item.title, message)
        }
    }

    fun markDownloadCancelled(item: DownloadItem) {
        activeDownloads.remove(item)
        clearPaused(item.id)
        onProgressUpdate?.invoke()
    }

    fun notificationId(id: String): Int = (id.hashCode() and 0x7fffffff).coerceAtLeast(1)

    private fun extensionFor(format: String): String = when (format.uppercase()) {
        "M4A" -> "m4a"
        "MP3" -> "mp3"
        else -> "mp4"
    }

    private fun mimeTypeFor(format: String): String = when (format.uppercase()) {
        "M4A" -> "audio/mp4"
        "MP3" -> "audio/mpeg"
        else -> "video/mp4"
    }

    private fun publishDownloadedFile(source: File, requestedName: String, mime: String): String {
        val context = KZBStubeApp.appContext
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customLocation = prefs.getString(KEY_CUSTOM_PATH, null)

        if (customLocation?.startsWith("content://") == true) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(customLocation))
                ?: throw IOException("Seçilen klasöre erişilemiyor. Ayarlardan klasörü yeniden seçin.")
            val name = uniqueDocumentName(tree, requestedName)
            val document = tree.createFile(mime, name)
                ?: throw IOException("Dosya seçilen klasörde oluşturulamadı.")
            try {
                val output = resolver.openOutputStream(document.uri, "w")
                    ?: throw IOException("Seçilen klasöre yazılamadı.")
                source.inputStream().use { input -> output.use { input.copyTo(it, BUFFER_SIZE) } }
                return document.uri.toString()
            } catch (error: Exception) {
                document.delete()
                throw error
            }
        }

        if (!customLocation.isNullOrBlank()) {
            val customDirectory = File(customLocation)
            if (customDirectory.isDirectory) {
                val destination = uniqueFile(customDirectory, requestedName)
                source.copyTo(destination, overwrite = false)
                MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf(mime), null)
                return destination.absolutePath
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/KZBStube/"
            val name = uniqueMediaStoreName(requestedName, relativePath)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Dosya İndirilenler klasöründe oluşturulamadı.")
            try {
                val output = resolver.openOutputStream(uri, "w")
                    ?: throw IOException("İndirilenler klasörüne yazılamadı.")
                source.inputStream().use { input -> output.use { input.copyTo(it, BUFFER_SIZE) } }
                val completed = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, completed, null, null)
                return uri.toString()
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        val legacyDirectory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            "KZBStube"
        )
        if (!legacyDirectory.exists() && !legacyDirectory.mkdirs()) {
            throw IOException("İndirme klasörü oluşturulamadı.")
        }
        val destination = uniqueFile(legacyDirectory, requestedName)
        source.copyTo(destination, overwrite = false)
        MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf(mime), null)
        return destination.absolutePath
    }

    private fun uniqueDocumentName(tree: DocumentFile, requestedName: String): String {
        val extensionIndex = requestedName.lastIndexOf('.')
        val base = if (extensionIndex > 0) requestedName.substring(0, extensionIndex) else requestedName
        val extension = if (extensionIndex > 0) requestedName.substring(extensionIndex) else ""
        var counter = 1
        var candidate = requestedName
        while (tree.findFile(candidate) != null) {
            candidate = "$base ($counter)$extension"
            counter++
        }
        return candidate
    }

    private fun uniqueFile(directory: File, requestedName: String): File {
        val extensionIndex = requestedName.lastIndexOf('.')
        val base = if (extensionIndex > 0) requestedName.substring(0, extensionIndex) else requestedName
        val extension = if (extensionIndex > 0) requestedName.substring(extensionIndex) else ""
        var candidate = File(directory, requestedName)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($counter)$extension")
            counter++
        }
        return candidate
    }

    private fun uniqueMediaStoreName(requestedName: String, relativePath: String): String {
        val extensionIndex = requestedName.lastIndexOf('.')
        val base = if (extensionIndex > 0) requestedName.substring(0, extensionIndex) else requestedName
        val extension = if (extensionIndex > 0) requestedName.substring(extensionIndex) else ""
        val resolver = KZBStubeApp.appContext.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        var candidate = requestedName
        var counter = 1
        while (true) {
            val exists = resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(candidate, relativePath),
                null
            )?.use { it.moveToFirst() } ?: false
            if (!exists) return candidate
            candidate = "$base ($counter)$extension"
            counter++
        }
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
                throw DownloadHttpException(responseCode)
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
            KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PAUSED_PREFIX + id, true).apply()
            onProgressUpdate?.invoke()
        }
    }

    fun resumeDownload(id: String) {
        activeDownloads.find { it.id == id }?.let {
            it.isPaused = false
            clearPaused(id)
            onProgressUpdate?.invoke()
        }
    }

    fun cancelDownload(id: String) {
        WorkManager.getInstance(KZBStubeApp.appContext).cancelAllWorkByTag(WORK_TAG_PREFIX + id)
        activeDownloads.removeAll { it.id == id }
        clearPaused(id)
        onProgressUpdate?.invoke()
    }

    private fun clearPaused(id: String) {
        KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_PAUSED_PREFIX + id).apply()
    }
}

class DownloadHttpException(val statusCode: Int) : IOException("HTTP $statusCode")
