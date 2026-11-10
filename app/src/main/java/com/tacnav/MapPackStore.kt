package com.tacnav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

data class MapManifest(val name: String, val bounds: List<Double>, val minZoom: Int, val maxZoom: Int, val resolutionMeters: Double, val captured: String, val produced: String, val source: String, val license: String, val tileFile: String, val tileBytes: Long, val sha256: String, val kind: String = "raster") {
    val detail: String get() = if(kind == "protomaps-v4") "경량 2D 벡터 · 도로·지명" else "원본 $resolutionMeters m/px"
    fun contains(point: GeoPoint) = point.longitude in bounds[0]..bounds[2] && point.latitude in bounds[1]..bounds[3]
    fun json() = JSONObject().put("version",1).put("kind",kind).put("name",name).put("bounds",JSONArray(bounds)).put("minZoom",minZoom).put("maxZoom",maxZoom).put("resolutionMeters",resolutionMeters).put("captured",captured).put("produced",produced).put("source",source).put("license",license).put("tiles",JSONObject().put("path",tileFile).put("bytes",tileBytes).put("sha256",sha256))
    companion object {
        fun parse(text: String): MapManifest {
            val j = JSONObject(text)
            require(j.getInt("version") == 1) { "지원하지 않는 지도팩 버전입니다." }
            fun field(key: String) = j.getString(key).also { require(it.isNotBlank() && it.length <= 2048) { "지도 메타데이터 오류: $key" } }
            val b = j.getJSONArray("bounds")
            require(b.length() == 4)
            val bounds = (0..3).map { b.getDouble(it) }
            require(bounds.all { it.isFinite() } && bounds[0] >= -180 && bounds[2] <= 180 && bounds[0] < bounds[2] && bounds[1] >= -80 && bounds[3] <= 84 && bounds[1] < bounds[3]) { "지도 범위가 올바르지 않습니다." }
            val min = j.getInt("minZoom"); val max = j.getInt("maxZoom")
            require(min in 0..22 && max in min..22)
            val resolution = j.getDouble("resolutionMeters")
            val kind = j.optString("kind","raster")
            require(kind in setOf("raster","protomaps-v4"))
            require(resolution.isFinite() && if(kind == "raster") resolution > 0 else resolution == 0.0)
            val t = j.getJSONObject("tiles")
            val path = t.getString("path")
            require(Regex("[A-Za-z0-9_-]+\\.pmtiles").matches(path)) { "지도 파일명이 잘못되었습니다." }
            val bytes = t.getLong("bytes")
            require(bytes in 127..MAX_PACK_BYTES)
            val hash = t.getString("sha256").lowercase()
            require(Regex("[0-9a-f]{64}").matches(hash))
            return MapManifest(field("name"),bounds,min,max,resolution,field("captured"),field("produced"),field("source"),field("license"),path,bytes,hash,kind)
        }
    }
}
const val MAX_PACK_BYTES = 16L * 1024 * 1024 * 1024

const val BUILT_IN_MAP_ID = "builtin-korea-2d"

