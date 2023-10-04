package com.artifex.mupdf.viewer.core

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream
import java.io.IOException
import java.io.InputStream

class ContentInputStream(
    private var contentResolver: ContentResolver,
    private var uri: Uri,
    private var length: Long
) : SeekableInputStream {

    private var inputStream: InputStream? = null
    private var position: Long = 0
    private var mustReopenStream = false

    init {
        reopenStream()
    }

    @Throws(IOException::class)
    override fun seek(offset: Long, whence: Int): Long {

        val newPosition = when (whence) {

            SeekableStream.SEEK_SET -> {
                offset
            }

            SeekableStream.SEEK_CUR -> {
                position + offset
            }

            SeekableStream.SEEK_END -> {

                if (length < 0) {

                    val buf = ByteArray(16384)
                    var k: Int

                    while (inputStream!!.read(buf).also { k = it } != -1) {
                        position += k.toLong()
                    }

                    length = position

                }

                length + offset

            }

            else -> {
                position
            }

        }

        if (newPosition < position) {

            if (mustReopenStream.not()) {
                try {
                    inputStream?.skip(newPosition - position)
                } catch (x: IOException) {
                    Log.i(TAG, "Unable to skip backwards, reopening input stream")
                    mustReopenStream = true
                }
            }

            if (mustReopenStream) {
                reopenStream()
                inputStream?.skip(newPosition)
            }

        } else if (newPosition > position) {
            inputStream?.skip(newPosition - position)
        }

        return newPosition.also { position = it }

    }

    @Throws(IOException::class)
    override fun position(): Long {
        return position
    }

    @Throws(IOException::class)
    override fun read(buf: ByteArray): Int {

        val bytesRead = inputStream?.read(buf) ?: 0

        if (bytesRead > 0) {
            position += bytesRead.toLong()
        } else if (bytesRead < 0 && length < 0) {
            length = position
        }

        return bytesRead
    }

    @Throws(IOException::class)
    fun reopenStream() {

        inputStream?.close()
        inputStream = null

        inputStream = contentResolver.openInputStream(uri)

        position = 0

    }

    companion object {
        private const val TAG: String = "ContentInputStream"
    }

}
