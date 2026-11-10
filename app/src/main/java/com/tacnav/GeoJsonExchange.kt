package com.tacnav

import java.io.InputStream
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class ImportedData(val waypoints: List<Waypoint>, val tracks: List<List<List<GeoPoint>>>)
object GeoJsonExchange {
    const val MAX_BYTES = 10 * 1024 * 1024
    fun export(waypoints: List<Waypoint>, tracks: List<Track>, points: List<TrackPoint>): String {
        val features = JSONArray()
        waypoints.forEach { w -> features.put(JSONObject().put("type","Feature").put("properties",JSONObject().put("name",w.name).put("createdAt",w.createdAt))
            .put("geometry",JSONObject().put("type","Point").put("coordinates",coordinate(w.point())))) }
        tracks.forEach { t ->
            val segments = points.filter { it.trackId == t.id }.groupBy { it.segment }.values.filter { it.size >= 2 }
            if (segments.isNotEmpty()) features.put(JSONObject().put("type","Feature").put("properties",JSONObject().put("name","이동기록 ${t.id}").put("startedAt",t.startedAt).put("endedAt",t.endedAt).put("interrupted",t.interrupted))
                .put("geometry",JSONObject().put("type","MultiLineString").put("coordinates",JSONArray(segments.map { segment -> JSONArray(segment.map { coordinate(it.point()) }) }))))
        }
        return JSONObject().put("type","FeatureCollection").put("features",features).toString(2)
    }
    fun read(input: InputStream): String {
        val output=ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        while(true) {
            val n=input.read(buffer)
            if(n<0) break
            require(output.size()+n<=MAX_BYTES) { "교환 파일은 10MB 이하여야 합니다." }
            output.write(buffer,0,n)
        }
        return output.toString("UTF-8")
    }
    fun parse(text: String): ImportedData {
        require(text.toByteArray().size <= MAX_BYTES) { "교환 파일은 10MB 이하여야 합니다." }
        val root=JSONObject(text)
        require(root.getString("type") == "FeatureCollection" && !root.has("crs")) { "WGS84 GeoJSON FeatureCollection만 지원합니다." }
        val features=root.getJSONArray("features")
        require(features.length() <= 10000) { "항목 수 제한을 초과했습니다." }
        val waypoints=mutableListOf<Waypoint>(); val tracks=mutableListOf<List<List<GeoPoint>>>()
        var vertices=0
        fun point(a: JSONArray): GeoPoint { require(a.length() in 2..3); vertices++; require(vertices<=100000); return GeoPoint(a.getDouble(1),a.getDouble(0)) }
        fun line(a: JSONArray): List<GeoPoint> { require(a.length()>=2); return (0 until a.length()).map { point(a.getJSONArray(it)) } }
        for(i in 0 until features.length()) {
            val f=features.getJSONObject(i); require(f.getString("type")=="Feature")
            val geometry=f.getJSONObject("geometry"); val coordinates=geometry.getJSONArray("coordinates")
            when(geometry.getString("type")) {
                "Point" -> { val p=point(coordinates); val name=f.optJSONObject("properties")?.optString("name")?.takeIf { it.isNotBlank() } ?: "반입 지점"; require(name.length<=80); waypoints.add(Waypoint(name=name,latitude=p.latitude,longitude=p.longitude)) }
                "LineString" -> tracks.add(listOf(line(coordinates)))
                "MultiLineString" -> { require(coordinates.length()>0); tracks.add((0 until coordinates.length()).map { line(coordinates.getJSONArray(it)) }) }
                else -> error("Point·LineString·MultiLineString만 반입할 수 있습니다.")
            }
        }
        return ImportedData(waypoints,tracks)
    }
    private fun coordinate(p: GeoPoint) = JSONArray().put(p.longitude).put(p.latitude)
}
