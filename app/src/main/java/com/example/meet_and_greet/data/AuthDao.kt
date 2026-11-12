package com.example.meet_and_greet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface AuthDao {
    // --- Модуль авторизации ---
    @Query("SELECT * FROM students WHERE email = :email LIMIT 1")
    suspend fun getStudentByEmail(email: String): StudentEntity?

    @Query("SELECT * FROM students WHERE id = :id LIMIT 1")
    suspend fun getStudentById(id: Int): StudentEntity?

    @Insert
    suspend fun insertStudent(student: StudentEntity): Long

    @Update
    suspend fun updateStudent(student: StudentEntity)

    @Insert
    suspend fun logAttempt(attempt: LoginAttemptEntity)

    @Query("SELECT COUNT(*) FROM login_attempts WHERE email = :email AND success = 0 AND attempted_at > :timeLimit")
    suspend fun getFailedAttemptsCount(email: String, timeLimit: Long): Int

    // --- Основной модуль знакомств (Анкеты, Лайки, Мэтчи, Сообщения) ---
    @Query(
        """
        SELECT * FROM students 
        WHERE id != :currentStudentId 
        AND id NOT IN (SELECT to_student_id FROM likes WHERE from_student_id = :currentStudentId)
        ORDER BY id ASC
        """
    )
    suspend fun getCandidateStudents(currentStudentId: Int): List<StudentEntity>

    @Insert
    suspend fun insertLike(like: LikeEntity): Long

    @Query(
        """
        SELECT * FROM likes 
        WHERE from_student_id = :fromId AND to_student_id = :toId AND is_like = 1 
        LIMIT 1
        """
    )
    suspend fun getPositiveLike(fromId: Int, toId: Int): LikeEntity?

    @Query("DELETE FROM likes WHERE from_student_id = :studentId")
    suspend fun clearStudentSwipes(studentId: Int)

    @Insert
    suspend fun insertMatch(match: MatchEntity): Long

    @Query(
        """
        SELECT * FROM matches 
        WHERE (student_a_id = :firstId AND student_b_id = :secondId)
           OR (student_a_id = :secondId AND student_b_id = :firstId)
        LIMIT 1
        """
    )
    suspend fun getExistingMatch(firstId: Int, secondId: Int): MatchEntity?

    @Query(
        """
        SELECT * FROM matches 
        WHERE student_a_id = :studentId OR student_b_id = :studentId
        ORDER BY created_at DESC
        """
    )
    suspend fun getMatchesForStudent(studentId: Int): List<MatchEntity>

    @Query("SELECT * FROM messages WHERE match_id = :matchId ORDER BY sent_at ASC")
    suspend fun getMessagesForMatch(matchId: Int): List<MessageEntity>

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long
}
