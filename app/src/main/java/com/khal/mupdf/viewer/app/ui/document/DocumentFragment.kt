package com.khal.mupdf.viewer.app.ui.document

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.artifex.mupdf.viewer.app.R
import com.artifex.mupdf.viewer.app.databinding.FragmentDocumentBinding
import com.artifex.mupdf.viewer.core.MuPDFCore
import com.artifex.mupdf.viewer.core.SearchTask
import com.artifex.mupdf.viewer.model.OutlineItem
import com.artifex.mupdf.viewer.model.SearchResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.khal.mupdf.viewer.app.ui.outline.OutlineFragment
import com.khal.mupdf.viewer.app.ui.view.PageView
import com.khal.mupdf.viewer.app.ui.view.ReaderView
import java.util.Locale
import kotlin.math.max
import com.artifex.mupdf.viewer.R as libR

class DocumentFragment : Fragment() {

    val viewModel: DocumentViewModel by viewModels()


    /* The core rendering instance */
    internal enum class TopBarMode {
        Main, Search, More
    }

    private var _binding: FragmentDocumentBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private var muPDFCore: MuPDFCore? = null
    private var docTitle: String? = null
    private var docKey: String? = null

    private var docView: ReaderView? = null
    private var buttonsVisible = false
    private var pageSliderRes = 0
    private var topBarMode = TopBarMode.Main

    private var searchTask: SearchTask? = null

    private var alertBuilder: AlertDialog.Builder? = null

    private var linkHighlight = false
    private var flatOutline: ArrayList<OutlineItem>? = null
    private var returnToLibraryActivity = false

    private var displayDPI = 0
    private var layoutEM = 10
    private var layoutW = 312
    private var layoutH = 504

    private var layoutButton: View? = null

    private var layoutPopupMenu: PopupMenu? = null


