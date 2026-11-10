package com.tacnav

import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*

private const val EMPTY_STYLE="""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#18211e"}}]}"""
private fun GeoPoint.mapPoint()=Point.fromLngLat(longitude,latitude)
private fun featureLine(points: List<GeoPoint>)=Feature.fromGeometry(LineString.fromLngLats(points.map { it.mapPoint() }))

@Composable fun MapSurface(
    installed: InstalledMap?, settings: AppSettings, center: GeoPoint,
    waypoints: List<Waypoint>, points: List<TrackPoint>, fix: PositionFix?, selected: CoordinateCell?,
    target: GeoPoint?, route: List<GeoPoint>, request: Pair<Long,GeoPoint>?,
    onCenter: (GeoPoint)->Unit, onGrid: (String)->Unit, onError: (String)->Unit,
    modifier: Modifier=Modifier
) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val centerCallback by rememberUpdatedState(onCenter)
    val errorCallback by rememberUpdatedState(onError)
    val mapView=remember { MapLibre.getInstance(context); MapView(context,MapLibreMapOptions.createFromAttributes(context).localIdeographFontFamilyEnabled(false)).apply { onCreate(Bundle()) } }
    val labels=remember { GridZoneLabelsView(context) }
    val container=remember { FrameLayout(context).apply {
        addView(mapView,FrameLayout.LayoutParams(-1,-1))
        addView(labels,FrameLayout.LayoutParams(-1,-1))
    } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var viewport by remember { mutableStateOf<List<Double>?>(null) }
    DisposableEffect(mapView,lifecycle) {
        var started=false; var resumed=false
        fun start() { if(!started) { mapView.onStart(); started=true } }
        fun resume() { start(); if(!resumed) { mapView.onResume(); resumed=true } }
        fun pause() { if(resumed) { mapView.onPause(); resumed=false } }
        fun stop() { pause(); if(started) { mapView.onStop(); started=false } }
        val observer=LifecycleEventObserver { _,event -> when(event) { Lifecycle.Event.ON_START->start();Lifecycle.Event.ON_RESUME->resume();Lifecycle.Event.ON_PAUSE->stop();Lifecycle.Event.ON_STOP->stop();else->Unit } }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume() else if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        val failed=MapView.OnDidFailLoadingMapListener { error -> errorCallback("지도 로딩 실패: 지도팩을 확인하세요.") }
        mapView.addOnDidFailLoadingMapListener(failed)
        mapView.getMapAsync { ready ->
            map=ready
            labels.map=ready
            ready.addOnCameraMoveListener { labels.invalidate() }
            ready.setLatLngBoundsForCameraTarget(LatLngBounds.from(84.0,180.0,-80.0,-180.0))
            ready.uiSettings.isLogoEnabled=false
            ready.uiSettings.isAttributionEnabled=false
            ready.uiSettings.isCompassEnabled=false
            ready.uiSettings.isRotateGesturesEnabled=false
            ready.uiSettings.isTiltGesturesEnabled=false
            ready.cameraPosition=CameraPosition.Builder().target(LatLng(center.latitude,center.longitude)).zoom(12.0).build()
            ready.addOnCameraIdleListener {
                val p=ready.cameraPosition.target
                if(p!=null && p.latitude in -80.0..84.0) centerCallback(GeoPoint(p.latitude,p.longitude))
                val b=ready.projection.visibleRegion.latLngBounds
                viewport=listOf(b.longitudeWest,b.latitudeSouth.coerceAtLeast(-80.0),b.longitudeEast,b.latitudeNorth.coerceAtMost(84.0))
            }
        }
        onDispose { lifecycle.removeObserver(observer); mapView.removeOnDidFailLoadingMapListener(failed); stop(); mapView.onDestroy() }
    }
    LaunchedEffect(map,installed?.id) { withContext(Dispatchers.Main.immediate) {
        style=null
        map?.setStyle(Style.Builder().fromJson(installed?.style() ?: EMPTY_STYLE)) { loaded ->
            if(!mapView.isDestroyed) {
                for(id in listOf("grid","cell","route","track","waypoints","fix","target")) loaded.addSource(GeoJsonSource(id,FeatureCollection.fromFeatures(emptyList())))
                loaded.addLayer(LineLayer("grid-lines","grid").withProperties(lineColor("#b4d88a"),lineWidth(1f),lineOpacity(0.65f)))
                loaded.addLayer(LineLayer("cell-lines","cell").withProperties(lineColor("#ffce73"),lineWidth(3f)))
                loaded.addLayer(LineLayer("route-lines","route").withProperties(lineColor("#ffce73"),lineWidth(3f),lineDasharray(arrayOf(2f,2f))))
                loaded.addLayer(LineLayer("track-lines","track").withProperties(lineColor("#66d8d0"),lineWidth(3f)))
                loaded.addLayer(CircleLayer("waypoint-dots","waypoints").withProperties(circleColor("#ffce73"),circleRadius(7f),circleStrokeColor("#19221d"),circleStrokeWidth(2f)))
                loaded.addLayer(CircleLayer("fix-dot","fix").withProperties(circleColor("#66d8d0"),circleRadius(8f),circleStrokeColor("#ffffff"),circleStrokeWidth(2f)))
                loaded.addLayer(CircleLayer("target-dot","target").withProperties(circleColor("#ffce73"),circleRadius(5f),circleStrokeColor("#ffffff"),circleStrokeWidth(1f)))
                style=loaded
                installed?.manifest?.bounds?.let { bounds ->
                    map?.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(bounds[3],bounds[2],bounds[1],bounds[0]),24))
                }
                val b=map?.projection?.visibleRegion?.latLngBounds
                if(b!=null) viewport=listOf(b.longitudeWest,b.latitudeSouth.coerceAtLeast(-80.0),b.longitudeEast,b.latitudeNorth.coerceAtMost(84.0))
            }
        }
    } }
    LaunchedEffect(style,viewport,settings.showGrid) {
        val s=style ?: return@LaunchedEffect
        val b=viewport
        val grid=if(settings.showGrid && b!=null) withContext(Dispatchers.Default) { MgrsGrid.build(b[0],b[1],b[2],b[3]) } else GridGeometry(emptyList(),"끔")
        withContext(Dispatchers.Main.immediate) {
            s.getSourceAs<GeoJsonSource>("grid")?.setGeoJson(FeatureCollection.fromFeatures(grid.lines.map { featureLine(it) }))
            labels.labels=grid.labels
            labels.invalidate()
            onGrid(grid.spacing)
        }
    }
    LaunchedEffect(style,waypoints,points,selected,target,route) { withContext(Dispatchers.Main.immediate) {
        val s=style ?: return@withContext
        fun set(id: String,features: List<Feature>) { s.getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(features)) }
        set("waypoints",waypoints.map { Feature.fromGeometry(it.point().mapPoint()) })
        set("track",points.groupBy { it.trackId to it.segment }.values.filter { it.size>=2 }.map { featureLine(it.map { p->p.point() }) })
        set("target",listOfNotNull(target?.let { Feature.fromGeometry(it.mapPoint()) }))
        set("cell",selected?.let { listOf(featureLine(it.corners+it.corners.first())) } ?: emptyList())
        set("route",if(route.size>=2) listOf(featureLine(route)) else emptyList())
    } }
    LaunchedEffect(style,fix) { withContext(Dispatchers.Main.immediate) {
        style?.getSourceAs<GeoJsonSource>("fix")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(fix?.let { Feature.fromGeometry(it.point.mapPoint()) })))
    } }
    LaunchedEffect(map,request) { withContext(Dispatchers.Main.immediate) { request?.second?.let { p -> map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(p.latitude,p.longitude))) } } }
    AndroidView(factory={container},modifier=modifier)
}

private class GridZoneLabelsView(context: Context): View(context) {
    var map: MapLibreMap?=null
    var labels: List<GridLabel> = emptyList()
    private val coordinate=LatLng()
    private val density=resources.displayMetrics.density
    private val text=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(180,216,138);textSize=12*density;typeface=android.graphics.Typeface.MONOSPACE }
    private val background=Paint().apply { color=Color.argb(210,16,25,20) }
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val projection=map?.projection ?: return
        for(label in labels.take(32)) {
            coordinate.latitude=label.point.latitude
            coordinate.longitude=label.point.longitude
            val screen=projection.toScreenLocation(coordinate)
            if(screen.x !in 0f..width.toFloat() || screen.y !in 0f..height.toFloat()) continue
            val w=text.measureText(label.text)
            canvas.drawRoundRect(screen.x-w/2-5*density,screen.y-14*density,screen.x+w/2+5*density,screen.y+5*density,4*density,4*density,background)
            canvas.drawText(label.text,screen.x-w/2,screen.y,text)
        }
    }
}
