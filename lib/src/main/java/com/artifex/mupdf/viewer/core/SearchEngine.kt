package com.artifex.mupdf.viewer.core

import com.artifex.mupdf.viewer.model.SearchDirection
import com.artifex.mupdf.viewer.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class SearchEngine(private val muPDFCore: MuPDFCore) {

    private var job: Job? = null

    private var scope = MainScope()

    protected abstract fun onTextFound(result: SearchResult)

    protected abstract fun onProgress(isLoading: Boolean)

    protected abstract fun onError(failure: Failure)


    fun stop() {
        job?.cancel()
        job = null
        onProgress(false)
    }

    fun search(query: String, direction: SearchDirection, displayPage: Int, searchPage: Int) {

        job?.cancel()

        val startIndex = when (searchPage) {
            -1 -> displayPage
            else -> searchPage + direction.value
        }

        job = scope.launch {

            onProgress(true)

            val result = search(
                startIndex = startIndex,
                text = query,
                searchDirection = direction
            )

            withContext(Dispatchers.IO){
                if (result == null) {

                    val message = when {
                        SearchResult.get() == null -> Failure.TextNotFound("Text not found")
                        else -> Failure.NoFurtherOccurrencesFound("No further occurrences found")
                    }

                    onError(message)

                } else {
                    onTextFound(result)
                }

                onProgress(false)
            }


            onProgress(false)

        }

    }

    private fun search(text: String, startIndex: Int, searchDirection: SearchDirection): SearchResult? {

        var index = startIndex

        while (0 <= index && index < muPDFCore.countPages()) {

            val searchHits = muPDFCore.searchPage(index, text)

            if (searchHits.isNullOrEmpty().not()) {
                return SearchResult(
                    txt = text,
                    pageNumber = index,
                    searchBoxes = searchHits
                )
            }

            index += searchDirection.value

        }

        return null
    }

    sealed class Failure() {
        data class NoFurtherOccurrencesFound(val message: String?): Failure()
        data class TextNotFound(val message: String?): Failure()
    }

}