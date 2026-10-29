package com.red38.librescanner.vision

import org.opencv.android.OpenCVLoader

object OpenCv {
    private const val UNKNOWN = 0
    private const val READY = 1
    private const val FAILED = -1

    @Volatile
    private var state = UNKNOWN

    fun ensureLoaded(): Boolean {
        if (state == READY) return true
        if (state == FAILED) return false
        synchronized(this) {
            if (state == UNKNOWN) {
                state = if (OpenCVLoader.initLocal()) READY else FAILED
            }
        }
        return state == READY
    }
}
