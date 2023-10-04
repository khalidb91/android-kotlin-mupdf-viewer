package com.khal.mupdf.viewer.app.ui.home

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.artifex.mupdf.viewer.core.PickFileContract
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.khal.mupdf.viewer.app.R
import com.khal.mupdf.viewer.app.databinding.FragmentHomeBinding
import com.khal.mupdf.viewer.app.ui.document.DocumentFragment


class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!! // Property only valid between onCreateView & onDestroy.


    private val pickFileLauncher = registerForActivityResult(PickFileContract()) { uri: Uri? ->
        if (uri != null) {
            findNavController().navigate(R.id.documentFragment, DocumentFragment.getBundle(uri))
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.app_name))
                .setMessage(getString(R.string.cannot_open_document_Reason, "No document uri to open"))
                .setPositiveButton(getString(R.string.dismiss)) { dialog , _ -> dialog.dismiss() }
                .show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        _binding = FragmentHomeBinding.inflate(inflater)
        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.button.setOnClickListener {
            pickFile()
        }

    }

    private fun pickFile() {
        pickFileLauncher.launch(null)
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }


}