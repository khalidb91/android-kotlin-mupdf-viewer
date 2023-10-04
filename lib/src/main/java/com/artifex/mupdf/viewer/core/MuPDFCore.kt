package com.artifex.mupdf.viewer.core

import android.graphics.Bitmap
import android.graphics.PointF
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.RectI
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.artifex.mupdf.viewer.model.OutlineItem

private const val A_FORMAT_WITH = 312
private const val A_FORMAT_HEIGHT = 504

class MuPDFCore private constructor(doc: Document) {

    private val resolution: Int
    private var doc: Document?
    private var pageCount = -1
    private var currentPage: Int
    private var page: Page? = null
    private var pageWidth = 0f
    private var pageHeight = 0f
    private var displayList: DisplayList? = null

    var outline: ArrayList<Outline> = arrayListOf()

    /* Default to "A Format" pocket book size. */
    private var layoutW = A_FORMAT_WITH
    private var layoutH = A_FORMAT_HEIGHT
    private var layoutEM = 10

    init {
        this.doc = doc
        doc.layout(layoutW.toFloat(), layoutH.toFloat(), layoutEM.toFloat())
        pageCount = doc.countPages()
        resolution = 160
        currentPage = -1
    }

    constructor(
        buffer: ByteArray?,
        mimetype: String?
    ) : this(doc = Document.openDocument(buffer, mimetype))

    constructor(
        stm: SeekableInputStream?,
        mimetype: String?
    ) : this(doc = Document.openDocument(stm, mimetype))


    val title: String
        get() = doc?.getMetaData(Document.META_INFO_TITLE).toString()

    fun countPages(): Int {
        return pageCount
    }

    @get:Synchronized
    val isReflowable: Boolean
        get() = doc?.isReflowable == true

    @Synchronized
    fun layout(oldPage: Int, w: Int, h: Int, em: Int): Int {

        if (w != layoutW || h != layoutH || em != layoutEM) {
            println("LAYOUT: $w,$h")

            layoutW = w
            layoutH = h
            layoutEM = em

            val bookmark = doc?.makeBookmark(doc?.locationFromPageNumber(oldPage))

            doc?.layout(
                /* width = */ layoutW.toFloat(),
                /* height = */layoutH.toFloat(),
                /* em = */layoutEM.toFloat()
            )

            currentPage = -1

            pageCount = doc?.countPages() ?: 0

            outline = arrayListOf()

            try {
                outline = doc?.loadOutline().orEmpty().toList() as ArrayList<Outline>
            } catch (ex: Exception) {
                /* ignore error */
            }

            return if (bookmark != null) {
                val bookmarkLocation = doc?.findBookmark(bookmark)
                doc?.pageNumberFromLocation(bookmarkLocation) ?: -1
            } else {
                oldPage
            }

        }

        return oldPage

    }

    @Synchronized
    private fun gotoPage(pageNum: Int) {

        val normalizedPageNumber = when {
            (pageNum > pageCount - 1) -> pageCount - 1
            (pageNum < 0) -> 0
            else -> pageNum
        }

        if (normalizedPageNumber != currentPage) {

            page?.destroy()
            page = null

            displayList?.destroy()
            displayList = null

            page = null
            pageWidth = 0f
            pageHeight = 0f
            currentPage = -1

            if (doc != null) {
                page = doc?.loadPage(normalizedPageNumber)
                val bounds: Rect = page?.bounds ?: Rect(0f, 0f, 0f, 0f)
                pageWidth = bounds.x1 - bounds.x0
                pageHeight = bounds.y1 - bounds.y0
            }

            currentPage = normalizedPageNumber

        }

    }

    @Synchronized
    fun getPageSize(pageNum: Int): PointF {
        gotoPage(pageNum)
        return PointF(pageWidth, pageHeight)
    }

    @Synchronized
    fun onDestroy() {

        displayList?.destroy()
        displayList = null

        page?.destroy()
        page = null

        doc?.destroy()
        doc = null

    }

    @Synchronized
    fun drawPage(
        bm: Bitmap?, pageNum: Int,
        pageW: Int, pageH: Int,
        patchX: Int, patchY: Int,
        patchW: Int, patchH: Int,
        cookie: Cookie?
    ) {

        gotoPage(pageNum)

        if (displayList == null && page != null) {
            displayList = try {
                page?.toDisplayList()
            } catch (ex: Exception) {
                null
            }
        }

        if (displayList == null || page == null) {
            return
        }

        val zoom = (resolution / 72).toFloat()
        val ctm = Matrix(zoom, zoom)
        val bounds = RectI(page!!.bounds.transform(ctm))
        val xScale = pageW.toFloat() / (bounds.x1 - bounds.x0).toFloat()
        val yScale = pageH.toFloat() / (bounds.y1 - bounds.y0).toFloat()
        ctm.scale(xScale, yScale)
        val dev = AndroidDrawDevice(bm, patchX, patchY)

        try {
            displayList!!.run(dev, ctm, cookie)
            dev.close()
        } finally {
            dev.destroy()
        }

    }

    @Synchronized
    fun updatePage(
        bm: Bitmap?, pageNum: Int,
        pageW: Int, pageH: Int,
        patchX: Int, patchY: Int,
        patchW: Int, patchH: Int,
        cookie: Cookie?
    ) {
        drawPage(bm, pageNum, pageW, pageH, patchX, patchY, patchW, patchH, cookie)
    }

    @Synchronized
    fun getPageLinks(pageNum: Int): Array<Link>? {
        gotoPage(pageNum)
        return if (page != null) page!!.links else null
    }

    @Synchronized
    fun resolveLink(link: Link?): Int {
        return doc!!.pageNumberFromLocation(doc!!.resolveLink(link))
    }

    @Synchronized
    fun searchPage(pageNum: Int, text: String?): Array<Array<Quad>> {
        gotoPage(pageNum)
        return page!!.search(text)
    }

    @Synchronized
    fun hasOutline(): Boolean {

        if (outline.isEmpty()) {
            try {
                outline = doc!!.loadOutline().toList() as ArrayList<Outline>
            } catch (ex: Exception) {
                /* ignore error */
            }
        }

        return outline.isEmpty().not()

    }

    private fun flattenOutlineNodes(
        result: ArrayList<OutlineItem>,
        list: List<Outline>,
        indent: String
    ) {

        for (node in list) {

            node.title?.let {
                val page = doc!!.pageNumberFromLocation(doc!!.resolveLink(node))
                result.add(OutlineItem(indent + node.title, page))
            }

            node.down?.let { outlines ->
                flattenOutlineNodes(result, outlines.toList(), "$indent    ")
            }

        }

    }

    @Synchronized
    fun getOutline(): List<OutlineItem> {
        val result = ArrayList<OutlineItem>()
        flattenOutlineNodes(result, outline, "")
        return result
    }

    @Synchronized
    fun needsPassword(): Boolean {
        return doc?.needsPassword() == true
    }

    @Synchronized
    fun authenticatePassword(password: String?): Boolean {
        return doc?.authenticatePassword(password) == true
    }

}
