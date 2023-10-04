package com.artifex.mupdf.viewer.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.PointF
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.artifex.mupdf.viewer.core.MuPDFCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        bitmap?.recycle()
        bitmap = null
    }

    fun refresh() {
        pageSizes.clear()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {

        val pageView: PageView = if (convertView == null) {

            if (bitmap == null || bitmap!!.width != parent.width || bitmap!!.height != parent.height) {

                bitmap = if (parent.width > 0 && parent.height > 0) {
                    Bitmap.createBitmap(
                        /* width = */ parent.width,
                        /* height = */ parent.height,
                        /* config = */ Bitmap.Config.ARGB_8888
                    )
                } else {
                    null
                }

            }

            PageView(context, muPDFCore, Point(parent.width, parent.height), bitmap)

        } else {
            convertView as PageView
        }

        val pageSize = pageSizes[position]

        if (pageSize != null) {
            // We already know the page size. Set it up immediately.
            pageView.setPage(position, pageSize)
        } else {
            // Page size as yet unknown. Blank it for now, and compute in a background.
            pageView.blank(position)
            sizePageView(position, pageView)
        }

        return pageView

    }

    private fun sizePageView(position: Int, pageView: PageView) {
        CoroutineScope(Dispatchers.Main).launch {

            val result = withContext(Dispatchers.IO) {
                return@withContext try {
                    muPDFCore.getPageSize(position)
                } catch (e: RuntimeException) {
                    null
                }
            }

            pageSizes.put(position, result)

            if (pageView.page == position) {
                pageView.setPage(position, result)
            }

        }
    }

}