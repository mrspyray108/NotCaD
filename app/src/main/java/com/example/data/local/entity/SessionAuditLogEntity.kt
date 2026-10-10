package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_audit_logs")
data class SessionAuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String, // "AUTH_SUCCESS", "AUTH_FAIL", "SESSION_START", "SESSION_EXPIRED", "STRICT_BLOCKED", "EMERGENCY_OVERRIDE"
    val packageName: String? = null,
    val details: String = ""
)
