package com.kzbs.kzbstube.model

data class VideoItem(
    val id: String,
    val title: String,
    val author: String,
    val duration: String,
    val viewCount: String,
    val uploadDate: String,
    val thumbnailUrl: String
)

data class StreamOption(
    val format: String, // MP4, MP3 download, or M4A preview source
    val quality: String, // 720p, 360p, Audio HQ
    val sizeBytes: Long,
    val sizeFormatted: String,
    val streamUrl: String
)

data class DownloadItem(
    val id: String,
    val videoId: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String,
    val format: String, // MP4, MP3, or legacy M4A
    val quality: String,
    var filePath: String,
    var totalBytes: Long,
    var downloadedBytes: Long = 0,
    var speedBytesPerSec: Double = 0.0,
    var isCompleted: Boolean = false,
    var isPaused: Boolean = false,
    var isConverting: Boolean = false
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

    val sizeFormatted: String
        get() {
            val downMB = String.format("%.1f", downloadedBytes / (1024.0 * 1024.0))
            val totMB = String.format("%.1f", totalBytes / (1024.0 * 1024.0))
            return "$downMB MB / $totMB MB"
        }

    val speedFormatted: String
        get() {
            val megabitsPerSecond = speedBytesPerSec * 8.0 / 1_000_000.0
            return if (speedBytesPerSec > 1024 * 1024) {
                String.format(
                    "%.1f MB/s • %.1f Mbit/s",
                    speedBytesPerSec / (1024.0 * 1024.0),
                    megabitsPerSecond
                )
            } else {
                String.format("%.0f KB/s • %.1f Mbit/s", speedBytesPerSec / 1024.0, megabitsPerSecond)
            }
        }
}

data class UpdateModel(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val changelog: String,
    val apkUrl: String,
    val isMandatory: Boolean = false
)
