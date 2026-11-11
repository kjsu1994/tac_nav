package com.tacnav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.*

data class PositionFix(val point: GeoPoint, val accuracy: Float, val elapsedNanos: Long, val time: Long, val altitude: Double?, val course: Float?) {
    fun ageSeconds() = ((SystemClock.elapsedRealtimeNanos() - elapsedNanos) / 1_000_000_000L).coerceAtLeast(0)
}
fun Location.toFix(): PositionFix? = runCatching {
    require(hasAccuracy() && accuracy.isFinite() && accuracy >= 0 && elapsedRealtimeNanos > 0)
    PositionFix(GeoPoint(latitude,longitude),accuracy,elapsedRealtimeNanos,time,altitude.takeIf { hasAltitude() && it.isFinite() },bearing.takeIf { hasBearing() && speed >= 1f })
}.getOrNull()
fun Context.hasPreciseLocation() = ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
class LocationSensors(private val context: Context) {
    fun recentPosition(): PositionFix? {
        if (!context.hasPreciseLocation()) return null
        val manager = context.getSystemService(LocationManager::class.java)
        return try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.toFix()?.takeIf { it.ageSeconds() <= 10 }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun positions() = callbackFlow {
        val manager=context.getSystemService(LocationManager::class.java)
        if (!context.hasPreciseLocation()) { close(); return@callbackFlow }
        val listener=object : LocationListener {
            override fun onLocationChanged(location: Location) { location.toFix()?.let { trySend(it) } }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,listener,Looper.getMainLooper()) }
        catch (e: SecurityException) { close(e) }
        catch (e: IllegalArgumentException) { close(e) }
        awaitClose { manager.removeUpdates(listener) }
    }
    fun headings(rotation: () -> Int) = callbackFlow<Pair<Double,Int>> {
        val manager=context.getSystemService(SensorManager::class.java)
        val sensor=manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) { close(); return@callbackFlow }
        var accuracy=SensorManager.SENSOR_STATUS_UNRELIABLE
        val listener=object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, value: Int) { accuracy=value }
            override fun onSensorChanged(event: SensorEvent) {
                val matrix=FloatArray(9); val adjusted=FloatArray(9); val orientation=FloatArray(3)
                SensorManager.getRotationMatrixFromVector(matrix,event.values)
                val axes=when(rotation()) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(matrix,axes.first,axes.second,adjusted)
                SensorManager.getOrientation(adjusted,orientation)
                trySend(Coordinates.normalize(Math.toDegrees(orientation[0].toDouble())) to accuracy)
            }
        }
        manager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_UI)
        awaitClose { manager.unregisterListener(listener) }
    }
}
