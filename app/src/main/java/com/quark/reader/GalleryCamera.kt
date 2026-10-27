package com.quark.reader

import kotlin.math.*

/** Unwrapped yaw keeps collections larger than four bays ordered, without a 360° wrap. */
internal object GalleryCamera {
    const val BAY_ANGLE = 90f
    const val MAX_OVER_SCROLL = 12f
    private const val RESISTANCE = 0.35f

    fun maxYaw(count: Int) = (count - 1).coerceAtLeast(0) * BAY_ANGLE
    fun clampYaw(yaw: Float, count: Int) = yaw.coerceIn(0f, maxYaw(count))
    fun nearest(yaw: Float, count: Int) =
        (clampYaw(yaw, count) / BAY_ANGLE).roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))

    // Logical yaw is clamped; only the rendered camera gets this bounded elastic offset.
    fun damp(raw: Float, count: Int): Float {
        val edge = clampYaw(raw, count)
        val excess = raw - edge
        return edge + excess * RESISTANCE / (1f + abs(excess) * RESISTANCE / MAX_OVER_SCROLL)
    }

    fun undamp(displayYaw: Float, count: Int): Float {
        val edge = clampYaw(displayYaw, count)
        val excess = (displayYaw - edge).coerceIn(-MAX_OVER_SCROLL + 0.001f, MAX_OVER_SCROLL - 0.001f)
        return edge + excess / (RESISTANCE * (1f - abs(excess) / MAX_OVER_SCROLL))
    }

    fun releaseTarget(yaw: Float, velocityX: Float, flingThreshold: Float, count: Int): Int {
        if (yaw < 0f) return 0
        if (yaw > maxYaw(count)) return (count - 1).coerceAtLeast(0)
        val target = when {
            velocityX < -flingThreshold -> floor(yaw / BAY_ANGLE).toInt() + 1
            velocityX > flingThreshold -> ceil(yaw / BAY_ANGLE).toInt() - 1
            else -> nearest(yaw, count)
        }
        return target.coerceIn(0, (count - 1).coerceAtLeast(0))
    }

    /** Continuous, soft side light from a bay on the adjacent wall, even outside the frustum. */
    fun sideLight(relativeAngle: Float, right: Boolean): Float {
        val angle = if (right) relativeAngle else -relativeAngle
        val t = (1f - abs(angle - BAY_ANGLE) / 65f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t) * 0.42f
    }

    fun focalLength(viewportWidth: Float): Float = viewportWidth / (2f * tan(Math.toRadians(42.0)).toFloat())

    /** Project a wall-facing plane through a stationary first-person camera (84° horizontal FOV).
     * x/z, not a linear screen translation, determines position and perspective foreshortening.
     * Returns TL, TR, BR, BL, or null for a plane behind/outside the camera.
     */
    fun project(relativeAngle: Float, width: Float, height: Float,
                bayWidth: Float, bayHeight: Float, fit: Float, verticalOffset: Float): FloatArray? {
        if (width <= 0f || height <= 0f || bayWidth <= 0f || bayHeight <= 0f || abs(relativeAngle) >= 89f) return null
        val radians = Math.toRadians(relativeAngle.toDouble())
        val sine = sin(radians).toFloat()
        val cosine = cos(radians).toFloat()
        val radius = focalLength(width)
        val points = FloatArray(8)
        for (corner in 0..3) {
            val x = (if (corner == 0 || corner == 3) -0.5f else 0.5f) * bayWidth * fit
            val y = ((if (corner < 2) -0.5f else 0.5f) * bayHeight + verticalOffset) * fit
            val depth = radius * cosine - x * sine
            if (depth < radius * 0.05f) return null
            points[corner * 2] = width * 0.5f + radius * (radius * sine + x * cosine) / depth
            points[corner * 2 + 1] = height * 0.5f + radius * y / depth
        }
        if ((0..3).all { points[it * 2] < 0f } || (0..3).all { points[it * 2] > width }) return null
        return points
    }
}
