package com.example.meet_and_greet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "students")
data class StudentEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val email: String,
    val password_hash: String,
    val full_name: String,
    val created_at: Long = System.currentTimeMillis()
)

@Entity(tableName = "login_attempts")
data class LoginAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val email: String,
    val success: Boolean,
    val attempted_at: Long = System.currentTimeMillis()
)

