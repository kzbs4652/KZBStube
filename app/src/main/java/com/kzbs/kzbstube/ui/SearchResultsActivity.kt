package com.kzbs.kzbstube.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val query = intent.getStringExtra("QUERY") ?: "mavi espor"
        binding.etTopSearch.setText(query)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener { binding.etTopSearch.setText("") }

        adapter = VideoAdapter(videoList) { video ->
            showDownloadOptionsDialog(video)
        }

        binding.rvVideos.layoutManager = LinearLayoutManager(this)
        binding.rvVideos.adapter = adapter

        performSearch(query)

        binding.etTopSearch.setOnEditorActionListener { _, _, _ ->
            val newQuery = binding.etTopSearch.text.toString().trim()
            if (newQuery.isNotEmpty()) performSearch(newQuery)
            true
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

    private fun showDownloadOptionsDialog(video: VideoItem) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_download_options, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvOptionTitle)
        val rgFormat = view.findViewById<RadioGroup>(R.id.rgFormat)
        val rgQuality = view.findViewById<RadioGroup>(R.id.rgQuality)
        val btnStart = view.findViewById<Button>(R.id.btnStartDownload)

        tvTitle.text = video.title

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        btnStart.setOnClickListener {
            val format = if (rgFormat.checkedRadioButtonId == R.id.rbAudio) "MP3" else "MP4"
            val quality = when (rgQuality.checkedRadioButtonId) {
                R.id.q1080 -> "1080p FHD"
                R.id.q720 -> "720p HD"
                R.id.q480 -> "480p"
                else -> "360p"
            }

            DownloadManager.startDownload(video, format, quality, "")
            dialog.dismiss()

            val intent = Intent(this, ActiveDownloadsActivity::class.java).apply {
                putExtra("TITLE", video.title)
                putExtra("FORMAT", "$format • $quality")
            }
            startActivity(intent)
        }

        dialog.show()
    }
}

class VideoAdapter(
    private val items: List<VideoItem>,
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
        holder.itemView.setOnClickListener { onDownloadClick(item) }
    }

    override fun getItemCount() = items.size
}
