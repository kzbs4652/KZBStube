package com.kzbs.kzbstube.ui

import android.app.AlertDialog
import android.app.PendingIntent
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
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kzbs.kzbstube.KZBStubeApp
import com.kzbs.kzbstube.R
import com.kzbs.kzbstube.databinding.ActivitySearchResultsBinding
import com.kzbs.kzbstube.databinding.ItemVideoBinding
import com.kzbs.kzbstube.downloader.DownloadManager
import com.kzbs.kzbstube.downloader.YouTubeHelper
import com.kzbs.kzbstube.model.VideoItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class SearchResultsActivity : AppCompatActivity() {

    companion object {
        const val ACTION_PREVIEW_PLAY_PAUSE = "com.kzbs.kzbstube.ACTION_PREVIEW_PLAY_PAUSE"
        const val ACTION_PREVIEW_REWIND = "com.kzbs.kzbstube.ACTION_PREVIEW_REWIND"
        const val ACTION_PREVIEW_FORWARD = "com.kzbs.kzbstube.ACTION_PREVIEW_FORWARD"
        const val ACTION_PREVIEW_CLOSE = "com.kzbs.kzbstube.ACTION_PREVIEW_CLOSE"
        const val NOTIF_ID_PREVIEW = 20001
    }

    private lateinit var binding: ActivitySearchResultsBinding
    private val videoList = mutableListOf<VideoItem>()
    private lateinit var adapter: VideoAdapter
    private var currentMode = "MP4" // "MP4" or "MP3"
    private var mediaPlayer: MediaPlayer? = null
    private var isPlayingAudio = false
    private var activePreviewVideoId: String? = null
    private var currentPlayingVideo: VideoItem? = null
    private var isUserSeeking = false
    private var progressJob: Job? = null
    private var previewResolveJob: Job? = null
    private var searchJob: Job? = null
    private var searchSequence = 0
    private var previewRetryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val query = intent.getStringExtra("QUERY") ?: "mavi espor"
        currentMode = intent.getStringExtra("MODE")?.let { if (it == "M4A") "MP3" else it } ?: "MP4"

        updateModeButtons()

        binding.etTopSearch.setText(query)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener { binding.etTopSearch.setText("") }
        binding.tvSearchMessage.setOnClickListener {
            val retryQuery = binding.etTopSearch.text.toString().trim()
            if (retryQuery.isNotEmpty()) performSearch(retryQuery)
        }

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

        // Mini player controls
        binding.btnMiniPlayPause.setOnClickListener {
            togglePlayPause()
        }

        binding.btnMiniRewind.setOnClickListener {
            seekBy(-10000)
        }

        binding.btnMiniForward.setOnClickListener {
            seekBy(10000)
        }

        binding.btnMiniClose.setOnClickListener {
            previewResolveJob?.cancel()
            previewResolveJob = null
            activePreviewVideoId = null
            stopAudio()
            binding.llMiniPlayer.visibility = View.GONE
            dismissPreviewNotification()
        }

        // Seekbar tracking
        binding.sbMiniProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvMiniCurrentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                seekBar?.let {
                    mediaPlayer?.seekTo(it.progress)
                    updatePreviewNotification()
                }
            }
        })

        // Wire status bar notification actions
        setupPreviewController()
    }

    private fun setupPreviewController() {
        PreviewController.onPlayPause = {
            runOnUiThread { togglePlayPause() }
        }
        PreviewController.onRewind = {
            runOnUiThread { seekBy(-10000) }
        }
        PreviewController.onForward = {
            runOnUiThread { seekBy(10000) }
        }
        PreviewController.onClose = {
            runOnUiThread {
                previewResolveJob?.cancel()
                previewResolveJob = null
                stopAudio()
                binding.llMiniPlayer.visibility = View.GONE
                dismissPreviewNotification()
            }
        }
    }

    private fun togglePlayPause() {
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
                startProgressUpdater()
            }
            updatePreviewNotification()
        }
    }

    private fun seekBy(deltaMillis: Int) {
        mediaPlayer?.let { mp ->
            val duration = mp.duration.coerceAtLeast(0)
            val current = mp.currentPosition
            val target = (current + deltaMillis).coerceIn(0, duration)
            mp.seekTo(target)
            binding.sbMiniProgress.progress = target
            binding.tvMiniCurrentTime.text = formatTime(target)
            updatePreviewNotification()
        }
    }

    private fun startProgressUpdater() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isPlayingAudio) {
                mediaPlayer?.let { mp ->
                    if (!isUserSeeking) {
                        try {
                            if (mp.isPlaying) {
                                val current = mp.currentPosition
                                val total = mp.duration
                                binding.sbMiniProgress.progress = current
                                binding.tvMiniCurrentTime.text = formatTime(current)
                                if (total > 0 && binding.sbMiniProgress.max != total) {
                                    binding.sbMiniProgress.max = total
                                    binding.tvMiniTotalTime.text = formatTime(total)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                delay(500)
            }
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
        val requestSequence = ++searchSequence
        searchJob?.cancel()
        videoList.clear()
        adapter.notifyDataSetChanged()
        binding.tvSearchMessage.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        searchJob = lifecycleScope.launch {
            try {
                val results = YouTubeHelper.searchVideos(query)
                if (requestSequence != searchSequence) return@launch
                videoList.clear()
                videoList.addAll(results)
                adapter.notifyDataSetChanged()
                if (results.isEmpty()) {
                    binding.tvSearchMessage.text = "Sonuç bulunamadı. Yeniden denemek için buraya dokunun."
                    binding.tvSearchMessage.visibility = View.VISIBLE
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestSequence == searchSequence) {
                    binding.tvSearchMessage.text = "Arama kaynağı yanıt vermedi. Yeniden denemek için dokunun."
                    binding.tvSearchMessage.visibility = View.VISIBLE
                }
            } finally {
                if (requestSequence == searchSequence) binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun onVideoItemClicked(video: VideoItem) {
        playAudioInApp(video)
    }

    /**
     * Starts audio preview in mini player and shows interactive status bar notification
     */
    private fun playAudioInApp(video: VideoItem) {
        previewResolveJob?.cancel()
        previewResolveJob = null
        previewRetryCount = 0
        stopAudio()
        activePreviewVideoId = video.id
        currentPlayingVideo = video

        binding.llMiniPlayer.visibility = View.VISIBLE
        binding.tvMiniTitle.text = video.title
        binding.tvMiniStatus.text = "Ses akışı aranıyor..."
        binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_play)
        binding.sbMiniProgress.progress = 0
        binding.tvMiniCurrentTime.text = "00:00"
        binding.tvMiniTotalTime.text = "00:00"

        Glide.with(this).load(video.thumbnailUrl).into(binding.ivMiniThumb)

        previewResolveJob = lifecycleScope.launch {
            val cacheFile = File(cacheDir, "preview_${video.id}.m4a")

            // If already cached locally, play instantly (0ms)
            if (cacheFile.exists() && cacheFile.length() > 50000) {
                playLocalAudio(cacheFile.absolutePath, video.id)
                return@launch
            }

            binding.tvMiniStatus.text = "Ses akışı aranıyor..."
            val resolution = YouTubeHelper.resolveStreams(video.id, requiredFormat = "M4A")
            if (activePreviewVideoId != video.id) return@launch
            val audioOpt = resolution.options.firstOrNull { it.format == "M4A" }

            if (audioOpt != null && audioOpt.streamUrl.startsWith("http")) {
                binding.tvMiniStatus.text = "Yükleniyor..."
                playStreamDirect(audioOpt.streamUrl, video, retryAttempt = 0)
            } else {
                binding.tvMiniStatus.text = resolution.errorMessage ?: "Ses akışı bulunamadı"
            }
        }
    }

    private fun playLocalAudio(filePath: String, videoId: String) {
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
                setOnPreparedListener { preparedPlayer ->
                    if (activePreviewVideoId != videoId) {
                        preparedPlayer.release()
                        return@setOnPreparedListener
                    }
                    preparedPlayer.start()
                    isPlayingAudio = true
                    binding.tvMiniStatus.text = "Çalıyor 🎵"
                    binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                    binding.sbMiniProgress.max = preparedPlayer.duration
                    binding.tvMiniTotalTime.text = formatTime(preparedPlayer.duration)
                    startProgressUpdater()
                    updatePreviewNotification()
                }
                setOnCompletionListener {
                    binding.tvMiniStatus.text = "Tamamlandı"
                    binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    isPlayingAudio = false
                    updatePreviewNotification()
                }
                setOnErrorListener { _, _, _ ->
                    binding.tvMiniStatus.text = "Çalma hatası"
                    binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    isPlayingAudio = false
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            binding.tvMiniStatus.text = "Çalma hatası"
        }
    }

    private fun playStreamDirect(streamUrl: String, video: VideoItem, retryAttempt: Int) {
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
                setOnPreparedListener { preparedPlayer ->
                    if (activePreviewVideoId != video.id) {
                        preparedPlayer.release()
                        return@setOnPreparedListener
                    }
                    preparedPlayer.start()
                    isPlayingAudio = true
                    binding.tvMiniStatus.text = "Çalıyor 🎵"
                    binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                    binding.sbMiniProgress.max = preparedPlayer.duration
                    binding.tvMiniTotalTime.text = formatTime(preparedPlayer.duration)
                    startProgressUpdater()
                    updatePreviewNotification()
                }
                setOnInfoListener { _, what, _ ->
                    if (activePreviewVideoId != video.id) return@setOnInfoListener false
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> binding.tvMiniStatus.text = "Yükleniyor..."
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> binding.tvMiniStatus.text = "Çalıyor 🎵"
                        else -> return@setOnInfoListener false
                    }
                    true
                }
                setOnCompletionListener {
                    if (activePreviewVideoId != video.id) return@setOnCompletionListener
                    binding.tvMiniStatus.text = "Tamamlandı"
                    binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    isPlayingAudio = false
                    updatePreviewNotification()
                }
                setOnErrorListener { failedPlayer, _, _ ->
                    runCatching { failedPlayer.release() }
                    if (mediaPlayer === failedPlayer) mediaPlayer = null
                    if (activePreviewVideoId != video.id) return@setOnErrorListener true

                    if (retryAttempt == 0 && previewRetryCount == 0) {
                        previewRetryCount++
                        binding.tvMiniStatus.text = "Alternatif ses kaynağı deneniyor..."
                        previewResolveJob?.cancel()
                        previewResolveJob = lifecycleScope.launch {
                            val failedHost = runCatching { Uri.parse(streamUrl).host }.getOrNull()
                            val refreshed = YouTubeHelper.resolveStreams(
                                video.id,
                                requiredFormat = "M4A",
                                forceRefresh = true,
                                skipInstanceHost = failedHost
                            )
                            if (activePreviewVideoId != video.id) return@launch
                            val alternative = refreshed.options.firstOrNull { it.format == "M4A" }
                            if (alternative?.streamUrl?.startsWith("http") == true) {
                                playStreamDirect(alternative.streamUrl, video, retryAttempt = 1)
                            } else {
                                binding.tvMiniStatus.text = refreshed.errorMessage ?: "Ses şu anda açılamıyor"
                                binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_play)
                                isPlayingAudio = false
                            }
                        }
                    } else {
                        binding.tvMiniStatus.text = "Ses oynatılamadı. Başka bir şarkı veya kaynak deneyin."
                        binding.btnMiniPlayPause.setImageResource(android.R.drawable.ic_media_play)
                        isPlayingAudio = false
                    }
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            binding.tvMiniStatus.text = "Çalma hatası"
        }
    }

    private fun updatePreviewNotification() {
        val video = currentPlayingVideo ?: return
        if (!KZBStubeApp.areNotificationsEnabled()) return

        val context = this
        val notifManager = NotificationManagerCompat.from(context)

        val intentOpen = Intent(context, SearchResultsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            context, 201, intentOpen, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val intentPlayPause = Intent(context, PreviewNotificationReceiver::class.java).apply {
            action = ACTION_PREVIEW_PLAY_PAUSE
        }
        val pendingPlayPause = PendingIntent.getBroadcast(
            context, 202, intentPlayPause, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val intentRewind = Intent(context, PreviewNotificationReceiver::class.java).apply {
            action = ACTION_PREVIEW_REWIND
        }
        val pendingRewind = PendingIntent.getBroadcast(
            context, 203, intentRewind, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val intentForward = Intent(context, PreviewNotificationReceiver::class.java).apply {
            action = ACTION_PREVIEW_FORWARD
        }
        val pendingForward = PendingIntent.getBroadcast(
            context, 204, intentForward, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val intentClose = Intent(context, PreviewNotificationReceiver::class.java).apply {
            action = ACTION_PREVIEW_CLOSE
        }
        val pendingClose = PendingIntent.getBroadcast(
            context, 205, intentClose, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val currentPos = mediaPlayer?.currentPosition ?: 0
        val totalDuration = mediaPlayer?.duration ?: 0
        val timeStr = "${formatTime(currentPos)} / ${formatTime(totalDuration)}"

        val playPauseIcon = if (isPlayingAudio) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlayingAudio) "Duraklat" else "Oynat"

        val builder = NotificationCompat.Builder(context, KZBStubeApp.NOTIF_CHANNEL_PREVIEW)
            .setSmallIcon(R.drawable.ic_kzbs_logo)
            .setContentTitle(video.title)
            .setContentText("${video.author} • $timeStr")
            .setSubText("KZBStube Önizleme")
            .setContentIntent(pendingOpen)
            .setOngoing(isPlayingAudio)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_rew, "-10s", pendingRewind)
            .addAction(playPauseIcon, playPauseTitle, pendingPlayPause)
            .addAction(android.R.drawable.ic_media_ff, "+10s", pendingForward)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", pendingClose)

        if (totalDuration > 0) {
            builder.setProgress(totalDuration, currentPos, false)
        }

        try {
            notifManager.notify(NOTIF_ID_PREVIEW, builder.build())
        } catch (_: SecurityException) {}
    }

    private fun dismissPreviewNotification() {
        try {
            val notifManager = NotificationManagerCompat.from(this)
            notifManager.cancel(NOTIF_ID_PREVIEW)
        } catch (_: Exception) {}
    }

    private fun formatTime(millis: Int): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun stopAudio() {
        progressJob?.cancel()
        val player = mediaPlayer
        mediaPlayer = null
        isPlayingAudio = false
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
    }

    private fun showDownloadOptionsDialog(video: VideoItem) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_download_options, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvOptionTitle)
        val pbLoading = view.findViewById<ProgressBar>(R.id.pbOptionsLoading)
        val tvLabel = view.findViewById<TextView>(R.id.tvOptionsLabel)
        val btnRetry = view.findViewById<Button>(R.id.btnRetryOptions)
        val rgOptions = view.findViewById<RadioGroup>(R.id.rgRealOptions)
        val btnStart = view.findViewById<Button>(R.id.btnStartDownload)

        tvTitle.text = video.title

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        var resolveJob: Job? = null
        fun loadOptions(forceRefresh: Boolean = false) {
            resolveJob?.cancel()
            pbLoading.visibility = View.VISIBLE
            tvLabel.visibility = View.GONE
            rgOptions.visibility = View.GONE
            btnRetry.visibility = View.GONE
            btnStart.visibility = View.GONE

            resolveJob = lifecycleScope.launch {
                val resolution = YouTubeHelper.resolveStreams(
                    video.id,
                    requiredFormat = currentMode,
                    forceRefresh = forceRefresh
                )
                if (!dialog.isShowing || isFinishing) return@launch

                val filteredStreams = resolution.options.filter { it.format == currentMode }

                pbLoading.visibility = View.GONE
                tvLabel.visibility = View.VISIBLE
                if (filteredStreams.isEmpty()) {
                    tvLabel.text = if (resolution.errorMessage != null) {
                        resolution.errorMessage
                    } else {
                        "Bu biçim için indirme akışı bulunamadı."
                    }
                    rgOptions.visibility = View.GONE
                    btnStart.visibility = View.GONE
                    btnRetry.visibility = View.VISIBLE
                    btnRetry.setOnClickListener { loadOptions(forceRefresh = true) }
                    return@launch
                }

                tvLabel.text = "İndirilecek kaliteyi seçin:"
                btnRetry.visibility = View.GONE
                btnStart.visibility = View.VISIBLE
                rgOptions.visibility = View.VISIBLE
                rgOptions.removeAllViews()
                filteredStreams.forEachIndexed { index, opt ->
                    val rb = RadioButton(this@SearchResultsActivity).apply {
                        id = index + 100
                        text = if (opt.format == "MP3") {
                            "${opt.quality}  •  cihaza göre dönüştürülür"
                        } else {
                            "${opt.quality}  •  ${opt.sizeFormatted}"
                        }
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
        }

        dialog.setOnDismissListener { resolveJob?.cancel() }
        dialog.show()
        loadOptions()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewResolveJob?.cancel()
        searchJob?.cancel()
        activePreviewVideoId = null
        stopAudio()
        dismissPreviewNotification()
        PreviewController.onPlayPause = null
        PreviewController.onRewind = null
        PreviewController.onForward = null
        PreviewController.onClose = null
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
