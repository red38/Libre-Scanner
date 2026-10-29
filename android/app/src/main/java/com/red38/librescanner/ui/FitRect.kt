package com.red38.librescanner.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize

fun fitRect(size: IntSize, aspect: Float): Rect {
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    if (w <= 0f || h <= 0f || aspect <= 0f) return Rect.Zero
    val viewAspect = w / h
    return if (aspect > viewAspect) {
        val dh = w / aspect
        Rect(0f, (h - dh) / 2f, w, (h + dh) / 2f)
    } else {
        val dw = h * aspect
        Rect((w - dw) / 2f, 0f, (w + dw) / 2f, h)
    }
}

fun fitRect(size: Size, aspect: Float): Rect {
    if (size.width <= 0f || size.height <= 0f || aspect <= 0f) return Rect.Zero
    val viewAspect = size.width / size.height
    return if (aspect > viewAspect) {
        val dh = size.width / aspect
        Rect(0f, (size.height - dh) / 2f, size.width, (size.height + dh) / 2f)
    } else {
        val dw = size.height * aspect
        Rect((size.width - dw) / 2f, 0f, (size.width + dw) / 2f, size.height)
    }
}
