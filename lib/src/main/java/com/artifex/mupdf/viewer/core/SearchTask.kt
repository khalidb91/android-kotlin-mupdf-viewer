package com.artifex.mupdf.viewer.core

import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.os.AsyncTask
import android.os.Handler
import com.artifex.mupdf.viewer.R
import com.artifex.mupdf.viewer.model.SearchResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal class ProgressDialogX(context: Context?) : ProgressDialog(context) {

    var isCancelled = false
        private set

    override fun cancel() {
        isCancelled = true
        super.cancel()
    }

}

abstract class SearchTask(
    private val context: Context,
    private val core: MuPDFCore?,
) {

    private val handler: Handler = Handler()

    private var searchTask: AsyncTask<Void?, Int?, SearchResult?>? = null

    protected abstract fun onTextFound(result: SearchResult)

    fun stop() {
        searchTask?.cancel(true)
        searchTask = null
    }

    fun go(text: String?, direction: Int, displayPage: Int, searchPage: Int) {

        core ?: return

        stop()

        val startIndex = if (searchPage == -1) displayPage else searchPage + direction

        val progressDialog = ProgressDialogX(context).apply {
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setTitle(context.getString(R.string.searching))
            setOnCancelListener { _: DialogInterface? -> stop() }
            max = core.countPages()
        }


        searchTask = object : AsyncTask<Void?, Int?, SearchResult?>() {

            override fun doInBackground(vararg params: Void?): SearchResult? {

                var index = startIndex

                while (0 <= index && index < core.countPages() && !isCancelled) {

                    publishProgress(index)

                    val searchHits = core.searchPage(index, text)

                    if (searchHits.isNullOrEmpty().not()) return SearchResult(
                        text!!, index, searchHits
                    )

                    index += direction

                }

                return null
            }

            override fun onPostExecute(result: SearchResult?) {

                progressDialog.cancel()

                if (result == null) {

                    val message = when {
                        SearchResult.get() == null -> R.string.text_not_found
                        else -> R.string.no_further_occurrences_found
                    }

                    MaterialAlertDialogBuilder(context)
                        .setMessage(message)
                        .setPositiveButton(R.string.dismiss) { dialog, which -> dialog.dismiss() }
                        .show()

                } else {
                    onTextFound(result!!)
                }

            }

            override fun onCancelled() {
                progressDialog.cancel()
            }

            override fun onProgressUpdate(vararg values: Int?) {
                progressDialog.progress = values[0] ?: 0
            }

            override fun onPreExecute() {
                super.onPreExecute()
                handler.postDelayed({
                    if (!progressDialog.isCancelled) {
                        progressDialog.show()
                        progressDialog.progress = startIndex
                    }
                }, SEARCH_PROGRESS_DELAY.toLong())
            }

        }

        searchTask?.execute()

    }

    companion object {
        private const val SEARCH_PROGRESS_DELAY = 200
    }
}