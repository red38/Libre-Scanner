package com.red38.librescanner.vision

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

enum class DocumentFilter {
    ORIGINAL,
    GRAY,
    BW,
    BW2,
    MAGIC,
}

object ImageFilters {
    fun apply(src: Bitmap, filter: DocumentFilter): Bitmap {
        if (filter == DocumentFilter.ORIGINAL) return src
        if (!OpenCv.ensureLoaded()) return src

        val rgba = BitmapUtils.bitmapToMat(src)
        val out = Mat()
        try {
            when (filter) {
                DocumentFilter.GRAY -> {
                    val gray = Mat()
                    try {
                        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                        Core.normalize(gray, gray, 0.0, 255.0, Core.NORM_MINMAX)
                        Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGBA)
                    } finally {
                        gray.release()
                    }
                }

                DocumentFilter.BW -> threshold(rgba, out, 21, 12.0)
                DocumentFilter.BW2 -> threshold(rgba, out, 41, 18.0)

                DocumentFilter.MAGIC -> {
                    val rgb = Mat()
                    val lab = Mat()
                    val channels = ArrayList<Mat>()
                    try {
                        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
                        Core.split(lab, channels)
                        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                        try {
                            clahe.apply(channels[0], channels[0])
                        } finally {
                            clahe.collectGarbage()
                        }
                        Core.merge(channels, lab)
                        Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
                        Imgproc.cvtColor(rgb, out, Imgproc.COLOR_RGB2RGBA)
                    } finally {
                        channels.forEach { it.release() }
                        rgb.release()
                        lab.release()
                    }
                }

                DocumentFilter.ORIGINAL -> Unit
            }
            return BitmapUtils.matToBitmap(out)
        } finally {
            rgba.release()
            out.release()
        }
    }

    private fun threshold(rgba: Mat, out: Mat, blockSize: Int, c: Double) {
        val gray = Mat()
        val bin = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.adaptiveThreshold(
                gray, bin, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, blockSize, c,
            )
            Imgproc.cvtColor(bin, out, Imgproc.COLOR_GRAY2RGBA)
        } finally {
            gray.release()
            bin.release()
        }
    }
}
