package com.red38.librescanner.vision

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object DocumentDetector {
    private const val MAX_SIDE = 1200.0
    private const val MIN_QUAD_SIDE = 25.0

    private data class Candidate(val pts: List<Pt>, val score: Double)

    fun detect(bitmap: Bitmap): Quad? {
        if (!OpenCv.ensureLoaded()) return null

        val maxSide = max(bitmap.width, bitmap.height)
        val scale = if (maxSide > MAX_SIDE) MAX_SIDE / maxSide else 1.0
        val w = max(1, (bitmap.width * scale).roundToInt())
        val h = max(1, (bitmap.height * scale).roundToInt())

        val srcMat = Mat()
        val small = Mat()
        val gray = Mat()
        val blur = Mat()
        val edges = Mat()
        val fixedMask = Mat()
        val hierarchy = Mat()
        val brightMasks = ArrayList<Mat>()
        val contours = ArrayList<MatOfPoint>()
        val candidates = ArrayList<Candidate>()
        val seen = HashSet<String>()

        fun collect(contour: MatOfPoint, minAreaFrac: Double = 0.1) {
            val area = abs(Imgproc.contourArea(contour, false))
            if (area < minAreaFrac * w * h) return

            val contourPts = contour.toArray()
            val hullIdx = MatOfInt()
            Imgproc.convexHull(contour, hullIdx)
            val hull = MatOfPoint(*hullIdx.toArray().map { contourPts[it] }.toTypedArray())
            hullIdx.release()

            try {
                for (basis in listOf(contour, hull)) {
                    val basis2f = MatOfPoint2f(*basis.toArray())
                    val perimeter = Imgproc.arcLength(basis2f, true)
                    try {
                        for (eps in doubleArrayOf(0.02, 0.035, 0.055, 0.075)) {
                            val approx = MatOfPoint2f()
                            Imgproc.approxPolyDP(basis2f, approx, eps * perimeter, true)
                            try {
                                if (approx.total() == 4L) {
                                    val pts = approx.toArray().map { Pt(it.x, it.y) }
                                    if (isConvexQuad(pts) && minSide(pts) >= MIN_QUAD_SIDE) {
                                        val key = pts.joinToString("|") { "${it.x.toInt()},${it.y.toInt()}" }
                                        if (seen.add(key)) {
                                            candidates.add(Candidate(pts, quadScore(pts, w, h)))
                                        }
                                    }
                                }
                            } finally {
                                approx.release()
                            }
                        }
                    } finally {
                        basis2f.release()
                    }
                }
            } finally {
                hull.release()
            }
        }

        try {
            Utils.bitmapToMat(BitmapUtils.toRgba(bitmap), srcMat)
            Imgproc.resize(srcMat, small, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)

            val mean = Core.mean(blur).`val`[0]
            Imgproc.Canny(blur, edges, max(30.0, mean * 0.5), max(90.0, mean * 1.2))
            Imgproc.Canny(blur, fixedMask, 40.0, 130.0)
            Core.bitwise_or(edges, fixedMask, edges)

            val dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            try {
                Imgproc.dilate(edges, edges, dilateKernel)
            } finally {
                dilateKernel.release()
            }

            for (cval in doubleArrayOf(7.0, 15.0)) {
                val bin = Mat()
                Imgproc.adaptiveThreshold(
                    blur, bin, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 51, cval,
                )
                val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
                try {
                    Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, closeKernel)
                } finally {
                    closeKernel.release()
                }
                brightMasks.add(bin)
            }

            val otsu = Mat()
            Imgproc.threshold(blur, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            val otsuKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            try {
                Imgproc.morphologyEx(otsu, otsu, Imgproc.MORPH_CLOSE, otsuKernel)
            } finally {
                otsuKernel.release()
            }
            brightMasks.add(otsu)

            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            contours.forEach { collect(it) }
            contours.forEach { it.release() }
            contours.clear()

            for (mask in brightMasks) {
                Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                contours.forEach { contour ->
                    if (abs(Imgproc.contourArea(contour, false)) >= 0.18 * w * h) {
                        collect(contour, 0.18)
                    }
                }
                contours.forEach { it.release() }
                contours.clear()
            }
        } finally {
            contours.forEach { it.release() }
            brightMasks.forEach { it.release() }
            srcMat.release()
            small.release()
            gray.release()
            blur.release()
            edges.release()
            fixedMask.release()
            hierarchy.release()
        }

        if (candidates.isEmpty()) return null

        val margin = 0.015 * max(w, h)
        val fullFrame = candidates.filter { c ->
            c.pts.minOf { it.x } < margin && c.pts.maxOf { it.x } > w - margin &&
                c.pts.minOf { it.y } < margin && c.pts.maxOf { it.y } > h - margin
        }
        val pool = if (candidates.size > fullFrame.size) candidates.filter { it !in fullFrame } else fullFrame
        val best = pool.maxByOrNull { it.score } ?: return null

        val ordered = orderQuad(best.pts)
        return Quad(
            ordered.pts.map { Pt(it.x / scale, it.y / scale) },
            best.score,
        )
    }
}
