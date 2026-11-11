package com.example.meet_and_greet.data

import java.security.MessageDigest

class AuthRepository(private val dao: AuthDao) {

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun login(email: String, password: String): Result<StudentEntity> {
        val fifteenMinsAgo = System.currentTimeMillis() - (15 * 60 * 1000)

        // Проверка на количество попыток
        if (dao.getFailedAttemptsCount(email, fifteenMinsAgo) >= 5) {
            return Result.failure(Exception("Слишком много попыток. Подождите 15 минут."))
        }

        val student = dao.getStudentByEmail(email)
        if (student == null) {
            dao.logAttempt(LoginAttemptEntity(email = email, success = false))
            return Result.failure(Exception("Неверный email или пароль"))
        }

        if (student.password_hash != hashPassword(password)) {
            dao.logAttempt(LoginAttemptEntity(email = email, success = false))
            return Result.failure(Exception("Неверный email или пароль"))
        }

        dao.logAttempt(LoginAttemptEntity(email = email, success = true))
        return Result.success(student)
    }

    suspend fun register(email: String, password: String, name: String): Result<StudentEntity> {
        if (dao.getStudentByEmail(email) != null) {
            return Result.failure(Exception("Email уже занят"))
        }
        val newStudent = StudentEntity(
            email = email,
            password_hash = hashPassword(password),
            full_name = name
        )
        val id = dao.insertStudent(newStudent)
        return Result.success(newStudent.copy(id = id.toInt()))
    }
}