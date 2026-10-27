package com.quark.reader

import kotlin.math.*

internal object StarShelfCapacity {
    const val SLOTS = 7
    const val INITIAL = 7
    fun count(books: Int) = maxOf(INITIAL, (books + SLOTS - 1) / SLOTS)
    fun group(book: Int) = book / SLOTS
    fun slot(book: Int) = book % SLOTS
    fun full(group: Int, books: Int) = books >= (group + 1) * SLOTS
}

/** Angles are degrees, elevation is measured above the horizon. */
internal object SkyGeometry {
    data class Vector(val x: Double, val y: Double, val z: Double) {
        operator fun plus(v: Vector) = Vector(x + v.x, y + v.y, z + v.z)
        operator fun times(n: Double) = Vector(x * n, y * n, z * n)
        fun dot(v: Vector) = x * v.x + y * v.y + z * v.z
    }
    fun radians(degrees: Double) = degrees * PI / 180.0
    fun wrap(degrees: Double) = (degrees % 360.0 + 360.0) % 360.0
    fun shortest(from: Double, to: Double) = wrap(to - from + 180.0) - 180.0
    fun direction(azimuth: Double, elevation: Double): Vector {
        val a = radians(azimuth); val e = radians(elevation)
        return Vector(cos(e) * sin(a), sin(e), cos(e) * cos(a))
    }
    fun separation(a: Vector, b: Vector) = acos(a.dot(b).coerceIn(-1.0, 1.0)) * 180.0 / PI
    /** Template points stay inside a spherical cap, including at the azimuth seam. */
    fun offset(azimuth: Double, elevation: Double, east: Double, north: Double): Vector {
        val center = direction(azimuth, elevation)
        val length = hypot(east, north)
        if (length < 1e-9) return center
        val a = radians(azimuth); val e = radians(elevation)
        val right = Vector(cos(a), 0.0, -sin(a))
        val up = Vector(-sin(e) * sin(a), cos(e), -sin(e) * cos(a))
        val angle = radians(length)
        return center * cos(angle) + (right * (east / length) + up * (north / length)) * sin(angle)
    }
    data class Projection(val x: Double, val y: Double, val depth: Double)
    class Camera(azimuth: Double, elevation: Double) {
        private val forward = direction(azimuth, elevation)
        private val a = radians(azimuth)
        private val e = radians(elevation)
        private val right = Vector(cos(a), 0.0, -sin(a))
        private val up = Vector(-sin(e) * sin(a), cos(e), -sin(e) * cos(a))
        fun project(point: Vector): Projection? {
            val depth = forward.dot(point)
            if (depth <= 0.05) return null
            return Projection(right.dot(point) / depth, -up.dot(point) / depth, depth)
        }
    }
}

/** Disjoint spherical caps confined entirely to the 30°–70° elevation band. */
internal class ConstellationField(val count: Int) {
    data class Body(var azimuth: Double, var elevation: Double, var heading: Double, var radius: Double = 8.0) {
        val vector get() = SkyGeometry.direction(azimuth, elevation)
    }
    val bodies: List<Body>
    private var time = 0.0
    init {
        require(count > 0)
        val rows = ceil(sqrt(count / 10.0)).toInt().coerceAtLeast(1)
        var remaining = count
        val positions = mutableListOf<Body>()
        repeat(rows) { row ->
            val columns = (remaining + rows - row - 1) / (rows - row)
            val elevation = 30.0 + (row + 0.5) * 40.0 / rows
            repeat(columns) { column ->
                positions.add(Body(SkyGeometry.wrap((column + (row % 2) * 0.5) * 360.0 / columns),
                    elevation, positions.size * 2.399963229728653))
            }
            remaining -= columns
        }
        var radius = minOf(8.0, 16.0 / rows)
        for (i in positions.indices) for (j in 0 until i) {
            radius = minOf(radius, SkyGeometry.separation(positions[i].vector, positions[j].vector) / 2.4)
        }
        positions.forEach { it.radius = radius }
        bodies = positions
    }
    fun advance(seconds: Float, active: (Int) -> Boolean) {
        var remaining = seconds.coerceIn(0f, 0.2f)
        val vectors = bodies.map { it.vector }.toMutableList()
        while (remaining > 0f) {
            val dt = minOf(remaining, 1f / 30f).toDouble()
            remaining = (remaining - dt).toFloat()
            time += dt
            bodies.forEachIndexed { i, body ->
                if (!active(i)) return@forEachIndexed
                body.heading += (sin(time * 0.13 + i * 1.73) * 0.18 + sin(time * 0.071 + i * 4.1) * 0.11) * dt
                val speed = minOf(0.16, body.radius * 0.045) * (0.8 + (i % 5) * 0.06)
                val azimuth = SkyGeometry.wrap(body.azimuth + cos(body.heading) * speed * dt / cos(SkyGeometry.radians(body.elevation)))
                val elevation = body.elevation + sin(body.heading) * speed * dt
                val next = SkyGeometry.direction(azimuth, elevation)
                val blocked = bodies.indices.any { j ->
                    j != i && next.dot(vectors[j]) > cos(SkyGeometry.radians((body.radius + bodies[j].radius) * 1.06))
                }
                when {
                    elevation < 30.0 + body.radius || elevation > 70.0 - body.radius -> body.heading = -body.heading
                    blocked -> body.heading += PI * 0.63
                    else -> { body.azimuth = azimuth; body.elevation = elevation; vectors[i] = next }
                }
            }
        }
    }
}
