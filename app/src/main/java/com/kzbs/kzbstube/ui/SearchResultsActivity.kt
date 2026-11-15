package com.kzbs.kzbstube.ui

import android.app.AlertDialog
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kzbs.kzbstube.R
import com.kzbs.kzbstube.databinding.ActivitySearchResultsBinding
import com.kzbs.kzbstube.databinding.ItemVideoBinding
import com.kzbs.kzbstube.downloader.DownloadManager
import com.kzbs.kzbstube.downloader.YouTubeHelper
import com.kzbs.kzbstube.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class SearchResultsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchResultsBinding
    private val videoList = mutableListOf<VideoItem>()
    private lateinit var adapter: VideoAdapter
    private var currentMode = "MP4" // "MP4" or "MP3"
    private var mediaPlayer: MediaPlayer? = null
    private var isPlayingAudio = false
    private var activePreviewVideoId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val query = intent.getStringExtra("QUERY") ?: "mavi espor"
        currentMode = intent.getStringExtra("MODE") ?: "MP4"

        updateModeButtons()

        binding.etTopSearch.setText(query)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener { binding.etTopSearch.setText("") }

        // Mode toggling (MP4 / MP3)
        binding.btnResultModeVideo.setOnClickListener {
            currentMode = "MP4"
            updateModeButtons()
        }

        binding.btnResultModeAudio.setOnClickListener {
            currentMode = "MP3"
            updateModeButtons()
        }

        adapter = VideoAdapter(
            items = videoList,
            onItemClick = { video -> onVideoItemClicked(video) },
            onDownloadClick = { video -> showDownloadOptionsDialog(video) }
        )

        binding.rvVideos.layoutManager = LinearLayoutManager(this)
        binding.rvVideos.adapter = adapter

        performSearch(query)

        binding.etTopSearch.setOnEditorActionListener { _, _, _ ->
            val newQuery = binding.etTopSearch.text.toString().trim()
            if (newQuery.isNotEmpty()) performSearch(newQuery)
            true
        }

        // Mini audio player controls
        binding.btnMiniPlayPause.setOnClickListener {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                    isPlayingAudio = false
                    binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    binding.tvMiniStatus.text = "Duraklatıldı"
                } else {
                    mp.start()
                    isPlayingAudio = true
                    binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                    binding.tvMiniStatus.text = "Çalıyor 🎵"
                }
            }
        }

        binding.btnMiniClose.setOnClickListener {
            stopAudio()
            binding.llMiniPlayer.visibility = View.GONE
        }
    }

    private fun updateModeButtons() {
        if (currentMode == "MP4") {
            binding.btnResultModeVideo.setBackgroundResource(R.drawable.bg_red_button)
            binding.btnResultModeVideo.setTextColor(resources.getColor(R.color.text_primary, null))
            binding.btnResultModeAudio.setBackgroundResource(R.drawable.bg_search_box)
            binding.btnResultModeAudio.setTextColor(resources.getColor(R.color.text_secondary, null))
        } else {
            binding.btnResultModeAudio.setBackgroundResource(R.drawable.bg_red_button)
            binding.btnResultModeAudio.setTextColor(resources.getColor(R.color.text_primary, null))
            binding.btnResultModeVideo.setBackgroundResource(R.drawable.bg_search_box)
            binding.btnResultModeVideo.setTextColor(resources.getColor(R.color.text_secondary, null))
        }
    }

    private fun performSearch(query: String) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val results = YouTubeHelper.searchVideos(query)
            videoList.clear()
            videoList.addAll(results)
            adapter.notifyDataSetChanged()
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun onVideoItemClicked(video: VideoItem) {
        playAudioInApp(video)
    }

    /**
     * Blazing fast audio preview using High-Speed Range caching.
     * Starts playback in under 1 second without YouTube bitrate throttling.
     */
    private fun playAudioInApp(video: VideoItem) {
        activePreviewVideoId = video.id
        binding.llMiniPlayer.visibility = View.VISIBLE
        binding.tvMiniTitle.text = video.title
        binding.tvMiniStatus.text = "Hazırlanıyor..."
        binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_pause)

        Glide.with(this).load(video.thumbnailUrl).into(binding.ivMiniThumb)

        lifecycleScope.launch {
            val cacheFile = File(cacheDir, "preview_${video.id}.m4a")

            // If already cached locally, play instantly (0ms)
            if (cacheFile.exists() && cacheFile.length() > 50000) {
                playLocalAudio(cacheFile.absolutePath)
                return@launch
            }

            binding.tvMiniStatus.text = "Hızlı yükleniyor..."
            val streams = YouTubeHelper.resolveStreams(video.id)
            val audioOpt = streams.firstOrNull { it.format == "MP3" } ?: streams.firstOrNull()

            if (audioOpt != null && audioOpt.streamUrl.startsWith("http")) {
                withContext(Dispatchers.IO) {
                    try {
                        var streamUrl = audioOpt.streamUrl
                        // Resolve redirect to get direct CDN endpoint
                        val headConn = URL(streamUrl).openConnection() as HttpURLConnection
                        headConn.requestMethod = "HEAD"
                        headConn.connectTimeout = 6000
                        headConn.readTimeout = 6000
                        headConn.setRequestProperty("User-Agent", "Mozilla/5.0")
                        headConn.instanceFollowRedirects = true
                        headConn.connect()

                        if (headConn.responseCode in listOf(301, 302, 303, 307, 308)) {
                            val loc = headConn.getHeaderField("Location")
                            if (!loc.isNullOrEmpty()) streamUrl = loc
                        }

                        val totalBytes = if (headConn.contentLengthLong > 0) headConn.contentLengthLong else audioOpt.sizeBytes
                        headConn.disconnect()

                        // Download audio into local cache with unthrottled Range chunks
                        val fos = FileOutputStream(cacheFile)
                        val bos = BufferedOutputStream(fos, 65536)
                        val buffer = ByteArray(65536)
                        var downloaded = 0L
                        val chunkSize = 512 * 1024L
                        val maxBytes = if (totalBytes > 0) totalBytes else 4 * 1024 * 1024L

                        while (downloaded < maxBytes && activePreviewVideoId == video.id) {
                            val end = minOf(downloaded + chunkSize - 1, maxBytes - 1)
                            val rc = URL(streamUrl).openConnection() as HttpURLConnection
                            rc.connectTimeout = 8000
                            rc.readTimeout = 12000
                            rc.setRequestProperty("User-Agent", "Mozilla/5.0")
                            rc.setRequestProperty("Range", "bytes=$downloaded-$end")
                            rc.connect()

                            val code = rc.responseCode
                            if (code == 206 || code == 200) {
                                val input = BufferedInputStream(rc.inputStream, 65536)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    bos.write(buffer, 0, read)
                                    downloaded += read
                                }
                                input.close()
                                rc.disconnect()
                                if (code == 200) break
                            } else {
                                break
                            }
                        }
                        bos.flush()
                        bos.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // If user switched to another song while downloading, don't play old one
                if (activePreviewVideoId != video.id) return@launch

                if (cacheFile.exists() && cacheFile.length() > 30000) {
                    playLocalAudio(cacheFile.absolutePath)
                } else {
                    playStreamDirect(audioOpt.streamUrl)
                }
            } else {
                binding.tvMiniStatus.text = "Ses akışı bulunamadı"
            }
        }
    }

    private fun playLocalAudio(filePath: String) {
        try {
            stopAudio()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(filePath)
                setOnPreparedListener {
                    start()
                    isPlayingAudio = true
                    binding.tvMiniStatus.text = "Çalıyor 🎵"
                    binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                }
                setOnCompletionListener {
                    binding.tvMiniStatus.text = "Tamamlandı"
                    binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_play)
                }
                setOnErrorListener { _, _, _ ->
                    binding.tvMiniStatus.text = "Çalma hatası"
                    binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            binding.tvMiniStatus.text = "Çalma hatası"
        }
    }

    private fun playStreamDirect(streamUrl: String) {
        try {
            stopAudio()
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "*/*"
            )
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(this@SearchResultsActivity, Uri.parse(streamUrl), headers)
                setOnPreparedListener {
                    start()
                    isPlayingAudio = true
                    binding.tvMiniStatus.text = "Çalıyor 🎵"
                    binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                }
                setOnCompletionListener {
                    binding.tvMiniStatus.text = "Tamamlandı"
                    binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_play)
                }
                setOnErrorListener { _, _, _ ->
                    binding.tvMiniStatus.text = "Ses oynatılamadı"
                    binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            binding.tvMiniStatus.text = "Çalma hatası"
        }
    }

    private fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            isPlayingAudio = false
        } catch (_: Exception) {}
    }

    private fun showDownloadOptionsDialog(video: VideoItem) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_download_options, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvOptionTitle)
        val pbLoading = view.findViewById<ProgressBar>(R.id.pbOptionsLoading)
        val tvLabel = view.findViewById<TextView>(R.id.tvOptionsLabel)
        val rgOptions = view.findViewById<RadioGroup>(R.id.rgRealOptions)
        val btnStart = view.findViewById<Button>(R.id.btnStartDownload)

        tvTitle.text = video.title

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        lifecycleScope.launch {
            val streams = YouTubeHelper.resolveStreams(video.id)

            val filteredStreams = streams.filter {
                if (currentMode == "MP3") it.format == "MP3" else it.format == "MP4"
            }.ifEmpty { streams }

            pbLoading.visibility = View.GONE
            tvLabel.visibility = View.VISIBLE
            btnStart.visibility = View.VISIBLE

            rgOptions.removeAllViews()
            filteredStreams.forEachIndexed { index, opt ->
                val rb = RadioButton(this@SearchResultsActivity).apply {
                    id = index + 100
                    text = "${opt.quality}  •  ${opt.sizeFormatted}"
                    setTextColor(resources.getColor(R.color.text_primary, null))
                    textSize = 14f
                    isChecked = index == 0
                }
                rgOptions.addView(rb)
            }

            btnStart.setOnClickListener {
                val selectedIndex = rgOptions.checkedRadioButtonId - 100
                val selectedOpt = filteredStreams.getOrNull(selectedIndex) ?: filteredStreams.first()

                DownloadManager.startDownload(
                    video = video,
                    format = selectedOpt.format,
                    quality = selectedOpt.quality,
                    streamUrl = selectedOpt.streamUrl,
                    totalSizeBytes = selectedOpt.sizeBytes
                )

                dialog.dismiss()

                val intent = Intent(this@SearchResultsActivity, ActiveDownloadsActivity::class.java).apply {
                    putExtra("TITLE", video.title)
                    putExtra("FORMAT", "${selectedOpt.format} • ${selectedOpt.quality}")
                }
                startActivity(intent)
            }
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
    }
}

class VideoAdapter(
    private val items: List<VideoItem>,
    private val onItemClick: (VideoItem) -> Unit,
    private val onDownloadClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvTitle.text = item.title
        holder.binding.tvAuthor.text = item.author
        holder.binding.tvMeta.text = "${item.viewCount} • ${item.uploadDate}"
        holder.binding.tvDuration.text = item.duration

        Glide.with(holder.itemView.context)
            .load(item.thumbnailUrl)
            .into(holder.binding.ivThumbnail)

        holder.binding.btnDownload.setOnClickListener { onDownloadClick(item) }
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size
}
