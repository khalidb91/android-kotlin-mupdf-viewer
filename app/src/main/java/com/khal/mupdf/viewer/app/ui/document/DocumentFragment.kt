package com.khal.mupdf.viewer.app.ui.document

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity.INPUT_METHOD_SERVICE
import androidx.appcompat.app.AppCompatActivity.RESULT_FIRST_USER
import androidx.appcompat.widget.SearchView
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.artifex.mupdf.viewer.app.R
import com.artifex.mupdf.viewer.app.databinding.FragmentDocumentBinding
import com.artifex.mupdf.viewer.model.OutlineItem
import com.artifex.mupdf.viewer.model.SearchDirection
import com.artifex.mupdf.viewer.model.SearchResult
import com.artifex.mupdf.viewer.view.PageView
import com.artifex.mupdf.viewer.view.ReaderView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.khal.mupdf.viewer.app.ui.document.DocumentViewModel.UiState
import com.khal.mupdf.viewer.app.ui.outline.OutlineFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.artifex.mupdf.viewer.R as libR


class DocumentFragment : Fragment() {

    private val viewModel: DocumentViewModel by viewModels()

    private var _binding: FragmentDocumentBinding? = null
    private val binding get() = _binding!! // Only valid between onCreateView and onDestroy.

    private var docView: ReaderView? = null


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {

        _binding = FragmentDocumentBinding.inflate(inflater)

        val uri = arguments?.getParcelable<Uri?>(ARG_URI)

        uri ?: findNavController().navigateUp()

        viewModel.init(uri)

        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launch {
            viewModel.uiState.collectLatest { value ->
                when (value) {

                    is UiState.OnProgress -> {
                        binding.progress.isVisible = value.isLoading
                    }

                    is UiState.OnError -> {
                        onError(value.error)
                    }

                    is UiState.OnLinkHighlight -> {
                        setLinkHighlight(value.linkHighlight)
                    }

                    is UiState.OnPageChange -> {
                        binding.pageNumber.text = value.pageNumber
                        binding.pageSlider.max = value.sliderMax
                        binding.pageSlider.progress = value.sliderProgress
                    }

                    is UiState.OnRequestPassword -> {
                        requestPassword()
                    }

                    is UiState.OnRelayoutDocument -> {
                        relayoutDocument(
                            layoutEM = value.layoutEm,
                            layoutH = value.layoutH,
                            layoutW = value.layoutW
                        )
                    }

                    is UiState.OverlayVisibility -> {
                        setOverlayVisibility(value.isVisible)
                    }

                    is UiState.SearchMode -> {
                        if (value.isEnabled) {
                            //Focus on EditTextWidget
                            binding.searchView.requestFocus()
                            showKeyboard()
                            binding.searchBar.isVisible = true
                        } else {
                            hideKeyboard()
                            binding.searchBar.isVisible = false
                            docView?.resetupChildren()
                        }
                    }

                    is UiState.PageChange -> {
                        docView?.pushHistory()
                        docView?.displayedViewIndex = value.index
                    }

                    is UiState.OnTextFound -> {
                        onTextFound(value.result)
                    }

                    is UiState.OnShowOutline -> {
                        showOutline(value.outlines)
                    }

                    null -> {
                        // Do nothing
                    }
                }
            }
        }

        binding.toolbar.title = viewModel.getDocTitle()
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            return@setOnMenuItemClickListener when (menuItem.itemId) {

                R.id.menu_search -> {
                    viewModel.setSearchMode(true)
                    true
                }

                R.id.menu_link -> {
                    viewModel.toggleLinkHighlight()
                    true
                }

                R.id.menu_layout -> {
                    true
                }


                R.id.menu_outline -> {
                    viewModel.onOutlineClick()
                    true
                }

                else -> {
                    viewModel.updateLayout(
                        newLayoutEM = when (menuItem.itemId) {
                            R.id.action_layout_6pt -> 6
                            R.id.action_layout_7pt -> 7
                            R.id.action_layout_8pt -> 8
                            R.id.action_layout_9pt -> 9
                            R.id.action_layout_10pt -> 10
                            R.id.action_layout_11pt -> 11
                            R.id.action_layout_12pt -> 12
                            R.id.action_layout_13pt -> 13
                            R.id.action_layout_14pt -> 14
                            R.id.action_layout_15pt -> 15
                            R.id.action_layout_16pt -> 16
                            else -> 10
                        }
                    )
                    true
                }

            }
        }

