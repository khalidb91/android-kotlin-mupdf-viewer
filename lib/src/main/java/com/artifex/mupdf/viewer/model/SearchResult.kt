package com.artifex.mupdf.viewer.model

import com.artifex.mupdf.fitz.Quad

class SearchResult internal constructor(
    val txt: String,
    val pageNumber: Int,
    val searchBoxes: Array<Array<Quad>>
) {

    companion object {

        private var singleton: SearchResult? = null

        @JvmStatic
		fun get(): SearchResult? {
            return singleton
        }

        fun set(searchResult: SearchResult?) {
            singleton = searchResult
        }

    }

}