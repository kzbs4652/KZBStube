package com.kzbs.kzbstube.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.kzbs.kzbstube.R
import com.kzbs.kzbstube.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var selectedMode = "MP4" // "MP4" or "MP3"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Mode Switcher
        binding.btnModeVideo.setOnClickListener {
            selectedMode = "MP4"
            binding.btnModeVideo.setBackgroundResource(R.drawable.bg_red_button)
            binding.btnModeVideo.setTextColor(resources.getColor(R.color.text_primary, null))
            binding.btnModeAudio.setBackgroundResource(R.drawable.bg_search_box)
            binding.btnModeAudio.setTextColor(resources.getColor(R.color.text_secondary, null))
            binding.etSearch.hint = "Video ismi yazın veya link yapıştırın..."
        }

        binding.btnModeAudio.setOnClickListener {
            selectedMode = "MP3"
            binding.btnModeAudio.setBackgroundResource(R.drawable.bg_red_button)
            binding.btnModeAudio.setTextColor(resources.getColor(R.color.text_primary, null))
            binding.btnModeVideo.setBackgroundResource(R.drawable.bg_search_box)
            binding.btnModeVideo.setTextColor(resources.getColor(R.color.text_secondary, null))
            binding.etSearch.hint = "Şarkı adı veya video bağlantısı yazın..."
        }

        binding.btnSearch.setOnClickListener {
            val query = binding.etSearch.text.toString().trim()
            if (query.isNotEmpty()) {
                val intent = Intent(requireContext(), SearchResultsActivity::class.java).apply {
                    putExtra("QUERY", query)
                    putExtra("MODE", selectedMode)
                }
                startActivity(intent)
            }
        }

        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            binding.btnSearch.performClick()
            true
        }

        checkClipboard()
    }

    private fun checkClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.contains("youtu.be") || text.contains("youtube.com")) {
                binding.tvQuickLink.visibility = View.VISIBLE
                binding.tvQuickLink.setOnClickListener {
                    binding.etSearch.setText(text)
                    binding.btnSearch.performClick()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
