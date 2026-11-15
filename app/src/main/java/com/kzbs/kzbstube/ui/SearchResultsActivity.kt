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
import kotlinx.coroutines.launch

class SearchResultsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchResultsBinding
    private val videoList = mutableListOf<VideoItem>()
    private lateinit var adapter: VideoAdapter
    private var currentMode = "MP4" // "MP4" or "MP3"
    private var mediaPlayer: MediaPlayer? = null
    private var isPlayingAudio = false

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
                    binding.tvMiniStatus.text = "Çalıyor..."
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

    /**
     * Tapping any item now always plays audio preview directly in the app.
     * This avoids YouTube copyright blocks and video playback errors completely.
     */
    private fun onVideoItemClicked(video: VideoItem) {
        playAudioInApp(video)
    }

    private fun playAudioInApp(video: VideoItem) {
        binding.llMiniPlayer.visibility = View.VISIBLE
        binding.tvMiniTitle.text = video.title
        binding.tvMiniStatus.text = "Hazırlanıyor..."
        binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_pause)

        Glide.with(this).load(video.thumbnailUrl).into(binding.ivMiniThumb)

        lifecycleScope.launch {
            val streams = YouTubeHelper.resolveStreams(video.id)
            val audioOpt = streams.firstOrNull { it.format == "MP3" } ?: streams.firstOrNull()

            if (audioOpt != null && audioOpt.streamUrl.startsWith("http")) {
                try {
                    stopAudio()
                    binding.tvMiniStatus.text = "Bağlanıyor..."
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
                        setDataSource(this@SearchResultsActivity, Uri.parse(audioOpt.streamUrl), headers)
                        setOnPreparedListener {
                            start()
                            isPlayingAudio = true
                            binding.tvMiniStatus.text = "Çalıyor 🎵"
                            binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                        }
                        setOnBufferingUpdateListener { _, percent ->
                            if (isPlayingAudio && percent < 100) {
                                binding.tvMiniStatus.text = "Çalıyor 🎵 (%$percent)"
                            }
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
                } catch (e: Exception) {
                    binding.tvMiniStatus.text = "Çalma hatası"
                }
            } else {
                binding.tvMiniStatus.text = "Ses akışı bulunamadı"
            }
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

    /**
     * Resolves REAL streams with REAL sizes from YouTube server
     */
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

            // Filter streams matching current mode if possible
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

                // Start 100% REAL file download
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
