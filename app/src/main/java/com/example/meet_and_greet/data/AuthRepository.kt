package com.example.meet_and_greet.data

import java.security.MessageDigest
import kotlin.random.Random

data class MatchWithPartner(
    val match: MatchEntity,
    val partner: StudentEntity,
    val lastMessage: String?
)

class AuthRepository(private val dao: AuthDao) {

    companion object {
        const val COLLEGE_EMAIL_DOMAIN = "@itmoscow.pro"
    }

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // --- Проверка принадлежности почты к домену колледжа @itmoscow.pro ---
    fun isCollegeEmail(email: String): Boolean {
        val normalized = email.trim().lowercase()
        val localPart = normalized.substringBefore("@", "")
        return localPart.isNotBlank() && normalized.endsWith(COLLEGE_EMAIL_DOMAIN)
    }

    // --- Генерация 4-значного одноразового кода подтверждения почты @itmoscow.pro ---
    fun generateVerificationCode(): String {
        return Random.nextInt(1000, 9999).toString()
    }

    // --- Предварительная проверка данных перед отправкой кода подтверждения ---
    suspend fun validateBeforeOtp(
        email: String,
        password: String,
        name: String
    ): Result<Unit> {
        val cleanEmail = email.trim().lowercase()
        if (!isCollegeEmail(cleanEmail)) {
            return Result.failure(
                Exception("Регистрация доступна только студентам колледжа с почтой $COLLEGE_EMAIL_DOMAIN (например, student$COLLEGE_EMAIL_DOMAIN)")
            )
        }
        if (password.length < 4) {
            return Result.failure(Exception("Пароль должен содержать минимум 4 символа"))
        }
        if (name.trim().length < 2) {
            return Result.failure(Exception("Введите ваше имя (минимум 2 буквы)"))
        }
        if (dao.getStudentByEmail(cleanEmail) != null) {
            return Result.failure(Exception("Студент с таким Email уже зарегистрирован"))
        }
        return Result.success(Unit)
    }

    // --- Получение пользователя по ID (для авто-входа из сохраненной сессии) ---
    suspend fun getStudentById(id: Int): StudentEntity? {
        return dao.getStudentById(id)
    }

    // --- Авторизация ---
    suspend fun login(email: String, password: String): Result<StudentEntity> {
        val cleanEmail = email.trim().lowercase()

        if (!isCollegeEmail(cleanEmail)) {
            return Result.failure(
                Exception("Доступ только для студентов колледжа! Используйте корпоративную почту $COLLEGE_EMAIL_DOMAIN")
            )
        }

        val fifteenMinsAgo = System.currentTimeMillis() - (15 * 60 * 1000)

        if (dao.getFailedAttemptsCount(cleanEmail, fifteenMinsAgo) >= 5) {
            return Result.failure(Exception("Слишком много попыток входа. Подождите 15 минут."))
        }

        val student = dao.getStudentByEmail(cleanEmail)
        if (student == null) {
            dao.logAttempt(LoginAttemptEntity(email = cleanEmail, success = false))
            return Result.failure(Exception("Аккаунт с таким email не найден. Зарегистрируйтесь!"))
        }

        if (student.password_hash != hashPassword(password)) {
            dao.logAttempt(LoginAttemptEntity(email = cleanEmail, success = false))
            return Result.failure(Exception("Неверный пароль"))
        }

        dao.logAttempt(LoginAttemptEntity(email = cleanEmail, success = true))
        return Result.success(student)
    }

