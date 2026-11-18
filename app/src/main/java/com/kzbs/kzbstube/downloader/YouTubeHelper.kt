package com.kzbs.kzbstube.downloader

import com.kzbs.kzbstube.model.StreamOption
import com.kzbs.kzbstube.model.VideoItem
import com.kzbs.kzbstube.KZBStubeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object YouTubeHelper {

    private const val INSTANCE_DIRECTORY_URL =
        "https://api.invidious.io/instances.json?pretty=1&sort_by=type,users"
    private const val FALLBACK_INSTANCE = "https://yt.chocolatemoo53.com"
    private const val INSTANCE_CACHE_MS = 6 * 60 * 60 * 1000L
    private const val STREAM_CACHE_MS = 45 * 1000L
    private const val PREFS_NAME = "kzbstube_resolver"
    private const val KEY_CACHED_INSTANCES = "cached_instances"
    private const val KEY_INSTANCE_CACHE_TIME = "instance_cache_time"
    private const val KEY_LAST_GOOD_INSTANCE = "last_good_instance"

    @Volatile private var cachedInstances: List<String> = emptyList()
    @Volatile private var instanceCacheTime = 0L
    private val instanceRefreshMutex = Mutex()
    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class CachedStreams(val options: List<StreamOption>, val savedAt: Long)
    private val streamCache = ConcurrentHashMap<String, CachedStreams>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .callTimeout(5500, TimeUnit.MILLISECONDS)
        .build()

    data class StreamResolutionResult(
        val options: List<StreamOption>,
        val errorMessage: String? = null
    )

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
            return@withContext listOfNotNull(directItem)
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext list
    }

    /**
     * Resolves muxed MP4 and AAC/M4A streams. Public Invidious instances are
     * queried concurrently so one unhealthy host cannot delay every other host.
     */
    suspend fun resolveStreams(
        videoId: String,
        requiredFormat: String? = null,
        forceRefresh: Boolean = false
    ): StreamResolutionResult = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            streamCache[videoId]?.let { cached ->
                val hasRequestedFormat = requiredFormat == null || cached.options.any { it.format == requiredFormat }
                if (hasRequestedFormat && System.currentTimeMillis() - cached.savedAt < STREAM_CACHE_MS) {
                    return@withContext StreamResolutionResult(cached.options)
                }
            }
        } else {
            streamCache.remove(videoId)
        }

        var instances = getApiInstances()
        var (options, reachedAnyInstance) = probeInstances(instances, videoId, requiredFormat)

        if (options.isEmpty() && !reachedAnyInstance) {
            val refreshed = getApiInstances(forceRefresh = true)
            if (refreshed != instances) {
                instances = refreshed
                val secondAttempt = probeInstances(instances, videoId, requiredFormat)
                options = secondAttempt.first
                reachedAnyInstance = secondAttempt.second
            }
        }

        if (options.isNotEmpty()) {
            streamCache[videoId] = CachedStreams(options, System.currentTimeMillis())
            StreamResolutionResult(options)
        } else if (reachedAnyInstance) {
            StreamResolutionResult(
                emptyList(),
                "Bu video için desteklenen MP4 veya M4A akışı bulunamadı."
            )
        } else {
            StreamResolutionResult(
                emptyList(),
                "İndirme kaynaklarına şu an ulaşılamıyor. Biraz sonra tekrar deneyin."
            )
        }
    }

    fun clearStreamCache(videoId: String) {
        streamCache.remove(videoId)
    }

    private data class InstanceProbe(
        val instance: String,
        val options: List<StreamOption>,
        val responded: Boolean
    )

    private suspend fun probeInstances(
        instances: List<String>,
        videoId: String,
        requiredFormat: String?
    ): Pair<List<StreamOption>, Boolean> = coroutineScope {
        if (instances.isEmpty()) return@coroutineScope emptyList<StreamOption>() to false

        val results = Channel<InstanceProbe>(instances.size)
        val jobs = instances.map { instance ->
            launch(Dispatchers.IO) {
                val result = try {
                    fetchStreamsFromInstance(instance, videoId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    InstanceProbe(instance, emptyList(), responded = false)
                }
                results.send(result)
            }
        }

        var responded = false
        val combinedOptions = mutableListOf<StreamOption>()
        try {
            repeat(instances.size) {
                val result = results.receive()
                responded = responded || result.responded
                result.options.forEach { option ->
                    if (combinedOptions.none { it.format == option.format && it.quality == option.quality }) {
                        combinedOptions.add(option)
                    }
                }
                val requiredFound = if (requiredFormat == null) {
                    result.options.isNotEmpty()
                } else {
                    result.options.any { it.format == requiredFormat }
                }
                if (requiredFound) {
                    rememberGoodInstance(result.instance)
                    return@coroutineScope combinedOptions to responded
                }
            }
            combinedOptions to responded
        } finally {
            jobs.forEach { it.cancel() }
            results.close()
        }
    }

    private suspend fun fetchStreamsFromInstance(instance: String, videoId: String): InstanceProbe {
        val request = Request.Builder()
            .url("$instance/api/v1/videos/$videoId")
            .header("User-Agent", "KZBStube Android")
            .build()

        return executeCall(request).use { response ->
                if (!response.isSuccessful) {
                    return@use InstanceProbe(instance, emptyList(), responded = true)
                }
                val body = response.body?.string()
                    ?: return@use InstanceProbe(instance, emptyList(), responded = true)
                val data = JSONObject(body)
                val options = mutableListOf<StreamOption>()
                val adaptive = data.optJSONArray("adaptiveFormats")
                val formatStreams = data.optJSONArray("formatStreams")

                // Invidious redirects audio through latest_version. Use the actual
                // itag selected above instead of always requesting itag 140.
                val audioFormats = mutableListOf<JSONObject>()
                if (adaptive != null) {
                    for (i in 0 until adaptive.length()) {
                        val audio = adaptive.optJSONObject(i) ?: continue
                        if (audio.optString("type").contains("audio/mp4", ignoreCase = true)) {
                            audioFormats.add(audio)
                        }
                    }
                }
                val audioFormat = audioFormats.firstOrNull { it.optString("itag") == "140" }
                    ?: audioFormats.maxByOrNull { it.optLong("clen", 0L) }
                if (audioFormat != null) {
                    val itag = audioFormat.optString("itag", "140")
                    val size = audioFormat.optLong("clen", 0L)
                    options.add(
                        StreamOption(
                            format = "M4A",
                            quality = "Ses (M4A / AAC)",
                            sizeBytes = size,
                            sizeFormatted = formatSize(size),
                            streamUrl = "$instance/latest_version?id=$videoId&itag=$itag"
                        )
                    )
                }

                // Only offer progressive MP4 streams that contain both audio and
                // video. Adaptive video-only streams must be merged before use.
                if (formatStreams != null) {
                    for (i in 0 until formatStreams.length()) {
                        val vf = formatStreams.optJSONObject(i) ?: continue
                        val mimeType = vf.optString("type", "")
                        if (!mimeType.contains("video/mp4", ignoreCase = true) ||
                            !mimeType.contains("mp4a", ignoreCase = true)
                        ) continue

                        var streamUrl = vf.optString("url", "")
                        if (streamUrl.startsWith("/")) streamUrl = "$instance$streamUrl"
                        val quality = vf.optString("qualityLabel", "Video")
                        if (streamUrl.isNotBlank() && options.none { it.format == "MP4" && it.quality == quality }) {
                            val size = vf.optLong("size", 0L)
                            options.add(
                                StreamOption(
                                    format = "MP4",
                                    quality = quality,
                                    sizeBytes = size,
                                    sizeFormatted = formatSize(size),
                                    streamUrl = streamUrl
                                )
                            )
                        }
                    }
                }

            InstanceProbe(instance, options, responded = true)
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun executeCall(
        request: Request,
        client: OkHttpClient = httpClient
    ): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val token = continuation.tryResumeWithException(e)
                if (token != null) continuation.completeResume(token)
            }

            override fun onResponse(call: Call, response: Response) {
                val token = continuation.tryResume(response)
                if (token != null) continuation.completeResume(token) else response.close()
            }
        })
    }

    private suspend fun getApiInstances(forceRefresh: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedInstances.isEmpty()) loadPersistedInstances()
        if (!forceRefresh && cachedInstances.isNotEmpty()) {
            if (now - instanceCacheTime < INSTANCE_CACHE_MS) {
                return@withContext orderInstances(cachedInstances)
            }
            resolverScope.launch { refreshApiInstances(force = true) }
            return@withContext orderInstances(cachedInstances)
        }
        refreshApiInstances(force = forceRefresh)
    }

    private fun loadPersistedInstances() {
        val prefs = KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CACHED_INSTANCES, null) ?: return
        try {
            val array = JSONArray(json)
            val loaded = (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            if (loaded.isNotEmpty()) {
                cachedInstances = loaded
                instanceCacheTime = prefs.getLong(KEY_INSTANCE_CACHE_TIME, 0L)
            }
        } catch (_: Exception) {
            cachedInstances = emptyList()
            instanceCacheTime = 0L
        }
    }

    private suspend fun refreshApiInstances(force: Boolean): List<String> = instanceRefreshMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && cachedInstances.isNotEmpty() && now - instanceCacheTime < INSTANCE_CACHE_MS) {
            return@withLock orderInstances(cachedInstances)
        }

        val candidates = mutableListOf<Pair<String, Double>>()
        try {
            val request = Request.Builder()
                .url(INSTANCE_DIRECTORY_URL)
                .header("User-Agent", "KZBStube Android")
                .build()
            executeCall(request, httpClient.newBuilder().callTimeout(3500, TimeUnit.MILLISECONDS).build())
                .use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val directory = JSONArray(body)
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
                    }
                }
        } catch (_: Exception) {
        }

        if (candidates.isNotEmpty()) {
            cachedInstances = (candidates.sortedByDescending { it.second }.take(3).map { it.first } + FALLBACK_INSTANCE).distinct()
            instanceCacheTime = System.currentTimeMillis()
            val prefs = KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CACHED_INSTANCES, JSONArray(cachedInstances).toString())
                .putLong(KEY_INSTANCE_CACHE_TIME, instanceCacheTime)
                .apply()
        }
        orderInstances(cachedInstances.ifEmpty { listOf(FALLBACK_INSTANCE) })
    }

    private fun orderInstances(instances: List<String>): List<String> {
        val prefs = KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val lastGood = prefs.getString(KEY_LAST_GOOD_INSTANCE, null)
        return (listOfNotNull(lastGood) + instances + FALLBACK_INSTANCE).distinct()
    }

    private fun rememberGoodInstance(instance: String) {
        val prefs = KZBStubeApp.appContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_GOOD_INSTANCE, instance).apply()
    }

    private fun formatSize(bytes: Long): String =
        if (bytes > 0) String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else "Boyut indirirken alınacak"

    private fun fetchSingleVideoInfo(videoId: String): VideoItem? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
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
        } finally {
            conn?.disconnect()
        }
    }
}
