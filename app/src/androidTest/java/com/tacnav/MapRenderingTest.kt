package com.tacnav

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MapRenderingTest {
    private fun find(view: View): MapView? {
        if(view is MapView) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun render(asset: String, vector: Boolean): Unit = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=context.applicationContext as TacApplication
        val previous=app.settings.flow.first()
        val store=MapPackStore(context)
        val installed=if(asset.isBlank()) store.list().first { it.builtIn } else instrumentation.context.assets.open(asset).use { store.import(it) }
        val mapBytesBefore=File(context.filesDir,"maps").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        app.settings.save(previous.copy(activeMap=installed.id,showGrid=false))
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            val finished=CountDownLatch(1)
            val requested=AtomicBoolean(false)
            val positioned=AtomicBoolean(false)
            val image=AtomicReference<Bitmap>()
            val glyphEvents=java.util.concurrent.CopyOnWriteArrayList<String>()
            var attached=false
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!attached && System.nanoTime()<deadline) {
                scenario.onActivity { activity ->
                    val view=find(activity.window.decorView)
                    if(view!=null) {
                        attached=true
                        // Only public test fixtures are captured; production windows remain secure.
                        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                        view.addOnGlyphsErrorListener { _,start,end -> glyphEvents.add("ERROR $start-$end");android.util.Log.i("TacGlyph","ERROR $start-$end") }
                        view.addOnGlyphsLoadedListener { _,start,end -> glyphEvents.add("LOADED $start-$end");android.util.Log.i("TacGlyph","LOADED $start-$end") }
                        view.addOnGlyphsRequestedListener { _,start,end -> glyphEvents.add("REQUEST $start-$end");android.util.Log.i("TacGlyph","REQUEST $start-$end") }
                        view.getMapAsync { map ->
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(37.5665,126.9780),12.0))
                            view.addOnDidFinishRenderingFrameListener { fully,_ ->
                                if(fully && !requested.get()) activity.runOnUiThread {
                                    if(requested.get()) return@runOnUiThread
                                    if(map.style?.getSource("imagery")!=null && view.foreground==null) {
                                        if(positioned.compareAndSet(false,true)) { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(37.5665,126.9780),12.0));return@runOnUiThread }
                                        val features=map.queryRenderedFeatures(RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),*(if(vector) arrayOf("places") else arrayOf("imagery")))
                                        if((!vector || features.isNotEmpty()) && requested.compareAndSet(false,true)) {
                                            view.postDelayed({ map.snapshot { bitmap -> image.set(bitmap);finished.countDown() } },1000)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if(!attached) Thread.sleep(100)
            }
            assertTrue("MapView attached",attached)
            val rendered=finished.await(90,TimeUnit.SECONDS)
            File(context.cacheDir,"vector-glyphs.txt").writeText(glyphEvents.joinToString("\n"))
            assertTrue("Offline map rendered: $asset",rendered)
            val bitmap=image.get()
            val colors=mutableSetOf<Int>()
            var textPixels=0
            for(y in 0 until bitmap.height step 4) for(x in 0 until bitmap.width step 4) { val pixel=bitmap.getPixel(x,y); colors.add(pixel)
                if(kotlin.math.abs(android.graphics.Color.red(pixel)-238)<=2 && kotlin.math.abs(android.graphics.Color.green(pixel)-241)<=2 && kotlin.math.abs(android.graphics.Color.blue(pixel)-223)<=2) textPixels++
            }
            File(context.cacheDir,if(vector) "vector-render.png" else "raster-render.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            assertTrue("Actual map pixels: ${colors.size}, ${colors.take(12)} (${bitmap.width}x${bitmap.height})",colors.size > if(vector) 20 else 500)
            if(vector) {
                assertTrue("Visible Korean label pixels: $textPixels",textPixels>10)
                instrumentation.uiAutomation.takeScreenshot().let { screen -> File(context.cacheDir,"vector-screen.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG,100,it) } }
                File(context.cacheDir,"vector-glyphs.txt").writeText(glyphEvents.joinToString("\n"))
                assertTrue(glyphEvents.joinToString(),glyphEvents.none { it.startsWith("ERROR") })
                scenario.onActivity { activity -> find(activity.window.decorView)!!.getMapAsync { map ->
                    val labels=map.queryRenderedFeatures(RectF(0f,0f,10000f,10000f),"places")
                    assertTrue("Offline place labels are placed",labels.isNotEmpty())
                } }
            }
        } finally {
            scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            scenario.close()
            app.settings.save(previous)
            if(!installed.builtIn) store.delete(installed)
            else assertEquals("Bundled map must not be copied to app storage",mapBytesBefore,File(context.filesDir,"maps").walkTopDown().filter { it.isFile }.sumOf { it.length() })
        }
    }
    @Test fun builtInMapRendersWithoutImport() { render("",true) }
    @Test fun rasterPixelsRenderOffline() { render("seoul-sample.tacmap",false) }
    @Test fun vectorRoadsAndLabelsRenderOffline() { render("seoul-lite.tacmap",true) }
}
