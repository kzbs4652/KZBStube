package com.kzbs.kzbstube.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.kzbs.kzbstube.databinding.ActivityActiveDownloadsBinding
import com.kzbs.kzbstube.downloader.DownloadManager

class ActiveDownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActiveDownloadsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActiveDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackActive.setOnClickListener { finish() }

        val title = intent.getStringExtra("TITLE") ?: "Video İndiriliyor..."
        val format = intent.getStringExtra("FORMAT") ?: "MP4 • 720p"

        binding.tvActiveTitle.text = title
        binding.tvActiveFormat.text = format

        DownloadManager.onProgressUpdate = {
            runOnUiThread {
                val current = DownloadManager.activeDownloads.firstOrNull()
                if (current != null) {
                    binding.pbActive.progress = current.progressPercent
                    binding.tvActivePercent.text = "%${current.progressPercent}"
                    binding.tvActiveMetrics.text = "${current.sizeFormatted} • ${current.speedFormatted}"
                    binding.btnPause.text = if (current.isPaused) "Devam Et" else "Duraklat"
                } else {
                    finish()
                }
            }
        }

        binding.btnPause.setOnClickListener {
            val current = DownloadManager.activeDownloads.firstOrNull() ?: return@setOnClickListener
            if (current.isPaused) {
                DownloadManager.resumeDownload(current.id)
            } else {
                DownloadManager.pauseDownload(current.id)
            }
        }

        binding.btnCancel.setOnClickListener {
            val current = DownloadManager.activeDownloads.firstOrNull() ?: return@setOnClickListener
            DownloadManager.cancelDownload(current.id)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadManager.onProgressUpdate = null
    }
}
