package com.example.meet_and_greet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AuthDao {
    @Query("SELECT * FROM students WHERE email = :email LIMIT 1")
    suspend fun getStudentByEmail(email: String): StudentEntity?

    @Insert
    suspend fun insertStudent(student: StudentEntity): Long

    @Insert
    suspend fun logAttempt(attempt: LoginAttemptEntity)

    @Query("SELECT COUNT(*) FROM login_attempts WHERE email = :email AND success = 0 AND attempted_at > :timeLimit")
    suspend fun getFailedAttemptsCount(email: String, timeLimit: Long): Int
}