    /**
     * Called when the activity is first created.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        displayDPI = this.resources.displayMetrics.densityDpi

        val uri = arguments?.getParcelable<Uri?>(ARG_URI)

        uri ?: findNavController().navigateUp()

        muPDFCore = viewModel.getMuPdf(uri!!)

        if (muPDFCore?.needsPassword() == true) {
            requestPassword(savedInstanceState)
            return
        }

        if (muPDFCore?.countPages() == 0) {
            muPDFCore = null
        }

        if (muPDFCore == null) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.app_name)
                .setMessage(R.string.cannot_open_document)
                .setPositiveButton(getString(R.string.dismiss)) { dialog, _ -> dialog.dismiss() }
                .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
                .show()
            return
        }

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        _binding = FragmentDocumentBinding.inflate(inflater)
        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        createUI(savedInstanceState)
    }

    private fun requestPassword(savedInstanceState: Bundle?) {

        val passwordView = EditText(requireContext())
        passwordView.inputType = EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
        passwordView.transformationMethod = PasswordTransformationMethod()

        val alert = alertBuilder?.create()
        alert?.setTitle(R.string.app_name)
        alert?.setMessage(getString(R.string.enter_password))
        alert?.setView(passwordView)
        alert?.setButton(AlertDialog.BUTTON_POSITIVE, getString(libR.string.okay)) { _, _ ->

            if (muPDFCore?.authenticatePassword(passwordView.text.toString()) == true) {
                createUI(savedInstanceState)
            } else {
                requestPassword(savedInstanceState)
            }

        }
        alert?.setButton(
            AlertDialog.BUTTON_NEGATIVE,
            getString(libR.string.cancel)
        ) { dialog, _ -> dialog.dismiss() }
        alert?.show()

    }

    fun relayoutDocument() {
        val loc = muPDFCore?.layout(docView?.current ?: -1, layoutW, layoutH, layoutEM)
        flatOutline = null
        docView?.history?.clear()
        docView?.refresh()
        docView?.displayedViewIndex = loc ?: 0
    }

    private fun createUI(savedInstanceState: Bundle?) {

        if (muPDFCore == null) return

        // Now create the UI.
        // First create the document view
        docView = object : ReaderView(requireContext()) {

            override fun onMoveToChild(i: Int) {

                muPDFCore ?: return

                val pageNumber =
                    String.format(Locale.ROOT, "%d / %d", i + 1, muPDFCore?.countPages())
                binding.pageNumber.text = pageNumber

                binding.pageSlider.max = ((muPDFCore?.countPages() ?: 0) - 1) * pageSliderRes

                binding.pageSlider.progress = (i * pageSliderRes)

                super.onMoveToChild(i)
            }

            override fun onTapMainDocArea() {
                if (buttonsVisible && topBarMode == TopBarMode.Main) {
                    hideButtons()
                } else {
                    showButtons()
                }
            }

            override fun onDocMotion() {
                hideButtons()
            }

            public override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                if (muPDFCore?.isReflowable == true) {
                    layoutW = w * 72 / displayDPI
                    layoutH = h * 72 / displayDPI
                    relayoutDocument()
                } else {
                    refresh()
                }
            }

        }

        docView?.adapter = PageAdapter(requireContext(), muPDFCore!!)

        searchTask = object : SearchTask(requireContext(), muPDFCore) {

            override fun onTextFound(result: SearchResult) {
                SearchResult.set(result)
                // Ask the ReaderView to move to the resulting page
                docView?.displayedViewIndex = result.pageNumber
                // Make the ReaderView act on the change to SearchTaskResult
                // via overridden onChildSetup method.
                docView?.resetupChildren()
            }

        }

        // Make the buttons overlay, and store all its
        // controls in variables
        makeButtonsView()

        // Set up the page slider
        val smax = max((muPDFCore?.countPages() ?: 0) - 1, 1)
        pageSliderRes = (10 + smax - 1) / smax * 2

        // Set the file-name text
        binding.docNameText.text = muPDFCore?.title ?: this.docTitle

        // Activate the seekbar
        binding.pageSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                docView?.pushHistory()
                docView?.displayedViewIndex = (seekBar.progress + pageSliderRes / 2) / pageSliderRes
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onProgressChanged(
                seekBar: SeekBar, progress: Int,
                fromUser: Boolean,
            ) {
                updatePageNumView((progress + pageSliderRes / 2) / pageSliderRes)
            }

        })

        // Activate the search-preparing button
        binding.searchButton.setOnClickListener { searchModeOn() }
        binding.searchClose.setOnClickListener { searchModeOff() }

        // Search invoking buttons are disabled while there is no text specified
        binding.searchBack.isEnabled = false
        binding.searchForward.isEnabled = false
        binding.searchBack.setColorFilter(Color.argb(255, 128, 128, 128))
        binding.searchForward.setColorFilter(Color.argb(255, 128, 128, 128))

        // React to interaction with the text widget
        binding.searchText.addTextChangedListener(object : TextWatcher {

            override fun afterTextChanged(s: Editable) {
                val haveText = s.toString().isNotEmpty()
                setButtonEnabled(binding.searchBack, haveText)
                setButtonEnabled(binding.searchForward, haveText)

                // Remove any previous search results
                if (SearchResult.get() != null && binding.searchText.text.toString() != SearchResult.get()?.txt) {
                    SearchResult.set(null)
                    (docView as ReaderView).resetupChildren()
                }
            }

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            }

        })

        //React to Done button on keyboard
        binding.searchText.setOnEditorActionListener { _: TextView?, actionId: Int, event: KeyEvent? ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                search(1)
            }
            false
        }

        binding.searchText.setOnKeyListener { _: View?, keyCode: Int, event: KeyEvent ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                search(1)
            }
            false
        }

        // Activate search invoking buttons

        binding.searchBack.setOnClickListener {
            search(-1)
        }

        binding.searchForward.setOnClickListener {
            search(1)
        }

        binding.linkButton.setOnClickListener {
            setLinkHighlight(!linkHighlight)
        }

        if (muPDFCore?.isReflowable == true) {

            layoutButton?.isVisible = true

            layoutPopupMenu = PopupMenu(requireContext(), layoutButton)
            layoutPopupMenu?.menuInflater?.inflate(R.menu.layout_menu, layoutPopupMenu?.menu)

            layoutPopupMenu?.setOnMenuItemClickListener { item: MenuItem ->

                val oldLayoutEM = layoutEM.toFloat()

                layoutEM = when (item.itemId) {
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

                if (oldLayoutEM != layoutEM.toFloat()) {
                    relayoutDocument()
                }

                true

            }

            layoutButton?.setOnClickListener {
                layoutPopupMenu?.show()
            }

        }

        if (muPDFCore?.hasOutline() == true) {

            binding.outlineButton.setOnClickListener {

                if (flatOutline != null) {

                    OutlineFragment(
                        items = flatOutline.orEmpty(),
                        currentPage = docView?.displayedViewIndex ?: 0,
                        onItemSelected = { page ->
                            if (page >= AppCompatActivity.RESULT_FIRST_USER && docView != null) {
                                docView?.pushHistory()
                                docView?.displayedViewIndex =
                                    page - AppCompatActivity.RESULT_FIRST_USER
                            }
                        }
                    ).show(childFragmentManager, OutlineFragment.TAG)

                } else {
                    flatOutline = muPDFCore?.outline
                }

            }

        } else {
            binding.outlineButton.isVisible = false
        }

        // Reinstate last state if it was recorded
        val prefs = requireActivity().getPreferences(AppCompatActivity.MODE_PRIVATE)

        docView?.displayedViewIndex = prefs.getInt("page$docKey", 0)

        if (savedInstanceState == null || !savedInstanceState.getBoolean("ButtonsHidden", false)) {
            showButtons()
        }

        if (savedInstanceState != null && savedInstanceState.getBoolean("SearchMode", false)) {
            searchModeOn()
        }

        // Stick the document view and the buttons overlay into a parent view

        binding.viewerContainer.addView(docView)

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (docKey != null && docView != null) {
            if (docTitle != null) outState.putString("DocTitle", docTitle)

            // Store current page in the prefs against the file name,
            // so that we can pick it up each time the file is loaded
            // Other info is needed only for screen-orientation change,
            // so it can go in the bundle
            val prefs = requireActivity().getPreferences(AppCompatActivity.MODE_PRIVATE)
            val edit = prefs.edit()
            edit.putInt("page$docKey", docView?.displayedViewIndex ?: 0)
            edit.apply()
        }

        if (!buttonsVisible) {
            outState.putBoolean("ButtonsHidden", true)
        }

        if (topBarMode == TopBarMode.Search) {
            outState.putBoolean("SearchMode", true)
        }

    }

    override fun onPause() {
        super.onPause()

        searchTask?.stop()

        if (docKey != null && docView != null) {
            val prefs = requireActivity().getPreferences(AppCompatActivity.MODE_PRIVATE)
            val edit = prefs.edit()
            edit.putInt("page$docKey", docView?.displayedViewIndex ?: 0)
            edit.apply()
        }

    }

    override fun onDestroy() {

        docView?.applyToChildren(object : ReaderView.ViewMapper() {
            override fun applyToView(view: View?) {
                (view as PageView?)?.releaseBitmaps()
            }
        })

        muPDFCore?.onDestroy()

        muPDFCore = null

        _binding = null

        super.onDestroy()
    }

    private fun setButtonEnabled(button: ImageButton?, enabled: Boolean) {
        button?.isEnabled = enabled
        val color = when {
            enabled -> Color.argb(255, 255, 255, 255)
            else -> Color.argb(255, 128, 128, 128)
        }
        button?.setColorFilter(color)
    }

    private fun setLinkHighlight(highlight: Boolean) {
        linkHighlight = highlight
        // LINK_COLOR tint
        val tintColor = when {
            highlight -> Color.argb(0xFF, 0x00, 0x66, 0xCC)
            else -> Color.argb(0xFF, 255, 255, 255)
        }
        binding.linkButton.setColorFilter(tintColor)
        // Inform pages of the change.
        docView?.setLinksEnabled(highlight)
    }

    private fun showButtons() {

        muPDFCore ?: return

        if (!buttonsVisible) {

            buttonsVisible = true

            // Update page number text and slider
            val index = docView?.displayedViewIndex ?: 0
            updatePageNumView(index)
            binding.pageSlider.max = ((muPDFCore?.countPages() ?: 0) - 1) * pageSliderRes
            binding.pageSlider.progress = index * pageSliderRes

            if (topBarMode == TopBarMode.Search) {
                binding.searchText.requestFocus()
                showKeyboard()
            }

            var anim: Animation = TranslateAnimation(0f, 0f, -binding.switcher.height.toFloat(), 0f)
            anim.duration = 200
            anim.setAnimationListener(object : Animation.AnimationListener {

                override fun onAnimationStart(animation: Animation) {
                    binding.switcher.isVisible = true
                }

                override fun onAnimationRepeat(animation: Animation) {}

                override fun onAnimationEnd(animation: Animation) {}

            })
            binding.switcher.startAnimation(anim)
            anim = TranslateAnimation(0f, 0f, binding.pageSlider.height.toFloat(), 0f)
            anim.setDuration(200)
            anim.setAnimationListener(object : Animation.AnimationListener {

                override fun onAnimationStart(animation: Animation) {
                    binding.pageSlider.isVisible = true
                }

                override fun onAnimationRepeat(animation: Animation) {}

                override fun onAnimationEnd(animation: Animation) {
                    binding.pageNumber.isVisible = true
                }

            })

            binding.pageSlider.startAnimation(anim)

        }
    }

    private fun hideButtons() {
        if (buttonsVisible) {

            buttonsVisible = false

            hideKeyboard()

            var anim: Animation = TranslateAnimation(0f, 0f, 0f, -binding.switcher.height.toFloat())
            anim.duration = 200
            anim.setAnimationListener(object : Animation.AnimationListener {

                override fun onAnimationStart(animation: Animation) {}

                override fun onAnimationRepeat(animation: Animation) {}

                override fun onAnimationEnd(animation: Animation) {
                    binding.switcher.visibility = View.INVISIBLE
                }

            })

            binding.switcher.startAnimation(anim)

            anim = TranslateAnimation(0f, 0f, 0f, binding.pageSlider.height.toFloat())
            anim.setDuration(200)
            anim.setAnimationListener(object : Animation.AnimationListener {

                override fun onAnimationStart(animation: Animation) {
                    binding.pageNumber.visibility = View.INVISIBLE
                }

                override fun onAnimationRepeat(animation: Animation) {}

                override fun onAnimationEnd(animation: Animation) {
                    binding.pageSlider.visibility = View.INVISIBLE
                }

            })

            binding.pageSlider.startAnimation(anim)

        }
    }

    private fun searchModeOn() {
        if (topBarMode != TopBarMode.Search) {
            topBarMode = TopBarMode.Search
            //Focus on EditTextWidget
            binding.searchText.requestFocus()
            showKeyboard()
            binding.switcher.displayedChild = topBarMode.ordinal
        }
    }

    private fun searchModeOff() {
        if (topBarMode == TopBarMode.Search) {
            topBarMode = TopBarMode.Main
            hideKeyboard()
            binding.switcher.displayedChild = topBarMode.ordinal
            SearchResult.set(null)
            // Make the ReaderView act on the change to mSearchTaskResult
            // via overridden onChildSetup method.
            docView?.resetupChildren()
        }
    }

    private fun updatePageNumView(index: Int) {
        muPDFCore ?: return
        val pageNumber = String.format(Locale.ROOT, "%d / %d", index + 1, muPDFCore?.countPages())
        binding.pageNumber.text = pageNumber
    }

    private fun makeButtonsView() {
        binding.switcher.visibility = View.INVISIBLE
        binding.pageNumber.visibility = View.INVISIBLE
        binding.pageSlider.visibility = View.INVISIBLE
    }

    private fun showKeyboard() {
        val imm =
            requireActivity().getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchText, 0)
    }

    private fun hideKeyboard() {
        val imm =
            requireActivity().getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchText.windowToken, 0)
    }

    private fun search(direction: Int) {
        hideKeyboard()
        val displayPage = docView?.displayedViewIndex ?: 0
        val searchResult = SearchResult.get()
        val searchPage = searchResult?.pageNumber ?: -1
        searchTask?.go(binding.searchText.text.toString(), direction, displayPage, searchPage)
    }

    /*override fun onSearchRequested(): Boolean {
        if (buttonsVisible && topBarMode == TopBarMode.Search) {
            hideButtons()
        } else {
            showButtons()
            searchModeOn()
        }
        return super.onSearchRequested()
    }*/


    override fun onPrepareOptionsMenu(menu: Menu) {
        if (buttonsVisible && topBarMode != TopBarMode.Search) {
            hideButtons()
        } else {
            showButtons()
            searchModeOff()
        }
        super.onPrepareOptionsMenu(menu)
    }


    companion object {

        private const val TAG = "DocumentFragment"
        private const val ARG_URI = "ARG_URI"

        fun getBundle(uri: Uri): Bundle {
            return bundleOf(ARG_URI to uri)
        }

    }

}