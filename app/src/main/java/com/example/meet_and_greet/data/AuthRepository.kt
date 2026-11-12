package com.example.meet_and_greet.data

import java.security.MessageDigest

data class MatchWithPartner(
    val match: MatchEntity,
    val partner: StudentEntity,
    val lastMessage: String?
)

class AuthRepository(private val dao: AuthDao) {

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // --- Авторизация ---
    suspend fun login(email: String, password: String): Result<StudentEntity> {
        val fifteenMinsAgo = System.currentTimeMillis() - (15 * 60 * 1000)

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

    // --- Регистрация ---
    suspend fun register(
        email: String,
        password: String,
        name: String,
        faculty: String = "Информационные технологии",
        studyGroup: String = "ИС-21",
        age: Int = 20,
        bio: String = "Привет! Ищу друзей и единомышленников в колледже.",
        interests: String = "Музыка, Кино, Спорт"
    ): Result<StudentEntity> {
        if (dao.getStudentByEmail(email) != null) {
            return Result.failure(Exception("Email уже занят"))
        }
        val newStudent = StudentEntity(
            email = email,
            password_hash = hashPassword(password),
            full_name = name,
            faculty = faculty,
            study_group = studyGroup,
            age = age,
            bio = bio,
            interests = interests
        )
        val id = dao.insertStudent(newStudent)
        return Result.success(newStudent.copy(id = id.toInt()))
    }

    // --- Заполнение БД тестовыми анкетами колледжа ---
    suspend fun seedTestDataIfNeeded() {
        val existingAnna = dao.getStudentByEmail("anna@college.ru")
        if (existingAnna != null) return

        val passHash = hashPassword("password123")

        val annaId = dao.insertStudent(
            StudentEntity(
                email = "anna@college.ru",
                password_hash = passHash,
                full_name = "Анна Иванова",
                faculty = "Информационные технологии",
                study_group = "ИС-21",
                age = 20,
                bio = "Люблю кофе, разработку под Android на Kotlin и студенческие хакатоны.",
                interests = "Программирование, Кофе, Книги"
            )
        ).toInt()

        val bobId = dao.insertStudent(
            StudentEntity(
                email = "bob@college.ru",
                password_hash = passHash,
                full_name = "Борис Петров",
                faculty = "Информационные технологии",
                study_group = "ИС-22",
                age = 21,
                bio = "Играю в баскетбол за сборную колледжа. Ищу компанию на матчи и в кино!",
                interests = "Спорт, Баскетбол, Игры"
            )
        ).toInt()

        val claraId = dao.insertStudent(
            StudentEntity(
                email = "clara@college.ru",
                password_hash = passHash,
                full_name = "Клара Сидорова",
                faculty = "Дизайн",
                study_group = "ДЗ-21",
                age = 19,
                bio = "Фотографирую всё вокруг на плёнку, рисую постеры и обожаю выставки.",
                interests = "Фотография, Дизайн, Кино"
            )
        ).toInt()

        val denisId = dao.insertStudent(
            StudentEntity(
                email = "denis@college.ru",
                password_hash = passHash,
                full_name = "Денис Кузнецов",
                faculty = "Информационные технологии",
                study_group = "ПИ-23",
                age = 20,
                bio = "Увлекаюсь backend-разработкой на Kotlin, играю на электрогитаре.",
                interests = "Программирование, Музыка, Игры"
            )
        ).toInt()

        dao.insertStudent(
            StudentEntity(
                email = "elena@college.ru",
                password_hash = passHash,
                full_name = "Елена Морозова",
                faculty = "Экономика",
                study_group = "ЭК-21",
                age = 19,
                bio = "Танцую современную хореографию и читаю детективы в перерывах между парами.",
                interests = "Танцы, Книги, Путешествия"
            )
        )

        dao.insertStudent(
            StudentEntity(
                email = "fedor@college.ru",
                password_hash = passHash,
                full_name = "Фёдор Соколов",
                faculty = "Право",
                study_group = "ЮР-22",
                age = 21,
                bio = "Организую настольные игры в студсовете и люблю выбираться в походы.",
                interests = "Путешествия, Кино, Настолки"
            )
        )

        // Входящие лайки для Анны, чтобы при нажатии «Нравится» сразу срабатывал взаимный Мэтч
        dao.insertLike(LikeEntity(from_student_id = bobId, to_student_id = annaId, is_like = true))
        dao.insertLike(LikeEntity(from_student_id = claraId, to_student_id = annaId, is_like = true))
        dao.insertLike(LikeEntity(from_student_id = denisId, to_student_id = annaId, is_like = true))
    }

    // --- Основной функционал знакомств ---
    suspend fun getCandidates(currentStudentId: Int): List<StudentEntity> {
        return dao.getCandidateStudents(currentStudentId)
    }

    suspend fun swipeStudent(
        currentStudentId: Int,
        targetStudentId: Int,
        isLike: Boolean
    ): StudentEntity? {
        dao.insertLike(
            LikeEntity(
                from_student_id = currentStudentId,
                to_student_id = targetStudentId,
                is_like = isLike
            )
        )
        if (!isLike) return null

        // Проверяем, лайкал ли второй студент текущего пользователя
        val mutualLike = dao.getPositiveLike(fromId = targetStudentId, toId = currentStudentId)
        if (mutualLike != null) {
            val existing = dao.getExistingMatch(currentStudentId, targetStudentId)
            if (existing == null) {
                val a = minOf(currentStudentId, targetStudentId)
                val b = maxOf(currentStudentId, targetStudentId)
                val matchId = dao.insertMatch(MatchEntity(student_a_id = a, student_b_id = b)).toInt()
                val partner = dao.getStudentById(targetStudentId)
                dao.insertMessage(
                    MessageEntity(
                        match_id = matchId,
                        sender_id = targetStudentId,
                        body = "Привет! Рад(а) взаимной симпатии в колледже!"
                    )
                )
                return partner
            }
        }
        return null
    }

    suspend fun resetSwipes(currentStudentId: Int) {
        dao.clearStudentSwipes(currentStudentId)
    }

    suspend fun getMatchesWithPartners(currentStudentId: Int): List<MatchWithPartner> {
        val matches = dao.getMatchesForStudent(currentStudentId)
        val result = mutableListOf<MatchWithPartner>()
        for (m in matches) {
            val partnerId = if (m.student_a_id == currentStudentId) m.student_b_id else m.student_a_id
            val partner = dao.getStudentById(partnerId) ?: continue
            val messages = dao.getMessagesForMatch(m.id)
            result.add(
                MatchWithPartner(
                    match = m,
                    partner = partner,
                    lastMessage = messages.lastOrNull()?.body
                )
            )
        }
        return result
    }

    suspend fun getMessages(matchId: Int): List<MessageEntity> {
        return dao.getMessagesForMatch(matchId)
    }

    suspend fun sendMessage(matchId: Int, senderId: Int, text: String) {
        dao.insertMessage(
            MessageEntity(
                match_id = matchId,
                sender_id = senderId,
                body = text.trim()
            )
        )
    }

    suspend fun updateProfile(student: StudentEntity): StudentEntity {
        dao.updateStudent(student)
        return dao.getStudentById(student.id) ?: student
    }
}
