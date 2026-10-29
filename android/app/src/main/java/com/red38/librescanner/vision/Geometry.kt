package com.red38.librescanner.vision

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Pt(val x: Double, val y: Double)

data class Quad(val pts: List<Pt>, val score: Double = 0.0)

internal fun polygonArea(pts: List<Pt>): Double {
    var a = 0.0
    for (i in pts.indices) {
        val p = pts[i]
        val q = pts[(i + 1) % pts.size]
        a += p.x * q.y - q.x * p.y
    }
    return abs(a) / 2.0
}

internal fun isConvexQuad(pts: List<Pt>): Boolean {
    if (pts.size != 4) return false
    var sign = 0
    for (i in 0 until 4) {
        val a = pts[(i + 3) % 4]
        val b = pts[i]
        val c = pts[(i + 1) % 4]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        if (abs(cross) < 1e-6) return false
        val s = if (cross > 0) 1 else -1
        if (sign == 0) sign = s else if (s != sign) return false
    }
    return true
}

internal fun maxAngleDeviation(pts: List<Pt>): Double {
    var dev = 0.0
    for (i in 0 until 4) {
        val a = pts[(i + 3) % 4]
        val b = pts[i]
        val c = pts[(i + 1) % 4]
        val v1x = a.x - b.x
        val v1y = a.y - b.y
        val v2x = c.x - b.x
        val v2y = c.y - b.y
        val denom = hypot(v1x, v1y) * hypot(v2x, v2y)
        val dot = if (denom == 0.0) 1.0 else (v1x * v2x + v1y * v2y) / denom
        val angle = acos(dot.coerceIn(-1.0, 1.0)) * 180.0 / Math.PI
        dev = max(dev, abs(90.0 - angle))
    }
    return dev
}

internal fun minSide(pts: List<Pt>): Double {
    var m = Double.MAX_VALUE
    for (i in 0 until 4) {
        val p = pts[i]
        val q = pts[(i + 1) % 4]
        m = min(m, hypot(q.x - p.x, q.y - p.y))
    }
    return m
}

internal fun quadScore(pts: List<Pt>, w: Int, h: Int): Double {
    val area = polygonArea(pts)
    val frac = area / (w.toDouble() * h.toDouble())
    val xs = pts.map { it.x }
    val ys = pts.map { it.y }
    val aabb = max(1.0, (xs.max() - xs.min()) * (ys.max() - ys.min()))
    val fill = area / aabb
    val ang = 1.0 - min(1.0, maxAngleDeviation(pts) / 45.0)
    return frac * 0.55 + fill * 0.3 + ang * 0.15
}

fun orderQuad(quad: List<Pt>): Quad {
    if (quad.size != 4) return Quad(quad)
    val cx = quad.sumOf { it.x } / 4.0
    val left = quad.filter { it.x < cx }.sortedBy { it.y }
    val right = quad.filter { it.x >= cx }.sortedBy { it.y }
    val ordered = if (left.size == 2 && right.size == 2) {
        listOf(left[0], right[0], right[1], left[1])
    } else {
        val bySum = quad.sortedBy { it.x + it.y }
        val byDiff = quad.sortedBy { it.x - it.y }
        listOf(bySum.first(), byDiff.last(), bySum.last(), byDiff.first())
    }
    return Quad(ordered)
}