        binding.pageSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                viewModel.changePage(seekBar.progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                viewModel.onProgressChange(progress)
            }

        })

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {

            override fun onQueryTextSubmit(query: String): Boolean {
                // Start search for query
                search()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                val haveText = newText.isNullOrEmpty().not()
                setButtonEnabled(binding.searchBack, haveText)
                setButtonEnabled(binding.searchForward, haveText)
                // Remove any previous search results
                if (SearchResult.get() != null && binding.searchView.query.toString() != SearchResult.get()?.txt) {
                    SearchResult.set(null)
                    (docView as ReaderView).resetupChildren()
                }
                return false
            }
        })

        // Search invoking buttons are disabled while there is no text specified
        binding.searchBack.isEnabled = false
        binding.searchForward.isEnabled = false
        binding.searchBack.setOnClickListener { search(SearchDirection.Backward) }
        binding.searchForward.setOnClickListener { search(SearchDirection.Forward) }
        binding.searchClose.setOnClickListener { viewModel.setSearchMode(false) }

        val menuItemLayout: MenuItem = binding.toolbar.menu.findItem(R.id.menu_layout)
        menuItemLayout.isEnabled = viewModel.isReflowable

        addReaderView()

    }


    private fun setOverlayVisibility(isVisible: Boolean) {
        if (isVisible) {
            // Update page number text and slider
            val index = docView?.displayedViewIndex ?: 0
            viewModel.moveToPage(index)
            binding.toolbar.isVisible = true
            binding.progressContainer.isVisible = true
        } else {
            hideKeyboard()
            binding.toolbar.isVisible = false
            binding.progressContainer.isVisible = false
        }
    }

    private fun onTextFound(result: SearchResult) {
        docView?.displayedViewIndex = result.pageNumber
        docView?.resetupChildren()
    }

    private fun onError(errorMsg: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_name)
            .setMessage(errorMsg)
            .setPositiveButton(R.string.dismiss) { dialog, _ -> dialog.dismiss() }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun requestPassword() {

        val passwordView: View = LayoutInflater.from(context)
            .inflate(R.layout.text_inpu_password, view as ViewGroup?, false)
        val input = passwordView.findViewById<View>(R.id.input) as EditText

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.enter_password))
            .setView(passwordView)
            .setPositiveButton(getString(libR.string.okay)) { _, _ ->

                val pwd = input.toString()

                if (viewModel.authenticate(pwd)) {
                    addReaderView()
                } else {
                    requestPassword()
                }

            }
            .setNegativeButton(getString(libR.string.cancel)) { _, _ ->
                findNavController().navigateUp()
            }
            .show()

    }

    private fun relayoutDocument(layoutEM: Int, layoutW: Int, layoutH: Int) {
        docView?.history?.clear()
        docView?.refresh()
        docView?.displayedViewIndex = viewModel.getViewIndex(
            currentPage = docView?.current ?: -1,
            layoutEm = layoutEM,
            layoutW = layoutW,
            layoutH = layoutH
        )
    }

    private fun addReaderView() {

        // First create the document view
        docView = object : ReaderView(requireContext()) {

            override fun onMoveToChild(i: Int) {
                viewModel.moveToPage(i)
                super.onMoveToChild(i)
            }

            override fun onTapMainDocArea() {
                viewModel.toggleOverlay()
            }

            override fun onDocMotion() {

            }

            public override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                if (viewModel.isReflowable) {
                    viewModel.updateSize(w, h)
                } else {
                    refresh()
                }
            }

        }

        docView?.adapter = viewModel.getPagesAdapter()

        binding.viewerContainer.removeAllViews()
        binding.viewerContainer.addView(docView)
        binding.viewerContainer.invalidate()

    }

    private fun showOutline(items: List<OutlineItem>) {
        OutlineFragment(
            items = items,
            currentPage = docView?.displayedViewIndex ?: 0,
            onItemSelected = { page ->
                if (page >= RESULT_FIRST_USER && docView != null) {
                    docView?.pushHistory()
                    docView?.displayedViewIndex = page - RESULT_FIRST_USER
                }
            }
        ).show(childFragmentManager, OutlineFragment.TAG)
    }

    override fun onDestroy() {

        docView?.applyToChildren(object : ReaderView.ViewMapper() {
            override fun applyToView(view: View?) {
                (view as PageView?)?.releaseBitmaps()
            }
        })

        _binding = null

        super.onDestroy()
    }

    private fun setButtonEnabled(button: ImageView?, enabled: Boolean) {
        button?.isEnabled = enabled
        val color = when {
            enabled -> ResourcesCompat.getColor(this.resources, R.color.color_secondary, null)
            else -> ResourcesCompat.getColor(this.resources, R.color.color_primary, null)
        }
        button?.setColorFilter(color)
    }

    private fun setLinkHighlight(highlight: Boolean) {
        binding.toolbar.menu.findItem(R.id.menu_link).setIcon(
            when {
                highlight -> R.drawable.round_link_off_24
                else -> R.drawable.round_link_24
            }
        )
        docView?.setLinksEnabled(highlight)
    }

    private fun showKeyboard() {
        inputMethodManager()?.showSoftInput(binding.searchView, 0)
    }

    private fun hideKeyboard() {
        inputMethodManager()?.hideSoftInputFromWindow(binding.searchView.windowToken, 0)
    }

    private fun inputMethodManager(): InputMethodManager? {
        return activity?.getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
    }

    private fun search(searchDirection: SearchDirection = SearchDirection.Forward) {
        hideKeyboard()
        val query = binding.searchView.query.toString()
        val displayPage = docView?.displayedViewIndex ?: 0
        viewModel.search(query, searchDirection, displayPage)
    }

    companion object {

        private const val ARG_URI = "ARG_URI"

        fun getBundle(uri: Uri): Bundle {
            return bundleOf(ARG_URI to uri)
        }

    }

}