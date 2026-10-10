package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "focus_sessions")
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val startTimeMillis: Long,
    val expirationTimeMillis: Long,
    val durationMinutes: Int,
    val status: String // "ACTIVE", "COMPLETED", "EXPIRED", "EARLY_TERMINATED", "BLOCKED"
)
