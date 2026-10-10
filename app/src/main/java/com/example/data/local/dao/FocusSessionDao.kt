package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.FocusSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusSessionDao {
    @Query("SELECT * FROM focus_sessions ORDER BY startTimeMillis DESC")
    fun getAllSessions(): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions WHERE status = 'ACTIVE' ORDER BY startTimeMillis DESC LIMIT 1")
    fun getActiveSessionFlow(): Flow<FocusSessionEntity?>

    @Query("SELECT * FROM focus_sessions WHERE status = 'ACTIVE' ORDER BY startTimeMillis DESC LIMIT 1")
    suspend fun getActiveSession(): FocusSessionEntity?

    @Query("SELECT * FROM focus_sessions WHERE startTimeMillis >= :sinceTimestampMillis ORDER BY startTimeMillis DESC")
    fun getSessionsSince(sinceTimestampMillis: Long): Flow<List<FocusSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: FocusSessionEntity): Long

    @Update
    suspend fun updateSession(session: FocusSessionEntity)

    @Query("UPDATE focus_sessions SET status = :status WHERE id = :sessionId")
    suspend fun updateSessionStatus(sessionId: Long, status: String)

    @Query("UPDATE focus_sessions SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND expirationTimeMillis <= :currentTimeMillis")
    suspend fun expireStaleSessions(currentTimeMillis: Long)

    @Query("SELECT SUM(durationMinutes) FROM focus_sessions WHERE startTimeMillis >= :startOfDayMillis AND status IN ('COMPLETED', 'EXPIRED')")
    fun getTodayTotalMinutes(startOfDayMillis: Long): Flow<Int?>

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE startTimeMillis >= :startOfDayMillis")
    fun getTodaySessionCount(startOfDayMillis: Long): Flow<Int>
}
