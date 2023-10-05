package com.khal.mupdf.viewer.app.ui.document

import android.app.Application
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.viewer.core.ContentInputStream
import com.artifex.mupdf.viewer.core.MuPDFCore
import com.artifex.mupdf.viewer.core.SearchEngine
import com.artifex.mupdf.viewer.model.OutlineItem
import com.artifex.mupdf.viewer.model.SearchDirection
import com.artifex.mupdf.viewer.model.SearchResult
import com.artifex.mupdf.viewer.view.PageAdapter
import com.khal.mupdf.viewer.app.R
import com.khal.mupdf.viewer.app.ui.home.HomeViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.max

class DocumentViewModel(application: Application) : AndroidViewModel(application) {

    private var muPDFCore: MuPDFCore? = null

    private var docTitle: String? = null

    private var searchEngine: SearchEngine? = null

    var isReflowable = false

    private var linkHighlight = false

    private var isOverlayVisible = false

    private var pageSliderRes = 0

    private var displayDPI = 0

    private var layoutEM = 10
    private var layoutW = 312
    private var layoutH = 504


    val uiState by lazy {
        MutableStateFlow<UiEvent?>(null)
    }

    sealed class UiEvent {
        object RequestPassword : UiEvent()
        data class Error(val error: Int) : UiEvent()
        data class Progress(val isLoading: Boolean) : UiEvent()
        data class PageChange(
            val pageNumber: String,
            val sliderMax: Int,
            val sliderProgress: Int
        ) : UiEvent()

        data class LinkHighlight(val linkHighlight: Boolean) : UiEvent()
        data class RelayoutDocument(
            val layoutEm: Int,
            val layoutW: Int,
            val layoutH: Int
        ) : UiEvent()

        data class OverlayVisibility(val isVisible: Boolean) : UiEvent()
        data class SearchMode(val isEnabled: Boolean) : UiEvent()
        data class PageChangeByIndex(val index: Int) : UiEvent()
        data class TextFound(val result: SearchResult) : UiEvent()
        data class  ShowOutline(val outlines: List<OutlineItem>) : UiEvent()
    }

    fun init(uri: Uri?) {

        displayDPI = getApplication<Application>().resources.displayMetrics.densityDpi

        muPDFCore = getMuPdf(uri!!)

        if (muPDFCore?.needsPassword() == true) {
            uiState.value = UiEvent.RequestPassword
            return
        }

        if (muPDFCore?.countPages() == 0) {
            muPDFCore = null
        }

        if (muPDFCore == null) {
            viewModelScope.launch {
                uiState.emit(UiEvent.Error(R.string.cannot_open_document))
            }
            return
        }

        // Set up the page slider
        val smax = max((muPDFCore?.countPages() ?: 0) - 1, 1)
        pageSliderRes = (10 + smax - 1) / smax * 2

        isReflowable = (muPDFCore?.isReflowable == true)

        searchEngine = object : SearchEngine(muPDFCore!!) {

            override fun onTextFound(result: SearchResult) {
                SearchResult.set(result)
                viewModelScope.launch {
                    uiState.emit(UiEvent.TextFound(result))
                }
            }

            override fun onProgress(isLoading: Boolean) {
                viewModelScope.launch {
                    uiState.emit(UiEvent.Progress(isLoading))
                }
            }

            override fun onError(failure: Failure) {
                viewModelScope.launch {
                    val error = when (failure) {
                        is Failure.TextNotFound -> R.string.text_not_found
                        else -> R.string.no_further_occurrences_found
                    }
                    uiState.emit(UiEvent.Error(error))
                }
            }

        }

    }

    fun onOutlineClick() {
        val outlines = muPDFCore?.getOutline().orEmpty()
        viewModelScope.launch {
            uiState.emit(UiEvent.ShowOutline(outlines))
        }
    }

    private fun getMuPdf(uri: Uri): MuPDFCore? {

        var docTitle: String? = null
        var size: Long = -1
        var cursor: Cursor? = null
        val contentResolver: ContentResolver = getApplication<Application>().contentResolver
        var mimeType: String? = contentResolver.getType(uri)

        Log.i(HomeViewModel.TAG, "OPEN URI $uri")
        Log.i(HomeViewModel.TAG, "  MAGIC (Intent) $mimeType")

        try {

            cursor = contentResolver.query(uri, null, null, null, null)

            if (cursor?.moveToFirst() == true) {

                var idx: Int = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                if (idx >= 0 && cursor.getType(idx) == Cursor.FIELD_TYPE_STRING) {
                    docTitle = cursor.getString(idx)
                }

                idx = cursor.getColumnIndex(OpenableColumns.SIZE)

                if (idx >= 0 && cursor.getType(idx) == Cursor.FIELD_TYPE_INTEGER) {
                    size = cursor.getLong(idx)
                }

                if (size == 0L) size = -1

            }

        } catch (ex: Exception) {
            // Ignore any exception and depend on default values for title
            // and size (unless one was decoded
        } finally {
            cursor?.close()
        }

        Log.i(HomeViewModel.TAG, "  NAME $docTitle")
        Log.i(HomeViewModel.TAG, "  SIZE $size")

        if (mimeType == null || mimeType == "application/octet-stream") {
            mimeType = contentResolver.getType(uri)
            Log.i(HomeViewModel.TAG, "  MAGIC (Resolved) $mimeType")
        }

        if (mimeType == null || mimeType == "application/octet-stream") {
            mimeType = docTitle
            Log.i(HomeViewModel.TAG, "  MAGIC (Filename) $mimeType")
        }

        return try {
            SearchResult.set(null)
            openCore(uri, size, mimeType, contentResolver)
        } catch (ex: Exception) {
            Log.e(HomeViewModel.TAG, ex.message, ex)
            null
        }

    }

