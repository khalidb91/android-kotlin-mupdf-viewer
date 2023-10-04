package com.khal.mupdf.viewer.app.ui.outline

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.artifex.mupdf.viewer.model.OutlineItem
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.khal.mupdf.viewer.app.databinding.FragmentOulineBinding


open class OutlineFragment(
    items: List<OutlineItem>,
    currentPage: Int = -1,
    private val onItemSelected: ((page:Int) -> Unit?)? = null,
) : BottomSheetDialogFragment() {

    private var _binding: FragmentOulineBinding? = null
    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!


    private var adapter = OutlineAdapter(
        items = items,
        currentPage = currentPage,
        onItemSelected = { page ->
            onItemSelected?.invoke(page)
            dismiss()
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        _binding = FragmentOulineBinding.inflate(inflater)
        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.list.adapter = this.adapter
    }


    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    companion object {
        const val TAG: String = "OutlineFragment"
    }

}