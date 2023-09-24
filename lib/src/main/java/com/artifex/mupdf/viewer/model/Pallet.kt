package com.artifex.mupdf.viewer.model

import android.os.Bundle

class Pallet private constructor() {

    private val pallet: MutableMap<Int, Any> = HashMap()
    private var sequenceNumber = 0

    companion object {

        private val instance = Pallet()

        fun sendBundle(bundle: Bundle): Int {
            val instance = instance
            val i = instance.sequenceNumber++
            if (instance.sequenceNumber < 0) {
                instance.sequenceNumber = 0
            }
            instance.pallet[i] = bundle
            return i
        }

        @JvmStatic
		fun receiveBundle(number: Int): Bundle? {
            val bundle = instance.pallet[number] as Bundle?
            if (bundle != null) {
                instance.pallet.remove(number)
            }
            return bundle
        }

        fun hasBundle(number: Int): Boolean {
            return instance.pallet.containsKey(number)
        }

    }

}