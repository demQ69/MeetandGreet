
package com.example.meet_and_greet

import com.example.meet_and_greet.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class AuthRepositoryTest {

    private lateinit var fakeDao: FakeAuthDao
    private lateinit var repository: AuthRepository

    @Before
    fun setup() {
        fakeDao = FakeAuthDao()
        repository = AuthRepository(fakeDao)
    }

    private fun hash(password: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun loginWithCorrectCredentials() = runBlocking {
        fakeDao.students.add(
            StudentEntity(
                id = 1,
                email = "test@mail.com",
                password_hash = hash("12345"),
                full_name = "Test User"
            )
        )

        val result = repository.login("test@mail.com", "12345")

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.id)
    }

    @Test
    fun loginWithWrongPassword() = runBlocking {
        fakeDao.students.add(
            StudentEntity(
                email = "test@mail.com",
                password_hash = hash("12345"),
                full_name = "Test User"
            )
        )

        val result = repository.login("test@mail.com", "wrong")

        assertTrue(result.isFailure)
        assertEquals(1, fakeDao.attempts.size)
        assertFalse(fakeDao.attempts[0].success)
    }

    @Test
    fun loginWithUnknownEmail() = runBlocking {
        val result = repository.login("unknown@mail.com", "12345")

        assertTrue(result.isFailure)
        assertEquals(
            "Неверный email или пароль",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun loginBlockedAfterFiveFailures() = runBlocking {
        repeat(5) {
            fakeDao.attempts.add(
                LoginAttemptEntity(
                    email = "test@mail.com",
                    success = false
                )
            )
        }

        val result = repository.login("test@mail.com", "12345")

        assertTrue(result.isFailure)
        assertEquals(
            "Слишком много попыток. Подождите 15 минут.",
            result.exceptionOrNull()?.message
        )
        assertEquals(5, fakeDao.attempts.size)
    }

    @Test
    fun registerNewUser() = runBlocking {
        val result = repository.register(
            "new@mail.com",
            "12345",
            "New User"
        )

        assertTrue(result.isSuccess)
        assertEquals("new@mail.com", result.getOrNull()?.email)
        assertEquals(1, fakeDao.students.size)
        assertEquals(hash("12345"), fakeDao.students[0].password_hash)
    }

    @Test
    fun registerWithExistingEmail() = runBlocking {
        fakeDao.students.add(
            StudentEntity(
                email = "test@mail.com",
                password_hash = hash("12345"),
                full_name = "Test User"
            )
        )

        val result = repository.register(
            "test@mail.com",
            "anotherPassword",
            "Another User"
        )

        assertTrue(result.isFailure)
        assertEquals("Email уже занят", result.exceptionOrNull()?.message)
        assertEquals(1, fakeDao.students.size)
    }
}

private class FakeAuthDao : AuthDao {

    val students = mutableListOf<StudentEntity>()
    val attempts = mutableListOf<LoginAttemptEntity>()

    override suspend fun getStudentByEmail(email: String): StudentEntity? {
        return students.find { it.email == email }
    }

    override suspend fun insertStudent(student: StudentEntity): Long {
        val id = (students.maxOfOrNull { it.id } ?: 0) + 1
        students.add(student.copy(id = id))
        return id.toLong()
    }

    override suspend fun logAttempt(attempt: LoginAttemptEntity) {
        attempts.add(attempt)
    }

    override suspend fun getFailedAttemptsCount(
        email: String,
        timeLimit: Long
    ): Int {
        return attempts.count {
            it.email == email &&
            !it.success &&
            it.attempted_at > timeLimit
        }
    }
}

