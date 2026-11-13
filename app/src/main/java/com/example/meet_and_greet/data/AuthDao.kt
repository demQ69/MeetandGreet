package com.example.meet_and_greet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AuthDao {
    // --- Модуль авторизации и регистрации ---
    @Query("SELECT * FROM students WHERE LOWER(email) = LOWER(:email) LIMIT 1")
    suspend fun getStudentByEmail(email: String): StudentEntity?

    @Query("SELECT * FROM students WHERE id = :id LIMIT 1")
    suspend fun getStudentById(id: Int): StudentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudent(student: StudentEntity): Long

    @Update
    suspend fun updateStudent(student: StudentEntity)

    @Query("DELETE FROM students WHERE id = :studentId")
    suspend fun deleteStudentById(studentId: Int)

    @Insert
    suspend fun logAttempt(attempt: LoginAttemptEntity)

    @Query("SELECT COUNT(*) FROM login_attempts WHERE LOWER(email) = LOWER(:email) AND success = 0 AND attempted_at > :timeLimit")
    suspend fun getFailedAttemptsCount(email: String, timeLimit: Long): Int

    // --- Основной модуль знакомств (с учетом блокировок/жалоб) ---
    @Query(
        """
        SELECT * FROM students 
        WHERE id != :currentStudentId 
        AND id NOT IN (SELECT to_student_id FROM likes WHERE from_student_id = :currentStudentId)
        AND id NOT IN (SELECT reported_student_id FROM reports WHERE reporter_id = :currentStudentId AND is_blocked = 1)
        ORDER BY id ASC
        """
    )
    suspend fun getCandidateStudents(currentStudentId: Int): List<StudentEntity>

    @Query("SELECT * FROM students ORDER BY likes_received DESC, id ASC")
    suspend fun getAllStudentsByRating(): List<StudentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLike(like: LikeEntity): Long

    @Query("DELETE FROM likes WHERE from_student_id = :fromId AND to_student_id = :toId")
    suspend fun deleteLike(fromId: Int, toId: Int)

    @Query(
        """
        SELECT * FROM likes 
        WHERE from_student_id = :fromId AND to_student_id = :toId AND is_like = 1 
        LIMIT 1
        """
    )
    suspend fun getPositiveLike(fromId: Int, toId: Int): LikeEntity?

    @Query(
        """
        SELECT * FROM students 
        WHERE id IN (SELECT to_student_id FROM likes WHERE from_student_id = :studentId AND is_like = 1)
        ORDER BY id ASC
        """
    )
    suspend fun getOutgoingLikedStudents(studentId: Int): List<StudentEntity>

    @Query(
        """
        SELECT * FROM students 
        WHERE id IN (SELECT from_student_id FROM likes WHERE to_student_id = :studentId AND is_like = 1)
        AND id NOT IN (
            SELECT CASE WHEN student_a_id = :studentId THEN student_b_id ELSE student_a_id END
            FROM matches
            WHERE student_a_id = :studentId OR student_b_id = :studentId
        )
        AND id NOT IN (SELECT reported_student_id FROM reports WHERE reporter_id = :studentId AND is_blocked = 1)
        ORDER BY id ASC
        """
    )
    suspend fun getIncomingLikedStudents(studentId: Int): List<StudentEntity>

    @Query("DELETE FROM likes WHERE from_student_id = :studentId")
    suspend fun clearStudentSwipes(studentId: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatch(match: MatchEntity): Long

    @Query("DELETE FROM matches WHERE id = :matchId")
    suspend fun deleteMatchById(matchId: Int)

    @Query("DELETE FROM messages WHERE match_id = :matchId")
    suspend fun deleteMessagesForMatch(matchId: Int)

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

    // --- Модуль модерации, жалоб и блокировок ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(report: ReportEntity): Long

    @Query("DELETE FROM likes WHERE from_student_id = :studentId OR to_student_id = :studentId")
    suspend fun deleteAllLikesForStudent(studentId: Int)

    @Query("DELETE FROM matches WHERE student_a_id = :studentId OR student_b_id = :studentId")
    suspend fun deleteAllMatchesForStudent(studentId: Int)
}
