package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "protected_apps")
data class ProtectedAppEntity(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val category: String = "Distracting",
    val isProtected: Boolean = true,
    val dailyLimitMinutes: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
