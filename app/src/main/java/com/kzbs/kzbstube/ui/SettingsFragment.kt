package com.kzbs.kzbstube.ui

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.kzbs.kzbstube.BuildConfig
import com.kzbs.kzbstube.KZBStubeApp
import com.kzbs.kzbstube.databinding.FragmentSettingsBinding
import com.kzbs.kzbstube.downloader.DownloadManager
import com.kzbs.kzbstube.update.UpdateChecker

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                requireContext().contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {}
            DownloadManager.setCustomDownloadDirectory(uri.toString())
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
        updateThemeText()

        // Simultaneous downloads count
        val options = arrayOf("1", "2", "3", "5")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options)
        binding.spSimultaneous.adapter = adapter
        binding.spSimultaneous.setSelection(2) // 3

        // Notifications switch
        binding.swNotifications.isChecked = KZBStubeApp.areNotificationsEnabled()
        binding.swNotifications.setOnCheckedChangeListener { _, isChecked ->
            KZBStubeApp.setNotificationsEnabled(isChecked)
            val msg = if (isChecked) "İndirme bildirimleri açıldı." else "İndirme bildirimleri kapatıldı."
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // 3-way Theme Selection (Auto, Dark, Light)
        binding.llTheme.setOnClickListener {
            val themeOptions = arrayOf("Otomatik (Sistem Varsayılanı)", "Koyu Mod", "Açık Mod")
            val currentMode = KZBStubeApp.getSavedThemeMode()

            AlertDialog.Builder(requireContext())
                .setTitle("Uygulama Teması")
                .setSingleChoiceItems(themeOptions, currentMode) { dialog, which ->
                    KZBStubeApp.saveThemeMode(which)
                    updateThemeText()
                    dialog.dismiss()
                }
                .setNegativeButton("İptal", null)
                .show()
        }

        // Allow changing download directory
        binding.llFolder.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("İndirme Klasörü")
                .setMessage("Mevcut Konum:\n${DownloadManager.getDownloadDirectoryLabel()}\n\nFarklı bir klasör seçmek ister misiniz?")
                .setPositiveButton("Klasör Seç") { _, _ ->
                    folderPickerLauncher.launch(null)
                }
                .setNeutralButton("Varsayılana Sıfırla (Download)") { _, _ ->
                    DownloadManager.clearCustomDownloadDirectory()
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

        // Clean "Hakkında" Section
        binding.tvAboutSubtitle.text = "KZBStube"
        binding.llAbout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("KZBStube")
                .setMessage("Uygulama: KZBStube\nSürüm: v${BuildConfig.VERSION_NAME}\nGeliştirici: KZBS")
                .setPositiveButton("Tamam", null)
                .show()
        }
    }

    private fun updateFolderText() {
        binding.tvFolderSubtitle.text = DownloadManager.getDownloadDirectoryLabel()
    }

    private fun updateThemeText() {
        val mode = KZBStubeApp.getSavedThemeMode()
        binding.tvThemeSubtitle.text = when (mode) {
            1 -> "Koyu Mod"
            2 -> "Açık Mod"
            else -> "Otomatik (Sistem Varsayılanı)"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
