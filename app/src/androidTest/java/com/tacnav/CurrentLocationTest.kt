package com.tacnav

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

class CurrentLocationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String): String {
        val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        check(!output.contains("Exception") && !output.contains("Error")) { "$command: $output" }
        return output
    }

    private fun mapView(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            mapView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun gpsRegistration(): String {
        val current = shell("dumpsys location").substringBefore("Historical Aggregate")
        val gps = current.substringAfter("gps provider:")
        return gps.lineSequence().first { it.contains("com.tacnav") }.trim()
    }

    private fun waitForCamera(point: GeoPoint) {
        compose.waitUntil(15_000) {
            var arrived = false
            compose.runOnIdle {
                mapView(compose.activity.window.decorView)?.getMapAsync { map ->
                    val target = map.cameraPosition.target!!
                    arrived = Coordinates.range(point, GeoPoint(target.latitude, target.longitude)).meters < 5 &&
                        kotlin.math.abs(map.cameraPosition.zoom - 14.5) < 0.01
                }
            }
            arrived
        }
    }

    @Test fun delayedGpsCentersOnceAndZoomsToNeighborhood() {
        // Test-provider changes are restricted to the emulator.
        assumeTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val automation = instrumentation.uiAutomation
        val previousLocationEnabled = shell("cmd location is-location-enabled").trim()
        val previousMockOp = shell("appops get com.android.shell android:mock_location")
        val mockWasAllowed = previousMockOp.contains("allow")
        try {
            automation.grantRuntimePermission("com.tacnav", Manifest.permission.ACCESS_COARSE_LOCATION)
            automation.grantRuntimePermission("com.tacnav", Manifest.permission.ACCESS_FINE_LOCATION)
            shell("appops set com.android.shell android:mock_location allow")
            shell("cmd location set-location-enabled true")
            shell("cmd location providers add-test-provider gps")
            shell("cmd location providers set-test-provider-enabled gps true")
            compose.activityRule.scenario.recreate()

            // There is no initial fix. A single click must remain pending until GPS arrives.
            compose.onNodeWithContentDescription("현위치").performClick()
            compose.onNode(hasContentDescription("현위치") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "GPS 수신 대기 · 누르면 취소")).assertExists()
            compose.onNodeWithContentDescription("현위치").performClick()
            compose.onNodeWithContentDescription("현위치").assertIsDisplayed()
            compose.onNodeWithContentDescription("현위치").performClick()
            val busan = GeoPoint(35.1796, 129.0756)
            shell("cmd location providers set-test-provider-location gps --location 35.1796,129.0756 --accuracy 5")
            compose.waitUntil(10_000) {
                compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "현위치 추적 중 · 누르면 해제")).fetchSemanticsNodes().isNotEmpty()
            }
            waitForCamera(busan)
            compose.runOnIdle {
                mapView(compose.activity.window.decorView)!!.getMapAsync { map ->
                    val target = map.cameraPosition.target!!
                    assertEquals("Neighborhood zoom", 14.5, map.cameraPosition.zoom, 0.01)
                    // Check actual screen geometry to catch density conversion errors.
                    val middle = map.projection.toScreenLocation(target)
                    val shifted = map.projection.fromScreenLocation(
                        android.graphics.PointF(middle.x + 100 * compose.activity.resources.displayMetrics.density, middle.y)
                    )
                    val meters = Coordinates.range(
                        GeoPoint(target.latitude, target.longitude), GeoPoint(shifted.latitude, shifted.longitude)
                    ).meters
                    assertTrue("Neighborhood scale across 100 dp, got $meters", meters in 260.0..300.0)
                }
            }
            compose.onNodeWithContentDescription("GPS 정보").performClick()
            compose.onNodeWithText("현위치 MGRS 52S ED 06883 92962").assertIsDisplayed()

            // Explicitly dismiss tracking before freely moving the map.
            compose.onNodeWithContentDescription("현위치").performClick()
            val seoul = GeoPoint(37.5665, 126.9780)
            compose.runOnIdle {
                mapView(compose.activity.window.decorView)!!.getMapAsync {
                    it.moveCamera(CameraUpdateFactory.newLatLng(LatLng(seoul.latitude, seoul.longitude)))
                }
            }
            shell("cmd location providers set-test-provider-location gps --location 35.1797,129.0757 --accuracy 5")
            waitForCamera(seoul)

            // An old fix must not be mistaken for a current one.
            Thread.sleep(11_000)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription("저장 위치 수신 시각").assertIsDisplayed()
            val registration = gpsRegistration()
            compose.onNodeWithContentDescription("현위치").performClick()
            compose.onNode(hasContentDescription("현위치") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "GPS 수신 대기 · 누르면 취소")).assertExists()
            waitForCamera(GeoPoint(35.1797, 129.0757))
            compose.onNodeWithContentDescription("저장 위치 수신 시각").assertIsDisplayed()
            assertEquals("Location button must preserve the existing GPS listener", registration, gpsRegistration())
            compose.runOnIdle {
                mapView(compose.activity.window.decorView)!!.getMapAsync { map ->
                    assertEquals(android.graphics.Color.parseColor("#ffce73"),
                        map.style!!.getLayerAs<org.maplibre.android.style.layers.CircleLayer>("fix-dot")!!.circleColorAsInt)
                }
            }
            shell("cmd location providers set-test-provider-location gps --location 35.1796,129.0756 --accuracy 5")
            waitForCamera(busan)
            compose.onNodeWithContentDescription("현위치").assertIsDisplayed()
            compose.onNodeWithContentDescription("저장 위치 수신 시각").assertDoesNotExist()
        } finally {
            shell("cmd location providers remove-test-provider gps")
            shell("appops set com.android.shell android:mock_location ${if (mockWasAllowed) "allow" else "default"}")
            shell("cmd location set-location-enabled $previousLocationEnabled")
        }
    }
}
