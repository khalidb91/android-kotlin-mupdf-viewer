package com.artifex.mupdf.viewer.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

class PickFileContract : ActivityResultContract<Void?, Uri?>() {

    // Open the mime-types we know about
    private val knownMimeTypes = arrayOf(
        "application/pdf",
        "application/vnd.ms-xpsdocument",
        "application/oxps",
        "application/x-cbz",
        "application/vnd.comicbook+zip",
        "application/epub+zip",
        "application/x-fictionbook",
        "application/x-mobipocket-ebook",
        "application/octet-stream"
    )

    override fun createIntent(context: Context, input: Void?): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_MIME_TYPES, knownMimeTypes)
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode == Activity.RESULT_OK && intent != null) {
            return intent.data
        }
        return null
    }

}