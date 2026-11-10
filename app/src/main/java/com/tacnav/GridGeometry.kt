package com.tacnav

import mil.nga.grid.features.Bounds
import mil.nga.mgrs.gzd.GridZones
import mil.nga.mgrs.grid.GridType
import kotlin.math.*

data class GridLabel(val point: GeoPoint,val text: String)
data class GridGeometry(val lines: List<List<GeoPoint>>, val spacing: String,val labels: List<GridLabel> = emptyList())
object MgrsGrid {
    fun build(west: Double,south: Double,east: Double,north: Double): GridGeometry {
        if(west>=east || south>=north || south < -80 || north > 84) return GridGeometry(emptyList(),"격자 범위 밖")
        val height=(north-south)*111320
        val width=(east-west)*111320*abs(cos(Math.toRadians((south+north)/2)))
        val size=listOf(100,1000,10000,100000).firstOrNull { max(width,height)/it<=10 }
        val type=when(size) { 100->GridType.HUNDRED_METER;1000->GridType.KILOMETER;10000->GridType.TEN_KILOMETER;100000->GridType.HUNDRED_KILOMETER;else->GridType.GZD }
        val bounds=Bounds.degrees(west,south,east,north)
        val output=mutableListOf<List<GeoPoint>>()
        for(zone in GridZones.getZones(bounds)) {
            if(output.size>=1000) break
            val lines=zone.getLines(bounds,type).orEmpty()+if(type!=GridType.GZD) zone.getLines(bounds,GridType.GZD).orEmpty() else emptyList()
            for(line in lines) {
                if(output.size>=1000) break
                val d=line.toDegrees()
                val a=d.point1; val b=d.point2
                if(a.latitude in -80.0..84.0 && b.latitude in -80.0..84.0) output.add(listOf(GeoPoint(a.latitude,a.longitude),GeoPoint(b.latitude,b.longitude)))
            }
        }
        val labels=GridZones.getZones(bounds).mapNotNull { zone ->
            val overlap=zone.bounds.overlap(bounds) ?: return@mapNotNull null
            val p=overlap.centroid.toDegrees()
            GridLabel(GeoPoint(p.latitude,p.longitude),zone.name)
        }
        return GridGeometry(output,when(size){100->"100 m";1000->"1 km";10000->"10 km";100000->"100 km";else->"구역 경계"},labels)
    }
}
