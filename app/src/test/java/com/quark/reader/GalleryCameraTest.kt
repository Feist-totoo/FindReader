package com.quark.reader

import org.junit.Assert.*
import org.junit.Test

class GalleryCameraTest {
    private fun project(angle: Float, width: Float = 400f, height: Float = 700f) =
        GalleryCamera.project(angle, width, height, 240f, 360f, 1f, 0f)

    @Test fun frontIsCenteredAndSideWallsAreOutsideView() {
        val front = project(0f)!!
        assertEquals(80f, front[0], 0.001f)
        assertEquals(320f, front[2], 0.001f)
        assertEquals(170f, front[1], 0.001f)
        assertNull(project(90f))
        assertNull(project(-90f))
        assertNull(project(180f))
        assertNull(project(360f))
    }

    @Test fun quarterTurnReplacesTheFrontWithoutWrappingOlderBays() {
        for (count in listOf(1, 2, 4, 5, 12)) {
            for (index in 0 until count) {
                val yaw = index * 90f
                val visible = (0 until count).filter { project(it * 90f - yaw) != null }
                assertEquals(listOf(index), visible)
                val left = (0 until count).maxOf { GalleryCamera.sideLight(it * 90f - yaw, false) }
                val right = (0 until count).maxOf { GalleryCamera.sideLight(it * 90f - yaw, true) }
                assertEquals(if (index > 0) 0.42f else 0f, left, 0.001f)
                assertEquals(if (index < count - 1) 0.42f else 0f, right, 0.001f)
            }
        }
    }

    @Test fun turningUsesPerspectiveAndMirrorSymmetry() {
        val left = project(-30f)!!
        val right = project(30f)!!
        assertEquals(400f - right[2], left[0], 0.001f)
        assertEquals(right[3], left[1], 0.001f)
        // The nearer edge grows vertically: this is a projective plane, not a flat carousel card.
        assertTrue(right[5] - right[3] > right[7] - right[1])
        assertNotNull(project(-45f))
        assertNotNull(project(45f))
    }

    @Test fun draggingHasContinuousBoundedResistanceAndCanBeResumed() {
        for (count in listOf(1, 2, 5)) {
            val max = GalleryCamera.maxYaw(count)
            for (raw in listOf(-100000f, -100f, -1f, 0f, max, max + 1f, max + 100f, max + 100000f)) {
                val display = GalleryCamera.damp(raw, count)
                assertTrue(display >= -12f && display <= max + 12f)
                assertTrue(GalleryCamera.clampYaw(display, count) in 0f..max)
                if (kotlin.math.abs(raw) < 10000f) {
                    assertEquals(raw, GalleryCamera.undamp(display, count), 0.01f)
                }
            }
            val first = GalleryCamera.damp(max + 10f, count) - max
            val second = GalleryCamera.damp(max + 20f, count) - GalleryCamera.damp(max + 10f, count)
            assertTrue(second < first)
        }
    }

    @Test fun releaseUsesCurrentYawAndReturnsToTheEndpointWhenOverScrolled() {
        assertEquals(3, GalleryCamera.releaseTarget(205f, -1200f, 500f, 6))
        assertEquals(2, GalleryCamera.releaseTarget(205f, 1200f, 500f, 6))
        assertEquals(2, GalleryCamera.releaseTarget(205f, 0f, 500f, 6))
        assertEquals(0, GalleryCamera.releaseTarget(-8f, -3000f, 500f, 6))
        assertEquals(5, GalleryCamera.releaseTarget(458f, 3000f, 500f, 6))
        assertEquals(0, GalleryCamera.releaseTarget(8f, -3000f, 500f, 1))
    }

    @Test fun allSupportedViewportsHaveFiniteProjectionAndInvalidSizeIsIgnored() {
        for ((width, height) in listOf(320f to 700f, 800f to 400f, 1200f to 900f)) {
            for (angle in -450..450) {
                project(angle.toFloat(), width, height)?.forEach { assertTrue(it.isFinite()) }
            }
        }
        assertNull(project(0f, 0f))
        assertEquals(0f, GalleryCamera.maxYaw(0), 0f)
    }
}
