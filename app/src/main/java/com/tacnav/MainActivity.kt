package com.tacnav

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private val navigation: NavViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { TacNavApp(navigation) }
    }
    override fun onResume() { super.onResume();navigation.setForeground(true) }
    override fun onPause() { navigation.setForeground(false);super.onPause() }
}
private val Olive=Color(0xFFB4D88A)
private val Ink=Color(0xFF101914)
private val Amber=Color(0xFFFFCE73)
private val Aqua=Color(0xFF66D8D0)
private fun stamp(time: Long)=SimpleDateFormat("MM/dd HH:mm:ss",Locale.KOREA).format(Date(time))
private fun distance(meters: Double)=if(meters>=1000) String.format(Locale.US,"%.2f km",meters/1000) else String.format(Locale.US,"%.0f m",meters)

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable fun TacNavApp(vm: NavViewModel=viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val dark=when(settings.theme){ThemeMode.DARK->true;ThemeMode.LIGHT->false;ThemeMode.SYSTEM->isSystemInDarkTheme()}
    val colors=if(dark) darkColorScheme(primary=Olive,secondary=Aqua,tertiary=Amber,background=Ink,surface=Color(0xFF19251D),surfaceVariant=Color(0xFF25332A)) else lightColorScheme(primary=Color(0xFF405D29),secondary=Color(0xFF176B66),tertiary=Color(0xFF795314))
    MaterialTheme(colorScheme=colors) {
        val context=LocalContext.current
        val activity=context as Activity
        val lifecycle=LocalLifecycleOwner.current.lifecycle
        var page by rememberSaveable { mutableStateOf("지도") }
        var showCoordinatePanel by rememberSaveable { mutableStateOf(false) }
        var showGpsPanel by rememberSaveable { mutableStateOf(false) }
        var waitingForLocation by rememberSaveable { mutableStateOf(false) }
        var followLocation by rememberSaveable { mutableStateOf(false) }
        var followHeading by rememberSaveable { mutableStateOf(false) }
        val locationSensors = remember(context) { LocationSensors(context) }
        val locationManager = remember(context) { context.getSystemService(LocationManager::class.java) }
        var mapLicense by remember { mutableStateOf<InstalledMap?>(null) }
        var permissionEpoch by remember { mutableIntStateOf(0) }
        var precise by remember { mutableStateOf(context.hasPreciseLocation()) }
        var gpsEnabled by remember { mutableStateOf(false) }
        var fix by remember { mutableStateOf<PositionFix?>(null) }
        var heading by remember { mutableStateOf<Pair<Double,Int>?>(null) }
        var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var gridSpacing by remember { mutableStateOf("—") }
        val maps by vm.maps.collectAsStateWithLifecycle()
        val waypoints by vm.waypoints.collectAsStateWithLifecycle()
        val tracks by vm.tracks.collectAsStateWithLifecycle()
        val points by vm.points.collectAsStateWithLifecycle()
        val center by vm.center.collectAsStateWithLifecycle()
        val target by vm.target.collectAsStateWithLifecycle()
        val cell by vm.cell.collectAsStateWithLifecycle()
        val route by vm.route.collectAsStateWithLifecycle()
        val request by vm.cameraRequest.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val busy by vm.busy.collectAsStateWithLifecycle()
        val recording by vm.recording.collectAsStateWithLifecycle()
        val installed=maps.firstOrNull { it.id==settings.activeMap } ?: maps.firstOrNull { it.builtIn }
        var naming by remember { mutableStateOf<GeoPoint?>(null) }
        var editing by remember { mutableStateOf<Waypoint?>(null) }
        var exportConfirmation by remember { mutableStateOf(false) }
        var deleteConfirmation by remember { mutableStateOf<Pair<String,()->Unit>?>(null) }
        val snack=remember { SnackbarHostState() }
        val locationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (waitingForLocation && !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                waitingForLocation = false
                followHeading = false
                vm.message.value = "기기 설정에서 위치를 켜세요."
            }
        }
        val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            precise = context.hasPreciseLocation()
            permissionEpoch++
            if (waitingForLocation && !precise) {
                waitingForLocation = false
                followHeading = false
                vm.message.value = "정확한 위치 권한을 허용한 뒤 다시 시도하세요."
            } else if (waitingForLocation && !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationSettings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        val importMap=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importMap) }
        val importData=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importGeoJson) }
        val exportData=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/geo+json")) { it?.let(vm::exportGeoJson) }
        LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.message.value=null } }
        LaunchedEffect(permissionEpoch,lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                precise=context.hasPreciseLocation()
                if(precise) launch {
                    try {
                        locationSensors.lastPosition()?.let { cached ->
                            if (fix?.isLive() != true && cached.elapsedNanos > (fix?.elapsedNanos ?: 0)) fix = cached
                        }
                        locationSensors.positions().collect { received ->
                            val current = fix
                            if (current == null || received.elapsedNanos >= current.elapsedNanos || current.cached && received.isLive()) {
                                fix = received.copy(cached = !received.isLive())
                            }
                            vm.recordFix(received)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        waitingForLocation = false
                        followLocation = false
                        followHeading = false
                        vm.message.value = "GPS를 사용할 수 없습니다. 위치 권한과 설정을 확인하세요."
                    }
                }
                launch {
                    try {
                        locationSensors.headings { @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.rotation }
                            .sample(200)
                            .distinctUntilChanged { previous, next ->
                                previous.second == next.second && abs((next.first - previous.first + 540) % 360 - 180) < 2
                            }
                            .collect { heading = it }
                    } finally { heading = null }
                }
                while(isActive) {
                    tick=System.currentTimeMillis()
                    gpsEnabled=runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
                    delay(1000)
                }
            }
        }
        LaunchedEffect(waitingForLocation, precise, page, lifecycle) {
            if (page != "지도") {
                followLocation = false
                followHeading = false
                waitingForLocation = false
                return@LaunchedEffect
            }
            if (!waitingForLocation || !precise) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val preview = fix?.takeIf { it.isLive() } ?:
                    listOfNotNull(fix, locationSensors.lastPosition()).maxByOrNull { it.elapsedNanos }
                if (preview != null && !preview.isLive()) {
                    fix = preview.copy(cached = true)
                    vm.centerOnLocation(preview.point)
                }
                val position = withTimeoutOrNull(90_000) {
                    snapshotFlow {
                        if (gpsEnabled) fix?.takeIf { it.isLive() } else null
                    }.filterNotNull().first()
                }
                waitingForLocation = false
                if (position != null) {
                    followLocation = true
                    vm.centerOnLocation(position.point)
                } else {
                    followHeading = false
                    vm.message.value = "GPS를 수신하지 못했습니다. 하늘이 트인 곳에서 다시 시도하세요."
                }
            }
        }
        fun moveToLocation() {
            if (waitingForLocation || followLocation) {
                waitingForLocation = false
                followLocation = false
                followHeading = false
                return
            }
            precise = context.hasPreciseLocation()
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            waitingForLocation = true
            if (!precise) {
                requestPermissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            } else if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationSettings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        DisposableEffect(settings.keepScreenOn,page) {
            if(settings.keepScreenOn && page=="지도") activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        BackHandler(page!="지도") { page="지도" }
        val age=remember(fix,tick) { fix?.ageSeconds() }
        val declination = remember(fix?.point, fix?.altitude) {
            fix?.let { MagneticModel.declination(it.point, it.altitude ?: 0.0) }
        }
        val facing = heading?.takeIf {
            precise && gpsEnabled && fix?.isLive() == true && it.second >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        }?.let { sensor -> declination?.let { Coordinates.normalize(sensor.first + it) } }
        val status=when { !precise->"정확한 위치 권한 필요";!gpsEnabled->"GPS 꺼짐";fix==null->"위성 위치 수신 대기";fix!!.cached->"저장 위치 · ${stamp(fix!!.time)}";age!=null && age>10->"위치 갱신 중단 ${age}초";else->"GPS ±${fix!!.accuracy.roundToInt()} m · ${age}초 전" }
        Scaffold(
            topBar={ Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("TAC_NAV",fontWeight=FontWeight.Black,letterSpacing=3.sp,fontSize=24.sp)
                        Text(if (recording) "● REC" else "OFFLINE  /  WGS84",style=MaterialTheme.typography.labelSmall,color=if (recording) Amber else MaterialTheme.colorScheme.primary)
                    }
                    if(page=="지도") Row(verticalAlignment=Alignment.CenterVertically) {
                        FilledTonalIconToggleButton(
                            checked = showGpsPanel,
                            onCheckedChange = { showGpsPanel = it },
                            modifier = Modifier.semantics { contentDescription = "GPS 정보" },
                        ) { Text("GPS", style = MaterialTheme.typography.labelMedium) }
                        FilledTonalIconToggleButton(checked=showCoordinatePanel,onCheckedChange={showCoordinatePanel=it},modifier=Modifier.semantics { contentDescription="지도 중심 좌표 패널" }) { Text("⌖",fontSize=24.sp) }
                        IconButton(onClick={mapLicense=installed},enabled=installed!=null,modifier=Modifier.semantics { contentDescription="지도 출처 및 라이선스" }) { Text("ⓘ",fontSize=22.sp) }
                    }
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            } },
            snackbarHost={SnackbarHost(snack)},
            bottomBar={ NavigationBar {
                listOf("지도" to "◎","좌표" to "⌖","지점·기록" to "≋","설정" to "⚙").forEach { (name,icon) ->
                    NavigationBarItem(selected=page==name,onClick={page=name},icon={Text(icon,fontSize=22.sp)},label={Text(name)})
                }
            } }
        ) { padding ->
            when(page) {
                "지도" -> Box(Modifier.fillMaxSize().padding(padding)) {
                    MapSurface(installed,settings,center,waypoints,points,fix,cell,target,route,request,followLocation,followHeading,facing,
                        { vm.cameraRequest.compareAndSet(it, null) },
                        { followLocation = false; followHeading = false; waitingForLocation = false },
                        {vm.center.value=it},{gridSpacing=it},{vm.message.value=it},Modifier.fillMaxSize())
                    Text("＋",fontSize=36.sp,color=Amber,modifier=Modifier.align(Alignment.Center).semantics { contentDescription="지도 중심 십자선" })
                    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        if (fix?.cached == true) Surface(
                            modifier = Modifier.align(Alignment.End).semantics { contentDescription = "저장 위치 수신 시각" },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                        ) {
                            Text("저장 위치 · ${stamp(fix!!.time)}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall, color = Amber)
                        }
                        if (showGpsPanel) Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.96f)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                    Text(status,style=MaterialTheme.typography.labelMedium,color=if(age!=null && age>10 || !gpsEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary)
                                    Text("격자 $gridSpacing",style=MaterialTheme.typography.labelSmall)
                                }
                                fix?.let { position ->
                                    val label=if(precise && gpsEnabled && position.isLive()) "현위치" else "저장 위치"
                                    Text("$label MGRS ${Coordinates.mgrs(position.point,settings.mgrsDigits)}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if(installed==null || !installed.manifest.contains(center)) {
                            Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.96f)) {
                                Text(if(installed==null) "지도팩이 없습니다. 설정 → 지도팩 반입" else "현재 중심은 지도 적용 범위 밖입니다.",Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.align(Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalIconToggleButton(
                                checked = followHeading,
                                onCheckedChange = { enabled ->
                                    if (enabled && !followLocation && !waitingForLocation) moveToLocation()
                                    followHeading = enabled
                                    if (enabled && (heading?.second ?: SensorManager.SENSOR_STATUS_UNRELIABLE) < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) vm.message.value = "방향 센서 수신 대기 · 금속·자석을 피하고 센서를 보정하세요."
                                },
                                modifier = Modifier.size(48.dp).semantics { contentDescription = "나침반 · 바라보는 방향으로 회전" },
                            ) { Text("△", fontSize = 26.sp) }
                            FilledTonalIconToggleButton(
                                checked = waitingForLocation || followLocation,
                                onCheckedChange = { moveToLocation() },
                                modifier = Modifier.size(48.dp).semantics {
                                    contentDescription = "현위치"
                                    stateDescription = when {
                                        waitingForLocation -> "GPS 수신 대기 · 누르면 취소"
                                        followLocation -> "현위치 추적 중 · 누르면 해제"
                                        else -> "현위치 추적 꺼짐"
                                    }
                                },
                            ) {
                                if (waitingForLocation) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                else Text("◎", fontSize = 26.sp)
                            }
                        }
                        if(showCoordinatePanel && target!=null && fix?.isLive()==true) {
                            val rb=Coordinates.range(fix!!.point,target!!)
                            val declination=MagneticModel.declination(fix!!.point,fix!!.altitude ?: 0.0)
                            val bearing=Coordinates.bearing(rb.trueDegrees,settings.northReference,declination,Coordinates.convergence(fix!!.point))
                            Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.96f)) { Text("선택점 ${distance(rb.meters)} · ${Coordinates.bearingText(bearing,settings.showMils)} ${settings.northReference.label}",Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall) }
                        }
                        if (showCoordinatePanel) Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.98f)) {
                            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("지도 중심",style=MaterialTheme.typography.labelSmall);Text("MGRS · ${10.0.pow(5-settings.mgrsDigits).toInt()} m 셀",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary) }
                                Text(Coordinates.mgrs(center,settings.mgrsDigits),fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Bold,fontSize=20.sp)
                                Text(Coordinates.format(center,settings.coordinateFormat),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick={naming=center},modifier=Modifier.weight(1f)) { Text("지점 저장") }
                                    OutlinedButton(onClick={if(route.size<100) vm.route.value=route+center else vm.message.value="경유점은 최대 100개입니다."},modifier=Modifier.weight(1f)) { Text("경유점 +") }
                                }
                                if(route.isNotEmpty()) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                    Text("경유점 ${route.size}개 · ${distance(route.zipWithNext().sumOf { Coordinates.range(it.first,it.second).meters })}",style=MaterialTheme.typography.labelSmall)
                                    TextButton(onClick={vm.route.value=emptyList()}) { Text("선 지우기") }
                                }
                            }
                        }

                    }
                }
                "좌표" -> CoordinateScreen(settings,fix,heading,age,{point,selected->vm.select(point,selected);page="지도"},{point->naming=point},{vm.message.value=it},Modifier.padding(padding))
                "지점·기록" -> LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    item { SectionTitle("지점 · 이동기록","자동 공유 없이 이 기기에만 저장합니다.") }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={
                            if(recording) vm.stopRecording()
                            else if(!context.hasPreciseLocation()) requestPermissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
                            else if(!gpsEnabled) vm.message.value="기기 설정에서 GPS를 켜세요."
                            else vm.startRecording()
                        }) { Text(if(recording) "기록 종료" else "이동기록 시작") }
                        OutlinedButton(onClick={importData.launch(arrayOf("application/json","application/geo+json","application/octet-stream","text/*"))},enabled=!busy) { Text("반입") }
                        OutlinedButton(onClick={exportConfirmation=true},enabled=!busy) { Text("반출") }
                    } }
                    item { Text("기록 간격 ${settings.trackIntervalSeconds}초 · 앱 화면을 보고 있을 때만 기록 · 화면을 닫으면 자동 종료\n지도·목록은 최근 10,000점까지 표시, 반출은 전체 기록",style=MaterialTheme.typography.bodySmall) }
                    item { Text("저장 지점 ${waypoints.size}",fontWeight=FontWeight.Bold) }
                    if(waypoints.isEmpty()) item { EmptyCard("아직 저장된 지점이 없습니다.","지도 중심이나 변환한 좌표를 저장하세요.") }
                    items(waypoints,key={"w${it.id}"}) { w ->
                        Card { Column(Modifier.padding(14.dp)) {
                            Text(w.name,fontWeight=FontWeight.Bold)
                            Text(Coordinates.mgrs(w.point(),settings.mgrsDigits),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodyMedium)
                            Text(Coordinates.format(w.point(),settings.coordinateFormat),style=MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick={vm.select(w.point());page="지도"}) { Text("지도") }
                                TextButton(onClick={editing=w}) { Text("이름 수정") }
                                TextButton(onClick={deleteConfirmation="‘${w.name}’ 지점을 삭제할까요?" to {vm.deleteWaypoint(w)}}) { Text("삭제") }
                            }
                        } }
                    }
                    item { Text("이동기록 ${tracks.size}",fontWeight=FontWeight.Bold) }
                    if(tracks.isEmpty()) item { EmptyCard("이동기록이 없습니다.","기록은 시작 버튼을 누른 경우에만 남습니다.") }
                    items(tracks,key={"t${it.id}"}) { t ->
                        val recorded=points.filter { it.trackId==t.id }
                        Card { Column(Modifier.padding(14.dp)) {
                            Text(stamp(t.startedAt),fontWeight=FontWeight.Bold)
                            Text("${recorded.size}개 위치 · ${if(t.endedAt==null) "기록 중" else if(t.interrupted) "중단됨 — 확인 필요" else "종료 ${stamp(t.endedAt)}"}",style=MaterialTheme.typography.bodySmall)
                            if(recorded.any { it.accuracy<0 }) Text("반입 궤적 · 원본 시간·GPS 정확도 미보존",style=MaterialTheme.typography.labelSmall)
                            Row {
                                TextButton(onClick={recorded.lastOrNull()?.let { vm.select(it.point());page="지도" }},enabled=recorded.isNotEmpty()) { Text("지도") }
                                TextButton(onClick={deleteConfirmation="이동기록과 포함된 위치를 삭제할까요?" to {vm.deleteTrack(t)}},enabled=t.endedAt!=null) { Text("삭제") }
                            }
                        } }
                    }
                }
                "설정" -> SettingsScreen(settings,{vm.updateSettings(it)},maps,busy,{importMap.launch(arrayOf("application/zip","application/octet-stream","*/*"))},{m->vm.updateSettings { it.copy(activeMap=m.id) }; val b=m.manifest.bounds;vm.select(GeoPoint((b[1]+b[3])/2,(b[0]+b[2])/2))},{m->deleteConfirmation="‘${m.manifest.name}’ 지도팩을 삭제할까요? 저장 지점과 이동기록은 유지됩니다." to {vm.deleteMap(m)}},Modifier.padding(padding))
            }
        }
        mapLicense?.let { map -> MapLicenseDialog(map) { mapLicense=null } }
        naming?.let { p -> NameDialog("지점 저장","지점 ${waypoints.size+1}",{naming=null}) { name->vm.saveWaypoint(name,p);naming=null } }
        editing?.let { w -> NameDialog("지점 이름 수정",w.name,{editing=null}) { name->vm.renameWaypoint(w,name);editing=null } }
        deleteConfirmation?.let { (text,action) -> AlertDialog(onDismissRequest={deleteConfirmation=null},title={Text("삭제 확인")},text={Text(text)},confirmButton={TextButton(onClick={action();deleteConfirmation=null}){Text("삭제")}},dismissButton={TextButton(onClick={deleteConfirmation=null}){Text("취소")}}) }
        if(exportConfirmation) AlertDialog(onDismissRequest={exportConfirmation=false},title={Text("GeoJSON 반출")},text={Text("저장 지점 ${waypoints.size}개와 이동기록 ${tracks.size}개를 반출합니다. 위치가 포함된 평문 파일이며 앱 밖에서는 이 앱의 보호가 적용되지 않습니다.")},confirmButton={TextButton(onClick={exportConfirmation=false;exportData.launch("Tac_Nav_${System.currentTimeMillis()}.geojson")}){Text("파일 저장")}},dismissButton={TextButton(onClick={exportConfirmation=false}){Text("취소")}})
    }
}

