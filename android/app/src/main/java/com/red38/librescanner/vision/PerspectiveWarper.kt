package com.red38.librescanner.vision

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

object PerspectiveWarper {
    fun targetSize(quad: List<Pt>?, srcWidth: Int, srcHeight: Int, maxSide: Int = 1600): Pair<Int, Int> {
        if (quad == null || quad.size != 4) {
            val s = maxSide.toDouble() / max(srcWidth, srcHeight)
            return max(1, (srcWidth * s).roundToInt()) to max(1, (srcHeight * s).roundToInt())
        }
        val q = orderQuad(quad).pts
        fun dist(a: Pt, b: Pt) = hypot(b.x - a.x, b.y - a.y)
        val width = max(dist(q[0], q[1]), dist(q[3], q[2]))
        val height = max(dist(q[0], q[3]), dist(q[1], q[2]))
        val s = maxSide.toDouble() / max(width, height)
        return max(1, (width * s).roundToInt()) to max(1, (height * s).roundToInt())
    }

    fun warp(src: Bitmap, quad: List<Pt>?, targetWidth: Int, targetHeight: Int): Bitmap {
        if (!OpenCv.ensureLoaded()) return src
        val srcMat = BitmapUtils.bitmapToMat(src)
        val dst = Mat()
        try {
            val size = Size(targetWidth.toDouble(), targetHeight.toDouble())
            if (quad == null || quad.size != 4) {
                Imgproc.resize(srcMat, dst, size, 0.0, 0.0, Imgproc.INTER_AREA)
            } else {
                val q = orderQuad(quad).pts
                val srcPts = Mat(4, 1, CvType.CV_32FC2)
                val dstPts = Mat(4, 1, CvType.CV_32FC2)
                var transform: Mat? = null
                try {
                    srcPts.put(0, 0, *doubleArrayOf(q[0].x, q[0].y, q[1].x, q[1].y, q[2].x, q[2].y, q[3].x, q[3].y))
                    dstPts.put(
                        0, 0,
                        *doubleArrayOf(
                            0.0, 0.0,
                            targetWidth.toDouble(), 0.0,
                            targetWidth.toDouble(), targetHeight.toDouble(),
                            0.0, targetHeight.toDouble(),
                        ),
                    )
                    transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
                    Imgproc.warpPerspective(
                        srcMat, dst, transform, size,
                        Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar.all(0.0),
                    )
                } finally {
                    srcPts.release()
                    dstPts.release()
                    transform?.release()
                }
            }
            return BitmapUtils.matToBitmap(dst)
        } finally {
            srcMat.release()
            dst.release()
        }
    }
}
