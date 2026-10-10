package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.ProtectedAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProtectedAppDao {
    @Query("SELECT * FROM protected_apps ORDER BY appName ASC")
    fun getAllProtectedApps(): Flow<List<ProtectedAppEntity>>

    @Query("SELECT * FROM protected_apps WHERE isProtected = 1")
    fun getActiveProtectedApps(): Flow<List<ProtectedAppEntity>>

    @Query("SELECT * FROM protected_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getAppByPackage(packageName: String): ProtectedAppEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM protected_apps WHERE packageName = :packageName AND isProtected = 1)")
    suspend fun isAppProtected(packageName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateApp(app: ProtectedAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<ProtectedAppEntity>)

    @Update
    suspend fun updateApp(app: ProtectedAppEntity)

    @Delete
    suspend fun deleteApp(app: ProtectedAppEntity)

    @Query("DELETE FROM protected_apps WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)
}
