package com.kzbs.kzbstube.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.kzbs.kzbstube.databinding.FragmentSettingsBinding
import com.kzbs.kzbstube.downloader.DownloadManager
import com.kzbs.kzbstube.update.UpdateChecker
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = uri.path ?: ""
            // Save selected directory
            DownloadManager.setCustomDownloadDirectory(path)
            updateFolderText()
            Toast.makeText(requireContext(), "İndirme klasörü güncellendi.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateFolderText()

        val options = arrayOf("1", "2", "3", "5")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options)
        binding.spSimultaneous.adapter = adapter
        binding.spSimultaneous.setSelection(2) // 3

        // Allow changing download directory
        binding.llFolder.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("İndirme Klasörü")
                .setMessage("Mevcut Konum:\n${DownloadManager.getDownloadDirectory().absolutePath}\n\nFarklı bir klasör seçmek ister misiniz?")
                .setPositiveButton("Klasör Seç") { _, _ ->
                    folderPickerLauncher.launch(null)
                }
                .setNeutralButton("Varsayılana Sıfırla (Download)") { _, _ ->
                    DownloadManager.setCustomDownloadDirectory(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        ).absolutePath
                    )
                    updateFolderText()
                    Toast.makeText(requireContext(), "Download klasörüne sıfırlandı.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("İptal", null)
                .show()
        }

        binding.llCheckUpdates.setOnClickListener {
            Toast.makeText(requireContext(), "Güncellemeler kontrol ediliyor...", Toast.LENGTH_SHORT).show()
            UpdateChecker(requireActivity()).checkForUpdates(isManual = true)
        }

        // Hakkında - GitHub linki tamamen kaldırıldı!
        binding.llAbout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("KZBStube v1.0.2")
                .setMessage("YouTube Video ve Müzik İndirici\n\nKişisel kullanım için geliştirilmiştir.")
                .setPositiveButton("Tamam", null)
                .show()
        }
    }

    private fun updateFolderText() {
        val dir = DownloadManager.getDownloadDirectory()
        // Find the subtitle TextView inside llFolder
        val tvSub = binding.llFolder.getChildAt(1) as? android.widget.TextView
        tvSub?.text = dir.absolutePath
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
