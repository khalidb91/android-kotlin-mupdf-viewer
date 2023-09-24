package com.artifex.mupdf.viewer.model

import java.io.Serializable

data class OutlineItem(
    var title: String,
    var page: Int,
) : Serializable