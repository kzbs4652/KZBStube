package com.kzbs.kzbstube.downloader

import com.kzbs.kzbstube.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object YouTubeHelper {

    fun extractVideoId(input: String): String? {
        val clean = input.trim()
        val regex = Regex("(?:https?://)?(?:www\\.)?(?:youtube\\.com/(?:watch\\?v=|shorts/|embed/)|youtu\\.be/)([a-zA-Z0-9_-]{11})")
        return regex.find(clean)?.groupValues?.get(1)
    }

    suspend fun searchVideos(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<VideoItem>()
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext list

        // Check if query is a direct YouTube link
        val directVideoId = extractVideoId(cleanQuery)
        if (directVideoId != null) {
            val directItem = fetchSingleVideoInfo(directVideoId)
            if (directItem != null) {
                list.add(directItem)
                return@withContext list
            }
        }

        // Live YouTube Search via InnerTube API
        try {
            val url = URL("https://www.youtube.com/youtubei/v1/search")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

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

                                // Title
                                val titleRuns = vr.optJSONObject("title")?.optJSONArray("runs")
                                val title = if (titleRuns != null && titleRuns.length() > 0) {
                                    titleRuns.getJSONObject(0).optString("text")
                                } else {
                                    vr.optJSONObject("title")?.optString("simpleText", "") ?: ""
                                }

                                if (title.isEmpty()) continue

                                // Author / Channel Name
                                val ownerRuns = vr.optJSONObject("ownerText")?.optJSONArray("runs")
                                val author = if (ownerRuns != null && ownerRuns.length() > 0) {
                                    ownerRuns.getJSONObject(0).optString("text")
                                } else {
                                    "YouTube"
                                }

                                // Duration
                                val duration = vr.optJSONObject("lengthText")?.optString("simpleText", "Video") ?: "Video"

                                // Views
                                val views = vr.optJSONObject("viewCountText")?.optString("simpleText", "YouTube") ?: "YouTube"

                                // Upload Date
                                val date = vr.optJSONObject("publishedTimeText")?.optString("simpleText", "") ?: ""

                                // High quality thumbnail
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

    private fun fetchSingleVideoInfo(videoId: String): VideoItem? {
        return try {
            val url = URL("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
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
        } catch (e: Exception) {
            null
        }
    }
}