data class InstalledMap(val id: String, val manifest: MapManifest, val directory: File) {
    val builtIn: Boolean get() = id == BUILT_IN_MAP_ID
    private val tileUrl: String get() = if(builtIn) "pmtiles://asset://maps/${manifest.tileFile}" else "pmtiles://file://${File(directory,manifest.tileFile).absolutePath}"
    fun style(): String = if(manifest.kind == "protomaps-v4") vectorStyle(tileUrl,manifest) else JSONObject().put("version",8).put("sources",JSONObject().put("imagery",JSONObject()
        .put("type","raster").put("url",tileUrl)
        .put("tileSize",256).put("minzoom",manifest.minZoom).put("maxzoom",manifest.maxZoom).put("bounds",JSONArray(manifest.bounds))))
        .put("layers",JSONArray().put(JSONObject().put("id","background").put("type","background").put("paint",JSONObject().put("background-color","#18211e")))
            .put(JSONObject().put("id","imagery").put("type","raster").put("source","imagery"))).toString()
}
class MapPackStore(private val context: Context) {
    private val root = File(context.filesDir,"maps").apply { mkdirs() }
    fun list(): List<InstalledMap> = listOf(InstalledMap(BUILT_IN_MAP_ID,context.assets.open("maps/manifest.json").bufferedReader().use { MapManifest.parse(it.readText()) },root)) + root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.mapNotNull { dir ->
        runCatching { InstalledMap(dir.name,MapManifest.parse(File(dir,"manifest.json").readText()),dir) }.getOrNull()
    }.sortedBy { it.manifest.name }
    fun import(input: InputStream): InstalledMap {
        val id = UUID.randomUUID().toString()
        val temp = File(root,".import-$id").apply { check(mkdir()) }
        try {
            var total = 0L
            val names = mutableSetOf<String>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(names.size < 8 && names.add(entry.name)) { "지도팩에 중복 또는 과도한 항목이 있습니다." }
                    val name = entry.name
                    require(!entry.isDirectory && (name == "manifest.json" || Regex("[A-Za-z0-9_-]+\\.pmtiles").matches(name))) { "허용하지 않는 지도팩 경로입니다." }
                    val limit = if (name == "manifest.json") 65536L else MAX_PACK_BYTES
                    var count = 0L
                    File(temp,name).outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val read = zip.read(buffer); if (read < 0) break
                            count += read; total += read
                            require(count <= limit && total <= MAX_PACK_BYTES + 65536) { "지도팩 크기 제한을 초과했습니다." }
                            require(temp.usableSpace > read + 64L * 1024 * 1024) { "지도 반입에 필요한 저장공간이 부족합니다." }
                            out.write(buffer,0,read)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifest = MapManifest.parse(File(temp,"manifest.json").readText())
            require(names == setOf("manifest.json",manifest.tileFile)) { "지도팩 구성 파일이 일치하지 않습니다." }
            val tiles = File(temp,manifest.tileFile)
            require(tiles.length() == manifest.tileBytes) { "지도 파일 크기가 일치하지 않습니다." }
            val digest = MessageDigest.getInstance("SHA-256")
            tiles.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val n=stream.read(buffer); if(n<0) break; digest.update(buffer,0,n) } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == manifest.sha256) { "지도 파일 손상: SHA-256 불일치" }
            tiles.inputStream().use { stream ->
                val header = ByteArray(127); require(stream.read(header) == 127)
                require(header.copyOfRange(0,7).toString(Charsets.US_ASCII) == "PMTiles" && header[7].toInt() == 3) { "PMTiles v3 파일이 아닙니다." }
                require(if(manifest.kind == "protomaps-v4") header[99].toInt() == 1 else header[99].toInt() in 2..4) { "지도 종류와 PMTiles 형식이 일치하지 않습니다." }
                val b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                for((offsetAt,lengthAt) in listOf(8 to 16,24 to 32,40 to 48,56 to 64)) {
                    val offset=b.getLong(offsetAt);val length=b.getLong(lengthAt)
                    require(offset>=0 && length>=0 && (length==0L || offset>=127 && offset<=tiles.length() && length<=tiles.length()-offset)) { "PMTiles 내부 범위가 잘못되었습니다." }
                    if(offsetAt==8 || offsetAt==56) require(length>0)
                }
                require(header[100].toInt()==manifest.minZoom && header[101].toInt()==manifest.maxZoom) { "지도 확대 범위가 메타데이터와 일치하지 않습니다." }
                for(i in 0..3) require(kotlin.math.abs(b.getInt(102+4*i)/1e7-manifest.bounds[i])<0.000001) { "지도 적용 범위가 메타데이터와 일치하지 않습니다." }
            }
            val target = File(root,id)
            check(temp.renameTo(target)) { "검증된 지도팩을 활성화하지 못했습니다." }
            return InstalledMap(id,manifest,target)
        } finally { if (temp.exists()) check(temp.deleteRecursively()) { "임시 지도 파일을 정리하지 못했습니다." } }
    }
    fun delete(map: InstalledMap) {
        require(!map.builtIn) { "앱 기본 지도는 삭제할 수 없습니다." }
        require(map.directory.canonicalFile.parentFile == root.canonicalFile && map.id == map.directory.name)
        check(map.directory.deleteRecursively()) { "지도 삭제에 실패했습니다." }
    }
}
