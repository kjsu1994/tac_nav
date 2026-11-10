package com.tacnav

import android.app.Application
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.async

@Entity data class Waypoint(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val latitude: Double, val longitude: Double, val createdAt: Long = System.currentTimeMillis()) {
    fun point() = GeoPoint(latitude, longitude)
}
@Entity data class Track(@PrimaryKey(autoGenerate = true) val id: Long = 0, val startedAt: Long = System.currentTimeMillis(), val endedAt: Long? = null, val interrupted: Boolean = false)
@Entity(foreignKeys = [ForeignKey(entity = Track::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)], indices = [Index("trackId")])
data class TrackPoint(@PrimaryKey(autoGenerate = true) val id: Long = 0, val trackId: Long, val latitude: Double, val longitude: Double, val time: Long, val accuracy: Float, val segment: Int) {
    fun point() = GeoPoint(latitude, longitude)
}
@Dao interface NavDao {
    @Query("SELECT * FROM Waypoint ORDER BY createdAt DESC") fun waypoints(): Flow<List<Waypoint>>
    @Query("SELECT * FROM Waypoint ORDER BY createdAt DESC") suspend fun exportWaypoints(): List<Waypoint>
    @Query("SELECT * FROM Track ORDER BY startedAt DESC") suspend fun exportTracks(): List<Track>
    @Query("SELECT * FROM TrackPoint ORDER BY trackId, time, id") suspend fun exportPoints(): List<TrackPoint>
    @Query("SELECT COUNT(*) FROM TrackPoint") suspend fun pointCount(): Long
    @Insert suspend fun insertWaypoint(point: Waypoint): Long
    @Update suspend fun updateWaypoint(point: Waypoint)
    @Delete suspend fun deleteWaypoint(point: Waypoint)
    @Insert suspend fun insertTrack(track: Track): Long
    @Insert suspend fun insertPoint(point: TrackPoint)
    @Query("SELECT * FROM Track ORDER BY startedAt DESC") fun tracks(): Flow<List<Track>>
    // shortcut: display the latest 10,000 fixes; page individual tracks when longer histories are needed.
    @Query("SELECT * FROM (SELECT * FROM TrackPoint ORDER BY id DESC LIMIT 10000) ORDER BY trackId, time, id") fun points(): Flow<List<TrackPoint>>
    @Query("UPDATE Track SET endedAt = :end WHERE id = :id") suspend fun finishTrack(id: Long, end: Long)
    @Query("UPDATE Track SET endedAt = :now, interrupted = 1 WHERE endedAt IS NULL") suspend fun interruptOpenTracks(now: Long)
    @Delete suspend fun deleteTrack(track: Track)
    @Transaction suspend fun importWaypoints(points: List<Waypoint>) { points.forEach { insertWaypoint(it) } }
    @Transaction suspend fun importData(points: List<Waypoint>, tracks: List<List<List<GeoPoint>>>) {
        points.forEach { insertWaypoint(it) }
        tracks.forEach { segments ->
            val now = System.currentTimeMillis()
            val id = insertTrack(Track(startedAt = now, endedAt = now))
            segments.forEachIndexed { segment, line -> line.forEachIndexed { index, p -> insertPoint(TrackPoint(trackId = id, latitude = p.latitude, longitude = p.longitude, time = now + index, accuracy = -1f, segment = segment)) } }
        }
    }
}
@Database(entities = [Waypoint::class, Track::class, TrackPoint::class], version = 1, exportSchema = true)
abstract class NavDatabase : RoomDatabase() { abstract fun dao(): NavDao }
class TacApplication : Application() {
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val recovery by lazy { appScope.async { database.dao().interruptOpenTracks(System.currentTimeMillis()) } }
    val database by lazy { Room.databaseBuilder(this, NavDatabase::class.java, "navigation.db").build() }
    val settings by lazy { SettingsStore(this) }
    val maps by lazy { MapPackStore(this) }
}
