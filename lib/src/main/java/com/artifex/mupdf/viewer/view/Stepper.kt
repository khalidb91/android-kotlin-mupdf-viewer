package com.artifex.mupdf.viewer.view

import android.view.View

open class Stepper(
    private val poster: View,
    private val task: Runnable,
) {

    private var pending = false

    fun prod() {

        if (pending) return

        pending = true

        poster.postOnAnimation {
            pending = false
            task.run()
        }

    }
}