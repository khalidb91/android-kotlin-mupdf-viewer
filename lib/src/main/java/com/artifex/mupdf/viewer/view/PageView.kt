package com.artifex.mupdf.viewer.view

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.os.AsyncTask
import android.os.FileUriExposedException
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.viewer.R
import com.artifex.mupdf.viewer.core.CancellableAsyncTask
import com.artifex.mupdf.viewer.core.CancellableTaskDefinition
import com.artifex.mupdf.viewer.core.MuPDFCancellableTaskDefinition
import com.artifex.mupdf.viewer.core.MuPDFCore

open class PageView(
    private val context: Context,
    private val muPDFCore: MuPDFCore,
    private val parentSize: Point,
    sharedHqBm: Bitmap?
) : ViewGroup(context) {


    var page = 0
        protected set

    protected var size: Point? = null // Size of page at minimum zoom
    protected var sourceScale = 0f
    private var entire: ImageView? = null // Image rendered at minimum zoom
    private var entireBm: Bitmap?
    private val entireMat: Matrix
    private var getLinkInfo: AsyncTask<Void?, Void?, Array<Link>?>? = null
    private var drawEntire: CancellableAsyncTask<Void?, Boolean?>? = null
    private var patchViewSize: Point? = null // Patch creation basis view size
    private var patchArea: Rect? = null
    private var patch: ImageView? = null
    private var patchBm: Bitmap?
    private var drawPatch: CancellableAsyncTask<Void?, Boolean?>? = null
    private var searchBoxes: Array<Array<Quad>>? = null
    protected var links: Array<Link>? = null
    private var searchView: View? = null
    private var isBlank = false
    private var highlightLinks = false
    private var errorIndicator: ImageView? = null
    private var busyIndicator: ProgressBar? = null
    private val handler = Handler()

    protected val linkInfo: Array<Link>?
        get() = try {
            muPDFCore.getPageLinks(page)
        } catch (e: RuntimeException) {
            null
        }

    init {
        this.setBackgroundColor(BACKGROUND_COLOR)
        entireBm = Bitmap.createBitmap(
            /* width = */ parentSize.x,
            /* height = */ parentSize.y,
            /* config = */ Bitmap.Config.ARGB_8888
        )
        patchBm = sharedHqBm
        entireMat = Matrix()
    }

    private fun reinit() {

        // Cancel pending render task

        drawEntire?.cancel()
        drawEntire = null

        drawPatch?.cancel()
        drawPatch = null

        getLinkInfo?.cancel(true)
        getLinkInfo = null

        isBlank = true
        page = 0

        size = size ?: parentSize

        entire?.setImageBitmap(null)
        entire?.invalidate()

        patch?.setImageBitmap(null)
        patch?.invalidate()

        patchViewSize = null
        patchArea = null
        searchBoxes = null
        links = null

        clearRenderError()
    }

    fun releaseResources() {

        reinit()

        if (busyIndicator != null) {
            removeView(busyIndicator)
            busyIndicator = null
        }

        clearRenderError()

    }

    fun releaseBitmaps() {

        reinit()

        // recycle bitmaps before releasing them.
        entireBm?.recycle()
        entireBm = null

        patchBm?.recycle()
        patchBm = null

    }

    fun blank(page: Int) {

        reinit()

        this.page = page

        if (busyIndicator == null) {
            busyIndicator = ProgressBar(context).apply { isIndeterminate = true }
            addView(busyIndicator)
        }

        setBackgroundColor(BACKGROUND_COLOR)

    }

    protected fun clearRenderError() {
        if (errorIndicator == null) return
        removeView(errorIndicator)
        errorIndicator = null
        invalidate()
    }

    protected fun setRenderError(why: String?) {
        val page = page

        reinit()

        this.page = page

        if (busyIndicator != null) {
            removeView(busyIndicator)
            busyIndicator = null
        }

        if (searchView != null) {
            removeView(searchView)
            searchView = null
        }

        if (errorIndicator == null) {
            errorIndicator = OpaqueImageView(context)
            (errorIndicator as OpaqueImageView).scaleType = ImageView.ScaleType.CENTER
            addView(errorIndicator)
            val errorIcon =
                ResourcesCompat.getDrawable(resources, R.drawable.ic_error_red_24dp, null)
            (errorIndicator as OpaqueImageView).setImageDrawable(errorIcon)
            (errorIndicator as OpaqueImageView).setBackgroundColor(BACKGROUND_COLOR)
        }

        setBackgroundColor(Color.TRANSPARENT)
        errorIndicator?.bringToFront()
        errorIndicator?.invalidate()

    }

    fun setPage(page: Int, size: PointF?) {
        // Cancel pending render task
        var size: PointF? = size

        drawEntire?.cancel()
        drawEntire = null

        isBlank = false

        // Highlights may be missing because mIsBlank was true on last draw
        searchView?.invalidate()

        this.page = page

        if (size == null) {
            setRenderError("Error loading page")
            size = PointF(612f, 792f)
        }

        // Calculate scaled size that fits within the screen limits
        // This is the size at minimum zoom
        sourceScale = Math.min(parentSize.x / size.x, parentSize.y / size.y)
        val newSize = Point((size.x * sourceScale).toInt(), (size.y * sourceScale).toInt())
        this.size = newSize

        if (errorIndicator != null) {
            return
        }

        if (entire == null) {
            entire = OpaqueImageView(context)
            (entire as OpaqueImageView).scaleType = ImageView.ScaleType.MATRIX
            addView(entire)
        }

        entire?.setImageBitmap(null)
        entire?.invalidate()

        // Get the link info in the background
        getLinkInfo = object : AsyncTask<Void?, Void?, Array<Link>?>() {

            override fun onPostExecute(result: Array<Link>?) {
                links = result
                searchView?.invalidate()
            }


            override fun doInBackground(vararg params: Void?): Array<Link>? {
                return linkInfo
            }

        }

        (getLinkInfo as AsyncTask<Void?, Void?, Array<Link>?>).execute()

        // Render the page in the background
        drawEntire = object : CancellableAsyncTask<Void?, Boolean?>(
            getDrawPageTask(
                entireBm,
                this@PageView.size!!.x,
                this@PageView.size!!.y,
                0,
                0,
                this@PageView.size!!.x,
                this@PageView.size!!.y
            )
        ) {

            override fun onPreExecute() {

                setBackgroundColor(BACKGROUND_COLOR)

                entire?.setImageBitmap(null)
                entire?.invalidate()

                if (busyIndicator == null) {

                    busyIndicator = ProgressBar(context).apply {
                        isIndeterminate = true
                    }

                    addView(busyIndicator)

                    busyIndicator?.visibility = INVISIBLE

                    handler.postDelayed(
                        /* r = */ { busyIndicator?.visibility = VISIBLE },
                        /* delayMillis = */ PROGRESS_DIALOG_DELAY.toLong()
                    )

                }

            }

            override fun onPostExecute(result: Boolean?) {

                removeView(busyIndicator)

                busyIndicator = null

                if (result == true) {
                    clearRenderError()
                    entire?.setImageBitmap(entireBm)
                    entire?.invalidate()
                } else {
                    setRenderError("Error rendering page")
                }

                setBackgroundColor(Color.TRANSPARENT)

            }

        }

        (drawEntire as CancellableAsyncTask<Void?, Boolean?>).execute()

        if (searchView == null) {

            searchView = object : View(context) {

                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)

                    // Work out current total scale factor
                    // from source to view
                    val scale = sourceScale * width.toFloat() / this@PageView.size!!.x.toFloat()
                    val paint = Paint()

                    if (!isBlank && searchBoxes != null) {

                        paint.color = HIGHLIGHT_COLOR

                        for (searchBox in searchBoxes!!) {
                            for (q in searchBox) {
                                val path = Path()
                                path.moveTo(q.ul_x * scale, q.ul_y * scale)
                                path.lineTo(q.ll_x * scale, q.ll_y * scale)
                                path.lineTo(q.lr_x * scale, q.lr_y * scale)
                                path.lineTo(q.ur_x * scale, q.ur_y * scale)
                                path.close()
                                canvas.drawPath(path, paint)
                            }
                        }

                    }

                    if (!isBlank && links != null && highlightLinks) {
                        paint.color = LINK_COLOR
                        for (link in links!!) canvas.drawRect(
                            link.bounds.x0 * scale, link.bounds.y0 * scale,
                            link.bounds.x1 * scale, link.bounds.y1 * scale,
                            paint
                        )
                    }

                }

            }

            addView(searchView)

        }

        requestLayout()
    }

    fun setSearchBoxes(searchBoxes: Array<Array<Quad>>?) {
        this.searchBoxes = searchBoxes
        searchView?.invalidate()
    }

    fun setLinkHighlighting(highlight: Boolean) {
        highlightLinks = highlight
        searchView?.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {

        val x: Int = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> size!!.x
            else -> MeasureSpec.getSize(widthMeasureSpec)
        }

        val y: Int = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> size!!.y
            else -> MeasureSpec.getSize(heightMeasureSpec)
        }

        setMeasuredDimension(x, y)

        busyIndicator?.let {
            val limit = Math.min(parentSize.x, parentSize.y) / 2
            it.measure(
                /* widthMeasureSpec = */ MeasureSpec.AT_MOST or limit,
                /* heightMeasureSpec = */MeasureSpec.AT_MOST or limit
            )
        }

        errorIndicator?.let {
            val limit = Math.min(parentSize.x, parentSize.y) / 2
            it.measure(
                /* widthMeasureSpec = */ MeasureSpec.AT_MOST or limit,
                /* heightMeasureSpec = */ MeasureSpec.AT_MOST or limit
            )
        }

    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {

        val w = right - left
        val h = bottom - top

        entire?.let {
            if (it.width != w || it.height != h) {
                entireMat.setScale(
                    /* sx = */ w / size!!.x.toFloat(),
                    /* sy = */ h / size!!.y.toFloat()
                )
                it.imageMatrix = entireMat
                it.invalidate()
            }
            it.layout(0, 0, w, h)
        }

        searchView?.layout(0, 0, w, h)

        patchViewSize?.let {
            if (it.x != w || it.y != h) {
                // Zoomed since patch was created
                patchViewSize = null
                patchArea = null
                patch?.setImageBitmap(null)
                patch?.invalidate()
            } else {
                patch?.layout(
                    patchArea!!.left,
                    patchArea!!.top,
                    patchArea!!.right,
                    patchArea!!.bottom
                )
            }
        }

        busyIndicator?.let {
            val bw = it.measuredWidth
            val bh = it.measuredHeight
            it.layout((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
        }

        errorIndicator?.let {
            val bw = (8.5 * it.measuredWidth).toInt()
            val bh = 11 * it.measuredHeight
            it.layout((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
        }

    }

    fun updateHq(update: Boolean) {

        errorIndicator?.let {
            patch?.setImageBitmap(null)
            patch?.invalidate()
            return
        }

        val viewArea = Rect(left, top, right, bottom)
        if (viewArea.width() == size!!.x || viewArea.height() == size!!.y) {
            // If the viewArea's size matches the unzoomed size, there is no need for an hq patch
            patch?.setImageBitmap(null)
            patch?.invalidate()
        } else {
            val patchViewSize = Point(viewArea.width(), viewArea.height())
            val patchArea = Rect(0, 0, parentSize.x, parentSize.y)

            // Intersect and test that there is an intersection
            if (!patchArea.intersect(viewArea)) {
                return
            }

            // Offset patch area to be relative to the view top left
            patchArea.offset(-viewArea.left, -viewArea.top)
            val area_unchanged = patchArea == this.patchArea && patchViewSize == this.patchViewSize

            // If being asked for the same area as last time and not because of an update then nothing to do
            if (area_unchanged && !update) return
            val completeRedraw = !(area_unchanged && update)

            // Stop the drawing of previous patch if still going
            drawPatch?.cancel()
            drawPatch = null

            // Create and add the image view if not already done
            if (patch == null) {
                patch = OpaqueImageView(context).apply {
                    scaleType = ImageView.ScaleType.MATRIX
                }
                addView(patch)
                searchView?.bringToFront()
            }

            val task: CancellableTaskDefinition<Void?, Boolean?>? = if (completeRedraw) {
                getDrawPageTask(
                    patchBm, patchViewSize.x, patchViewSize.y,
                    patchArea.left, patchArea.top,
                    patchArea.width(), patchArea.height()
                )
            } else {
                getUpdatePageTask(
                    patchBm, patchViewSize.x, patchViewSize.y,
                    patchArea.left, patchArea.top,
                    patchArea.width(), patchArea.height()
                )
            }

            drawPatch = object : CancellableAsyncTask<Void?, Boolean?>(task) {

                override fun onPostExecute(result: Boolean?) {
                    if (result == true) {
                        this@PageView.patchViewSize = patchViewSize
                        this@PageView.patchArea = patchArea
                        clearRenderError()
                        patch?.setImageBitmap(patchBm)
                        patch?.invalidate()
                        //requestLayout();
                        // Calling requestLayout here doesn't lead to a later call to layout. No idea
                        // why, but apparently others have run into the problem.
                        patch?.layout(
                            this@PageView.patchArea!!.left,
                            this@PageView.patchArea!!.top,
                            this@PageView.patchArea!!.right,
                            this@PageView.patchArea!!.bottom
                        )
                    } else {
                        setRenderError("Error rendering patch")
                    }
                }

            }

            drawPatch?.execute()
        }
    }

    fun update() {

        // Cancel pending render task
        drawEntire?.cancel()
        drawEntire = null

        drawPatch?.cancel()
        drawPatch = null

        // Render the page in the background
        drawEntire = object : CancellableAsyncTask<Void?, Boolean?>(
            getUpdatePageTask(
                entireBm,
                size!!.x,
                size!!.y,
                0,
                0,
                size!!.x,
                size!!.y
            )
        ) {
            override fun onPostExecute(result: Boolean?) {
                if (result == true) {
                    clearRenderError()
                    entire?.setImageBitmap(entireBm)
                    entire?.invalidate()
                } else {
                    setRenderError("Error updating page")
                }
            }
        }
        drawEntire?.execute()

        updateHq(true)

    }

    fun removeHq() {

        // Stop the drawing of the patch if still going
        drawPatch?.cancel()
        drawPatch = null

        // And get rid of it
        patchViewSize = null
        patchArea = null

        patch?.setImageBitmap(null)
        patch?.invalidate()

    }

    override fun isOpaque(): Boolean {
        return true
    }

    fun hitLink(link: Link): Int {

        if (link.isExternal.not()) {
            return muPDFCore.resolveLink(link)
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.uri))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

        try {
            context.startActivity(intent)
        } catch (ex: FileUriExposedException) {
            Log.e(TAG, ex.toString())
            val message = "Android does not allow following file:// link: ${link.uri}"
            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show()
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString())
            Toast.makeText(getContext(), ex.message, Toast.LENGTH_LONG).show()
        }

        return 0

    }

    fun hitLink(x: Float, y: Float): Int {

        // Since link highlighting was implemented, the super class
        // PageView has had sufficient information to be able to
        // perform this method directly. Making that change would
        // make MuPDFCore.hitLinkPage superfluous.
        val scale = sourceScale * width.toFloat() / size!!.x.toFloat()
        val docRelX = (x - left) / scale
        val docRelY = (y - top) / scale
        if (links != null) {
            for (l in links!!) {
                if (l.bounds.contains(docRelX, docRelY)) {
                    return hitLink(l)
                }
            }
        }
        return 0
    }

    protected fun getDrawPageTask(
        bm: Bitmap?,
        sizeX: Int,
        sizeY: Int,
        patchX: Int,
        patchY: Int,
        patchWidth: Int,
        patchHeight: Int
    ): CancellableTaskDefinition<Void?, Boolean?> {
        return object : MuPDFCancellableTaskDefinition<Void?, Boolean?>() {
            override fun doInBackground(cookie: Cookie?, vararg params: Void?): Boolean? {
                return if (bm == null) {
                    java.lang.Boolean.FALSE
                } else try {
                    muPDFCore.drawPage(
                        bm,
                        page,
                        sizeX,
                        sizeY,
                        patchX,
                        patchY,
                        patchWidth,
                        patchHeight,
                        cookie
                    )
                    java.lang.Boolean.TRUE
                } catch (e: RuntimeException) {
                    java.lang.Boolean.FALSE
                }
            }
        }
    }

    protected fun getUpdatePageTask(
        bm: Bitmap?,
        sizeX: Int,
        sizeY: Int,
        patchX: Int,
        patchY: Int,
        patchWidth: Int,
        patchHeight: Int
    ): CancellableTaskDefinition<Void?, Boolean?> {
        return object : MuPDFCancellableTaskDefinition<Void?, Boolean?>() {

            override fun doInBackground(cookie: Cookie?, vararg params: Void?): Boolean? {
                return if (bm == null) {
                    java.lang.Boolean.FALSE
                } else try {
                    muPDFCore.updatePage(
                        bm,
                        page,
                        sizeX,
                        sizeY,
                        patchX,
                        patchY,
                        patchWidth,
                        patchHeight,
                        cookie
                    )
                    java.lang.Boolean.TRUE
                } catch (e: RuntimeException) {
                    java.lang.Boolean.FALSE
                }
            }

        }
    }


    companion object {

        private const val TAG = "PageView"

        private const val HIGHLIGHT_COLOR = -0x7f339a00
        private const val LINK_COLOR = -0x7fff9934
        private const val BOX_COLOR = -0xbbbb01
        private const val BACKGROUND_COLOR = -0x1
        private const val PROGRESS_DIALOG_DELAY = 200
    }

}
