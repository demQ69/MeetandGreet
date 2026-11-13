package com.example.meet_and_greet.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

    @Volatile
    var serverBaseUrl: String = ""
        private set

    fun updateServerUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        serverBaseUrl = if (cleaned.isNotEmpty() && !cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            "https://$cleaned"
        } else {
            cleaned
        }
    }

    fun isRemoteMode(): Boolean = serverBaseUrl.isNotBlank()

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

    // --- Проверка соединения с Java-сервером ---
    suspend fun checkServerHealth(urlToTest: String): Result<String> = withContext(Dispatchers.IO) {
        val normalized = urlToTest.trim().trimEnd('/').let {
            if (it.isNotEmpty() && !it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it
        }
        if (normalized.isBlank()) {
            return@withContext Result.failure(Exception("Введите адрес сервера (например, https://xxxx.trycloudflare.com)"))
        }
        try {
            val respText = httpRequest(normalized, "GET", "/api/health", null)
            val json = JSONObject(respText)
            val count = json.optInt("students", 0)
            Result.success("Подключено! Анкет на сервере: $count")
        } catch (e: Exception) {
            Result.failure(e)
        }
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
        if (!isRemoteMode() && dao.getStudentByEmail(cleanEmail) != null) {
            return Result.failure(Exception("Студент с таким Email уже зарегистрирован"))
        }
        return Result.success(Unit)
    }

    // --- Получение пользователя по ID (для авто-входа из сохраненной сессии) ---
    suspend fun getStudentById(id: Int): StudentEntity? {
        if (isRemoteMode()) {
            return try {
                withContext(Dispatchers.IO) {
                    val text = httpRequest(serverBaseUrl, "GET", "/api/student?id=$id", null)
                    parseStudent(JSONObject(text))
                }
            } catch (_: Exception) {
                null
            }
        }
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

        if (isRemoteMode()) {
            return withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply {
                        put("email", cleanEmail)
                        put("password", password)
                    }
                    val resp = httpRequest(serverBaseUrl, "POST", "/api/login", body.toString())
                    Result.success(parseStudent(JSONObject(resp)))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
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

        if (isRemoteMode()) {
            return withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply {
                        put("email", cleanEmail)
                        put("password", password)
                        put("full_name", name.trim())
                        put("faculty", faculty.trim().ifEmpty { "Информационные технологии" })
                        put("study_group", studyGroup.trim().ifEmpty { "ИС-21" })
                        put("age", age.coerceIn(15, 60))
                        put("bio", bio.trim().ifEmpty { "Люблю рисовать и читать" })
                        put("interests", interests.trim().ifEmpty { "Рисование, Чтение" })
                        put("looking_for", lookingFor)
                        val b64 = encodeLocalImageToBase64(photoUri)
                        if (b64 != null) put("photo_base64", b64)
                    }
                    val resp = httpRequest(serverBaseUrl, "POST", "/api/register", body.toString())
                    Result.success(parseStudent(JSONObject(resp)))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }

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
        if (isRemoteMode()) return
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

        dao.insertLike(LikeEntity(from_student_id = alexId, to_student_id = annaId, is_like = true))
        dao.insertLike(LikeEntity(from_student_id = bobId, to_student_id = annaId, is_like = true))
        dao.insertLike(LikeEntity(from_student_id = claraId, to_student_id = annaId, is_like = true))
        dao.insertLike(LikeEntity(from_student_id = denisId, to_student_id = annaId, is_like = true))
    }

    // --- Основной функционал знакомств ---
    suspend fun getCandidates(currentStudentId: Int): List<StudentEntity> {
        if (isRemoteMode()) {
            return try {
                withContext(Dispatchers.IO) {
                    val text = httpRequest(serverBaseUrl, "GET", "/api/candidates?student_id=$currentStudentId", null)
                    parseStudentList(JSONArray(text))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return dao.getCandidateStudents(currentStudentId)
    }

    suspend fun getOutgoingLikes(currentStudentId: Int): List<StudentEntity> {
        if (isRemoteMode()) {
            return try {
                withContext(Dispatchers.IO) {
                    val text = httpRequest(serverBaseUrl, "GET", "/api/outgoing_likes?student_id=$currentStudentId", null)
                    parseStudentList(JSONArray(text))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return dao.getOutgoingLikedStudents(currentStudentId)
    }

    suspend fun getIncomingLikes(currentStudentId: Int): List<StudentEntity> {
        if (isRemoteMode()) {
            return try {
                withContext(Dispatchers.IO) {
                    val text = httpRequest(serverBaseUrl, "GET", "/api/incoming_likes?student_id=$currentStudentId", null)
                    parseStudentList(JSONArray(text))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return dao.getIncomingLikedStudents(currentStudentId)
    }

    suspend fun getRatingList(): List<StudentEntity> {
        if (isRemoteMode()) {
            return try {
                withContext(Dispatchers.IO) {
                    val text = httpRequest(serverBaseUrl, "GET", "/api/rating", null)
                    parseStudentList(JSONArray(text))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return dao.getAllStudentsByRating()
    }

    suspend fun swipeStudent(
        currentStudentId: Int,
        targetStudentId: Int,
        isLike: Boolean
    ): StudentEntity? {
        if (isRemoteMode()) {
            return try {
                withContext(Dispatchers.IO) {
                    val body = JSONObject().apply {
                        put("from_id", currentStudentId)
                        put("to_id", targetStudentId)
                        put("is_like", isLike)
                    }
                    val resp = httpRequest(serverBaseUrl, "POST", "/api/swipe", body.toString())
                    val json = JSONObject(resp)
                    val matchedObj = json.optJSONObject("matched_partner")
                    matchedObj?.let { parseStudent(it) }
                }
            } catch (_: Exception) {
                null
            }
        }

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

    suspend fun revokeLike(currentStudentId: Int, targetStudentId: Int) {
        if (isRemoteMode()) {
            withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply {
                        put("from_id", currentStudentId)
                        put("to_id", targetStudentId)
                    }
                    httpRequest(serverBaseUrl, "POST", "/api/revoke_like", body.toString())
                } catch (_: Exception) {}
            }
            return
        }
        dao.deleteLike(fromId = currentStudentId, toId = targetStudentId)
    }

    suspend fun unmatch(matchId: Int, currentStudentId: Int, partnerId: Int) {
        if (isRemoteMode()) {
            withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply {
                        put("match_id", matchId)
                        put("student_id", currentStudentId)
                        put("partner_id", partnerId)
                    }
                    httpRequest(serverBaseUrl, "POST", "/api/unmatch", body.toString())
                } catch (_: Exception) {}
            }
            return
        }
        dao.deleteMessagesForMatch(matchId)
        dao.deleteMatchById(matchId)
        dao.deleteLike(fromId = currentStudentId, toId = partnerId)
    }

    suspend fun reportAndBlockStudent(
        reporterId: Int,
        reportedStudentId: Int,
        reason: String
    ) {
        if (isRemoteMode()) {
            withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply {
                        put("reporter_id", reporterId)
                        put("reported_id", reportedStudentId)
                        put("reason", reason)
                    }
                    httpRequest(serverBaseUrl, "POST", "/api/report", body.toString())
                } catch (_: Exception) {}
            }
            return
        }
        dao.insertReport(
            ReportEntity(
                reporter_id = reporterId,
                reported_student_id = reportedStudentId,
                reason = reason,
                is_blocked = true
            )
        )
        dao.deleteLike(fromId = reporterId, toId = reportedStudentId)
        dao.deleteLike(fromId = reportedStudentId, toId = reporterId)
        val existingMatch = dao.getExistingMatch(reporterId, reportedStudentId)
        if (existingMatch != null) {
            dao.deleteMessagesForMatch(existingMatch.id)
            dao.deleteMatchById(existingMatch.id)
        }
    }

    suspend fun deleteAccount(studentId: Int) {
        if (isRemoteMode()) {
            withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply { put("student_id", studentId) }
                    httpRequest(serverBaseUrl, "POST", "/api/delete_account", body.toString())
                } catch (_: Exception) {}
            }
            return
        }
        dao.deleteAllLikesForStudent(studentId)
        dao.deleteAllMatchesForStudent(studentId)
        dao.deleteStudentById(studentId)
    }

    suspend fun resetSwipes(currentStudentId: Int) {
        if (isRemoteMode()) {
            withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply { put("student_id", currentStudentId) }
                    httpRequest(serverBaseUrl, "POST", "/api/reset_swipes", body.toString())
                } catch (_: Exception) {}
            }
            return
        }
        dao.clearStudentSwipes(currentStudentId)
    }

    suspend fun getMatchesWithPartners(currentStudentId: Int): List<MatchWithPartner> {
        if (isRemoteMode()) {
            return try {
                withContext(Dispatchers.IO) {
                    val text = httpRequest(serverBaseUrl, "GET", "/api/matches?student_id=$currentStudentId", null)
                    val arr = JSONArray(text)
                    val result = mutableListOf<MatchWithPartner>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val mObj = obj.getJSONObject("match")
                        val pObj = obj.getJSONObject("partner")
                        val matchEntity = MatchEntity(
                            id = mObj.getInt("id"),
                            student_a_id = mObj.getInt("student_a_id"),
                            student_b_id = mObj.getInt("student_b_id"),
                            created_at = mObj.optLong("created_at", System.currentTimeMillis())
                        )
                        val partner = parseStudent(pObj)
                        val lastMsg = if (obj.isNull("last_message")) null else obj.optString("last_message", "")
                        result.add(MatchWithPartner(matchEntity, partner, lastMsg))
                    }
                    result
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

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
        if (isRemoteMode()) {
            return try {
                withContext(Dispatchers.IO) {
                    val text = httpRequest(serverBaseUrl, "GET", "/api/messages?match_id=$matchId", null)
                    val arr = JSONArray(text)
                    val list = mutableListOf<MessageEntity>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            MessageEntity(
                                id = o.optInt("id", 0),
                                match_id = o.optInt("match_id", matchId),
                                sender_id = o.optInt("sender_id", 0),
                                body = o.optString("body", ""),
                                sent_at = o.optLong("sent_at", System.currentTimeMillis())
                            )
                        )
                    }
                    list
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return dao.getMessagesForMatch(matchId)
    }

    suspend fun sendMessage(matchId: Int, senderId: Int, text: String, autoReplyPartnerId: Int? = null) {
        if (isRemoteMode()) {
            withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply {
                        put("match_id", matchId)
                        put("sender_id", senderId)
                        put("body", text.trim())
                    }
                    httpRequest(serverBaseUrl, "POST", "/api/send_message", body.toString())
                } catch (_: Exception) {}
            }
            return
        }

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
        if (isRemoteMode()) {
            return try {
                withContext(Dispatchers.IO) {
                    val body = JSONObject().apply {
                        put("id", student.id)
                        put("full_name", student.full_name)
                        put("age", student.age)
                        put("faculty", student.faculty)
                        put("study_group", student.study_group)
                        put("bio", student.bio)
                        put("interests", student.interests)
                        put("looking_for", student.looking_for)
                        if (student.photo_uri == null) {
                            put("clear_photo", true)
                        } else if (!student.photo_uri.startsWith("http")) {
                            val b64 = encodeLocalImageToBase64(student.photo_uri)
                            if (b64 != null) put("photo_base64", b64)
                        }
                    }
                    val resp = httpRequest(serverBaseUrl, "POST", "/api/profile", body.toString())
                    parseStudent(JSONObject(resp))
                }
            } catch (_: Exception) {
                student
            }
        }

        dao.updateStudent(student)
        return dao.getStudentById(student.id) ?: student
    }

    // ======================= HTTP И JSON ХЕЛПЕРЫ =======================

    private fun httpRequest(baseUrl: String, method: String, path: String, jsonBody: String?): String {
        val url = URL("$baseUrl$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (jsonBody != null) {
                conn.outputStream.use { os ->
                    os.write(jsonBody.toByteArray(Charsets.UTF_8))
                }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val msg = try {
                    JSONObject(responseText).optString("error", "")
                } catch (_: Exception) {
                    ""
                }
                throw Exception(
                    msg.ifEmpty { "Сервер недоступен (HTTP $code). Проверьте, запущен ли сервер и туннель на ПК." }
                )
            }
            return responseText
        } finally {
            conn.disconnect()
        }
    }

    private fun parseStudentList(arr: JSONArray): List<StudentEntity> {
        val list = mutableListOf<StudentEntity>()
        for (i in 0 until arr.length()) {
            list.add(parseStudent(arr.getJSONObject(i)))
        }
        return list
    }

    private fun parseStudent(o: JSONObject): StudentEntity {
        val rawPhoto = if (o.isNull("photo_uri")) null else o.optString("photo_uri", "").takeIf { it.isNotBlank() }
        val resolvedPhoto = when {
            rawPhoto == null -> null
            rawPhoto.startsWith("/api/") && serverBaseUrl.isNotBlank() -> "$serverBaseUrl$rawPhoto"
            else -> rawPhoto
        }
        return StudentEntity(
            id = o.optInt("id", 0),
            email = o.optString("email", ""),
            password_hash = "",
            full_name = o.optString("full_name", ""),
            faculty = o.optString("faculty", "Информационные технологии"),
            study_group = o.optString("study_group", "ИС-21"),
            age = o.optInt("age", 18),
            bio = o.optString("bio", ""),
            interests = o.optString("interests", ""),
            looking_for = o.optString("looking_for", "relationship"),
            photo_uri = resolvedPhoto,
            email_verified = o.optBoolean("email_verified", true),
            likes_received = o.optInt("likes_received", 10),
            created_at = o.optLong("created_at", System.currentTimeMillis())
        )
    }

    private fun encodeLocalImageToBase64(localPath: String?): String? {
        if (localPath.isNullOrBlank() || localPath.startsWith("http")) return null
        return try {
            val file = File(localPath)
            if (!file.exists()) return null
            val original = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val maxDim = 720
            val scale = minOf(maxDim.toFloat() / original.width, maxDim.toFloat() / original.height, 1f)
            val resized = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    original,
                    (original.width * scale).toInt().coerceAtLeast(1),
                    (original.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                original
            }
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 78, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }
}
