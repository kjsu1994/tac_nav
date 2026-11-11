package com.tacnav

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test

class PositionFixTest {
    private fun location() = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 37.5665
        longitude = 126.978
        accuracy = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    @Test fun cachedFixIsNeverLiveEvenWhenRecent() {
        val live = location().toFix()!!
        assertTrue(live.isLive())
        assertFalse(live.copy(cached = true).isLive())
    }

    @Test fun oldFixIsKeptForPreviewWithoutBecomingLive() {
        val stored = location().apply { elapsedRealtimeNanos -= 15_000_000_000L }.toFix()!!
        assertFalse(stored.isLive())
        assertTrue(stored.copy(cached = true).cached)
        assertEquals(GeoPoint(37.5665, 126.978), stored.point)
    }

    @Test fun invalidCacheTimestampAndMissingAccuracyAreRejected() {
        assertNull(location().apply { elapsedRealtimeNanos += 60_000_000_000L }.toFix())
        assertNull(location().apply { time = 0 }.toFix())
        assertNull(location().apply { removeAccuracy() }.toFix())
    }
}
