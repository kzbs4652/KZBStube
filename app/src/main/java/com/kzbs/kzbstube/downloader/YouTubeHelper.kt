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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
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
    private val searchClient = httpClient.newBuilder()
        .readTimeout(5500, TimeUnit.MILLISECONDS)
        .callTimeout(6500, TimeUnit.MILLISECONDS)
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
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val directVideoId = extractVideoId(cleanQuery)
        if (directVideoId != null) {
            return@withContext listOfNotNull(fetchSingleVideoInfo(directVideoId))
        }

        searchProviders(cleanQuery)
    }

    private suspend fun searchProviders(query: String): List<VideoItem> = coroutineScope {
        val responses = Channel<List<VideoItem>>(2)
        val jobs = listOf(
            launch(Dispatchers.IO) { responses.send(searchInnerTube(query)) },
            launch(Dispatchers.IO) {
                val result = try {
                    searchInvidious(query)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                responses.send(result)
            }
        )

        try {
            repeat(jobs.size) {
                val result = responses.receive()
                if (result.isNotEmpty()) return@coroutineScope result
            }
            emptyList()
        } finally {
            jobs.forEach { it.cancel() }
            responses.close()
        }
    }

    private suspend fun searchInnerTube(query: String): List<VideoItem> {
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20231201.00.00")
                    put("hl", "tr")
                    put("gl", "TR")
                })
            })
            put("query", query)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36")
            .post(body)
            .build()

        return try {
            executeCall(request, searchClient).use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val root = JSONObject(response.body?.string().orEmpty())
                val sections = root.optJSONObject("contents")
                    ?.optJSONObject("twoColumnSearchResultsRenderer")
                    ?.optJSONObject("primaryContents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents") ?: return@use emptyList()
                val videos = mutableListOf<VideoItem>()
                for (i in 0 until sections.length()) {
                    val items = sections.optJSONObject(i)
                        ?.optJSONObject("itemSectionRenderer")?.optJSONArray("contents") ?: continue
                    for (j in 0 until items.length()) {
                        val renderer = items.optJSONObject(j)?.optJSONObject("videoRenderer") ?: continue
                        val id = renderer.optString("videoId")
                        val titleObject = renderer.optJSONObject("title")
                        val title = titleObject?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                            ?: titleObject?.optString("simpleText", "").orEmpty()
                        if (id.isBlank() || title.isBlank()) continue
                        val author = renderer.optJSONObject("ownerText")?.optJSONArray("runs")
                            ?.optJSONObject(0)?.optString("text") ?: "YouTube"
                        val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        val thumb = thumbnails?.optJSONObject((thumbnails.length() - 1).coerceAtLeast(0))
                            ?.optString("url")?.takeIf(String::isNotBlank)
                            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                        videos.add(
                            VideoItem(
                                id = id,
                                title = title,
                                author = author,
                                duration = renderer.optJSONObject("lengthText")?.optString("simpleText", "Video") ?: "Video",
                                viewCount = renderer.optJSONObject("viewCountText")?.optString("simpleText", "YouTube") ?: "YouTube",
                                uploadDate = renderer.optJSONObject("publishedTimeText")?.optString("simpleText", "") ?: "",
                                thumbnailUrl = thumb
                            )
                        )
                    }
                }
                videos.distinctBy { it.id }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun searchInvidious(query: String): List<VideoItem> = coroutineScope {
        val instances = getApiInstances()
        val responses = Channel<Pair<String, List<VideoItem>>>(instances.size)
        val jobs = instances.map { instance ->
            launch(Dispatchers.IO) {
                val result = try {
                    searchInvidiousInstance(instance, query)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                responses.send(instance to result)
            }
        }

        try {
            repeat(instances.size) {
                val (instance, videos) = responses.receive()
                if (videos.isNotEmpty()) {
                    rememberGoodInstance(instance)
                    return@coroutineScope videos
                }
            }
            emptyList()
        } finally {
            jobs.forEach { it.cancel() }
            responses.close()
        }
    }

    private suspend fun searchInvidiousInstance(instance: String, query: String): List<VideoItem> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("$instance/api/v1/search?q=$encodedQuery&type=video")
            .header("User-Agent", "KZBStube Android")
            .build()

        return executeCall(request).use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val json = JSONArray(response.body?.string().orEmpty())
            buildList {
                for (i in 0 until json.length()) {
                    val item = json.optJSONObject(i) ?: continue
                    val id = item.optString("videoId")
                    val title = item.optString("title")
                    if (id.isBlank() || title.isBlank()) continue
                    val thumbnails = item.optJSONArray("videoThumbnails")
                    val thumb = thumbnails?.optJSONObject((thumbnails.length() - 1).coerceAtLeast(0))
                        ?.optString("url")?.takeIf(String::isNotBlank)
                        ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                    val durationSeconds = item.optLong("lengthSeconds", -1L)
                    add(
                        VideoItem(
                            id = id,
                            title = title,
                            author = item.optString("author", "YouTube"),
                            duration = formatDuration(durationSeconds),
                            viewCount = item.optString("viewCountText").ifBlank {
                                formatCount(item.optLong("viewCount", 0L))
                            },
                            uploadDate = item.optString("publishedText", ""),
                            thumbnailUrl = thumb
                        )
                    )
                }
            }.distinctBy { it.id }
        }
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds < 0L) return "Video"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
        else "%d:%02d".format(minutes, remainingSeconds)
    }

    private fun formatCount(count: Long): String =
        if (count > 0L) "$count görüntüleme" else "YouTube"
    /**
     * Resolves MP4 downloads and MP3 audio downloads. Audio is fetched as AAC,
     * then converted locally before the completed file is published. Public
     * Invidious instances are queried concurrently so one unhealthy host cannot
     * delay every other host.
     */
    suspend fun resolveStreams(
        videoId: String,
        requiredFormat: String? = null,
        forceRefresh: Boolean = false,
        skipInstanceHost: String? = null
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

        val excludedHost = skipInstanceHost?.lowercase()
        var instances = getApiInstances().filter { excludedHost == null || instanceHost(it)?.lowercase() != excludedHost }
        var (options, reachedAnyInstance) = probeInstances(instances, videoId, requiredFormat)

        if (options.isEmpty() && !reachedAnyInstance) {
            val refreshed = getApiInstances(forceRefresh = true)
                .filter { excludedHost == null || instanceHost(it)?.lowercase() != excludedHost }
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
                "Bu video için MP4 veya MP3 ses akışı bulunamadı."
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
                    val streamUrl = "$instance/latest_version?id=$videoId&itag=$itag"
                    options.add(
                        StreamOption(
                            format = "M4A",
                            quality = "Önizleme sesi",
                            sizeBytes = size,
                            sizeFormatted = formatSize(size),
                            streamUrl = streamUrl
                        )
                    )
                    options.add(
                        StreamOption(
                            format = "MP3",
                            quality = "Ses (MP3 • 192 kb/s)",
                            sizeBytes = size,
                            sizeFormatted = "MP3'e dönüştürülecek",
                            streamUrl = streamUrl
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

    private fun instanceHost(instance: String): String? =
        runCatching { URL(instance).host }.getOrNull()

    private fun formatSize(bytes: Long): String =
        if (bytes > 0) String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else "Boyut indirirken alınacak"

    private suspend fun fetchSingleVideoInfo(videoId: String): VideoItem? {
        val oembedRequest = Request.Builder()
            .url("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json")
            .header("User-Agent", "KZBStube Android")
            .build()

        try {
            executeCall(oembedRequest, searchClient).use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string().orEmpty())
                    return VideoItem(
                        id = videoId,
                        title = json.optString("title", "YouTube Video"),
                        author = json.optString("author_name", "YouTube"),
                        duration = "Video",
                        viewCount = "Doğrudan bağlantı",
                        uploadDate = "YouTube",
                        thumbnailUrl = json.optString(
                            "thumbnail_url",
                            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                        )
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }

        return fetchVideoInfoFromInstances(videoId)
    }

    private suspend fun fetchVideoInfoFromInstances(videoId: String): VideoItem? = coroutineScope {
        val instances = getApiInstances()
        val results = Channel<Pair<String, VideoItem?>>(instances.size)
        val jobs = instances.map { instance ->
            launch(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("$instance/api/v1/videos/$videoId")
                    .header("User-Agent", "KZBStube Android")
                    .build()
                val item = try {
                    executeCall(request).use { response ->
                        if (!response.isSuccessful) return@use null
                        val json = JSONObject(response.body?.string().orEmpty())
                        val thumbnails = json.optJSONArray("videoThumbnails")
                        val thumb = thumbnails?.optJSONObject((thumbnails.length() - 1).coerceAtLeast(0))
                            ?.optString("url")?.takeIf(String::isNotBlank)
                            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                        VideoItem(
                            id = videoId,
                            title = json.optString("title", "YouTube Video"),
                            author = json.optString("author", "YouTube"),
                            duration = formatDuration(json.optLong("lengthSeconds", -1L)),
                            viewCount = formatCount(json.optLong("viewCount", 0L)),
                            uploadDate = json.optString("publishedText", ""),
                            thumbnailUrl = thumb
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                results.send(instance to item)
            }
        }

        try {
            repeat(instances.size) {
                val (instance, item) = results.receive()
                if (item != null) {
                    rememberGoodInstance(instance)
                    return@coroutineScope item
                }
            }
            null
        } finally {
            jobs.forEach { it.cancel() }
            results.close()
        }
    }
}
