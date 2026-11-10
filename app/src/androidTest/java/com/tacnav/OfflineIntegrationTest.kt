package com.tacnav

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class OfflineIntegrationTest {
    @Test fun noInternetPermissionInInstalledApp() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val info=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains(android.Manifest.permission.INTERNET))
        assertFalse(info.requestedPermissions.orEmpty().any { it.startsWith("android.permission.FOREGROUND_SERVICE") })
        assertFalse(info.requestedPermissions.orEmpty().contains(android.Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_SERVICES).services.isNullOrEmpty())
    }
    @Test fun recordingFinishesWhenAppClosesAndDoesNotResumeAutomatically() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.ACCESS_COARSE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.ACCESS_FINE_LOCATION)
        val dao=(context.applicationContext as TacApplication).database.dao()
        val before=dao.exportTracks().map { it.id }.toSet()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm: NavViewModel
        try {
            scenario.onActivity { activity -> vm=ViewModelProvider(activity)[NavViewModel::class.java];vm.startRecording() }
            val track=withTimeout(10000) {
                var created: Track?=null
                while(created==null) { created=dao.exportTracks().firstOrNull { it.id !in before };if(created==null) delay(20) }
                created
            }
            vm.recordFix(PositionFix(GeoPoint(37.5665,126.978),5f,android.os.SystemClock.elapsedRealtimeNanos(),System.currentTimeMillis(),50.0,null))
            withTimeout(10000) { while(dao.exportPoints().none { it.trackId==track.id }) delay(20) }
            scenario.moveToState(Lifecycle.State.CREATED)
            withTimeout(10000) { while(dao.exportTracks().first { it.id==track.id }.endedAt==null) delay(20) }
            assertFalse(vm.recording.value)
            assertFalse(dao.exportTracks().first { it.id==track.id }.interrupted)
            val count=dao.exportPoints().count { it.trackId==track.id }
            scenario.moveToState(Lifecycle.State.RESUMED)
            vm.recordFix(PositionFix(GeoPoint(35.1796,129.0756),5f,android.os.SystemClock.elapsedRealtimeNanos(),System.currentTimeMillis(),50.0,null))
            assertFalse(vm.recording.value)
            assertEquals(count,dao.exportPoints().count { it.trackId==track.id })
        } finally {
            scenario.close()
            dao.exportTracks().filter { it.id !in before }.forEach { dao.deleteTrack(it) }
        }
    }
    @Test fun officialWmm2025ReferenceValues() {
        val context=InstrumentationRegistry.getInstrumentation().context
        context.assets.open("WMM2025_TEST_VALUES.txt").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                val p=line.trim().split(Regex("\\s+"))
                val lon=p[3].toDouble().let { if(it>180) it-360 else it }
                val actual=MagneticModel.compute(p[2].toDouble(),lon,p[1].toDouble(),p[0].toDouble())
                assertEquals(line,p[10].toDouble(),actual,0.02)
            }
        }
        assertTrue(MagneticModel.compute(37.0,127.0,0.0,2030.0).isNaN())
    }
    @Test fun settingsPersistAndRestore() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=(context.applicationContext as TacApplication).settings
        val previous=store.flow.first()
        try {
            val changed=previous.copy(coordinateFormat=CoordinateFormat.DMS,mgrsDigits=3,showMils=false,northReference=NorthReference.GRID,theme=ThemeMode.LIGHT,trackIntervalSeconds=30,showGrid=false,keepScreenOn=true)
            store.save(changed);assertEquals(changed,store.flow.first())
        } finally { store.save(previous) }
    }
    @Test fun concurrentSettingsChangesDoNotOverwriteEachOther() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TacApplication
        val store=app.settings
        val previous=store.flow.first()
        try {
            coroutineScope { listOf(
                async { store.update { it.copy(showMils=false) } },
                async { store.update { it.copy(theme=ThemeMode.LIGHT) } },
                async { store.update { it.copy(coordinateFormat=CoordinateFormat.DMS) } }
            ).awaitAll() }
            val result=store.flow.first()
            assertFalse(result.showMils)
            assertEquals(ThemeMode.LIGHT,result.theme)
            assertEquals(CoordinateFormat.DMS,result.coordinateFormat)
        } finally { store.save(previous) }
    }
    private fun archive(entries: Map<String,ByteArray>): ByteArray {
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name,bytes) -> zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() } }
        return output.toByteArray()
    }
    @Test fun realPublicSatellitePackImportsAndDeletes() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val store=MapPackStore(instrumentation.targetContext)
        val before=store.list().map { it.id }
        val installed=instrumentation.context.assets.open("seoul-sample.tacmap").use { store.import(it) }
        try {
            assertEquals(10.0,installed.manifest.resolutionMeters,0.0)
            assertTrue(installed.manifest.contains(GeoPoint(37.5665,126.9780)))
            assertTrue(org.json.JSONObject(installed.style()).getJSONObject("sources").getJSONObject("imagery").getString("url").startsWith("pmtiles://file://"))
            assertTrue(store.list().any { it.id==installed.id })
        } finally { store.delete(installed) }
        assertEquals(before,store.list().map { it.id })
    }
    @Test fun offlineGlyphsCoverAllBmpRanges() {
        val assets=InstrumentationRegistry.getInstrumentation().targetContext.assets
        val files=assets.list("fonts/Noto Sans Regular")!!.toSet()
        for(start in 0 until 65536 step 256) {
            val name="$start-${start+255}.pbf"
            assertTrue("Missing offline glyph range $name",name in files)
            assets.open("fonts/Noto Sans Regular/$name").use { assertEquals(10,it.read()) }
        }
    }
    @Test fun builtInMapIsReadOnlyAndAvailableFromApk() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=MapPackStore(context)
        val map=store.list().first { it.builtIn }
        assertEquals(BUILT_IN_MAP_ID,AppSettings().activeMap)
        assertTrue(map.manifest.contains(GeoPoint(37.5665,126.978)))
        context.assets.openFd("maps/${map.manifest.tileFile}").use { assertEquals(map.manifest.tileBytes,it.length) }
        assertTrue(org.json.JSONObject(map.style()).getJSONObject("sources").getJSONObject("imagery").getString("url").startsWith("pmtiles://asset://"))
        assertThrows(IllegalArgumentException::class.java) { store.delete(map) }
        assertTrue(store.list().any { it.id==BUILT_IN_MAP_ID })
    }
    @Test fun failedMapImportLeavesInstalledMapsUntouched() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=MapPackStore(context)
        val before=store.list().map { it.id }
        assertThrows(Exception::class.java) { store.import(ByteArrayInputStream(archive(mapOf("../escape" to byteArrayOf(1))))) }
        assertEquals(before,store.list().map { it.id })
    }
}
