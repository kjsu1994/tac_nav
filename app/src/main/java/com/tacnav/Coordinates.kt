package com.tacnav

import mil.nga.grid.features.Point
import mil.nga.mgrs.MGRS
import mil.nga.mgrs.utm.UTM
import net.sf.geographiclib.Geodesic
import java.util.Locale
import kotlin.math.*

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -80.0..84.0) { "위도는 남위 80°~북위 84°만 지원합니다." }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "경도는 -180°~180°여야 합니다." }
    }
    fun nga(): Point = Point.degrees(longitude, latitude)
}
data class CoordinateCell(val center: GeoPoint, val corners: List<GeoPoint>, val sizeMeters: Int)
data class RangeBearing(val meters: Double, val trueDegrees: Double)
enum class CoordinateFormat(val label: String) { DD("십진도"), DM("도·분"), DMS("도·분·초") }
enum class NorthReference(val label: String) { TRUE("진북"), MAGNETIC("자북"), GRID("도북") }

object Coordinates {
    fun mgrs(point: GeoPoint, digits: Int = 5): String {
        require(digits in 1..5)
        val value=MGRS.from(point.nga())
        val east=String.format(Locale.US,"%05d",value.easting).take(digits)
        val north=String.format(Locale.US,"%05d",value.northing).take(digits)
        return "${value.zone}${value.band} ${value.column}${value.row} $east $north"
    }
    fun parseMgrs(text: String): CoordinateCell {
        val raw = text.replace(Regex("\\s+"), "").uppercase(Locale.ROOT)
        val match = Regex("^(?:[1-9]|[1-5][0-9]|60)[C-HJ-NP-X][A-HJ-NP-Z]{2}([0-9]{2,10})$").matchEntire(raw)
        requireNotNull(match) { "구역·격자 문자가 포함된 완전한 MGRS 좌표를 입력하세요." }
        val count = match.groupValues[1].length
        require(count % 2 == 0) { "동·북방 좌표 자릿수가 같아야 합니다." }
        val value = MGRS.parse(raw)
        val utm = value.toUTM()
        val size = 10.0.pow(5 - count / 2).toInt()
        fun offset(e: Double, n: Double): GeoPoint {
            val p = UTM.point(utm.zone, utm.hemisphere, utm.easting + e, utm.northing + n).toDegrees()
            return GeoPoint(p.latitude, p.longitude)
        }
        return CoordinateCell(offset(size / 2.0, size / 2.0), listOf(offset(0.0, 0.0), offset(size.toDouble(), 0.0), offset(size.toDouble(), size.toDouble()), offset(0.0, size.toDouble())), size)
    }
    fun parseAngle(text: String, latitude: Boolean): Double {
        val raw = text.trim().uppercase(Locale.ROOT)
        require(raw.isNotEmpty()) { "좌표를 입력하세요." }
        val hemispheres = raw.filter { it in "NSEW" }
        require(hemispheres.isEmpty() || raw.first() in "NSEW" || raw.last() in "NSEW") { "방향 문자는 좌표 앞이나 뒤에만 입력하세요." }
        require(hemispheres.length <= 1) { "방향 문자는 하나만 입력하세요." }
        require(hemispheres.all { it in if (latitude) "NS" else "EW" }) { "위도는 N/S, 경도는 E/W를 사용하세요." }
        val cleaned = raw.replace(Regex("[NSEW°º′'″\"]"), " ").trim()
        require(!Regex("[^0-9+\\-.\\s]").containsMatchIn(cleaned)) { "지원하지 않는 좌표 문자입니다." }
        val tokens = cleaned.split(Regex("\\s+"))
        require(tokens.size in 1..3) { "십진도, 도·분, 도·분·초 형식을 사용하세요." }
        val parts = tokens.map { it.toDoubleOrNull() ?: error("잘못된 좌표 숫자입니다.") }
        require(parts.all { it.isFinite() })
        require(parts.drop(1).all { it >= 0 && it < 60 }) { "분과 초는 0 이상 60 미만이어야 합니다." }
        require(parts.size == 1 || parts.first() % 1.0 == 0.0) { "도·분 형식의 도는 정수여야 합니다." }
        require(parts.size < 3 || parts[1] % 1.0 == 0.0) { "도·분·초 형식의 분은 정수여야 합니다." }
        require(hemispheres.isEmpty() || !cleaned.startsWith('-') && !cleaned.startsWith('+')) { "부호와 방향 문자를 함께 쓰지 마세요." }
        val negative = cleaned.startsWith('-') || hemispheres.any { it == 'S' || it == 'W' }
        val angle = abs(parts[0]) + parts.getOrElse(1) { 0.0 } / 60 + parts.getOrElse(2) { 0.0 } / 3600
        val result = if (negative) -angle else angle
        require(if (latitude) result in -80.0..84.0 else result in -180.0..180.0) { "지원하는 좌표 범위를 벗어났습니다." }
        return result
    }
    fun format(point: GeoPoint, format: CoordinateFormat): String =
        "${formatAngle(point.latitude, true, format)}  ${formatAngle(point.longitude, false, format)}"
    fun formatAngle(value: Double, latitude: Boolean, format: CoordinateFormat): String {
        val hemisphere = if (latitude) { if (value < 0) "S" else "N" } else { if (value < 0) "W" else "E" }
        val magnitude = abs(value)
        return when (format) {
            CoordinateFormat.DD -> String.format(Locale.US, "%.6f°%s", magnitude, hemisphere)
            CoordinateFormat.DM -> {
                val total = round(magnitude * 60 * 10000).toLong()
                String.format(Locale.US, "%d°%07.4f′%s", total / 600000, total % 600000 / 10000.0, hemisphere)
            }
            CoordinateFormat.DMS -> {
                val total = round(magnitude * 3600 * 100).toLong()
                String.format(Locale.US, "%d°%02d′%05.2f″%s", total / 360000, total / 6000 % 60, total % 6000 / 100.0, hemisphere)
            }
        }
    }
    fun range(from: GeoPoint, to: GeoPoint): RangeBearing {
        val inverse = Geodesic.WGS84.Inverse(from.latitude, from.longitude, to.latitude, to.longitude)
        return RangeBearing(inverse.s12, normalize(inverse.azi1))
    }
    fun normalize(degrees: Double) = ((degrees % 360) + 360) % 360
    fun bearing(trueDegrees: Double, reference: NorthReference, declination: Double?, convergence: Double): Double? = when (reference) {
        NorthReference.TRUE -> normalize(trueDegrees)
        NorthReference.MAGNETIC -> declination?.let { normalize(trueDegrees - it) }
        NorthReference.GRID -> normalize(trueDegrees - convergence)
    }
    fun convergence(point: GeoPoint): Double {
        val utm = UTM.from(point.nga())
        val north = UTM.point(utm.zone, utm.hemisphere, utm.easting, utm.northing + 10).toDegrees()
        val azi = Geodesic.WGS84.Inverse(point.latitude, point.longitude, north.latitude, north.longitude).azi1
        return azi
    }
    fun bearingText(degrees: Double?, mils: Boolean): String = degrees?.let {
        if (mils) String.format(Locale.US, "%.1f° / %.0f mil", normalize(it), normalize(it) * 6400 / 360)
        else String.format(Locale.US, "%.1f°", normalize(it))
    } ?: "계산 불가"
}
