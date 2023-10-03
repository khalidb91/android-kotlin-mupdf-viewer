package com.artifex.mupdf.viewer.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.PointF
import android.os.AsyncTask
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.artifex.mupdf.viewer.core.MuPDFCore

class PageAdapter(
    private val context: Context,
    private val muPDFCore: MuPDFCore,
) : BaseAdapter() {

    private val pageSizes = SparseArray<PointF?>()
    private var bitmap: Bitmap? = null

    override fun getCount(): Int {
        return try {
            muPDFCore.countPages()
        } catch (e: RuntimeException) {
            0
        }
    }

    override fun getItem(position: Int): Any? {
        return null
    }

    override fun getItemId(position: Int): Long {
        return 0
    }

    fun releaseBitmaps() {
        //  recycle and release the shared bitmap.
        bitmap?.recycle()
        bitmap = null
    }

    fun refresh() {
        pageSizes.clear()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {

        val pageView: PageView

        if (convertView == null) {
            if (bitmap == null || bitmap!!.width != parent.width || bitmap!!.height != parent.height) {
                bitmap = if (parent.width > 0 && parent.height > 0) Bitmap.createBitmap(
                    parent.width,
                    parent.height,
                    Bitmap.Config.ARGB_8888
                ) else null
            }
            pageView = PageView(context, muPDFCore, Point(parent.width, parent.height), bitmap)
        } else {
            pageView = convertView as PageView
        }

        val pageSize = pageSizes[position]

        if (pageSize != null) {
            // We already know the page size. Set it up
            // immediately
            pageView.setPage(position, pageSize)
        } else {
            // Page size as yet unknown. Blank it for now, and
            // start a background task to find the size
            pageView.blank(position)

            val sizingTask: AsyncTask<Void?, Void?, PointF?> = object : AsyncTask<Void?, Void?, PointF?>() {

                    protected override fun doInBackground(vararg params: Void?): PointF? {
                        return try {
                            muPDFCore.getPageSize(position)
                        } catch (e: RuntimeException) {
                            null
                        }
                    }

                    override fun onPostExecute(result: PointF?) {
                        super.onPostExecute(result)
                        // We now know the page size
                        pageSizes.put(position, result)
                        // Check that this view hasn't been reused for
                        // another page since we started
                        if (pageView.page == position) {
                            pageView.setPage(position, result)
                        }
                    }
                }

            sizingTask.execute(null as Void?)

        }

        return pageView

    }

}