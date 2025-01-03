package app.aaps.database.daos

import androidx.room.Dao
import androidx.room.Query
import app.aaps.database.entities.OfflineEvent
import app.aaps.database.entities.TABLE_OFFLINE_EVENTS
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single

@Dao
internal interface OfflineEventDao : TraceableDao<OfflineEvent> {

    @Query("SELECT * FROM $TABLE_OFFLINE_EVENTS WHERE id = :id")
    override fun findById(id: Long): OfflineEvent?

    @Query("DELETE FROM $TABLE_OFFLINE_EVENTS")
    override fun deleteAllEntries()

    @Query("DELETE FROM $TABLE_OFFLINE_EVENTS WHERE timestamp < :than")
    override fun deleteOlderThan(than: Long): Int

    @Query("DELETE FROM $TABLE_OFFLINE_EVENTS WHERE referenceId IS NOT NULL")
    override fun deleteTrackedChanges(): Int

    @Query("SELECT id FROM $TABLE_OFFLINE_EVENTS ORDER BY id DESC limit 1")
    fun getLastId(): Long?

    @Query("SELECT * FROM $TABLE_OFFLINE_EVENTS WHERE unlikely(nightscoutId = :nsId) AND likely(referenceId IS NULL)")
    fun findByNSId(nsId: String): OfflineEvent?

    @Query(
        "SELECT * FROM $TABLE_OFFLINE_EVENTS WHERE unlikely(timestamp <= :timestamp) AND unlikely((timestamp + duration) > :timestamp) AND likely(referenceId IS NULL) AND likely(isValid = 1) " +
            "ORDER BY timestamp DESC LIMIT 1"
    )
    fun getOfflineEventActiveAt(timestamp: Long): Maybe<OfflineEvent>

    @Query("SELECT * FROM $TABLE_OFFLINE_EVENTS WHERE unlikely(timestamp BETWEEN :start AND :end) AND likely(isValid = 1) AND likely(referenceId IS NULL) ORDER BY timestamp ASC")
    fun getOfflineEventDataFromTimeToTime(start: Long, end: Long): Single<List<OfflineEvent>>

    // for WS we need 1 record only
    @Query("SELECT * FROM $TABLE_OFFLINE_EVENTS WHERE id > :id ORDER BY id ASC limit 1")
    fun getNextModifiedOrNewAfter(id: Long): Maybe<OfflineEvent>

    @Query("SELECT * FROM $TABLE_OFFLINE_EVENTS WHERE id = :referenceId")
    fun getCurrentFromHistoric(referenceId: Long): Maybe<OfflineEvent>

    @Query("SELECT * FROM $TABLE_OFFLINE_EVENTS WHERE dateCreated > :since AND dateCreated <= :until LIMIT :limit OFFSET :offset")
    fun getNewEntriesSince(since: Long, until: Long, limit: Int, offset: Int): List<OfflineEvent>
}