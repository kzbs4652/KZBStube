package com.kzbs.kzbstube.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kzbs.kzbstube.R
import com.kzbs.kzbstube.databinding.FragmentDownloadsBinding
import com.kzbs.kzbstube.databinding.ItemDownloadBinding
import com.kzbs.kzbstube.downloader.DownloadManager
import com.kzbs.kzbstube.model.DownloadItem
import java.io.File

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!
    private var activeFilter = "MP4"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvDownloads.layoutManager = LinearLayoutManager(requireContext())

        updateUI()

        binding.btnFilterVideos.setOnClickListener {
            activeFilter = "MP4"
            binding.btnFilterVideos.setBackgroundResource(R.drawable.bg_red_button)
            binding.btnFilterAudios.setBackgroundResource(R.drawable.bg_search_box)
            updateUI()
        }

        binding.btnFilterAudios.setOnClickListener {
            activeFilter = "MP3"
            binding.btnFilterAudios.setBackgroundResource(R.drawable.bg_red_button)
            binding.btnFilterVideos.setBackgroundResource(R.drawable.bg_search_box)
            updateUI()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val all = DownloadManager.completedDownloads
        val videos = all.filter { it.format == "MP4" }
        val audios = all.filter { it.format == "MP3" }

        binding.btnFilterVideos.text = "Videolar (${videos.size})"
        binding.btnFilterAudios.text = "Müzikler (${audios.size})"

        val currentList = if (activeFilter == "MP4") videos else audios
        binding.rvDownloads.adapter = DownloadsAdapter(currentList) { item ->
            openFile(item.filePath)
        }
    }

    private fun openFile(path: String) {
        val file = File(path)
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (path.endsWith(".mp3")) "audio/*" else "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class DownloadsAdapter(
    private val items: List<DownloadItem>,
    private val onItemClick: (DownloadItem) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvDownloadTitle.text = if (item.format == "MP4") "${item.title}.mp4" else "${item.title}.mp3"
        holder.binding.tvDownloadSize.text = "${String.format("%.1f", item.totalBytes / (1024.0 * 1024.0))} MB • ${item.quality}"

        if (item.format == "MP4") {
            holder.binding.ivMusicIcon.visibility = View.GONE
            holder.binding.ivDownloadThumb.visibility = View.VISIBLE
            Glide.with(holder.itemView.context).load(item.thumbnailUrl).into(holder.binding.ivDownloadThumb)
        } else {
            holder.binding.ivMusicIcon.visibility = View.VISIBLE
            holder.binding.ivDownloadThumb.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size
}
