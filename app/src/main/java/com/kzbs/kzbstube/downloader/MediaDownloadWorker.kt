package com.kzbs.kzbstube.downloader

import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kzbs.kzbstube.KZBStubeApp
import com.kzbs.kzbstube.R
import kotlinx.coroutines.CancellationException
import java.io.IOException

class MediaDownloadWorker(
    appContext: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_ID = "download_id"
        const val KEY_VIDEO_ID = "video_id"
        const val KEY_TITLE = "title"
        const val KEY_AUTHOR = "author"
        const val KEY_THUMBNAIL = "thumbnail"
        const val KEY_FORMAT = "format"
        const val KEY_QUALITY = "quality"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_STREAM_URL = "stream_url"
        const val KEY_TOTAL_BYTES = "total_bytes"
        private const val FOREGROUND_ID_SALT = 0x40000000
    }

    override suspend fun doWork(): Result {
        val item = DownloadManager.restoreWorkItem(inputData) ?: return Result.failure()
        val streamUrl = inputData.getString(KEY_STREAM_URL) ?: return Result.failure()

        return try {
            setForeground(createForegroundInfo(item))
            item.downloadedBytes = 0L
            item.speedBytesPerSec = 0.0
            var lastNotificationUpdate = 0L

            DownloadManager.performDownload(item, streamUrl) { current ->
                val now = System.currentTimeMillis()
                if (current.isConverting || now - lastNotificationUpdate >= 900) {
                    lastNotificationUpdate = now
                    setProgress(
                        Data.Builder()
                            .putInt("progress", current.progressPercent)
                            .putLong("downloaded_bytes", current.downloadedBytes)
                            .putLong("total_bytes", current.totalBytes)
                            .build()
                    )
                    setForeground(createForegroundInfo(current))
                }
            }
            Result.success()
        } catch (error: CancellationException) {
            DownloadManager.markDownloadCancelled(item)
            throw error
        } catch (error: Exception) {
            if (shouldRetry(error) && runAttemptCount < 2) {
                item.downloadedBytes = 0L
                item.speedBytesPerSec = 0.0
                Result.retry()
            } else {
                DownloadManager.markDownloadFailed(item, error)
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val item = DownloadManager.restoreWorkItem(inputData)
            ?: throw IllegalStateException("İndirme bilgisi bulunamadı")
        return createForegroundInfo(item)
    }

    private fun createForegroundInfo(item: com.kzbs.kzbstube.model.DownloadItem): ForegroundInfo {
        val manager = WorkManager.getInstance(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, KZBStubeApp.NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kzbs_logo)
            .setContentTitle(if (item.isConverting) "MP3 hazırlanıyor: ${item.title}" else "İndiriliyor: ${item.title}")
            .setContentText(if (item.isConverting) "Ses dosyası MP3 biçimine çevriliyor" else "${item.sizeFormatted} • ${item.speedFormatted}")
            .setProgress(100, item.progressPercent, item.totalBytes <= 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "İptal",
                manager.createCancelPendingIntent(id)
            )
            .build()

        val notificationId = DownloadManager.notificationId(item.id) xor FOREGROUND_ID_SALT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun shouldRetry(error: Throwable): Boolean = when (error) {
        is Mp3ConversionException -> false
        is DownloadHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
        is IOException -> true
        else -> false
    }
}
