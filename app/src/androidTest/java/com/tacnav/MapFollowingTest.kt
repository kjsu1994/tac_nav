package com.tacnav

import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.view.InputDevice
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.SymbolLayer

class MapFollowingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findMap(view.getChildAt(i))?.let { return it }
        }
        return null
    }
    private fun fix(point: GeoPoint) =
        PositionFix(point, 5f, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(), null, null)

    @Test fun followsPositionAndTrueHeadingAndStopsForGesturesAndBackground() {
        val start = GeoPoint(35.1796, 129.0756)
        var position by mutableStateOf(fix(start))
        var facing by mutableStateOf<Double?>(90.0)
        var following by mutableStateOf(true)
        var rotate by mutableStateOf(true)
        var request by mutableStateOf<CameraRequest?>(CameraRequest(start, zoomToLocation = true))
        var map: MapLibreMap? = null
        val installed = (compose.activity.application as TacApplication).maps.list().first { it.builtIn }
        compose.setContent {
            MaterialTheme {
                MapSurface(
                    installed, AppSettings(showGrid = false), start, emptyList(), emptyList(), position,
                    null, null, emptyList(), request, following, rotate, facing,
                    { if (request === it) request = null },
                    { following = false; rotate = false },
                    {}, {}, { error("Map failed: $it") }, Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitUntil(15_000) {
            compose.runOnIdle {
                findMap(compose.activity.window.decorView)?.getMapAsync { map = it }
            }
            map?.style != null
        }
        compose.runOnUiThread { position = fix(start) }
        fun waitFor(point: GeoPoint, bearing: Double) {
            compose.waitUntil(15_000) {
                var matched = false
                compose.runOnUiThread {
                    val camera = map!!.cameraPosition
                    val target = camera.target!!
                    matched = Coordinates.range(point, GeoPoint(target.latitude, target.longitude)).meters < 5 &&
                        kotlin.math.abs((camera.bearing - bearing + 540) % 360 - 180) < 1
                }
                matched
            }
        }
        waitFor(start, 90.0)
        compose.runOnIdle {
            val layer = map!!.style!!.getLayerAs<SymbolLayer>("fix-bearing")!!
            assertEquals(90f, layer.iconRotate.value!!, 0.01f)
            assertEquals(1f, layer.iconOpacity.value!!, 0.01f)
            assertEquals(14.5, map!!.cameraPosition.zoom, 0.01)
            facing = 180.0
        }
        waitFor(start, 180.0)
        val next = GeoPoint(35.1806, 129.0766)
        compose.runOnIdle { position = fix(next) }
        waitFor(next, 180.0)
        compose.runOnIdle { rotate = false }
        waitFor(next, 0.0)
        compose.waitUntil(10_000) {
            var visible = false
            compose.runOnUiThread {
                val view = findMap(compose.activity.window.decorView)!!
                visible = map!!.queryRenderedFeatures(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), "fix-bearing").isNotEmpty()
            }
            visible
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screen = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "direction-follow-screen.png").outputStream().use {
            screen.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val origin = IntArray(2)
        var startX = 0f
        var startY = 0f
        compose.runOnUiThread {
            val view = findMap(compose.activity.window.decorView)!!
            view.getLocationOnScreen(origin)
            startX = origin[0] + view.width / 2f
            startY = origin[1] + view.height / 2f
        }
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, startY, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, startX)
        for (step in 1..12) {
            Thread.sleep(30)
            touch(MotionEvent.ACTION_MOVE, startX + step * 15f)
        }
        touch(MotionEvent.ACTION_UP, startX + 180f)
        compose.waitUntil(5_000) { !following }
        var panned: GeoPoint? = null
        compose.runOnIdle {
            val target = map!!.cameraPosition.target!!
            panned = GeoPoint(target.latitude, target.longitude)
            assertTrue(Coordinates.range(next, panned!!).meters > 10)
            position = fix(start)
            facing = 270.0
        }
        Thread.sleep(500)
        waitFor(panned!!, 0.0)

        // A selected follow mode resumes only when the Activity is resumed.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnUiThread { following = true; position = fix(next) }
        Thread.sleep(500)
        waitFor(panned!!, 0.0)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor(next, 0.0)
        compose.runOnIdle {
            facing = null
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0f, map!!.style!!.getLayerAs<SymbolLayer>("fix-bearing")!!.iconOpacity.value!!, 0.01f)
        }
    }
}
