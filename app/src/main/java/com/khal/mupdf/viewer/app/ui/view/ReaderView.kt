package com.khal.mupdf.viewer.app.ui.view

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.SparseArray
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Adapter
import android.widget.AdapterView
import android.widget.Scroller
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.viewer.R
import com.artifex.mupdf.viewer.model.SearchResult
import com.khal.mupdf.viewer.app.ui.document.PageAdapter
import java.util.LinkedList
import java.util.Stack
import kotlin.math.abs
import kotlin.math.min

abstract class ReaderView : AdapterView<Adapter?>, GestureDetector.OnGestureListener,
    ScaleGestureDetector.OnScaleGestureListener, Runnable {

    private var context: Context? = null
    private var linksEnabled = false
    private var tapDisabled = false
    private var tapPageMargin = 0
    private var adapter: PageAdapter? = null

    // Adapter's index for the current view
    @JvmField
    var current = 0

    private var resetLayout = false
    private val childViews = SparseArray<View?>(3)

    // Shadows the children of the adapter view
    // but with more sensible indexing
    private val viewCache = LinkedList<View?>()

    // Whether the user is interacting
    private var userInteracting = false

    // Whether the user is currently pinch zooming
    private var isScaling = false

    private var scale = 1.0f

    // Scroll amounts recorded from events.
    private var xScroll = 0

    // and then accounted for in onLayout
    private var yScroll = 0

    private var gestureDetector: GestureDetector? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var scroller: Scroller? = null
    private var stepper: Stepper? = null
    private var scrollerLastX = 0
    private var scrollerLastY = 0
    private var lastScaleFocusX = 0f
    private var lastScaleFocusY = 0f

    @JvmField
    var history: Stack<Int> = Stack()

    abstract class ViewMapper {
        abstract fun applyToView(view: View?)
    }

    constructor(context: Context) : super(context) {
        setup(context)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        setup(context)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        setup(context)
    }

    private fun setup(context: Context) {

        this.context = context

        gestureDetector = GestureDetector(context, this)
        scaleGestureDetector = ScaleGestureDetector(context, this)
        scroller = Scroller(context)
        stepper = Stepper(this, this)
        history = Stack()

        // Get the screen size etc to customise tap margins.
        // We calculate the size of 1 inch of the screen for tapping.
        // On some devices the dpi values returned are wrong, so we
        // sanity check it: we first restrict it so that we are never
        // less than 100 pixels (the smallest Android device screen
        // dimension I've seen is 480 pixels or so). Then we check
        // to ensure we are never more than 1/5 of the screen width.

        val displayMetrics = DisplayMetrics()

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        tapPageMargin = displayMetrics.xdpi.toInt()

        if (tapPageMargin < 100) {
            tapPageMargin = 100
        }

        if (tapPageMargin > displayMetrics.widthPixels / 5) {
            tapPageMargin = displayMetrics.widthPixels / 5
        }

    }

    fun popHistory(): Boolean {

        if (history.empty()) {
            return false
        }

        displayedViewIndex = history.pop()

        return true
    }

    fun pushHistory() {
        history.push(current)
    }

    var displayedViewIndex: Int
        get() = current
        set(i) {
            if (0 <= i && i < adapter!!.count) {
                onMoveOffChild(current)
                current = i
                onMoveToChild(i)
                resetLayout = true
                requestLayout()
            }
        }

    fun moveToNext() {
        val view = childViews[current + 1]
        view?.let { slideViewOntoScreen(it) }
    }

    fun moveToPrevious() {
        val view = childViews[current - 1]
        view?.let { slideViewOntoScreen(it) }
    }

    // When advancing down the page, we want to advance by about
    // 90% of a screenful. But we'd be happy to advance by between
    // 80% and 95% if it means we hit the bottom in a whole number
    // of steps.
    private fun smartAdvanceAmount(screenHeight: Int, max: Int): Int {

        var advance = (screenHeight * 0.9 + 0.5).toInt()
        val leftOver = max % advance
        val steps = max / advance

        if (leftOver == 0) {
            // We'll make it exactly. No adjustment
        } else if (leftOver.toFloat() / steps <= screenHeight * 0.05) {
            // We can adjust up by less than 5% to make it exact.
            advance += (leftOver.toFloat() / steps + 0.5).toInt()
        } else {
            val overshoot = advance - leftOver
            if (overshoot.toFloat() / steps <= screenHeight * 0.1) {
                // We can adjust down by less than 10% to make it exact.
                advance -= (overshoot.toFloat() / steps + 0.5).toInt()
            }
        }

        if (advance > max) {
            advance = max
        }

        return advance

    }

    fun smartMoveForwards() {

        val view = childViews[current] ?: return

        // The following code works in terms of where the screen is on the views;
        // so for example, if the currentView is at (-100,-100), the visible
        // region would be at (100,100). If the previous page was (2000, 3000) in
        // size, the visible region of the previous page might be (2100 + GAP, 100)
        // (i.e. off the previous page). This is different to the way the rest of
        // the code in this file is written, but it's easier for me to think about.
        // At some point we may refactor this to fit better with the rest of the
        // code.

        // screenWidth/Height are the actual width/height of the screen. e.g. 480/800
        val screenWidth = width
        val screenHeight = height

        // We might be mid scroll; we want to calculate where we scroll to based on
        // where this scroll would end, not where we are now (to allow for people
        // bashing 'forwards' very fast.
        val remainingX = scroller!!.finalX - scroller!!.currX
        val remainingY = scroller!!.finalY - scroller!!.currY

        // right/bottom is in terms of pixels within the scaled document; e.g. 1000
        val top = -(view.top + yScroll + remainingY)
        val right = screenWidth - (view.left + xScroll + remainingX)
        val bottom = screenHeight + top

        // docWidth/Height are the width/height of the scaled document e.g. 2000x3000
        val docWidth = view.measuredWidth
        val docHeight = view.measuredHeight

        var xOffset: Int
        var yOffset: Int

        if (bottom >= docHeight) {

            // We are flush with the bottom. Advance to next column.
            if (right + screenWidth > docWidth) {

                // No room for another column - go to next page
                val nv = childViews[current + 1] ?: return // No page to advance to

                val nextTop = -(nv.top + yScroll + remainingY)
                val nextLeft = -(nv.left + xScroll + remainingX)

                val nextDocWidth = nv.measuredWidth
                val nextDocHeight = nv.measuredHeight

                // Allow for the next page maybe being shorter than the screen is high
                yOffset = if (nextDocHeight < screenHeight) {
                    nextDocHeight - screenHeight shr 1
                } else {
                    0
                }

                if (nextDocWidth < screenWidth) {
                    // Next page is too narrow to fill the screen. Scroll to the top, centred.
                    xOffset = nextDocWidth - screenWidth shr 1
                } else {

                    // Reset X back to the left hand column
                    xOffset = right % screenWidth

                    // Adjust in case the previous page is less wide
                    if (xOffset + screenWidth > nextDocWidth) {
                        xOffset = nextDocWidth - screenWidth
                    }

                }

                xOffset -= nextLeft
                yOffset -= nextTop

            } else {
                // Move to top of next column
                xOffset = screenWidth
                yOffset = screenHeight - bottom
            }

        } else {
            // Advance by 90% of the screen height downwards (in case lines are partially cut off)
            xOffset = 0
            yOffset = smartAdvanceAmount(screenHeight, docHeight - bottom)
        }

        scrollerLastY = 0
        scrollerLastX = scrollerLastY
        scroller?.startScroll(0, 0, remainingX - xOffset, remainingY - yOffset, 400)
        stepper?.prod()

    }

    fun smartMoveBackwards() {
        val v = childViews[current] ?: return

        // The following code works in terms of where the screen is on the views;
        // so for example, if the currentView is at (-100,-100), the visible
        // region would be at (100,100). If the previous page was (2000, 3000) in
        // size, the visible region of the previous page might be (2100 + GAP, 100)
        // (i.e. off the previous page). This is different to the way the rest of
        // the code in this file is written, but it's easier for me to think about.
        // At some point we may refactor this to fit better with the rest of the
        // code.

        // screenWidth/Height are the actual width/height of the screen. e.g. 480/800
        val screenWidth = width
        val screenHeight = height
        // We might be mid scroll; we want to calculate where we scroll to based on
        // where this scroll would end, not where we are now (to allow for people
        // bashing 'forwards' very fast.
        val remainingX = scroller!!.finalX - scroller!!.currX
        val remainingY = scroller!!.finalY - scroller!!.currY
        // left/top is in terms of pixels within the scaled document; e.g. 1000
        val left = -(v.left + xScroll + remainingX)
        val top = -(v.top + yScroll + remainingY)
        // docWidth/Height are the width/height of the scaled document e.g. 2000x3000
        val docHeight = v.measuredHeight
        var xOffset: Int
        var yOffset: Int
        if (top <= 0) {
            // We are flush with the top. Step back to previous column.
            if (left < screenWidth) {
                /* No room for previous column - go to previous page */
                val pv = childViews[current - 1] ?: /* No page to advance to */return
                val prevDocWidth = pv.measuredWidth
                val prevDocHeight = pv.measuredHeight

                // Allow for the next page maybe being shorter than the screen is high
                yOffset =
                    if (prevDocHeight < screenHeight) prevDocHeight - screenHeight shr 1 else 0
                val prevLeft = -(pv.left + xScroll)
                val prevTop = -(pv.top + yScroll)
                if (prevDocWidth < screenWidth) {
                    // Previous page is too narrow to fill the screen. Scroll to the bottom, centred.
                    xOffset = prevDocWidth - screenWidth shr 1
                } else {
                    // Reset X back to the right hand column
                    xOffset = if (left > 0) left % screenWidth else 0
                    if (xOffset + screenWidth > prevDocWidth) xOffset = prevDocWidth - screenWidth
                    while (xOffset + screenWidth * 2 < prevDocWidth) xOffset += screenWidth
                }
                xOffset -= prevLeft
                yOffset -= prevTop - prevDocHeight + screenHeight
            } else {
                // Move to bottom of previous column
                xOffset = -screenWidth
                yOffset = docHeight - screenHeight + top
            }
        } else {
            // Retreat by 90% of the screen height downwards (in case lines are partially cut off)
            xOffset = 0
            yOffset = -smartAdvanceAmount(screenHeight, top)
        }
        scrollerLastY = 0
        scrollerLastX = scrollerLastY
        scroller!!.startScroll(0, 0, remainingX - xOffset, remainingY - yOffset, 400)
        stepper!!.prod()
    }

    fun resetupChildren() {
        for (i in 0 until childViews.size()) onChildSetup(
            childViews.keyAt(i),
            childViews.valueAt(i)
        )
    }

    fun applyToChildren(mapper: ViewMapper) {
        for (i in 0 until childViews.size()) mapper.applyToView(childViews.valueAt(i))
    }

    fun refresh() {
        resetLayout = true
        scale = 1.0f
        yScroll = 0
        xScroll = yScroll

        /* All page views need recreating since both page and screen has changed size,
		 * invalidating both sizes and bitmaps. */adapter!!.refresh()
        val numChildren = childViews.size()
        for (i in 0 until childViews.size()) {
            val v = childViews.valueAt(i)
            onNotInUse(v)
            removeViewInLayout(v)
        }
        childViews.clear()
        viewCache.clear()
        requestLayout()
    }

    fun getView(i: Int): View? {
        return childViews[i]
    }

    val displayedView: View?
        get() = childViews[current]

    override fun run() {
        if (!scroller!!.isFinished) {
            scroller!!.computeScrollOffset()
            val x = scroller!!.currX
            val y = scroller!!.currY
            xScroll += x - scrollerLastX
            yScroll += y - scrollerLastY
            scrollerLastX = x
            scrollerLastY = y
            requestLayout()
            stepper!!.prod()
        } else if (!userInteracting) {
            // End of an inertial scroll and the user is not interacting.
            // The layout is stable
            val v = childViews[current]
            v?.let { postSettle(it) }
        }
    }

    override fun onDown(arg0: MotionEvent): Boolean {
        scroller!!.forceFinished(true)
        return true
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float,
    ): Boolean {
        if (isScaling) return true
        val v = childViews[current]
        if (v != null) {
            val bounds = getScrollBounds(v)
            when (directionOfTravel(velocityX, velocityY)) {
                MOVING_LEFT -> if (HORIZONTAL_SCROLLING && bounds.left >= 0) {
                    // Fling off to the left bring next view onto screen
                    val vl = childViews[current + 1]
                    if (vl != null) {
                        slideViewOntoScreen(vl)
                        return true
                    }
                }

                MOVING_UP -> if (!HORIZONTAL_SCROLLING && bounds.top >= 0) {
                    // Fling off to the top bring next view onto screen
                    val vl = childViews[current + 1]
                    if (vl != null) {
                        slideViewOntoScreen(vl)
                        return true
                    }
                }

                MOVING_RIGHT -> if (HORIZONTAL_SCROLLING && bounds.right <= 0) {
                    // Fling off to the right bring previous view onto screen
                    val vr = childViews[current - 1]
                    if (vr != null) {
                        slideViewOntoScreen(vr)
                        return true
                    }
                }

                MOVING_DOWN -> if (!HORIZONTAL_SCROLLING && bounds.bottom <= 0) {
                    // Fling off to the bottom bring previous view onto screen
                    val vr = childViews[current - 1]
                    if (vr != null) {
                        slideViewOntoScreen(vr)
                        return true
                    }
                }
            }
            scrollerLastY = 0
            scrollerLastX = scrollerLastY
            // If the page has been dragged out of bounds then we want to spring back
            // nicely. fling jumps back into bounds instantly, so we don't want to use
            // fling in that case. On the other hand, we don't want to forgo a fling
            // just because of a slightly off-angle drag taking us out of bounds other
            // than in the direction of the drag, so we test for out of bounds only
            // in the direction of travel.
            //
            // Also don't fling if out of bounds in any direction by more than fling
            // margin
            val expandedBounds = Rect(bounds)
            expandedBounds.inset(-FLING_MARGIN, -FLING_MARGIN)
            if (withinBoundsInDirectionOfTravel(bounds, velocityX, velocityY)
                && expandedBounds.contains(0, 0)
            ) {
                scroller!!.fling(
                    0,
                    0,
                    velocityX.toInt(),
                    velocityY.toInt(),
                    bounds.left,
                    bounds.right,
                    bounds.top,
                    bounds.bottom
                )
                stepper!!.prod()
            }
        }
        return true
    }

    override fun onLongPress(e: MotionEvent) {

    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float,
    ): Boolean {
        val pageView = displayedView as PageView?
        if (!tapDisabled) onDocMotion()
        if (!isScaling) {
            xScroll -= distanceX.toInt()
            yScroll -= distanceY.toInt()
            requestLayout()
        }
        return true
    }

    override fun onShowPress(e: MotionEvent) {

    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        val previousScale = scale
        scale = Math.min(Math.max(scale * detector.scaleFactor, MIN_SCALE), MAX_SCALE)
        run {
            val factor = scale / previousScale
            val v = childViews[current]
            if (v != null) {
                val currentFocusX = detector.focusX
                val currentFocusY = detector.focusY
                // Work out the focus point relative to the view top left
                val viewFocusX = currentFocusX.toInt() - (v.left + xScroll)
                val viewFocusY = currentFocusY.toInt() - (v.top + yScroll)
                // Scroll to maintain the focus point
                xScroll += (viewFocusX - viewFocusX * factor).toInt()
                yScroll += (viewFocusY - viewFocusY * factor).toInt()
                if (lastScaleFocusX >= 0) xScroll += (currentFocusX - lastScaleFocusX).toInt()
                if (lastScaleFocusY >= 0) yScroll += (currentFocusY - lastScaleFocusY).toInt()
                lastScaleFocusX = currentFocusX
                lastScaleFocusY = currentFocusY
                requestLayout()
            }
        }
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        tapDisabled = true
        isScaling = true
        // Ignore any scroll amounts yet to be accounted for: the
        // screen is not showing the effect of them, so they can
        // only confuse the user
        yScroll = 0
        xScroll = yScroll
        lastScaleFocusY = -1f
        lastScaleFocusX = lastScaleFocusY
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
        isScaling = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action and event.actionMasked == MotionEvent.ACTION_DOWN) {
            tapDisabled = false
        }
        scaleGestureDetector!!.onTouchEvent(event)
        gestureDetector!!.onTouchEvent(event)
        if (event.action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_DOWN) {
            userInteracting = true
        }
        if (event.action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_UP) {
            userInteracting = false
            val v = childViews[current]
            if (v != null) {
                if (scroller!!.isFinished) {
                    // If, at the end of user interaction, there is no
                    // current inertial scroll in operation then animate
                    // the view onto screen if necessary
                    slideViewOntoScreen(v)
                }
                if (scroller!!.isFinished) {
                    // If still there is no inertial scroll in operation
                    // then the layout is stable
                    postSettle(v)
                }
            }
        }
        requestLayout()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val n = childCount
        for (i in 0 until n) measureView(getChildAt(i))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        try {
            onLayout2(changed, left, top, right, bottom)
        } catch (e: OutOfMemoryError) {
            println("Out of memory during layout")
        }
    }

    private fun onLayout2(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {

        // "Edit mode" means when the View is being displayed in the Android GUI editor. (this class
        // is instantiated in the IDE, so we need to be a bit careful what we do).
        if (isInEditMode) return
        var cv = childViews[current]
        var cvOffset: Point
        if (!resetLayout) {
            // Move to next or previous if current is sufficiently off center
            if (cv != null) {
                var move: Boolean
                cvOffset = subScreenSizeOffset(cv)
                // cv.getRight() may be out of date with the current scale
                // so add left to the measured width for the correct position
                move =
                    if (HORIZONTAL_SCROLLING) cv.left + cv.measuredWidth + cvOffset.x + GAP / 2 + xScroll < width / 2 else cv.top + cv.measuredHeight + cvOffset.y + GAP / 2 + yScroll < height / 2
                if (move && current + 1 < adapter!!.count) {
                    postUnsettle(cv)
                    // post to invoke test for end of animation
                    // where we must set hq area for the new current view
                    stepper!!.prod()
                    onMoveOffChild(current)
                    current++
                    onMoveToChild(current)
                }
                move =
                    if (HORIZONTAL_SCROLLING) cv.left - cvOffset.x - GAP / 2 + xScroll >= width / 2 else cv.top - cvOffset.y - GAP / 2 + yScroll >= height / 2
                if (move && current > 0) {
                    postUnsettle(cv)
                    // post to invoke test for end of animation
                    // where we must set hq area for the new current view
                    stepper!!.prod()
                    onMoveOffChild(current)
                    current--
                    onMoveToChild(current)
                }
            }

            // Remove not needed children and hold them for reuse
            val numChildren = childViews.size()
            val childIndices = IntArray(numChildren)
            for (i in 0 until numChildren) childIndices[i] = childViews.keyAt(i)
            for (i in 0 until numChildren) {
                val ai = childIndices[i]
                if (ai < current - 1 || ai > current + 1) {
                    val v = childViews[ai]
                    onNotInUse(v)
                    viewCache.add(v)
                    removeViewInLayout(v)
                    childViews.remove(ai)
                }
            }
        } else {
            resetLayout = false
            yScroll = 0
            xScroll = yScroll

            // Remove all children and hold them for reuse
            val numChildren = childViews.size()
            for (i in 0 until numChildren) {
                val v = childViews.valueAt(i)
                onNotInUse(v)
                viewCache.add(v)
                removeViewInLayout(v)
            }
            childViews.clear()

            // post to ensure generation of hq area
            stepper!!.prod()
        }

        // Ensure current view is present
        var cvLeft: Int
        var cvRight: Int
        var cvTop: Int
        var cvBottom: Int
        val notPresent = childViews[current] == null
        cv = getOrCreateChild(current)
        // When the view is sub-screen-size in either dimension we
        // offset it to center within the screen area, and to keep
        // the views spaced out
        cvOffset = subScreenSizeOffset(cv)
        if (notPresent) {
            // Main item not already present. Just place it top left
            cvLeft = cvOffset.x
            cvTop = cvOffset.y
        } else {
            // Main item already present. Adjust by scroll offsets
            cvLeft = cv.left + xScroll
            cvTop = cv.top + yScroll
        }
        // Scroll values have been accounted for
        yScroll = 0
        xScroll = yScroll
        cvRight = cvLeft + cv.measuredWidth
        cvBottom = cvTop + cv.measuredHeight
        if (!userInteracting && scroller!!.isFinished) {
            val corr = getCorrection(getScrollBounds(cvLeft, cvTop, cvRight, cvBottom))
            cvRight += corr.x
            cvLeft += corr.x
            cvTop += corr.y
            cvBottom += corr.y
        } else if (HORIZONTAL_SCROLLING && cv.measuredHeight <= height) {
            // When the current view is as small as the screen in height, clamp
            // it vertically
            val corr = getCorrection(getScrollBounds(cvLeft, cvTop, cvRight, cvBottom))
            cvTop += corr.y
            cvBottom += corr.y
        } else if (!HORIZONTAL_SCROLLING && cv.measuredWidth <= width) {
            // When the current view is as small as the screen in width, clamp
            // it horizontally
            val corr = getCorrection(getScrollBounds(cvLeft, cvTop, cvRight, cvBottom))
            cvRight += corr.x
            cvLeft += corr.x
        }
        cv.layout(cvLeft, cvTop, cvRight, cvBottom)
        if (current > 0) {
            val lv = getOrCreateChild(current - 1)
            val leftOffset = subScreenSizeOffset(lv)
            if (HORIZONTAL_SCROLLING) {
                val gap = leftOffset.x + GAP + cvOffset.x
                lv.layout(
                    cvLeft - lv.measuredWidth - gap,
                    (cvBottom + cvTop - lv.measuredHeight) / 2,
                    cvLeft - gap,
                    (cvBottom + cvTop + lv.measuredHeight) / 2
                )
            } else {
                val gap = leftOffset.y + GAP + cvOffset.y
                lv.layout(
                    (cvLeft + cvRight - lv.measuredWidth) / 2,
                    cvTop - lv.measuredHeight - gap,
                    (cvLeft + cvRight + lv.measuredWidth) / 2,
                    cvTop - gap
                )
            }
        }
        if (current + 1 < adapter!!.count) {
            val rv = getOrCreateChild(current + 1)
            val rightOffset = subScreenSizeOffset(rv)
            if (HORIZONTAL_SCROLLING) {
                val gap = cvOffset.x + GAP + rightOffset.x
                rv.layout(
                    cvRight + gap,
                    (cvBottom + cvTop - rv.measuredHeight) / 2,
                    cvRight + rv.measuredWidth + gap,
                    (cvBottom + cvTop + rv.measuredHeight) / 2
                )
            } else {
                val gap = cvOffset.y + GAP + rightOffset.y
                rv.layout(
                    (cvLeft + cvRight - rv.measuredWidth) / 2,
                    cvBottom + gap,
                    (cvLeft + cvRight + rv.measuredWidth) / 2,
                    cvBottom + gap + rv.measuredHeight
                )
            }
        }
        invalidate()
    }

    override fun getAdapter(): Adapter? {
        return adapter
    }

    override fun getSelectedView(): View? {
        return null
    }

    override fun setAdapter(adapter: Adapter?) {
        if (adapter != null && adapter != adapter) {
            (adapter as? PageAdapter)?.releaseBitmaps()
        }
        this.adapter = adapter as PageAdapter?
        requestLayout()
    }

    override fun setSelection(arg0: Int) {
        throw UnsupportedOperationException(context?.getString(R.string.not_supported))
    }

    private val cached: View?
        private get() = if (viewCache.size == 0) null else viewCache.removeFirst()

    private fun getOrCreateChild(i: Int): View {
        var view = childViews[i]
        if (view == null) {
            view = adapter!!.getView(i, cached, this)
            addAndMeasureChild(i, view)
            onChildSetup(i, view)
        }
        return view
    }

    private fun addAndMeasureChild(i: Int, view: View) {
        var params = view.layoutParams
        if (params == null) {
            params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        addViewInLayout(view, 0, params, true)
        childViews.append(i, view) // Record the view against its adapter index
        measureView(view)
    }

    private fun measureView(view: View) {

        // See what size the view wants to be
        view.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)

        // Work out a scale that will fit it to this view
        val scale = min(
            width.toFloat() / view.measuredWidth.toFloat(),
            height.toFloat() / view.measuredHeight.toFloat()
        )

        // Use the fitting values scaled by our current scale factor
        view.measure(
            MeasureSpec.EXACTLY or (view.measuredWidth * scale * this.scale).toInt(),
            MeasureSpec.EXACTLY or (view.measuredHeight * scale * this.scale).toInt()
        )

    }

    private fun getScrollBounds(left: Int, top: Int, right: Int, bottom: Int): Rect {
        var xmin = width - right
        var xmax = -left
        var ymin = height - bottom
        var ymax = -top

        // In either dimension, if view smaller than screen then
        // constrain it to be central
        if (xmin > xmax) {
            xmax = (xmin + xmax) / 2
            xmin = xmax
        }

        if (ymin > ymax) {
            ymax = (ymin + ymax) / 2
            ymin = ymax
        }

        return Rect(xmin, ymin, xmax, ymax)

    }

    private fun getScrollBounds(view: View): Rect {
        // There can be scroll amounts not yet accounted for in
        // onLayout, so add mXScroll and mYScroll to the current
        // positions when calculating the bounds.
        return getScrollBounds(
            left = view.left + xScroll,
            top = view.top + yScroll,
            right = view.left + view.measuredWidth + xScroll,
            bottom = view.top + view.measuredHeight + yScroll
        )
    }

    private fun getCorrection(bounds: Rect): Point {
        return Point(
            /* x = */ 0.coerceAtLeast(bounds.left).coerceAtMost(bounds.right),
            /* y = */ 0.coerceAtLeast(bounds.top).coerceAtMost(bounds.bottom)
        )
    }

    private fun postSettle(view: View) {
        // onSettle and onUnsettle are posted so that the calls
        // won't be executed until after the system has performed
        // layout.
        post { onSettle(view) }
    }

    private fun postUnsettle(view: View) {
        post { onUnsettle(view) }
    }

    private fun slideViewOntoScreen(view: View) {
        val corr = getCorrection(getScrollBounds(view))
        if (corr.x != 0 || corr.y != 0) {
            scrollerLastY = 0
            scrollerLastX = scrollerLastY
            scroller?.startScroll(0, 0, corr.x, corr.y, 400)
            stepper?.prod()
        }
    }

    private fun subScreenSizeOffset(view: View?): Point {
        return Point(
            ((width - view!!.measuredWidth) / 2).coerceAtLeast(0),
            ((height - view.measuredHeight) / 2).coerceAtLeast(0)
        )
    }

    protected open fun onTapMainDocArea() {}

    protected open fun onDocMotion() {}

    fun setLinksEnabled(enabled: Boolean) {
        linksEnabled = enabled
        resetupChildren()
        invalidate()
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {

        val link: Link? = null

        if (tapDisabled) return true

        val pageView = displayedView as PageView?

        if (linksEnabled && pageView != null) {
            val page = pageView.hitLink(e.x, e.y)
            if (page > 0) {
                pushHistory()
                displayedViewIndex = page
            } else {
                onTapMainDocArea()
            }
        } else if (e.x < tapPageMargin) {
            smartMoveBackwards()
        } else if (e.x > super.getWidth() - tapPageMargin) {
            smartMoveForwards()
        } else if (e.y < tapPageMargin) {
            smartMoveBackwards()
        } else if (e.y > super.getHeight() - tapPageMargin) {
            smartMoveForwards()
        } else {
            onTapMainDocArea()
        }

        return true
    }

    private fun onChildSetup(i: Int, view: View?) {
        if (SearchResult.get() != null && SearchResult.get()?.pageNumber == i) {
            (view as PageView?)!!.setSearchBoxes(SearchResult.get()?.searchBoxes)
        } else {
            (view as PageView?)!!.setSearchBoxes(null)
        }
        view?.setLinkHighlighting(linksEnabled)
    }

    protected open fun onMoveToChild(i: Int) {
        if (SearchResult.get() != null && SearchResult.get()?.pageNumber != i) {
            SearchResult.set(null)
            resetupChildren()
        }
    }

    private fun onMoveOffChild(i: Int) {

    }

    private fun onSettle(view: View) {
        // When the layout has settled ask the page to render in HQ
        (view as PageView).updateHq(false)
    }

    private fun onUnsettle(view: View) {
        // When something changes making the previous settled view
        // no longer appropriate, tell the page to remove HQ
        (view as PageView).removeHq()
    }

    private fun onNotInUse(view: View?) {
        (view as PageView?)?.releaseResources()
    }

    companion object {

        private const val MOVING_DIAGONALLY = 0
        private const val MOVING_LEFT = 1
        private const val MOVING_RIGHT = 2
        private const val MOVING_UP = 3
        private const val MOVING_DOWN = 4
        private const val FLING_MARGIN = 100
        private const val GAP = 20
        private const val MIN_SCALE = 1.0f
        private const val MAX_SCALE = 64.0f
        private const val HORIZONTAL_SCROLLING = true

        private fun directionOfTravel(vx: Float, vy: Float): Int {
            return when {
                (abs(vx) > (2 * abs(vy))) -> {
                    when {
                        vx > 0 -> MOVING_RIGHT
                        else -> MOVING_LEFT
                    }
                }

                (abs(vy) > (2 * abs(vx))) -> {
                    when {
                        vy > 0 -> MOVING_DOWN
                        else -> MOVING_UP
                    }
                }

                else -> {
                    MOVING_DIAGONALLY
                }
            }
        }

        private fun withinBoundsInDirectionOfTravel(
            bounds: Rect,
            vx: Float,
            vy: Float,
        ): Boolean {
            return when (directionOfTravel(vx, vy)) {
                MOVING_DIAGONALLY -> bounds.contains(0, 0)
                MOVING_LEFT -> bounds.left <= 0
                MOVING_RIGHT -> bounds.right >= 0
                MOVING_UP -> bounds.top <= 0
                MOVING_DOWN -> bounds.bottom >= 0
                else -> throw NoSuchElementException()
            }
        }

    }

}