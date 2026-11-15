package com.kzbs.kzbstube.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.kzbs.kzbstube.databinding.FragmentSettingsBinding
import com.kzbs.kzbstube.update.UpdateChecker

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

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

        val options = arrayOf("1", "2", "3", "5")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options)
        binding.spSimultaneous.adapter = adapter
        binding.spSimultaneous.setSelection(2) // 3

        binding.llCheckUpdates.setOnClickListener {
            Toast.makeText(requireContext(), "Güncellemeler kontrol ediliyor...", Toast.LENGTH_SHORT).show()
            UpdateChecker(requireActivity()).checkForUpdates(isManual = true)
        }

        binding.llAbout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("KZBStube v1.0.0")
                .setMessage("YouTube Video ve Müzik İndirici.\n\nGitHub: kzbs4652/KZBStube\nGeliştirici: KZBS")
                .setPositiveButton("Tamam", null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
