package com.tacnav

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CoordinatesTest {
    private fun rejects(block: ()->Unit) { assertThrows(Exception::class.java) { block() } }
    @Test fun decimalAndCardinalParsing() {
        assertEquals(37.5665,Coordinates.parseAngle("37.5665 N",true),1e-9)
        assertEquals(-0.5,Coordinates.parseAngle("-0 30",true),1e-9)
        assertEquals(-126.5,Coordinates.parseAngle("126 30 W",false),1e-9)
    }
    @Test fun minutesSecondsParsing() {
        assertEquals(37.5665,Coordinates.parseAngle("37°33′59.4″N",true),1e-9)
        assertEquals(126.978,Coordinates.parseAngle("126 58 40.8 E",false),1e-9)
    }
    @Test fun invalidAnglesAreRejected() {
        for(text in listOf("NaN","Infinity","37 60","37 2 60","37 E","-37 N","37 N S","1 2 3 4","1.2 3","1 2.2 3","84 1","-80 1","--1","3N7")) rejects { Coordinates.parseAngle(text,true) }
        rejects { Coordinates.parseAngle("180 1",false) }; rejects { Coordinates.parseAngle("1E2",false) }
    }
    @Test fun invalidCoordinatesAreRejected() {
        rejects { GeoPoint(Double.NaN,0.0) };rejects { GeoPoint(0.0,Double.POSITIVE_INFINITY) };rejects { GeoPoint(85.0,0.0) };rejects { GeoPoint(0.0,-181.0) }
    }
    @Test fun mgrsReferenceEiffelTower() {
        // Published Eiffel Tower MGRS reference, independent of a round-trip test.
        val value=Coordinates.mgrs(GeoPoint(48.8582,2.2945),5)
        assertEquals("31UDQ4825111932",value)
    }
    @Test fun mgrsCellSizeAndCenter() {
        val cell=Coordinates.parseMgrs("31U DQ 482 119")
        assertEquals(100,cell.sizeMeters)
        assertEquals(4,cell.corners.size)
        assertTrue(Coordinates.range(cell.corners[0],cell.center).meters in 70.0..72.0)
        assertTrue(Coordinates.range(cell.corners[0],cell.corners[1]).meters in 99.0..101.0)
    }
    @Test fun fullMeterCellHasOneMeterSize() { assertEquals(1,Coordinates.parseMgrs("31UDQ4825111932").sizeMeters) }
    @Test fun mgrsRequiresFullZoneAndEvenDigits() {
        listOf("DQ4825111932","31UDQ123","00UDQ12341234","61UDQ12341234","31IDQ12341234","31UDQ","31UDQ1","31UDQ123456123456").forEach { text -> rejects { Coordinates.parseMgrs(text) } }
    }
    @Test fun koreanZoneBoundary() {
        assertTrue(Coordinates.mgrs(GeoPoint(37.0,125.9999)).startsWith("51S"))
        assertTrue(Coordinates.mgrs(GeoPoint(37.0,126.0001)).startsWith("52S"))
    }
    @Test fun precisionTruncatesRatherThanRounds() { assertEquals("31UDQ4811",Coordinates.mgrs(GeoPoint(48.8582,2.2945),2)) }
    @Test fun localeDoesNotChangeMgrsDigits() {
        val previous=Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("ar-EG"));assertEquals("31UDQ4825111932",Coordinates.mgrs(GeoPoint(48.8582,2.2945))) }
        finally { Locale.setDefault(previous) }
    }
    @Test fun geodesicReferenceOneDegreeEquator() {
        val result=Coordinates.range(GeoPoint(0.0,0.0),GeoPoint(0.0,1.0))
        assertEquals(111319.490793,result.meters,1e-5)
        assertEquals(90.0,result.trueDegrees,1e-9)
    }
    @Test fun referencesHaveCorrectSign() {
        assertEquals(100.0,Coordinates.bearing(90.0,NorthReference.MAGNETIC,-10.0,2.0)!!,1e-9)
        assertEquals(88.0,Coordinates.bearing(90.0,NorthReference.GRID,-10.0,2.0)!!,1e-9)
        assertNull(Coordinates.bearing(90.0,NorthReference.MAGNETIC,null,0.0))
    }
    @Test fun gridConvergenceAtCentralMeridian() { assertEquals(0.0,Coordinates.convergence(GeoPoint(37.0,129.0)),1e-5) }
    @Test fun gridConvergenceIsPositiveEastOfCentralMeridian() { assertTrue(Coordinates.convergence(GeoPoint(37.0,130.0))>0) }
    @Test fun roundedDmsCarriesToNextDegree() {
        val text=Coordinates.formatAngle(37.99999999,true,CoordinateFormat.DMS)
        assertEquals("38°00′00.00″N",text)
    }
    @Test fun bearingWrapsAndMils() { assertEquals(350.0,Coordinates.normalize(-10.0),0.0);assertEquals("90.0° / 1600 mil",Coordinates.bearingText(90.0,true)) }
    @Test fun gridStaysBoundedAndIncludesKoreanZones() {
        val grid=MgrsGrid.build(125.95,36.95,126.05,37.05)
        assertTrue(grid.lines.isNotEmpty());assertTrue(grid.lines.size<=1000)
        assertTrue(grid.lines.flatten().any { it.longitude<126 });assertTrue(grid.lines.flatten().any { it.longitude>=126 })
    }
    @Test fun invalidGridViewportIsEmpty() { assertTrue(MgrsGrid.build(170.0,0.0,-170.0,10.0).lines.isEmpty()) }
}