    // --- Полноценная регистрация нового пользователя (после подтверждения OTP-кода) ---
    suspend fun register(
        email: String,
        password: String,
        name: String,
        faculty: String = "Информационные технологии",
        studyGroup: String = "ИС-21",
        age: Int = 18,
        bio: String = "Люблю рисовать и читать",
        interests: String = "Рисование, Чтение",
        lookingFor: String = "relationship",
        photoUri: String? = null
    ): Result<StudentEntity> {
        val validation = validateBeforeOtp(email, password, name)
        if (validation.isFailure) {
            return Result.failure(validation.exceptionOrNull()!!)
        }

        val cleanEmail = email.trim().lowercase()

        // Гарантируем, что демо-анкеты уже созданы в БД перед регистрацией нового участника
        seedTestDataIfNeeded()

        val newStudent = StudentEntity(
            email = cleanEmail,
            password_hash = hashPassword(password),
            full_name = name.trim(),
            faculty = faculty.trim().ifEmpty { "Информационные технологии" },
            study_group = studyGroup.trim().ifEmpty { "ИС-21" },
            age = age.coerceIn(15, 60),
            bio = bio.trim().ifEmpty { "Люблю рисовать и читать" },
            interests = interests.trim().ifEmpty { "Рисование, Чтение" },
            looking_for = lookingFor,
            photo_uri = photoUri,
            email_verified = true,
            likes_received = 15
        )
        val newId = dao.insertStudent(newStudent).toInt()

        // Входящие лайки от тестовых анкет к новому пользователю
        val alex = dao.getStudentByEmail("alex@itmoscow.pro")
        val clara = dao.getStudentByEmail("clara@itmoscow.pro")
        val bob = dao.getStudentByEmail("bob@itmoscow.pro")

        if (alex != null && alex.id != newId) {
            dao.insertLike(LikeEntity(from_student_id = alex.id, to_student_id = newId, is_like = true))
        }
        if (clara != null && clara.id != newId) {
            dao.insertLike(LikeEntity(from_student_id = clara.id, to_student_id = newId, is_like = true))
        }
        if (bob != null && bob.id != newId) {
            dao.insertLike(LikeEntity(from_student_id = bob.id, to_student_id = newId, is_like = true))
        }

        return Result.success(newStudent.copy(id = newId))
    }

    // --- Предзаполнение тестовых анкет («Meet-and-Greet») ---
    suspend fun seedTestDataIfNeeded() {
        dao.migrateOldBrandingMessages()
        val existingAnna = dao.getStudentByEmail("anna@itmoscow.pro")
        if (existingAnna != null) return

        val passHash = hashPassword("password123")

        val annaId = dao.insertStudent(
            StudentEntity(
                email = "anna@itmoscow.pro",
                password_hash = passHash,
                full_name = "Анна Иванова",
                faculty = "Информационные технологии",
                study_group = "ИС-21",
                age = 18,
                bio = "Увлекаюсь иллюстрацией, читаю книги и пишу код на Kotlin",
                interests = "Рисование, Чтение, Музыка",
                looking_for = "relationship",
                likes_received = 42
            )
        ).toInt()

        // Первая карточка — точно как на макете: «Александр, 17 — Люблю рисовать и читать»
        val alexId = dao.insertStudent(
            StudentEntity(
                email = "alex@itmoscow.pro",
                password_hash = passHash,
                full_name = "Александр",
                faculty = "Дизайн и искусство",
                study_group = "ДЗ-11",
                age = 17,
                bio = "Люблю рисовать и читать",
                interests = "Рисование, Чтение, Фотография",
                looking_for = "relationship",
                likes_received = 58
            )
        ).toInt()

        val bobId = dao.insertStudent(
            StudentEntity(
                email = "bob@itmoscow.pro",
                password_hash = passHash,
                full_name = "Борис",
                faculty = "Информационные технологии",
                study_group = "ИС-22",
                age = 19,
                bio = "Играю в баскетбол, смотрю кино и люблю путешествия",
                interests = "Спорт, Кино, Игры, Чтение",
                looking_for = "relationship",
                likes_received = 36
            )
        ).toInt()

        val claraId = dao.insertStudent(
            StudentEntity(
                email = "clara@itmoscow.pro",
                password_hash = passHash,
                full_name = "Клара",
                faculty = "Дизайн и медиа",
                study_group = "ДЗ-21",
                age = 18,
                bio = "Фотографирую на плёнку, рисую скетчи и слушаю инди-музыку",
                interests = "Рисование, Фотография, Музыка",
                looking_for = "friends",
                likes_received = 49
            )
        ).toInt()

        val denisId = dao.insertStudent(
            StudentEntity(
                email = "denis@itmoscow.pro",
                password_hash = passHash,
                full_name = "Денис",
                faculty = "Информационные технологии",
                study_group = "ПИ-23",
                age = 20,
                bio = "Играю на гитаре, люблю настолки и вкусную кулинарию",
                interests = "Музыка, Игры, Кулинария, Чтение",
                looking_for = "friends",
                likes_received = 31
            )
        ).toInt()

        dao.insertStudent(
            StudentEntity(
                email = "elena@itmoscow.pro",
                password_hash = passHash,
                full_name = "Елена",
                faculty = "Разработка и дизайн",
                study_group = "РД-21",
                age = 18,
                bio = "Обожаю книги, уютные кофейни и поездки за город",
                interests = "Чтение, Путешествия, Кулинария",
                looking_for = "relationship",
                likes_received = 44
            )
        )

        // Входящие симпатии для Анны (вкладка «Вам» и мгновенные мэтчи при лайке)
        dao.insertLike(LikeEntity(from_student_id = alexId, to_student_id = annaId, is_like = true))
        dao.insertLike(LikeEntity(from_student_id = bobId, to_student_id = annaId, is_like = true))
        dao.insertLike(LikeEntity(from_student_id = claraId, to_student_id = annaId, is_like = true))
        dao.insertLike(LikeEntity(from_student_id = denisId, to_student_id = annaId, is_like = true))
    }

