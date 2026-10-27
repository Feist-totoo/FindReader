package com.quark.reader

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class ShelfBehaviorTest {
    @Test fun starsGrowOnlyWhenExistingSlotsAreExhausted() {
        assertEquals(7, StarShelfCapacity.count(0))
        assertEquals(7, StarShelfCapacity.count(49))
        assertEquals(8, StarShelfCapacity.count(50))
        assertEquals(8, StarShelfCapacity.count(56))
        assertEquals(9, StarShelfCapacity.count(57))
        assertEquals(100, StarShelfCapacity.count(700))
        assertEquals(101, StarShelfCapacity.count(701))
        assertFalse(StarShelfCapacity.full(0, 6))
        assertTrue(StarShelfCapacity.full(0, 7))
        assertFalse(StarShelfCapacity.full(1, 7))
        for (index in 0 until 900) assertEquals(index, StarShelfCapacity.group(index) * 7 + StarShelfCapacity.slot(index))
    }

    private fun assertNoOverlap(field: ConstellationField) {
        field.bodies.forEachIndexed { i, body ->
            assertTrue(body.azimuth >= 0 && body.azimuth < 360)
            assertTrue(body.elevation - body.radius >= 30.0 - 1e-6)
            assertTrue(body.elevation + body.radius <= 70.0 + 1e-6)
            for (j in 0 until i) {
                val other = field.bodies[j]
                assertTrue("overlap $i/$j", SkyGeometry.separation(body.vector, other.vector) >= (body.radius + other.radius) * 1.06 - 1e-6)
            }
        }
    }

    @Test fun oneHundredDriftingPatternsStayDisjointForTwoMinutes() {
        val field = ConstellationField(100)
        val before = field.bodies.map { it.vector }
        assertNoOverlap(field)
        repeat(1200) { field.advance(0.1f) { true }; assertNoOverlap(field) }
        field.bodies.forEachIndexed { i, body -> assertTrue(SkyGeometry.separation(body.vector, before[i]) > 0.01) }
    }

    @Test fun unfilledConstellationsRemainStillAndActAsObstacles() {
        val field = ConstellationField(7)
        val before = field.bodies.map { it.azimuth to it.elevation }
        repeat(1800) { field.advance(0.1f) { it < 3 }; assertNoOverlap(field) }
        for (i in 3..6) {
            assertEquals(before[i].first, field.bodies[i].azimuth, 0.0)
            assertEquals(before[i].second, field.bodies[i].elevation, 0.0)
        }
    }

    @Test fun longSuspensionDoesNotTeleportOrOverlapConstellations() {
        val field = ConstellationField(8)
        val before = field.bodies.map { it.vector }
        field.advance(3600f) { true }
        assertNoOverlap(field)
        field.bodies.forEachIndexed { i, body -> assertTrue(SkyGeometry.separation(body.vector, before[i]) < 0.04) }
    }

    @Test fun skyBandPackingAndTemplateExtentsSurviveCapacityGrowth() {
        for (count in listOf(7, 8, 11, 49, 100, 101, 700)) {
            val field = ConstellationField(count)
            assertNoOverlap(field)
            field.bodies.forEach { body ->
                for (angle in 0 until 360 step 15) {
                    val point = SkyGeometry.offset(body.azimuth, body.elevation,
                        body.radius * kotlin.math.cos(SkyGeometry.radians(angle.toDouble())),
                        body.radius * kotlin.math.sin(SkyGeometry.radians(angle.toDouble())))
                    val elevation = kotlin.math.asin(point.y) * 180 / Math.PI
                    assertTrue(elevation >= 30 - 1e-6 && elevation <= 70 + 1e-6)
                }
            }
        }
    }

    @Test fun sphericalCameraAndSeamUseShortestRotation() {
        val camera = SkyGeometry.Camera(359.0, 50.0)
        val center = camera.project(SkyGeometry.direction(359.0, 50.0))!!
        assertEquals(0.0, center.x, 1e-9); assertEquals(0.0, center.y, 1e-9)
        assertTrue(camera.project(SkyGeometry.direction(1.0, 50.0))!!.x > 0)
        assertTrue(camera.project(SkyGeometry.direction(359.0, 60.0))!!.y < 0)
        assertNull(camera.project(SkyGeometry.direction(179.0, -50.0)))
        assertEquals(2.0, SkyGeometry.shortest(359.0, 1.0), 0.0)
        val field = ConstellationField(7)
        field.bodies[0].azimuth = 359.99
        repeat(300) { field.advance(0.1f) { true }; assertNoOverlap(field) }
    }

    @Test fun cloudsGrowWithBooksAndOnlyTheSkyCycles() {
        assertEquals(1, CloudShelfPages.count(1))
        assertEquals(4, CloudShelfPages.count(28))
        assertEquals(5, CloudShelfPages.count(29))
        assertEquals(6, CloudShelfPages.count(36))
        assertEquals(100, CloudShelfPages.count(700))
        assertEquals(0, CloudShelfPages.phase(0))
        assertEquals(0, CloudShelfPages.phase(4))
        assertEquals(1, CloudShelfPages.phase(5))
        assertEquals(3, CloudShelfPages.phase(99))
    }

    @Test fun searchFindsTitleWordsAbbreviationsAndFullWidthLatin() {
        assertNotNull(ShelfSearch.score("时间之书：星海", "星海 时间"))
        assertNotNull(ShelfSearch.score("时间之书", "时书"))
        assertNotNull(ShelfSearch.score("ＤＵＮＥ：沙丘", "dune"))
        assertNull(ShelfSearch.score("时间之书", "山海"))
        assertTrue(ShelfSearch.score("时间之书", "时间之书")!! > ShelfSearch.score("时间之书续篇", "时间之书")!!)
        assertEquals(0, ShelfSearch.score("时间之书", "  "))
    }
}