    @Throws(IOException::class)
    private fun openCore(
        uri: Uri,
        size: Long,
        mimetype: String?,
        contentResolver: ContentResolver,
    ): MuPDFCore? {

        Log.i(HomeViewModel.TAG, "Opening document $uri")

        val inputStream = contentResolver.openInputStream(uri)
        var buffer: ByteArray? = null
        var used = -1

        try {

            val limit = 8 * 1024 * 1024

            if (size < 0) { // size is unknown
                buffer = ByteArray(limit)
                used = inputStream!!.read(buffer)
                val atEOF = inputStream.read() == -1
                if (used < 0 || used == limit && !atEOF) {
                    // no or partial data
                    buffer = null
                }
            } else if (size <= limit) { // size is known and below limit
                buffer = ByteArray(size.toInt())
                used = inputStream!!.read(buffer)
                if (used < 0 || used < size) {
                    // no or partial data
                    buffer = null
                }
            }

            if (buffer != null && buffer.size != used) {
                val newBuffer = ByteArray(used)
                System.arraycopy(buffer, 0, newBuffer, 0, used)
                buffer = newBuffer
            }

        } catch (e: OutOfMemoryError) {
            buffer = null
        } finally {
            inputStream?.close()
        }

        return if (buffer != null) {
            Log.i(HomeViewModel.TAG, "  Opening document from memory buffer of size " + buffer.size)
            openBuffer(
                buffer = buffer,
                mimetype = mimetype
            )
        } else {
            Log.i(HomeViewModel.TAG, "  Opening document from stream")
            openStream(
                seekableInputStream = ContentInputStream(contentResolver, uri, size),
                magic = mimetype
            )
        }
    }

    private fun openBuffer(
        buffer: ByteArray,
        mimetype: String?,
    ): MuPDFCore? {
        return try {
            MuPDFCore(buffer, mimetype)
        } catch (e: Exception) {
            Log.e(HomeViewModel.TAG, "Error opening document buffer: $e")
            return null
        }
    }

    private fun openStream(
        seekableInputStream: SeekableInputStream,
        magic: String?,
    ): MuPDFCore? {
        return try {
            MuPDFCore(seekableInputStream, magic)
        } catch (e: Exception) {
            Log.e(HomeViewModel.TAG, "Error opening document stream: $e")
            return null
        }
    }

    fun getDocTitle(): String? {
        return muPDFCore?.title ?: this.docTitle
    }

    fun authenticate(pwd: String): Boolean {
        return muPDFCore?.authenticatePassword(pwd) == true
    }

    fun toggleLinkHighlight() {
        linkHighlight = linkHighlight.not()
        viewModelScope.launch {
            uiState.emit(UiEvent.LinkHighlight(linkHighlight))
        }
    }

    override fun onCleared() {
        searchEngine?.stop()
        muPDFCore?.onDestroy()
        muPDFCore = null
        super.onCleared()
    }

    fun updateLayout(newLayoutEM: Int) {
        if (layoutEM != newLayoutEM) {
            layoutEM = newLayoutEM
            viewModelScope.launch {
                uiState.emit(
                    UiEvent.RelayoutDocument(
                        layoutEm = layoutEM,
                        layoutW = layoutW,
                        layoutH = layoutH
                    )
                )
            }
        }
    }

    fun toggleOverlay() {
        isOverlayVisible = isOverlayVisible.not()
        viewModelScope.launch {
            uiState.emit(UiEvent.OverlayVisibility(isOverlayVisible))
        }
    }

    fun moveToPage(index: Int) {
        muPDFCore ?: return
        val pageNumber = "${index + 1} / ${muPDFCore?.countPages() ?: 0}"
        val max = ((muPDFCore?.countPages() ?: 0) - 1) * pageSliderRes
        val progress = (index * pageSliderRes)
        viewModelScope.launch {
            uiState.emit(UiEvent.PageChange(pageNumber, max, progress))
        }
    }

    fun onProgressChange(progress: Int) {
        val index = (progress + pageSliderRes / 2) / pageSliderRes
        moveToPage(index)
    }

    fun search(
        query: String,
        searchDirection: SearchDirection,
        displayPage: Int
    ) {
        searchEngine?.search(
            query = query,
            direction = searchDirection,
            displayPage = displayPage,
            searchPage = SearchResult.get()?.pageNumber ?: -1
        )
    }

    fun getViewIndex(currentPage: Int, layoutW: Int, layoutH: Int, layoutEm: Int): Int {
        return muPDFCore?.layout(currentPage, layoutW, layoutH, layoutEm) ?: 0
    }

    fun updateSize(w: Int, h: Int) {
        layoutW = w * 72 / displayDPI
        layoutH = h * 72 / displayDPI
        viewModelScope.launch {
            uiState.emit(
                UiEvent.RelayoutDocument(
                    layoutEm = layoutEM,
                    layoutW = layoutW,
                    layoutH = layoutH
                )
            )
        }
    }

    fun setSearchMode(enabled: Boolean) {
        viewModelScope.launch {
            uiState.emit(UiEvent.SearchMode(enabled))
        }
        if (enabled.not()) {
            SearchResult.set(null)
        }
    }

    fun getPagesAdapter(): PageAdapter? {
        return if (muPDFCore != null) {
            PageAdapter(getApplication<Application>().applicationContext, muPDFCore!!)
        } else {
            null
        }
    }

    fun changePage(progress: Int) {
        val index = (progress + pageSliderRes / 2) / pageSliderRes
        viewModelScope.launch {
            uiState.emit(UiEvent.PageChangeByIndex(index))
        }
    }

}