package com.tacnav

import java.time.Instant
import java.time.ZoneOffset

object MagneticModel {
    init { System.loadLibrary("tacnav_wmm") }
    @Synchronized external fun compute(latitude: Double, longitude: Double, altitudeKm: Double, year: Double): Double
    fun declination(point: GeoPoint, altitudeMeters: Double = 0.0, time: Long = System.currentTimeMillis()): Double? {
        val date = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC)
        val start = date.withDayOfYear(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = date.withDayOfYear(1).plusYears(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val year = date.year + (time - start).toDouble() / (end - start)
        return compute(point.latitude, point.longitude, altitudeMeters / 1000, year).takeIf { it.isFinite() }
    }
}
