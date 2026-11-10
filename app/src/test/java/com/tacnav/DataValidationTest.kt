package com.tacnav

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DataValidationTest {
    private fun manifest()=MapManifest("시험 지도",listOf(126.0,37.0,127.0,38.0),5,14,10.0,"2026-01-01","2026-01-02","Copernicus Sentinel-2","Copernicus terms","imagery.pmtiles",127,"0".repeat(64)).json()
    @Test fun boundedReaderWorksOnOlderAndroidApis() {
        val text="{\"type\":\"FeatureCollection\",\"features\":[]}"
        assertEquals(text,GeoJsonExchange.read(text.byteInputStream()))
        assertThrows(Exception::class.java) { GeoJsonExchange.read(java.io.ByteArrayInputStream(ByteArray(GeoJsonExchange.MAX_BYTES+1))) }
    }
    @Test fun manifestRoundTrip() { val parsed=MapManifest.parse(manifest().toString());assertEquals("시험 지도",parsed.name);assertTrue(parsed.contains(GeoPoint(37.5,126.5)));assertFalse(parsed.contains(GeoPoint(36.0,126.5))) }
    @Test fun manifestRejectsPathTraversal() { val j=manifest();j.getJSONObject("tiles").put("path","../imagery.pmtiles");assertThrows(Exception::class.java) { MapManifest.parse(j.toString()) } }
    @Test fun manifestRejectsRemoteUrls() { val j=manifest();j.getJSONObject("tiles").put("path","https://example.org/a.pmtiles");assertThrows(Exception::class.java) { MapManifest.parse(j.toString()) } }
    @Test fun manifestRejectsReversedBounds() { val j=manifest().put("bounds",JSONArray(listOf(127,37,126,38)));assertThrows(Exception::class.java) { MapManifest.parse(j.toString()) } }
    @Test fun manifestRejectsBadHashAndSize() { for(field in listOf("sha256","bytes")) { val j=manifest();j.getJSONObject("tiles").put(field,if(field=="sha256") "invalid" else -1);assertThrows(Exception::class.java) { MapManifest.parse(j.toString()) } } }
    @Test fun manifestRejectsUnsupportedVersion() { assertThrows(Exception::class.java) { MapManifest.parse(manifest().put("version",2).toString()) } }
    @Test fun legacyRasterAndExplicitVectorManifest() {
        val legacy=manifest().apply { remove("kind") }
        assertEquals("raster",MapManifest.parse(legacy.toString()).kind)
        val vector=manifest().put("kind","protomaps-v4").put("resolutionMeters",0)
        val parsed=MapManifest.parse(vector.toString())
        assertEquals(parsed,MapManifest.parse(parsed.json().toString()))
        val style=org.json.JSONObject(vectorStyle("pmtiles://file:///local/map.pmtiles",parsed))
        assertEquals("asset://fonts/{fontstack}/{range}.pbf",style.getString("glyphs"))
        assertEquals("vector",style.getJSONObject("sources").getJSONObject("imagery").getString("type"))
    }
    @Test fun manifestRejectsUnknownKindAndIncorrectResolution() {
        for(kind in listOf("remote-style","protomaps-v4")) {
            assertThrows(Exception::class.java) { MapManifest.parse(manifest().put("kind",kind).toString()) }
        }
        assertThrows(Exception::class.java) { MapManifest.parse(manifest().put("resolutionMeters",0).toString()) }
    }
    @Test fun geoJsonUsesLongitudeLatitudeOrder() {
        val text=GeoJsonExchange.export(listOf(Waypoint(name="서울",latitude=37.5,longitude=126.5)),emptyList(),emptyList())
        val parsed=GeoJsonExchange.parse(text)
        assertEquals(37.5,parsed.waypoints.single().latitude,0.0)
        assertEquals(126.5,parsed.waypoints.single().longitude,0.0)
    }
    @Test fun geoJsonPreservesReceptionGaps() {
        val points=listOf(0,0,1,1).mapIndexed { index,segment -> TrackPoint(trackId=1,latitude=37.0+index*.001,longitude=127.0,time=index.toLong(),accuracy=3f,segment=segment) }
        val text=GeoJsonExchange.export(emptyList(),listOf(Track(id=1,endedAt=1)),points)
        val parsed=GeoJsonExchange.parse(text)
        assertEquals(2,parsed.tracks.single().size)
        assertEquals(2,parsed.tracks.single().first().size)
    }
    @Test fun geoJsonRejectsUnsupportedGeometry() { assertThrows(Exception::class.java) { GeoJsonExchange.parse("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[]}}]}""") } }
    @Test fun geoJsonRejectsOutOfRangePoint() { assertThrows(Exception::class.java) { GeoJsonExchange.parse("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[300,37]}}]}""") } }
    @Test fun geoJsonRejectsAlternateCrs() { assertThrows(Exception::class.java) { GeoJsonExchange.parse("""{"type":"FeatureCollection","crs":{},"features":[]}""") } }
}
