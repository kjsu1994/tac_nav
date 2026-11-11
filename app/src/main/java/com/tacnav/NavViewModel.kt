package com.tacnav

import android.app.Application
import android.net.Uri
import androidx.room.withTransaction
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CameraRequest(val point: GeoPoint, val zoomToLocation: Boolean = false)

class NavViewModel(application: Application) : AndroidViewModel(application) {
    private val app=application as TacApplication
    val settings=app.settings.flow.stateIn(viewModelScope,SharingStarted.Eagerly,AppSettings())
    val waypoints=app.database.dao().waypoints().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val tracks=app.database.dao().tracks().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val points=app.database.dao().points().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val maps=MutableStateFlow(app.maps.list())
    val message=MutableStateFlow<String?>(null)
    val busy=MutableStateFlow(false)
    val center=MutableStateFlow(GeoPoint(37.5665,126.9780))
    val target=MutableStateFlow<GeoPoint?>(null)
    val cell=MutableStateFlow<CoordinateCell?>(null)
    val route=MutableStateFlow<List<GeoPoint>>(emptyList())
    val cameraRequest=MutableStateFlow<CameraRequest?>(null)
    val recording=MutableStateFlow(false)
    private val fixes=MutableSharedFlow<PositionFix>()
    private var recordingJob: Job?=null
    private var foreground=false
    fun setForeground(active: Boolean) { foreground=active; if(!active) stopRecording() }
    fun startRecording() {
        if(!foreground || recordingJob!=null) return
        if(!app.hasPreciseLocation()) { message.value="정확한 위치 권한이 필요합니다.";return }
        recording.value=true
        recordingJob=viewModelScope.launch(start=CoroutineStart.LAZY) {
            var id=0L
            var lastReceived=0L
            var lastSaved=0L
            var segment=0
            try {
                app.recovery.await()
                // Finish creating the row even if the screen closes during the insert.
                withContext(NonCancellable) { id=app.database.dao().insertTrack(Track()) }
                fixes.collect { fix ->
                    if(!foreground || fix.ageSeconds()>10 || fix.elapsedNanos<=lastReceived) return@collect
                    if(lastReceived>0 && fix.elapsedNanos-lastReceived>10_000_000_000L) segment++
                    lastReceived=fix.elapsedNanos
                    if(lastSaved>0 && fix.elapsedNanos-lastSaved<settings.value.trackIntervalSeconds*1_000_000_000L) return@collect
                    app.database.dao().insertPoint(TrackPoint(trackId=id,latitude=fix.point.latitude,longitude=fix.point.longitude,time=fix.time,accuracy=fix.accuracy,segment=segment))
                    lastSaved=fix.elapsedNanos
                }
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                message.value="이동기록 저장 실패. 위치 권한과 저장공간을 확인하세요."
            } finally {
                try { withContext(NonCancellable) { if(id!=0L) app.database.dao().finishTrack(id,System.currentTimeMillis()) } }
                catch(e: Exception) { message.value="기록 종료 저장 실패. 저장공간을 확인하고 앱을 다시 여세요." }
                recording.value=false
                recordingJob=null
            }
        }
        recordingJob?.start()
    }
    fun stopRecording() { recording.value=false;recordingJob?.cancel() }
    suspend fun recordFix(fix: PositionFix) { if(foreground && recording.value) fixes.emit(fix) }
    init { app.recovery; task { app.settings.update { current -> if(maps.value.any { it.id==current.activeMap }) current else current.copy(activeMap=BUILT_IN_MAP_ID) } } }
    fun updateSettings(change: (AppSettings)->AppSettings) { task { app.settings.update(change) } }
    fun select(point: GeoPoint, selectedCell: CoordinateCell?=null) { center.value=point; target.value=point; cell.value=selectedCell; cameraRequest.value=CameraRequest(point) }
    fun centerOnLocation(point: GeoPoint) { cameraRequest.value = CameraRequest(point, zoomToLocation = true) }
    fun saveWaypoint(name: String, point: GeoPoint) { task { require(name.trim().length in 1..80) { "지점 이름은 1~80자여야 합니다." }; app.database.dao().insertWaypoint(Waypoint(name=name.trim(),latitude=point.latitude,longitude=point.longitude)); message.value="지점을 저장했습니다." } }
    fun renameWaypoint(w: Waypoint,name: String) { task { require(name.trim().length in 1..80); app.database.dao().updateWaypoint(w.copy(name=name.trim())) } }
    fun deleteWaypoint(w: Waypoint) { task { app.database.dao().deleteWaypoint(w) } }
    fun deleteTrack(track: Track) { task { require(track.endedAt!=null) { "기록을 먼저 종료하세요." }; app.database.dao().deleteTrack(track) } }
    fun importMap(uri: Uri) { task(true) {
        val map=withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)?.use { app.maps.import(it) } ?: error("지도 파일을 열 수 없습니다.") }
        maps.value=withContext(Dispatchers.IO) { app.maps.list() }
        app.settings.update { it.copy(activeMap=map.id) }
        val b=map.manifest.bounds; select(GeoPoint((b[1]+b[3])/2,(b[0]+b[2])/2))
        message.value="지도팩 검증·반입 완료"
    } }
    fun deleteMap(map: InstalledMap) { task(true) {
        require(!map.builtIn) { "앱 기본 지도는 삭제할 수 없습니다." }
        app.settings.update { if(it.activeMap==map.id) it.copy(activeMap=BUILT_IN_MAP_ID) else it }
        withContext(Dispatchers.IO) { app.maps.delete(map) }
        maps.value=withContext(Dispatchers.IO) { app.maps.list() }
    } }
    fun importGeoJson(uri: Uri) { task(true) {
        val imported=withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { input ->
                GeoJsonExchange.parse(GeoJsonExchange.read(input))
            } ?: error("파일을 열 수 없습니다.")
        }
        app.database.dao().importData(imported.waypoints,imported.tracks)
        message.value="지점 ${imported.waypoints.size}개·이동기록 ${imported.tracks.size}개 반입 완료"
    } }
    fun exportGeoJson(uri: Uri) { task(true) {
        val data=app.database.withTransaction {
            val dao=app.database.dao()
            // shortcut: bound full-file export memory; stream per-track exports above 100,000 fixes.
            require(dao.pointCount()<=100000) { "한 번에 반출할 수 있는 기록은 100,000점까지입니다." }
            Triple(dao.exportWaypoints(),dao.exportTracks(),dao.exportPoints())
        }
        val text=withContext(Dispatchers.Default) { GeoJsonExchange.export(data.first,data.second,data.third) }
        require(text.toByteArray().size<=GeoJsonExchange.MAX_BYTES) { "반출 파일이 10MB를 초과합니다. 불필요한 자료를 정리하거나 별도 보관하세요." }
        withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri,"wt")?.use { it.write(text.toByteArray()) } ?: error("반출 파일을 열 수 없습니다.") }
        message.value="GeoJSON 반출 완료 — 반출 파일은 평문입니다."
    } }
    fun task(blocking: Boolean=false, block: suspend ()->Unit) {
        if(blocking && busy.value) return
        viewModelScope.launch {
            if(blocking) busy.value=true
            try { block() }
            catch(e: Exception) { if(e is CancellationException) throw e; message.value=if(e is IllegalArgumentException || e is IllegalStateException || e is java.text.ParseException) e.message ?: "입력을 확인하세요." else "작업 실패: 파일 접근 권한·형식·저장공간을 확인하세요." }
            finally { if(blocking) busy.value=false }
        }
    }
}
