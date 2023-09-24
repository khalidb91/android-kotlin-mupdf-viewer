package com.khal.mupdf.viewer.app.ui.document

import android.app.Application
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.viewer.core.ContentInputStream
import com.artifex.mupdf.viewer.core.MuPDFCore
import com.artifex.mupdf.viewer.model.SearchResult
import com.khal.mupdf.viewer.app.ui.home.HomeViewModel
import java.io.IOException

class DocumentViewModel(application: Application) : AndroidViewModel(application) {


    fun getMuPdf(uri: Uri): MuPDFCore? {

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
                magic = mimetype
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
        magic: String?,
    ): MuPDFCore? {
        return try {
            MuPDFCore(buffer, magic)
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

}