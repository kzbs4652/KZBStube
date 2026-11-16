package com.kzbs.kzbstube.downloader

import com.kzbs.kzbstube.model.StreamOption
import com.kzbs.kzbstube.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object YouTubeHelper {

    private const val INSTANCE_DIRECTORY_URL =
        "https://api.invidious.io/instances.json?pretty=1&sort_by=type,users"
    private const val FALLBACK_INSTANCE = "https://yt.chocolatemoo53.com"
    private const val INSTANCE_CACHE_MS = 6 * 60 * 60 * 1000L

    @Volatile private var cachedInstances: List<String> = emptyList()
    @Volatile private var instanceCacheTime = 0L

    fun extractVideoId(input: String): String? {
        val clean = input.trim()
        val regex = Regex("(?:https?://)?(?:www\\.)?(?:youtube\\.com/(?:watch\\?v=|shorts/|embed/)|youtu\\.be/)([a-zA-Z0-9_-]{11})")
        return regex.find(clean)?.groupValues?.get(1)
    }

    suspend fun searchVideos(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<VideoItem>()
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext list

        // Direct YouTube link support
        val directVideoId = extractVideoId(cleanQuery)
        if (directVideoId != null) {
            val directItem = fetchSingleVideoInfo(directVideoId)
            if (directItem != null) {
                list.add(directItem)
                return@withContext list
            }
        }

        // Live YouTube InnerTube search (Fast & 100% real)
        try {
            val url = URL("https://www.youtube.com/youtubei/v1/search")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            )
            conn.doOutput = true
            conn.connectTimeout = 7000
            conn.readTimeout = 7000

            val jsonBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20231201.00.00")
                        put("hl", "tr")
                        put("gl", "TR")
                    })
                })
                put("query", cleanQuery)
            }

            conn.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(response)
                val primaryContents = root.optJSONObject("contents")
                    ?.optJSONObject("twoColumnSearchResultsRenderer")
                    ?.optJSONObject("primaryContents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")

                if (primaryContents != null) {
                    for (i in 0 until primaryContents.length()) {
                        val section = primaryContents.optJSONObject(i)
                        val itemContents = section?.optJSONObject("itemSectionRenderer")?.optJSONArray("contents")
                        if (itemContents != null) {
                            for (j in 0 until itemContents.length()) {
                                val item = itemContents.optJSONObject(j)
                                val vr = item?.optJSONObject("videoRenderer") ?: continue
                                val videoId = vr.optString("videoId")
                                if (videoId.isEmpty()) continue

                                val titleRuns = vr.optJSONObject("title")?.optJSONArray("runs")
                                val title = if (titleRuns != null && titleRuns.length() > 0) {
                                    titleRuns.getJSONObject(0).optString("text")
                                } else {
                                    vr.optJSONObject("title")?.optString("simpleText", "") ?: ""
                                }
                                if (title.isEmpty()) continue

                                val ownerRuns = vr.optJSONObject("ownerText")?.optJSONArray("runs")
                                val author = if (ownerRuns != null && ownerRuns.length() > 0) {
                                    ownerRuns.getJSONObject(0).optString("text")
                                } else {
                                    "YouTube"
                                }

                                val duration = vr.optJSONObject("lengthText")?.optString("simpleText", "Video") ?: "Video"
                                val views = vr.optJSONObject("viewCountText")?.optString("simpleText", "YouTube") ?: "YouTube"
                                val date = vr.optJSONObject("publishedTimeText")?.optString("simpleText", "") ?: ""

                                val thumbnails = vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                                val thumbUrl = if (thumbnails != null && thumbnails.length() > 0) {
                                    thumbnails.getJSONObject(thumbnails.length() - 1).optString("url")
                                } else {
                                    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                }

                                list.add(
                                    VideoItem(
                                        id = videoId,
                                        title = title,
                                        author = author,
                                        duration = duration,
                                        viewCount = views,
                                        uploadDate = date,
                                        thumbnailUrl = thumbUrl
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext list
    }

    /**
     * Resolves REAL streams and exact byte sizes from YouTube
     */
    suspend fun resolveStreams(videoId: String): List<StreamOption> = withContext(Dispatchers.IO) {
        for (instance in getApiInstances()) {
            val options = mutableListOf<StreamOption>()
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$instance/api/v1/videos/$videoId")
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 2500
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                conn.connect()

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val data = JSONObject(body)

                    val adaptive = data.optJSONArray("adaptiveFormats")
                    val formatStreams = data.optJSONArray("formatStreams")

                    // The adaptive URL can be rejected by Google's CDN with 403.
                    // Use Invidious' latest_version redirect for the audio stream instead.
                    val audioFormats = mutableListOf<JSONObject>()
                    if (adaptive != null) {
                        for (i in 0 until adaptive.length()) {
                            val audio = adaptive.optJSONObject(i) ?: continue
                            if (audio.optString("type").contains("audio/mp4")) {
                                audioFormats.add(audio)
                            }
                        }
                    }
                    val audioFormat = audioFormats.firstOrNull { it.optString("itag") == "140" }
                        ?: audioFormats.maxByOrNull { it.optLong("clen", 0L) }
                    val audioSize = audioFormat?.optLong("clen", 0L) ?: 0L
                    options.add(
                        StreamOption(
                            format = "MP3",
                            quality = "Ses (MP3 - Yüksek Kalite)",
                            sizeBytes = audioSize,
                            sizeFormatted = formatSize(audioSize),
                            streamUrl = "$instance/latest_version?id=$videoId&itag=140"
                        )
                    )

                    // 2. Video streams (MP4)
                    // A: formatStreams (muxed audio+video)
                    if (formatStreams != null && formatStreams.length() > 0) {
                        for (i in 0 until formatStreams.length()) {
                            val vf = formatStreams.optJSONObject(i) ?: continue
                            var streamUrl = vf.optString("url", "")
                            if (streamUrl.startsWith("/")) {
                                streamUrl = "$instance$streamUrl"
                            }
                            val qLabel = vf.optString("qualityLabel", "Video")
                            val size = vf.optLong("size", 0L)
                            if (streamUrl.isNotEmpty() && options.none { it.quality == qLabel }) {
                                options.add(
                                    StreamOption(
                                        format = "MP4",
                                        quality = qLabel,
                                        sizeBytes = size,
                                        sizeFormatted = formatSize(size),
                                        streamUrl = streamUrl
                                    )
                                )
                            }
                        }
                    }

                    // B: adaptiveFormats video (MP4)
                    if (adaptive != null) {
                        for (i in 0 until adaptive.length()) {
                            val vf = adaptive.optJSONObject(i) ?: continue
                            val type = vf.optString("type", "")
                            var streamUrl = vf.optString("url", "")
                            if (streamUrl.startsWith("/")) {
                                streamUrl = "$instance$streamUrl"
                            }
                            val qLabel = vf.optString("qualityLabel", "")

                            if (type.contains("video/mp4") && streamUrl.isNotEmpty() && qLabel.isNotEmpty()) {
                                if (options.none { it.quality == qLabel && it.format == "MP4" }) {
                                    val clen = vf.optLong("clen", 0L)
                                    options.add(
                                        StreamOption(
                                            format = "MP4",
                                            quality = qLabel,
                                            sizeBytes = clen,
                                            sizeFormatted = formatSize(clen),
                                            streamUrl = streamUrl
                                        )
                                    )
                                }
                            }
                        }
                    }

                    if (options.isNotEmpty()) {
                        return@withContext options
                    }
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }

        return@withContext emptyList()
    }

    private fun getApiInstances(): List<String> {
        val now = System.currentTimeMillis()
        if (cachedInstances.isNotEmpty() && now - instanceCacheTime < INSTANCE_CACHE_MS) {
            return cachedInstances
        }

        val candidates = mutableListOf<Pair<String, Double>>()
        var conn: HttpURLConnection? = null
        try {
            conn = URL(INSTANCE_DIRECTORY_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 2500
            conn.setRequestProperty("User-Agent", "KZBStube Android")
            if (conn.responseCode == 200) {
                val directory = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                for (i in 0 until directory.length()) {
                    val entry = directory.optJSONArray(i) ?: continue
                    val details = entry.optJSONObject(1) ?: continue
                    if (!details.optBoolean("api", false) || details.optString("type") != "https") continue

                    val monitor = details.optJSONObject("monitor")
                    if (monitor?.optBoolean("down", false) == true) continue
                    val uptime = monitor?.optDouble("uptime", 100.0) ?: 100.0
                    if (uptime < 90.0) continue

                    val uri = details.optString("uri").trimEnd('/')
                    if (uri.startsWith("https://")) candidates.add(uri to uptime)
                }
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }

        val instances = (candidates.sortedByDescending { it.second }.take(3).map { it.first } + FALLBACK_INSTANCE)
            .distinct()
        if (instances.isNotEmpty()) {
            cachedInstances = instances
            instanceCacheTime = now
            return instances
        }

        return cachedInstances.ifEmpty { listOf(FALLBACK_INSTANCE) }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes > 0) String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else "Boyut indirirken alınacak"

    private fun fetchSingleVideoInfo(videoId: String): VideoItem? {
        return try {
            val url = URL("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.connect()
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                val title = obj.optString("title", "YouTube Video")
                val author = obj.optString("author_name", "YouTube")
                val thumb = obj.optString("thumbnail_url", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")

                VideoItem(
                    id = videoId,
                    title = title,
                    author = author,
                    duration = "Video",
                    viewCount = "Doğrudan Link",
                    uploadDate = "YouTube",
                    thumbnailUrl = thumb
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