@Composable private fun SectionTitle(title: String,description: String) {
    Column(Modifier.padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) { Text(title,fontSize=26.sp,fontWeight=FontWeight.Bold);Text(description,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall) }
}
@Composable private fun EmptyCard(title: String,description: String) { OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) { Text(title,fontWeight=FontWeight.Medium);Text(description,style=MaterialTheme.typography.bodySmall) } } }
@Composable private fun NameDialog(title: String,initial: String,onClose: ()->Unit,onSave: (String)->Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest=onClose,title={Text(title)},text={OutlinedTextField(name,{if(it.length<=80) name=it},label={Text("이름 (최대 80자)")},singleLine=true)},confirmButton={TextButton(onClick={onSave(name.trim())},enabled=name.isNotBlank()){Text("저장")}},dismissButton={TextButton(onClick=onClose){Text("취소")}})
}

@Composable private fun CoordinateScreen(settings: AppSettings,fix: PositionFix?,heading: Pair<Double,Int>?,age: Long?,onMap: (GeoPoint,CoordinateCell?)->Unit,onSave: (GeoPoint)->Unit,onError: (String)->Unit,modifier: Modifier) {
    var mgrsMode by rememberSaveable { mutableStateOf(true) }
    var mgrs by rememberSaveable { mutableStateOf("") }
    var latitude by rememberSaveable { mutableStateOf("") }
    var longitude by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<CoordinateCell?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        SectionTitle("좌표 변환","WGS84 위경도 ↔ MGRS · 네트워크 불필요")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(mgrsMode,{mgrsMode=true;error=null},{Text("MGRS 입력")});FilterChip(!mgrsMode,{mgrsMode=false;error=null},{Text("위경도 입력")}) }
        if(mgrsMode) {
            OutlinedTextField(mgrs,{mgrs=it.take(80)},label={Text("완전한 MGRS")},placeholder={Text("52S CG 12345 67890")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("구역·격자 문자를 포함하세요. 동·북방 좌표는 각각 1~5자리입니다.",style=MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(latitude,{latitude=it.take(80)},label={Text("위도 · N/S")},placeholder={Text("37.5665 또는 37 33 59.4 N")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(longitude,{longitude=it.take(80)},label={Text("경도 · E/W")},placeholder={Text("126.978 또는 126 58 40.8 E")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("십진도·도분·도분초 지원. 남위 80°~북위 84° 범위입니다.",style=MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        Button(onClick={
            runCatching { if(mgrsMode) Coordinates.parseMgrs(mgrs) else CoordinateCell(GeoPoint(Coordinates.parseAngle(latitude,true),Coordinates.parseAngle(longitude,false)),emptyList(),0) }
                .onSuccess { result=it;error=null }.onFailure { result=null;error=it.message ?: "좌표를 확인하세요." }
        },modifier=Modifier.fillMaxWidth()) { Text("변환") }
        result?.let { r ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(Coordinates.mgrs(r.center,settings.mgrsDigits),fontFamily=FontFamily.Monospace,fontSize=20.sp,fontWeight=FontWeight.Bold)
                Text(Coordinates.format(r.center,settings.coordinateFormat),fontFamily=FontFamily.Monospace)
                if(r.sizeMeters>0) Text("입력 격자 ${r.sizeMeters} × ${r.sizeMeters} m · 표시 위치는 셀 중심",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(onClick={onMap(r.center,r.takeIf { it.corners.isNotEmpty() })}){Text("지도에서 보기")};OutlinedButton(onClick={onSave(r.center)}){Text("지점 저장")} }
            } }
        }
        HorizontalDivider()
        Text("현위치 · 방향",fontWeight=FontWeight.Bold)
        if(fix==null) Text("GPS 수신 대기 · 지도에서 위치 권한을 허용하세요.") else {
            if (fix.cached) Text("저장 위치 · ${stamp(fix.time)}", color = MaterialTheme.colorScheme.tertiary)
            Text(Coordinates.mgrs(fix.point,settings.mgrsDigits),fontFamily=FontFamily.Monospace)
            Text(Coordinates.format(fix.point,settings.coordinateFormat),fontFamily=FontFamily.Monospace)
            Text("±${fix.accuracy.roundToInt()} m · ${age ?: 0}초 전",color=if((age ?: 0)>10) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary)
            val declination=MagneticModel.declination(fix.point,fix.altitude ?: 0.0)
            val convergence=Coordinates.convergence(fix.point)
            Text("자기편각 ${declination?.let { String.format(Locale.US,"%.2f°",it) } ?: "모델 유효기간 밖"} · 도편각 ${String.format(Locale.US,"%.2f°",convergence)}",style=MaterialTheme.typography.bodySmall)
            val sensorHeading=heading?.takeIf { fix.isLive() && it.second>=SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM }
            val trueHeading=if(sensorHeading!=null && declination!=null) Coordinates.normalize(sensorHeading.first+declination) else null
            val displayed=trueHeading?.let { Coordinates.bearing(it,settings.northReference,declination,convergence) }
            Text("나침반 ${Coordinates.bearingText(displayed,settings.showMils)} · ${settings.northReference.label}")
            if(sensorHeading==null) Text("방향 센서 없음 또는 신뢰도 낮음 · 금속·자석을 피하고 센서를 보정하세요.",style=MaterialTheme.typography.bodySmall)
            fix.course?.let { Text("이동 방향 ${Coordinates.bearingText(it.toDouble(),settings.showMils)} · 진북",style=MaterialTheme.typography.bodySmall) }
            fix.altitude?.let { Text("WGS84 타원체고 ${distance(it)} · 해발고도와 다릅니다.",style=MaterialTheme.typography.bodySmall) }
        }
        Text("자기편각 모델: WMM2025 · 2025~2029년 유효",style=MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun SettingsScreen(settings: AppSettings,update: ((AppSettings)->AppSettings)->Unit,maps: List<InstalledMap>,busy: Boolean,onImport: ()->Unit,onSelect: (InstalledMap)->Unit,onDelete: (InstalledMap)->Unit,modifier: Modifier) {
    var licenseMap by remember { mutableStateOf<InstalledMap?>(null) }
    licenseMap?.let { map -> MapLicenseDialog(map) { licenseMap=null } }
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { SectionTitle("설정","변경 사항은 기기에 자동 저장됩니다.") }
        item { Text("앱 화면을 보고 있을 때만 GPS·방향 센서·지도가 동작합니다. 홈 이동·화면 꺼짐 시 이동기록도 저장 후 종료하며 다시 시작 버튼으로 켭니다.",style=MaterialTheme.typography.bodySmall) }
        item { SettingChoice("좌표 표시",CoordinateFormat.entries.map { it.label },settings.coordinateFormat.ordinal) { index->update { it.copy(coordinateFormat=CoordinateFormat.entries[index]) } } }
        item { SettingChoice("MGRS 정밀도",listOf("10 km","1 km","100 m","10 m","1 m"),(settings.mgrsDigits-1).coerceIn(0,4)) { index->update { it.copy(mgrsDigits=index+1) } }; Text("표기 정밀도이며 GPS 정확도가 아닙니다.",style=MaterialTheme.typography.bodySmall) }
        item { SettingToggle("6400밀 함께 표시",settings.showMils) { value->update { it.copy(showMils=value) } } }
        item { SettingChoice("방위 기준",NorthReference.entries.map { it.label },settings.northReference.ordinal) { index->update { it.copy(northReference=NorthReference.entries[index]) } } }
        item { SettingChoice("화면",ThemeMode.entries.map { it.label },settings.theme.ordinal) { index->update { it.copy(theme=ThemeMode.entries[index]) } } }
        item { SettingToggle("MGRS 격자",settings.showGrid) { value->update { it.copy(showGrid=value) } } }
        item { SettingToggle("지도 사용 중 화면 켜짐 유지",settings.keepScreenOn) { value->update { it.copy(keepScreenOn=value) } } }
        item { SettingChoice("이동기록 저장 간격",listOf("1초","5초","10초","30초"),listOf(1,5,10,30).indexOf(settings.trackIntervalSeconds)) { index->update { it.copy(trackIntervalSeconds=listOf(1,5,10,30)[index]) } };Text("진행 중인 기록에도 적용됩니다. GPS 수신 공백은 별도 구간으로 저장합니다.",style=MaterialTheme.typography.bodySmall) }
        item { HorizontalDivider();Spacer(Modifier.height(12.dp));Text("오프라인 지도팩",fontSize=20.sp,fontWeight=FontWeight.Bold);Text("한반도 기본 2D 지도는 설치 즉시 사용할 수 있습니다. 위성지도는 선택 반입합니다.",style=MaterialTheme.typography.bodySmall);Spacer(Modifier.height(8.dp));Button(onClick=onImport,enabled=!busy) { Text(".tacmap 반입") } }
        if(maps.isEmpty()) item { EmptyCard("지도팩이 없습니다.","PC에서 제작한 .tacmap 파일을 USB 등으로 복사한 후 반입하세요. 위성영상은 앱에 기본 포함되지 않습니다.") }
        items(maps,key={it.id}) { map->
            val m=map.manifest
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(m.name,fontWeight=FontWeight.Bold)
                if(map.id==settings.activeMap) Text("현재 사용 중",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
                Text("자료 ${m.captured} · ${m.detail}\n${String.format(Locale.US,"%.1f MB",m.tileBytes/1048576.0)}",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={licenseMap=map}) { Text("지도 출처·라이선스") }
                Row { TextButton(onClick={onSelect(map)},enabled=!busy) { Text("사용") };if(map.builtIn) Text("앱 기본 제공",Modifier.padding(12.dp),style=MaterialTheme.typography.labelMedium) else TextButton(onClick={onDelete(map)},enabled=!busy) { Text("삭제") } }
            } }
        }
        item { HorizontalDivider();Spacer(Modifier.height(12.dp));Text("보호 · 앱 정보",fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));Text("인터넷 권한 없음\n자동 백업·기기 이전 제외\n화면 캡처·최근 앱 미리보기 보호\n기기 잠금·앱 전용 저장소 사용\nGeoJSON 반출 파일은 평문\nWMM2025 유효기간: 2029-12-31\nTac_Nav 0.1.2 · 현장 검증 전 개발 버전",style=MaterialTheme.typography.bodySmall) }
    }
}
@Composable private fun MapLicenseDialog(map: InstalledMap,onClose: ()->Unit) {
    val m=map.manifest
    AlertDialog(onDismissRequest=onClose,title={Text("지도 출처·라이선스")},text={
        Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(m.name,fontWeight=FontWeight.Bold)
            Text(m.source)
            Text(m.license)
            Text("자료 ${m.captured}\n제작 ${m.produced}\n${m.detail} · 확대 ${m.minZoom}~${m.maxZoom}",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick=onClose) { Text("닫기") }})
}

@Composable private fun SettingChoice(title: String,options: List<String>,selected: Int,onSelect: (Int)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) { Text(title,fontWeight=FontWeight.Medium);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { options.forEachIndexed { i,label-> FilterChip(selected==i,{onSelect(i)},{Text(label)}) } } }
}
@Composable private fun SettingToggle(title: String,checked: Boolean,onChange: (Boolean)->Unit) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Text(title,Modifier.weight(1f));Switch(checked,onChange) } }
