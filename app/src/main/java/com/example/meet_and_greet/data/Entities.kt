package com.example.meet_and_greet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "students")
data class StudentEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val email: String,
    val password_hash: String,
    val full_name: String,
    val faculty: String = "Информационные технологии",
    val study_group: String = "ИС-21",
    val age: Int = 20,
    val bio: String = "Студент колледжа",
    val interests: String = "Музыка, Кино, Спорт",
    val created_at: Long = System.currentTimeMillis()
)

@Entity(tableName = "login_attempts")
data class LoginAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val email: String,
    val success: Boolean,
    val attempted_at: Long = System.currentTimeMillis()
)

@Entity(tableName = "likes")
data class LikeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val from_student_id: Int,
    val to_student_id: Int,
    val is_like: Boolean,
    val created_at: Long = System.currentTimeMillis()
)

@Entity(tableName = "matches")
data class MatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val student_a_id: Int,
    val student_b_id: Int,
    val created_at: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val match_id: Int,
    val sender_id: Int,
    val body: String,
    val sent_at: Long = System.currentTimeMillis()
)