    // --- Основной функционал знакомств ---
    suspend fun getCandidates(currentStudentId: Int): List<StudentEntity> {
        return dao.getCandidateStudents(currentStudentId)
    }

    suspend fun getOutgoingLikes(currentStudentId: Int): List<StudentEntity> {
        return dao.getOutgoingLikedStudents(currentStudentId)
    }

    suspend fun getIncomingLikes(currentStudentId: Int): List<StudentEntity> {
        return dao.getIncomingLikedStudents(currentStudentId)
    }

    suspend fun getRatingList(): List<StudentEntity> {
        return dao.getAllStudentsByRating()
    }

    suspend fun swipeStudent(
        currentStudentId: Int,
        targetStudentId: Int,
        isLike: Boolean
    ): StudentEntity? {
        if (dao.getPositiveLike(fromId = currentStudentId, toId = targetStudentId) == null) {
            dao.insertLike(
                LikeEntity(
                    from_student_id = currentStudentId,
                    to_student_id = targetStudentId,
                    is_like = isLike
                )
            )
            if (isLike) {
                val target = dao.getStudentById(targetStudentId)
                if (target != null) {
                    dao.updateStudent(target.copy(likes_received = target.likes_received + 1))
                }
            }
        }

        if (!isLike) return null

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
                        body = "Привет! У нас совпали интересы в Meet-and-Greet 😊"
                    )
                )
                return partner
            }
        }
        return null
    }

    // --- Отмена исходящего лайка во вкладке «Вы» ---
    suspend fun revokeLike(currentStudentId: Int, targetStudentId: Int) {
        dao.deleteLike(fromId = currentStudentId, toId = targetStudentId)
    }

    // --- Отмена мэтча (Unmatch) ---
    suspend fun unmatch(matchId: Int, currentStudentId: Int, partnerId: Int) {
        dao.deleteMessagesForMatch(matchId)
        dao.deleteMatchById(matchId)
        dao.deleteLike(fromId = currentStudentId, toId = partnerId)
    }

    // --- Жалоба и блокировка нежелательного пользователя (Модерация MVP) ---
    suspend fun reportAndBlockStudent(
        reporterId: Int,
        reportedStudentId: Int,
        reason: String
    ) {
        dao.insertReport(
            ReportEntity(
                reporter_id = reporterId,
                reported_student_id = reportedStudentId,
                reason = reason,
                is_blocked = true
            )
        )
        // Удаляем лайки и возможный мэтч с заблокированным пользователем
        dao.deleteLike(fromId = reporterId, toId = reportedStudentId)
        dao.deleteLike(fromId = reportedStudentId, toId = reporterId)
        val existingMatch = dao.getExistingMatch(reporterId, reportedStudentId)
        if (existingMatch != null) {
            dao.deleteMessagesForMatch(existingMatch.id)
            dao.deleteMatchById(existingMatch.id)
        }
    }

    // --- Удаление собственного аккаунта (требование MVP) ---
    suspend fun deleteAccount(studentId: Int) {
        dao.deleteAllLikesForStudent(studentId)
        dao.deleteAllMatchesForStudent(studentId)
        dao.deleteStudentById(studentId)
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

    suspend fun sendMessage(matchId: Int, senderId: Int, text: String, autoReplyPartnerId: Int? = null) {
        dao.insertMessage(
            MessageEntity(
                match_id = matchId,
                sender_id = senderId,
                body = text.trim()
            )
        )
        if (autoReplyPartnerId != null) {
            val allMessages = dao.getMessagesForMatch(matchId)
            val myMessagesCount = allMessages.count { it.sender_id == senderId }
            if (myMessagesCount == 1) {
                dao.insertMessage(
                    MessageEntity(
                        match_id = matchId,
                        sender_id = autoReplyPartnerId,
                        body = "Класс! Рад знакомству, давай пообщаемся подробнее! ✨",
                        sent_at = System.currentTimeMillis() + 500
                    )
                )
            }
        }
    }

    suspend fun updateProfile(student: StudentEntity): StudentEntity {
        dao.updateStudent(student)
        return dao.getStudentById(student.id) ?: student
    }
}
