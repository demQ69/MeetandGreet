package com.example.meet_and_greet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meet_and_greet.data.*
import com.example.meet_and_greet.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val PREFS_NAME = "flagman_session_prefs"
private const val KEY_LOGGED_STUDENT_ID = "logged_student_id"
private const val KEY_LAST_EMAIL = "last_email"
private const val KEY_SERVER_URL = "server_url"

private val remoteAvatarCache = ConcurrentHashMap<String, Bitmap>()

// --- Копирование выбранного фото из галереи во внутреннее хранилище приложения ---
fun copyUriToInternalStorage(context: Context, uri: Uri): String? {
    return try {
        val dir = File(context.filesDir, "avatars").apply { mkdirs() }
        val destFile = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        destFile.absolutePath
    } catch (e: Exception) {
        null
    }
}

@Composable
fun rememberAvatarBitmap(photoUri: String?): Bitmap? {
    val bmp by produceState<Bitmap?>(initialValue = null, key1 = photoUri) {
        if (photoUri.isNullOrBlank()) {
            value = null
            return@produceState
        }
        if (photoUri.startsWith("http://") || photoUri.startsWith("https://")) {
            val cached = remoteAvatarCache[photoUri]
            if (cached != null) {
                value = cached
                return@produceState
            }
            value = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(photoUri).openConnection().apply {
                        connectTimeout = 8000
                        readTimeout = 10000
                    }
                    conn.getInputStream().use { stream ->
                        BitmapFactory.decodeStream(stream)?.also { decoded ->
                            remoteAvatarCache[photoUri] = decoded
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
        } else {
            value = try {
                val file = File(photoUri)
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            } catch (_: Exception) {
                null
            }
        }
    }
    return bmp
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = AppDatabase.getDatabase(this)
        val repository = AuthRepository(db.authDao())

        setContent {
            MeetandGreetTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = FlagmanBg
                ) {
                    MeetAndGreetApp(repository)
                }
            }
        }
    }
}

@Composable
fun MeetAndGreetApp(repository: AuthRepository) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var currentStudent by remember { mutableStateOf<StudentEntity?>(null) }
    var isRestoringSession by remember { mutableStateOf(true) }
    var lastEmail by remember { mutableStateOf(prefs.getString(KEY_LAST_EMAIL, "") ?: "") }
    var serverUrl by remember { mutableStateOf(prefs.getString(KEY_SERVER_URL, "") ?: "") }

    LaunchedEffect(Unit) {
        repository.updateServerUrl(serverUrl)
        repository.seedTestDataIfNeeded()
        val savedStudentId = prefs.getInt(KEY_LOGGED_STUDENT_ID, -1)
        if (savedStudentId > 0) {
            val existing = repository.getStudentById(savedStudentId)
            if (existing != null) {
                currentStudent = existing
            } else {
                prefs.edit().remove(KEY_LOGGED_STUDENT_ID).apply()
            }
        }
        isRestoringSession = false
    }

    if (isRestoringSession) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FlagmanBg),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = FlagmanPrimary)
        }
        return
    }

    if (currentStudent == null) {
        LoginScreen(
            repository = repository,
            initialEmail = lastEmail,
            initialServerUrl = serverUrl,
            onServerUrlSaved = { newUrl ->
                serverUrl = newUrl
                repository.updateServerUrl(newUrl)
                prefs.edit().putString(KEY_SERVER_URL, repository.serverBaseUrl).apply()
            },
            onLoginSuccess = { student ->
                prefs.edit()
                    .putInt(KEY_LOGGED_STUDENT_ID, student.id)
                    .putString(KEY_LAST_EMAIL, student.email)
                    .apply()
                lastEmail = student.email
                currentStudent = student
            }
        )
    } else {
        MainFlagmanScreen(
            currentStudent = currentStudent!!,
            repository = repository,
            onStudentUpdated = { updated ->
                currentStudent = updated
                prefs.edit().putString(KEY_LAST_EMAIL, updated.email).apply()
            },
            onLogout = {
                prefs.edit().remove(KEY_LOGGED_STUDENT_ID).apply()
                currentStudent = null
            }
        )
    }
}

// ======================= ВЕРХНЯЯ ШАПКА «MEET-AND-GREET» =======================

@Composable
fun FlagmanTopBar(
    isMenuActive: Boolean = false,
    isFilterActive: Boolean = false,
    onMenuClick: () -> Unit,
    onFilterClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FlagmanBg)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Кнопка меню слева (3 полоски в темном кружке)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isMenuActive) FlagmanSurfaceSelected else FlagmanSurface)
                    .border(
                        width = 1.dp,
                        color = if (isMenuActive) FlagmanBorderSelected else FlagmanBorder,
                        shape = CircleShape
                    )
                    .clickable { onMenuClick() },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(18.dp)) {
                    val stroke = 2.dp.toPx()
                    drawLine(
                        color = FlagmanTextPrimary,
                        start = Offset(0f, size.height * 0.2f),
                        end = Offset(size.width, size.height * 0.2f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = FlagmanTextPrimary,
                        start = Offset(0f, size.height * 0.5f),
                        end = Offset(size.width, size.height * 0.5f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = FlagmanTextPrimary,
                        start = Offset(0f, size.height * 0.8f),
                        end = Offset(size.width, size.height * 0.8f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Meet-and-Greet",
                    color = FlagmanTextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 20.sp
                )
                Text(
                    text = "знакомства",
                    color = FlagmanTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 14.sp
                )
            }

            // Кнопка настроек поиска справа (такой же тёмный кружок + иконка шестерёнки)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isFilterActive) FlagmanSurfaceSelected else FlagmanSurface)
                    .border(
                        width = 1.dp,
                        color = if (isFilterActive) FlagmanBorderSelected else FlagmanBorder,
                        shape = CircleShape
                    )
                    .clickable { onFilterClick() },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(20.dp)) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = size.minDimension * 0.46f
                    val ringRadius = radius * 0.58f
                    val gearColor = if (isFilterActive) FlagmanPrimary else FlagmanTextPrimary

                    drawCircle(
                        color = gearColor,
                        radius = ringRadius,
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.1.dp.toPx())
                    )

                    val toothStroke = 2.4.dp.toPx()
                    val innerR = radius * 0.68f
                    val outerR = radius * 0.96f
                    for (i in 0 until 8) {
                        val angle = (i * 45.0) * PI / 180.0
                        val cosA = cos(angle).toFloat()
                        val sinA = sin(angle).toFloat()
                        drawLine(
                            color = gearColor,
                            start = Offset(cx + innerR * cosA, cy + innerR * sinA),
                            end = Offset(cx + outerR * cosA, cy + outerR * sinA),
                            strokeWidth = toothStroke,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = Color(0xFF171B2A), thickness = 1.dp)
    }
}

// ======================= НИЖНЯЯ ПАНЕЛЬ НАВИГАЦИИ (5 ВКЛАДОК С МАКЕТА) =======================

@Composable
fun FlagmanBottomBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit
) {
    val items = listOf("Поиск", "Вы", "Вам", "Взаимно", "Рейтинг")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FlagmanBg)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(color = Color(0xFF171B2A), thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, label ->
                val isSelected = selectedTab == index
                val contentColor = if (isSelected) FlagmanAccentBlue else Color(0xFF8B92AC)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isSelected) Color(0xFF1A223B) else Color.Transparent)
                        .clickable { onSelectTab(index) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BottomNavIcon(index = index, color = contentColor)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = label,
                        color = contentColor,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun BottomNavIcon(index: Int, color: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val stroke = 1.9.dp.toPx()
        when (index) {
            0 -> {
                // Лупа «Поиск»
                val r = size.minDimension * 0.34f
                val center = Offset(size.width * 0.43f, size.height * 0.43f)
                drawCircle(color = color, radius = r, center = center, style = Stroke(width = stroke))
                drawLine(
                    color = color,
                    start = Offset(center.x + r * 0.72f, center.y + r * 0.72f),
                    end = Offset(size.width * 0.88f, size.height * 0.88f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
            1, 2, 3 -> {
                // Контур сердца для «Вы», «Вам», «Взаимно»
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.84f)
                    cubicTo(w * 0.12f, h * 0.58f, w * 0.08f, h * 0.24f, w * 0.30f, h * 0.18f)
                    cubicTo(w * 0.42f, h * 0.15f, w * 0.5f, h * 0.26f, w * 0.5f, h * 0.32f)
                    cubicTo(w * 0.5f, h * 0.26f, w * 0.58f, h * 0.15f, w * 0.70f, h * 0.18f)
                    cubicTo(w * 0.92f, h * 0.24f, w * 0.88f, h * 0.58f, w * 0.5f, h * 0.84f)
                    close()
                }
                drawPath(path = path, color = color, style = Stroke(width = stroke, join = StrokeJoin.Round))

                val subStroke = 1.5.dp.toPx()
                if (index == 1) {
                    val cy = h * 0.48f
                    drawLine(color, Offset(w * 0.36f, cy), Offset(w * 0.62f, cy), subStroke, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.53f, cy - h * 0.09f), Offset(w * 0.63f, cy), subStroke, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.53f, cy + h * 0.09f), Offset(w * 0.63f, cy), subStroke, StrokeCap.Round)
                } else if (index == 2) {
                    val cy = h * 0.48f
                    drawLine(color, Offset(w * 0.64f, cy), Offset(w * 0.38f, cy), subStroke, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.47f, cy - h * 0.09f), Offset(w * 0.37f, cy), subStroke, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.47f, cy + h * 0.09f), Offset(w * 0.37f, cy), subStroke, StrokeCap.Round)
                } else {
                    drawLine(color, Offset(w * 0.37f, h * 0.49f), Offset(w * 0.47f, h * 0.58f), subStroke, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.47f, h * 0.58f), Offset(w * 0.64f, h * 0.40f), subStroke, StrokeCap.Round)
                }
            }
            4 -> {
                val w = size.width
                val h = size.height
                val barStroke = 2.2.dp.toPx()
                drawLine(color, Offset(w * 0.25f, h * 0.82f), Offset(w * 0.25f, h * 0.50f), barStroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.50f, h * 0.82f), Offset(w * 0.50f, h * 0.22f), barStroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.75f, h * 0.82f), Offset(w * 0.75f, h * 0.38f), barStroke, StrokeCap.Round)
            }
        }
    }
}

// ======================= ГЛАВНЫЙ КОНТЕЙНЕР ЭКРАНОВ =======================

@Composable
fun MainFlagmanScreen(
    currentStudent: StudentEntity,
    repository: AuthRepository,
    onStudentUpdated: (StudentEntity) -> Unit,
    onLogout: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSearchSettings by remember { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    var activeChatMatch by remember { mutableStateOf<MatchWithPartner?>(null) }

    // Состояние фильтров «Настройки поиска»
    var selectedGoal by remember(currentStudent.id) {
        mutableStateOf(currentStudent.looking_for.ifBlank { "relationship" })
    }
    var selectedInterests by remember(currentStudent.id) {
        val parsed = currentStudent.interests.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        mutableStateOf(if (parsed.isEmpty()) setOf("Рисование", "Чтение") else parsed)
    }
    var strictFilterMode by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = FlagmanBg,
        topBar = {
            FlagmanTopBar(
                isMenuActive = showProfileSheet,
                isFilterActive = showSearchSettings,
                onMenuClick = {
                    activeChatMatch = null
                    showSearchSettings = false
                    showProfileSheet = !showProfileSheet
                },
                onFilterClick = {
                    activeChatMatch = null
                    showProfileSheet = false
                    showSearchSettings = !showSearchSettings
                }
            )
        },
        bottomBar = {
            FlagmanBottomBar(
                selectedTab = selectedTab,
                onSelectTab = { index ->
                    selectedTab = index
                    showSearchSettings = false
                    showProfileSheet = false
                    activeChatMatch = null
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FlagmanBg)
                .padding(innerPadding)
        ) {
            when {
                activeChatMatch != null -> {
                    ChatScreen(
                        currentStudent = currentStudent,
                        matchWithPartner = activeChatMatch!!,
                        repository = repository,
                        onBack = { activeChatMatch = null }
                    )
                }
                showProfileSheet -> {
                    ProfileScreen(
                        currentStudent = currentStudent,
                        repository = repository,
                        onStudentUpdated = onStudentUpdated,
                        onClose = { showProfileSheet = false },
                        onLogout = onLogout
                    )
                }
                showSearchSettings -> {
                    SearchSettingsScreen(
                        selectedGoal = selectedGoal,
                        onSelectGoal = { selectedGoal = it },
                        selectedInterests = selectedInterests,
                        onToggleInterest = { interest ->
                            selectedInterests = if (selectedInterests.contains(interest)) {
                                selectedInterests - interest
                            } else {
                                selectedInterests + interest
                            }
                        },
                        strictFilterMode = strictFilterMode,
                        onToggleStrictFilter = { strictFilterMode = it },
                        onApplyClick = {
                            showSearchSettings = false
                            selectedTab = 0
                        }
                    )
                }
                else -> {
                    when (selectedTab) {
                        0 -> DiscoverScreen(
                            currentStudent = currentStudent,
                            repository = repository,
                            selectedGoal = selectedGoal,
                            selectedInterests = selectedInterests,
                            strictFilterMode = strictFilterMode,
                            onOpenMatches = { selectedTab = 3 }
                        )
                        1 -> OutgoingLikesScreen(
                            currentStudent = currentStudent,
                            repository = repository
                        )
                        2 -> IncomingLikesScreen(
                            currentStudent = currentStudent,
                            repository = repository,
                            onOpenMatches = { selectedTab = 3 }
                        )
                        3 -> MatchesScreen(
                            currentStudent = currentStudent,
                            repository = repository,
                            onOpenChat = { matchItem -> activeChatMatch = matchItem }
                        )
                        4 -> RatingScreen(
                            currentStudent = currentStudent,
                            repository = repository,
                            onOpenProfile = { showProfileSheet = true }
                        )
                    }
                }
            }
        }
    }
}

// ======================= ЭКРАН 1 С МАКЕТА: «ПОИСК» (КАРТОЧКА АНКЕТЫ + ЖЕСТ СВАЙПА + ЖАЛОБА) =======================

@Composable
fun DiscoverScreen(
    currentStudent: StudentEntity,
    repository: AuthRepository,
    selectedGoal: String,
    selectedInterests: Set<String>,
    strictFilterMode: Boolean,
    onOpenMatches: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf<List<StudentEntity>>(emptyList()) }
    var matchedPartnerDialog by remember { mutableStateOf<StudentEntity?>(null) }
    var reportTargetStudent by remember { mutableStateOf<StudentEntity?>(null) }
    var currentSlide by remember { mutableIntStateOf(0) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }

    fun loadCandidates() {
        scope.launch {
            val all = repository.getCandidates(currentStudent.id)
            val filtered = if (strictFilterMode) {
                all.filter { candidate ->
                    val candidateInterests = candidate.interests.split(",").map { it.trim() }
                    val hasCommonInterest = selectedInterests.isEmpty() || candidateInterests.any { it in selectedInterests }
                    candidate.looking_for == selectedGoal && hasCommonInterest
                }
            } else {
                all
            }
            candidates = filtered.sortedByDescending { candidate ->
                val candidateInterests = candidate.interests.split(",").map { it.trim() }
                val overlap = candidateInterests.count { it in selectedInterests }
                val goalBonus = if (candidate.looking_for == selectedGoal) 2 else 0
                val alexBonus = if (candidate.email == "alex@itmoscow.pro") 100 else 0
                alexBonus + overlap + goalBonus
            }
            currentSlide = 0
            dragOffsetX = 0f
        }
    }

    LaunchedEffect(currentStudent.id, selectedGoal, selectedInterests, strictFilterMode) {
        loadCandidates()
    }

    val currentCandidate = candidates.firstOrNull()

    fun handleSwipe(target: StudentEntity, isLike: Boolean) {
        scope.launch {
            val matched = repository.swipeStudent(
                currentStudentId = currentStudent.id,
                targetStudentId = target.id,
                isLike = isLike
            )
            if (matched != null) {
                matchedPartnerDialog = matched
            }
            loadCandidates()
        }
    }

    // Диалог «Взаимная симпатия!»
    if (matchedPartnerDialog != null) {
        AlertDialog(
            onDismissRequest = { matchedPartnerDialog = null },
            containerColor = FlagmanSurface,
            titleContentColor = FlagmanTextPrimary,
            textContentColor = FlagmanTextSecondary,
            title = { Text("Взаимная симпатия!", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Вы и ${matchedPartnerDialog!!.full_name}, ${matchedPartnerDialog!!.age} " +
                        "понравились друг другу! Перейдите во вкладку «Взаимно», чтобы написать сообщение."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        matchedPartnerDialog = null
                        onOpenMatches()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FlagmanPrimary,
                        contentColor = FlagmanOnPrimary
                    )
                ) {
                    Text("Открыть чат", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { matchedPartnerDialog = null }) {
                    Text("Дальше", color = FlagmanPrimary)
                }
            }
        )
    }

    // Диалог «Пожаловаться / Заблокировать» (Модерация MVP)
    if (reportTargetStudent != null) {
        ReportDialog(
            targetStudent = reportTargetStudent!!,
            onDismiss = { reportTargetStudent = null },
            onConfirmReport = { reason ->
                val target = reportTargetStudent!!
                reportTargetStudent = null
                scope.launch {
                    repository.reportAndBlockStudent(
                        reporterId = currentStudent.id,
                        reportedStudentId = target.id,
                        reason = reason
                    )
                    Toast.makeText(
                        context,
                        "Жалоба отправлена модераторам @itmoscow.pro. Анкета скрыта.",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadCandidates()
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = "Поиск",
            color = FlagmanTextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Новые знакомства начинаются здесь",
            color = FlagmanTextSecondary,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (currentCandidate == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(FlagmanSurface)
                    .border(1.dp, FlagmanBorder, RoundedCornerShape(24.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (strictFilterMode) "Нет анкет по выбранному строгому фильтру"
                        else "Вы просмотрели все анкеты",
                        color = FlagmanTextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Нажмите кнопку ниже, чтобы посмотреть анкеты заново",
                        color = FlagmanTextSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                repository.resetSwipes(currentStudent.id)
                                loadCandidates()
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FlagmanPrimary,
                            contentColor = FlagmanOnPrimary
                        )
                    ) {
                        Text("Показать анкеты заново", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            val customBitmap = rememberAvatarBitmap(currentCandidate.photo_uri)

            // Главная фото-карточка кандидата с поддержкой ЖЕСТА СВАЙПА влево/вправо и переключения слайдов
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .offset { IntOffset(dragOffsetX.roundToInt(), 0) }
                    .graphicsLayer {
                        rotationZ = (dragOffsetX / 40f).coerceIn(-12f, 12f)
                    }
                    .clip(RoundedCornerShape(24.dp))
                    .background(FlagmanSurface)
                    .pointerInput(currentCandidate.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                when {
                                    dragOffsetX > 220f -> {
                                        dragOffsetX = 0f
                                        handleSwipe(currentCandidate, isLike = true)
                                    }
                                    dragOffsetX < -220f -> {
                                        dragOffsetX = 0f
                                        handleSwipe(currentCandidate, isLike = false)
                                    }
                                    else -> {
                                        dragOffsetX = 0f
                                    }
                                }
                            },
                            onDragCancel = { dragOffsetX = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount
                            }
                        )
                    }
                    .clickable { currentSlide = (currentSlide + 1) % 3 }
            ) {
                when {
                    customBitmap != null -> {
                        Image(
                            bitmap = customBitmap.asImageBitmap(),
                            contentDescription = currentCandidate.full_name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    currentCandidate.email == "alex@itmoscow.pro" -> {
                        Image(
                            painter = painterResource(id = R.drawable.avatar_alexander),
                            contentDescription = currentCandidate.full_name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        Image(
                            painter = painterResource(id = R.drawable.avatar_alexander),
                            contentDescription = currentCandidate.full_name,
                            contentScale = ContentScale.Crop,
                            alpha = 0.25f,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0xAA1F294A),
                                            Color(0xCC151B30),
                                            Color(0xF50E111D)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(CircleShape)
                                    .background(FlagmanSurfaceSelected)
                                    .border(2.dp, FlagmanBorderSelected, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = currentCandidate.full_name.take(1).uppercase(),
                                    color = FlagmanPrimary,
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Нижний темный градиент поверх фото для читаемости имени и подписи
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(185.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xB80E121E),
                                    Color(0xF20C0F19)
                                )
                            )
                        )
                )

                // Верхние индикаторы историй (3 полоски как на макете)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (slideIdx in 0..2) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (slideIdx == currentSlide) Color.White
                                    else Color.White.copy(alpha = 0.38f)
                                )
                        )
                    }
                }

                // Кнопка модерации «Пожаловаться / Скрыть» в правом верхнем углу карточки
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 24.dp, end = 16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x880C0E17))
                        .clickable { reportTargetStudent = currentCandidate }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Пожаловаться",
                        color = Color(0xFFD5D9EB),
                        fontSize = 11.sp
                    )
                }

                // Визуальный бейдж при свайпе пальцем вправо/влево
                if (dragOffsetX > 80f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 34.dp, start = 20.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(FlagmanPrimary)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("✓ НРАВИТСЯ", color = FlagmanOnPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                } else if (dragOffsetX < -80f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 56.dp, end = 20.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(FlagmanSurfaceSelected)
                            .border(1.dp, FlagmanBorderSelected, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("✕ ПРОПУСТИТЬ", color = FlagmanTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                // Имя, возраст и описание в левом нижнем углу карточки
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 22.dp, vertical = 22.dp)
                ) {
                    Text(
                        text = "${currentCandidate.full_name}, ${currentCandidate.age}",
                        color = FlagmanTextPrimary,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val subtitleText = when (currentSlide) {
                        0 -> currentCandidate.bio
                        1 -> "Интересы: ${currentCandidate.interests}"
                        else -> "${currentCandidate.faculty} • Группа ${currentCandidate.study_group}"
                    }
                    Text(
                        text = subtitleText,
                        color = Color(0xFFD2D8EE),
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Круглые кнопки «Пропустить» (✕) и «Нравится» (✓) как на макете
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Кнопка «Пропустить»
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(FlagmanSurface)
                            .border(1.dp, FlagmanBorder, CircleShape)
                            .clickable {
                                handleSwipe(currentCandidate, isLike = false)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(22.dp)) {
                            val stroke = 2.2.dp.toPx()
                            drawLine(
                                color = Color(0xFFD5DBEE),
                                start = Offset(size.width * 0.15f, size.height * 0.15f),
                                end = Offset(size.width * 0.85f, size.height * 0.85f),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = Color(0xFFD5DBEE),
                                start = Offset(size.width * 0.85f, size.height * 0.15f),
                                end = Offset(size.width * 0.15f, size.height * 0.85f),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Пропустить",
                        color = FlagmanTextSecondary,
                        fontSize = 13.sp
                    )
                }

                // Кнопка «Нравится»
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(FlagmanPrimary)
                            .clickable {
                                handleSwipe(currentCandidate, isLike = true)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(24.dp)) {
                            val stroke = 2.5.dp.toPx()
                            drawLine(
                                color = FlagmanOnPrimary,
                                start = Offset(size.width * 0.16f, size.height * 0.54f),
                                end = Offset(size.width * 0.42f, size.height * 0.78f),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = FlagmanOnPrimary,
                                start = Offset(size.width * 0.42f, size.height * 0.78f),
                                end = Offset(size.width * 0.85f, size.height * 0.24f),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Нравится",
                        color = Color(0xFF9EA6C4),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ReportDialog(
    targetStudent: StudentEntity,
    onDismiss: () -> Unit,
    onConfirmReport: (String) -> Unit
) {
    val reasons = listOf(
        "Фейковая анкета / чужое фото",
        "Спам или реклама",
        "Некорректное описание или оскорбления",
        "Скрыть анкету из поиска"
    )
    var selectedReason by remember { mutableStateOf(reasons.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FlagmanSurface,
        titleContentColor = FlagmanTextPrimary,
        textContentColor = FlagmanTextSecondary,
        title = {
            Text(
                text = "Жалоба на анкету (${targetStudent.full_name})",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Выберите причину обращения к модераторам @itmoscow.pro:")
                Spacer(modifier = Modifier.height(4.dp))
                reasons.forEach { reason ->
                    val isSel = selectedReason == reason
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) FlagmanSurfaceSelected else FlagmanBg)
                            .border(
                                width = 1.dp,
                                color = if (isSel) FlagmanBorderSelected else FlagmanBorder,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedReason = reason }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (isSel) "✓ $reason" else reason,
                            color = if (isSel) FlagmanTextPrimary else FlagmanTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmReport(selectedReason) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE57373),
                    contentColor = Color.White
                )
            ) {
                Text("Заблокировать", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = FlagmanPrimary)
            }
        }
    )
}

// ======================= ЭКРАН 2 С МАКЕТА: «НАСТРОЙКИ ПОИСКА» =======================

@Composable
fun SearchSettingsScreen(
    selectedGoal: String,
    onSelectGoal: (String) -> Unit,
    selectedInterests: Set<String>,
    onToggleInterest: (String) -> Unit,
    strictFilterMode: Boolean,
    onToggleStrictFilter: (Boolean) -> Unit,
    onApplyClick: () -> Unit
) {
    val allInterests = listOf(
        listOf("Рисование", "Чтение", "Музыка"),
        listOf("Кино", "Путешествия", "Спорт"),
        listOf("Игры", "Кулинария", "Фотография")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = "Настройки поиска",
            color = FlagmanTextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Найди тех, с кем тебе по пути",
            color = FlagmanTextSecondary,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = "Кого ты ищешь?",
            color = FlagmanTextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Две карточки выбора: «Вторую половинку» и «Друзей»
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GoalSelectionCard(
                modifier = Modifier.weight(1f),
                title = "Вторую половинку",
                subtitle = "Для тёплых отношений",
                isHeartIcon = true,
                isSelected = selectedGoal == "relationship",
                onClick = { onSelectGoal("relationship") }
            )
            GoalSelectionCard(
                modifier = Modifier.weight(1f),
                title = "Друзей",
                subtitle = "Для общения и встреч",
                isHeartIcon = false,
                isSelected = selectedGoal == "friends",
                onClick = { onSelectGoal("friends") }
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        // Заголовок «Общие интересы» + плашка «2 выбрано»
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = "Общие интересы",
                    color = FlagmanTextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Выбирай то, что нравится тебе",
                    color = FlagmanTextSecondary,
                    fontSize = 13.sp
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF1A2035))
                    .border(1.dp, Color(0xFF283152), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "${selectedInterests.size} выбрано",
                    color = FlagmanAccentBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Сетка 3x3 интересов (Рисование, Чтение, Музыка, Кино, Путешествия, Спорт, Игры, Кулинария, Фотография)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            allInterests.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { interest ->
                        val isSelected = selectedInterests.contains(interest)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) FlagmanSurfaceSelected else FlagmanSurface)
                                .border(
                                    width = if (isSelected) 1.4.dp else 1.dp,
                                    color = if (isSelected) FlagmanBorderSelected else FlagmanBorder,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onToggleInterest(interest) }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isSelected) "✓ $interest" else interest,
                                color = if (isSelected) Color(0xFFDCE3FF) else Color(0xFF939BB5),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Можно выбрать несколько интересов",
            color = Color(0xFF6B728E),
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Опция строгого фильтра (MVP)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(FlagmanSurface)
                .border(1.dp, FlagmanBorder, RoundedCornerShape(14.dp))
                .clickable { onToggleStrictFilter(!strictFilterMode) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Строгий фильтр по интересам",
                    color = FlagmanTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Показывать только анкеты с точным совпадением цели и хотя бы 1 интереса",
                    color = FlagmanTextSecondary,
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = strictFilterMode,
                onCheckedChange = onToggleStrictFilter,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = FlagmanOnPrimary,
                    checkedTrackColor = FlagmanPrimary
                )
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(20.dp))

        // Широкая кнопка «Показать анкеты»
        Button(
            onClick = onApplyClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = FlagmanPrimary,
                contentColor = FlagmanOnPrimary
            )
        ) {
            Text(
                text = "Показать анкеты",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun GoalSelectionCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    isHeartIcon: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .height(126.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (isSelected) FlagmanSurfaceSelected else FlagmanSurface)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) FlagmanBorderSelected else FlagmanBorder,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable { onClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(modifier = Modifier.size(22.dp)) {
                val stroke = 1.7.dp.toPx()
                val iconColor = if (isSelected) Color(0xFFD0D8F8) else Color(0xFF939BB5)
                if (isHeartIcon) {
                    val w = size.width
                    val h = size.height
                    val path = Path().apply {
                        moveTo(w * 0.5f, h * 0.84f)
                        cubicTo(w * 0.12f, h * 0.58f, w * 0.08f, h * 0.24f, w * 0.30f, h * 0.18f)
                        cubicTo(w * 0.42f, h * 0.15f, w * 0.5f, h * 0.26f, w * 0.5f, h * 0.32f)
                        cubicTo(w * 0.5f, h * 0.26f, w * 0.58f, h * 0.15f, w * 0.70f, h * 0.18f)
                        cubicTo(w * 0.92f, h * 0.24f, w * 0.88f, h * 0.58f, w * 0.5f, h * 0.84f)
                        close()
                    }
                    drawPath(path, iconColor, style = Stroke(width = stroke, join = StrokeJoin.Round))
                } else {
                    val w = size.width
                    val h = size.height
                    drawCircle(iconColor, radius = w * 0.15f, center = Offset(w * 0.36f, h * 0.32f), style = Stroke(stroke))
                    drawArc(
                        color = iconColor,
                        startAngle = 190f,
                        sweepAngle = 160f,
                        useCenter = false,
                        topLeft = Offset(w * 0.12f, h * 0.54f),
                        size = androidx.compose.ui.geometry.Size(w * 0.48f, h * 0.38f),
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                    drawCircle(iconColor, radius = w * 0.13f, center = Offset(w * 0.68f, h * 0.36f), style = Stroke(stroke))
                    drawArc(
                        color = iconColor,
                        startAngle = 200f,
                        sweepAngle = 150f,
                        useCenter = false,
                        topLeft = Offset(w * 0.48f, h * 0.56f),
                        size = androidx.compose.ui.geometry.Size(w * 0.42f, h * 0.34f),
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(FlagmanPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(11.dp)) {
                        val s = 1.8.dp.toPx()
                        drawLine(FlagmanOnPrimary, Offset(size.width * 0.15f, size.height * 0.55f), Offset(size.width * 0.42f, size.height * 0.8f), s, StrokeCap.Round)
                        drawLine(FlagmanOnPrimary, Offset(size.width * 0.42f, size.height * 0.8f), Offset(size.width * 0.85f, size.height * 0.22f), s, StrokeCap.Round)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .border(1.4.dp, Color(0xFF49516D), CircleShape)
                )
            }
        }

        Column {
            Text(
                text = title,
                color = FlagmanTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = FlagmanTextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

// ======================= ВКЛАДКА «ВЫ» (КОМУ ВЫ ПОСТАВИЛИ ЛАЙК + ОТМЕНА ЛАЙКА) =======================

@Composable
fun OutgoingLikesScreen(
    currentStudent: StudentEntity,
    repository: AuthRepository
) {
    val scope = rememberCoroutineScope()
    var likedStudents by remember { mutableStateOf<List<StudentEntity>>(emptyList()) }

    fun loadLiked() {
        scope.launch {
            likedStudents = repository.getOutgoingLikes(currentStudent.id)
        }
    }

    LaunchedEffect(currentStudent.id) {
        loadLiked()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
    ) {
        Text(
            text = "Вы оценили",
            color = FlagmanTextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Анкеты, которым вы поставили «Нравится» (можно отозвать лайк)",
            color = FlagmanTextSecondary,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (likedStudents.isEmpty()) {
            EmptyStateCard("Вы пока никому не поставили «Нравится» во вкладке «Поиск».")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(likedStudents) { student ->
                    StudentListCard(
                        student = student,
                        badgeText = "Отозвать",
                        onActionClick = {
                            scope.launch {
                                repository.revokeLike(currentStudent.id, student.id)
                                loadLiked()
                            }
                        }
                    )
                }
            }
        }
    }
}

// ======================= ВКЛАДКА «ВАМ» (КТО ЛАЙКНУЛ ВАС) =======================

@Composable
fun IncomingLikesScreen(
    currentStudent: StudentEntity,
    repository: AuthRepository,
    onOpenMatches: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var incomingStudents by remember { mutableStateOf<List<StudentEntity>>(emptyList()) }

    fun loadIncoming() {
        scope.launch {
            incomingStudents = repository.getIncomingLikes(currentStudent.id)
        }
    }

    LaunchedEffect(currentStudent.id) {
        loadIncoming()
        if (repository.isRemoteMode()) {
            while (true) {
                delay(5000)
                incomingStudents = repository.getIncomingLikes(currentStudent.id)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
    ) {
        Text(
            text = "Кому вы нравитесь",
            color = FlagmanTextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Эти участники уже проявили к вам симпатию",
            color = FlagmanTextSecondary,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (incomingStudents.isEmpty()) {
            EmptyStateCard("Пока нет новых входящих симпатий.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(incomingStudents) { student ->
                    StudentListCard(
                        student = student,
                        badgeText = "Ответить взаимностью",
                        onActionClick = {
                            scope.launch {
                                repository.swipeStudent(
                                    currentStudentId = currentStudent.id,
                                    targetStudentId = student.id,
                                    isLike = true
                                )
                                onOpenMatches()
                            }
                        }
                    )
                }
            }
        }
    }
}

// ======================= ВКЛАДКА «ВЗАИМНО» (МЭТЧИ И ЧАТ) =======================

@Composable
fun MatchesScreen(
    currentStudent: StudentEntity,
    repository: AuthRepository,
    onOpenChat: (MatchWithPartner) -> Unit
) {
    val scope = rememberCoroutineScope()
    var matches by remember { mutableStateOf<List<MatchWithPartner>>(emptyList()) }

    fun loadMatches() {
        scope.launch {
            matches = repository.getMatchesWithPartners(currentStudent.id)
        }
    }

    LaunchedEffect(currentStudent.id) {
        loadMatches()
        if (repository.isRemoteMode()) {
            while (true) {
                delay(5000)
                matches = repository.getMatchesWithPartners(currentStudent.id)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
    ) {
        Text(
            text = "Взаимные симпатии",
            color = FlagmanTextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Нажмите на анкету, чтобы открыть диалог",
            color = FlagmanTextSecondary,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (matches.isEmpty()) {
            EmptyStateCard("Пока нет взаимных симпатий. Поставьте «Нравится» анкете во вкладке «Поиск»!")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(matches) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(FlagmanSurface)
                            .border(1.dp, FlagmanBorder, RoundedCornerShape(18.dp))
                            .clickable { onOpenChat(item) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarCircle(student = item.partner, sizeDp = 50)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${item.partner.full_name}, ${item.partner.age}",
                                color = FlagmanTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = item.lastMessage ?: "Начать переписку...",
                                color = FlagmanTextSecondary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(FlagmanPrimary)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Чат",
                                color = FlagmanOnPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    currentStudent: StudentEntity,
    matchWithPartner: MatchWithPartner,
    repository: AuthRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var showReportDialog by remember { mutableStateOf(false) }

    fun loadMessages() {
        scope.launch {
            messages = repository.getMessages(matchWithPartner.match.id)
        }
    }

    LaunchedEffect(matchWithPartner.match.id) {
        loadMessages()
        if (repository.isRemoteMode()) {
            while (true) {
                delay(2500)
                messages = repository.getMessages(matchWithPartner.match.id)
            }
        }
    }

    if (showReportDialog) {
        ReportDialog(
            targetStudent = matchWithPartner.partner,
            onDismiss = { showReportDialog = false },
            onConfirmReport = { reason ->
                showReportDialog = false
                scope.launch {
                    repository.reportAndBlockStudent(
                        reporterId = currentStudent.id,
                        reportedStudentId = matchWithPartner.partner.id,
                        reason = reason
                    )
                    Toast.makeText(context, "Пользователь заблокирован, чат удален", Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                AvatarCircle(student = matchWithPartner.partner, sizeDp = 42)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "${matchWithPartner.partner.full_name}, ${matchWithPartner.partner.age}",
                        color = FlagmanTextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${matchWithPartner.partner.faculty} • ${matchWithPartner.partner.study_group}",
                        color = FlagmanTextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.unmatch(
                                matchId = matchWithPartner.match.id,
                                currentStudentId = currentStudent.id,
                                partnerId = matchWithPartner.partner.id
                            )
                            Toast.makeText(context, "Мэтч отменен", Toast.LENGTH_SHORT).show()
                            onBack()
                        }
                    }
                ) {
                    Text("Отменить мэтч", color = Color(0xFFFF8A80), fontSize = 12.sp)
                }
                TextButton(onClick = onBack) {
                    Text("← Назад", color = FlagmanPrimary, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                val isMe = msg.sender_id == currentStudent.id
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isMe) FlagmanPrimary else FlagmanSurface)
                            .border(
                                width = 1.dp,
                                color = if (isMe) FlagmanPrimary else FlagmanBorder,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = msg.body,
                            color = if (isMe) FlagmanOnPrimary else FlagmanTextPrimary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Сообщение...", color = FlagmanTextSecondary) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = FlagmanSurface,
                    unfocusedContainerColor = FlagmanSurface,
                    focusedBorderColor = FlagmanBorderSelected,
                    unfocusedBorderColor = FlagmanBorder,
                    focusedTextColor = FlagmanTextPrimary,
                    unfocusedTextColor = FlagmanTextPrimary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        val txt = inputText
                        inputText = ""
                        scope.launch {
                            repository.sendMessage(
                                matchId = matchWithPartner.match.id,
                                senderId = currentStudent.id,
                                text = txt,
                                autoReplyPartnerId = matchWithPartner.partner.id
                            )
                            loadMessages()
                        }
                    }
                },
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FlagmanPrimary,
                    contentColor = FlagmanOnPrimary
                )
            ) {
                Text("Отпр.", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ======================= ВКЛАДКА «РЕЙТИНГ» =======================

@Composable
fun RatingScreen(
    currentStudent: StudentEntity,
    repository: AuthRepository,
    onOpenProfile: () -> Unit
) {
    var topStudents by remember { mutableStateOf<List<StudentEntity>>(emptyList()) }

    LaunchedEffect(currentStudent.id) {
        topStudents = repository.getRatingList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Рейтинг",
                    color = FlagmanTextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Самые популярные анкеты колледжа",
                    color = FlagmanTextSecondary,
                    fontSize = 14.sp
                )
            }

            OutlinedButton(
                onClick = onOpenProfile,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, FlagmanBorderSelected)
            ) {
                Text("Мой профиль", color = FlagmanPrimary, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(topStudents) { idx, student ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (student.id == currentStudent.id) FlagmanSurfaceSelected else FlagmanSurface)
                        .border(
                            width = 1.dp,
                            color = if (student.id == currentStudent.id) FlagmanBorderSelected else FlagmanBorder,
                            shape = RoundedCornerShape(18.dp)
                        )
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF232A45)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "#${idx + 1}",
                            color = FlagmanPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    AvatarCircle(student = student, sizeDp = 42)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${student.full_name}, ${student.age}" +
                                (if (student.id == currentStudent.id) " (Вы)" else ""),
                            color = FlagmanTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = student.interests,
                            color = FlagmanTextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "♥ ${student.likes_received}",
                        color = FlagmanAccentBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

// ======================= АВАТАР И ВСПОМОГАТЕЛЬНЫЕ КАРТОЧКИ =======================

@Composable
fun AvatarCircle(
    student: StudentEntity,
    sizeDp: Int = 50
) {
    val bmp = rememberAvatarBitmap(student.photo_uri)
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(FlagmanSurfaceSelected)
            .border(1.dp, FlagmanBorderSelected, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        when {
            bmp != null -> {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = student.full_name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            student.email == "alex@itmoscow.pro" -> {
                Image(
                    painter = painterResource(id = R.drawable.avatar_alexander),
                    contentDescription = student.full_name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                Text(
                    text = student.full_name.take(1).uppercase(),
                    color = FlagmanPrimary,
                    fontSize = (sizeDp * 0.4f).sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun StudentListCard(
    student: StudentEntity,
    badgeText: String,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(FlagmanSurface)
            .border(1.dp, FlagmanBorder, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarCircle(student = student, sizeDp = 50)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${student.full_name}, ${student.age}",
                color = FlagmanTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = student.bio,
                color = FlagmanTextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (onActionClick != null) {
            Button(
                onClick = onActionClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FlagmanPrimary,
                    contentColor = FlagmanOnPrimary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(badgeText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(FlagmanSurfaceSelected)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(badgeText, color = FlagmanAccentBlue, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun EmptyStateCard(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(FlagmanSurface)
            .border(1.dp, FlagmanBorder, RoundedCornerShape(20.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = FlagmanTextSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ======================= ПРОФИЛЬ С ЗАГРУЗКОЙ ФОТО ИЗ ГАЛЕРЕИ И УДАЛЕНИЕМ АККАУНТА =======================

@Composable
fun ProfileScreen(
    currentStudent: StudentEntity,
    repository: AuthRepository,
    onStudentUpdated: (StudentEntity) -> Unit,
    onClose: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allInterests = listOf(
        listOf("Рисование", "Чтение", "Музыка"),
        listOf("Кино", "Путешествия", "Спорт"),
        listOf("Игры", "Кулинария", "Фотография")
    )

    var fullName by remember(currentStudent) { mutableStateOf(currentStudent.full_name) }
    var ageText by remember(currentStudent) { mutableStateOf(currentStudent.age.toString()) }
    var faculty by remember(currentStudent) { mutableStateOf(currentStudent.faculty) }
    var studyGroup by remember(currentStudent) { mutableStateOf(currentStudent.study_group) }
    var bio by remember(currentStudent) { mutableStateOf(currentStudent.bio) }
    var lookingFor by remember(currentStudent) { mutableStateOf(currentStudent.looking_for) }
    var photoUri by remember(currentStudent) { mutableStateOf(currentStudent.photo_uri) }
    var selectedInterests by remember(currentStudent) {
        val set = currentStudent.interests.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        mutableStateOf(if (set.isEmpty()) setOf("Рисование", "Чтение") else set)
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val savedPath = copyUriToInternalStorage(context, uri)
            if (savedPath != null) {
                photoUri = savedPath
                Toast.makeText(context, "Фото выбрано из галереи!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val previewBitmap = rememberAvatarBitmap(photoUri)

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = FlagmanSurface,
            titleContentColor = FlagmanTextPrimary,
            textContentColor = FlagmanTextSecondary,
            title = { Text("Удалить аккаунт?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Ваша анкета (${currentStudent.email}), лайки и чаты будут безвозвратно удалены из базы данных.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        scope.launch {
                            repository.deleteAccount(currentStudent.id)
                            Toast.makeText(context, "Аккаунт удален", Toast.LENGTH_SHORT).show()
                            onLogout()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE57373),
                        contentColor = Color.White
                    )
                ) {
                    Text("Удалить навсегда", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Отмена", color = FlagmanPrimary)
                }
            }
        )
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = FlagmanSurface,
        unfocusedContainerColor = FlagmanSurface,
        focusedBorderColor = FlagmanBorderSelected,
        unfocusedBorderColor = FlagmanBorder,
        focusedTextColor = FlagmanTextPrimary,
        unfocusedTextColor = FlagmanTextPrimary,
        focusedLabelColor = FlagmanPrimary,
        unfocusedLabelColor = FlagmanTextSecondary
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Моя анкета",
                    color = FlagmanTextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${currentStudent.email} • Верифицирован ✓",
                    color = FlagmanAccentBlue,
                    fontSize = 12.sp
                )
            }
            TextButton(onClick = onClose) {
                Text("Закрыть", color = FlagmanPrimary)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Блок выбора фотографии из галереи телефона
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(FlagmanSurface)
                .border(1.dp, FlagmanBorder, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(FlagmanSurfaceSelected)
                    .border(1.5.dp, FlagmanBorderSelected, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = fullName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = fullName.take(1).uppercase(),
                        color = FlagmanPrimary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Фотография анкеты",
                    color = FlagmanTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FlagmanPrimary,
                            contentColor = FlagmanOnPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Загрузить из галереи", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    if (photoUri != null) {
                        TextButton(onClick = { photoUri = null }) {
                            Text("Сбросить", color = FlagmanTextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it },
                label = { Text("Имя") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = textFieldColors,
                modifier = Modifier.weight(2f)
            )
            OutlinedTextField(
                value = ageText,
                onValueChange = { ageText = it },
                label = { Text("Возраст") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = textFieldColors,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = faculty,
                onValueChange = { faculty = it },
                label = { Text("Факультет / направление") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = textFieldColors,
                modifier = Modifier.weight(2f)
            )
            OutlinedTextField(
                value = studyGroup,
                onValueChange = { studyGroup = it },
                label = { Text("Группа") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = textFieldColors,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = bio,
            onValueChange = { bio = it },
            label = { Text("О себе") },
            shape = RoundedCornerShape(14.dp),
            colors = textFieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Кого ты ищешь?",
            color = FlagmanTextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GoalSelectionCard(
                modifier = Modifier.weight(1f),
                title = "Вторую половинку",
                subtitle = "Для тёплых отношений",
                isHeartIcon = true,
                isSelected = lookingFor == "relationship",
                onClick = { lookingFor = "relationship" }
            )
            GoalSelectionCard(
                modifier = Modifier.weight(1f),
                title = "Друзей",
                subtitle = "Для общения и встреч",
                isHeartIcon = false,
                isSelected = lookingFor == "friends",
                onClick = { lookingFor = "friends" }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Мои интересы (${selectedInterests.size} выбрано)",
            color = FlagmanTextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            allInterests.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { interest ->
                        val isSelected = selectedInterests.contains(interest)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) FlagmanSurfaceSelected else FlagmanSurface)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) FlagmanBorderSelected else FlagmanBorder,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    selectedInterests = if (isSelected) {
                                        selectedInterests - interest
                                    } else {
                                        selectedInterests + interest
                                    }
                                }
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isSelected) "✓ $interest" else interest,
                                color = if (isSelected) Color(0xFFDCE3FF) else Color(0xFF939BB5),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        Button(
            onClick = {
                scope.launch {
                    val updated = repository.updateProfile(
                        currentStudent.copy(
                            full_name = fullName.trim().ifEmpty { currentStudent.full_name },
                            age = ageText.toIntOrNull() ?: currentStudent.age,
                            faculty = faculty.trim().ifEmpty { currentStudent.faculty },
                            study_group = studyGroup.trim().ifEmpty { currentStudent.study_group },
                            bio = bio.trim().ifEmpty { "Люблю рисовать и читать" },
                            looking_for = lookingFor,
                            photo_uri = photoUri,
                            interests = selectedInterests.joinToString(", ").ifEmpty { "Рисование, Чтение" }
                        )
                    )
                    onStudentUpdated(updated)
                    Toast.makeText(context, "Анкета сохранена!", Toast.LENGTH_SHORT).show()
                    onClose()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = FlagmanPrimary,
                contentColor = FlagmanOnPrimary
            )
        ) {
            Text("Сохранить анкету", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, FlagmanBorder)
            ) {
                Text("Выйти", color = FlagmanTextPrimary)
            }

            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF8C3A4B))
            ) {
                Text("Удалить аккаунт", color = Color(0xFFFF8A80))
            }
        }
    }
}

// ======================= ЭКРАН ВХОДА, РЕГИСТРАЦИИ С ФОТО И OTP-ПОДТВЕРЖДЕНИЕМ @ITMOSCOW.PRO =======================

@Composable
fun LoginScreen(
    repository: AuthRepository,
    initialEmail: String = "",
    initialServerUrl: String = "",
    onServerUrlSaved: (String) -> Unit = {},
    onLoginSuccess: (StudentEntity) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allInterests = listOf(
        listOf("Рисование", "Чтение", "Музыка"),
        listOf("Кино", "Путешествия", "Спорт"),
        listOf("Игры", "Кулинария", "Фотография")
    )

    var serverUrlInput by remember(initialServerUrl) { mutableStateOf(initialServerUrl) }
    var serverStatusText by remember { mutableStateOf("") }
    var serverStatusOk by remember { mutableStateOf<Boolean?>(null) }
    var showServerPanel by remember { mutableStateOf(true) }

    var isRegisterMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var ageText by remember { mutableStateOf("18") }
    var faculty by remember { mutableStateOf("Информационные технологии") }
    var studyGroup by remember { mutableStateOf("ИС-21") }
    var bio by remember { mutableStateOf("Люблю рисовать и читать") }
    var selectedGoal by remember { mutableStateOf("relationship") }
    var selectedInterests by remember { mutableStateOf(setOf("Рисование", "Чтение")) }
    var selectedPhotoUri by remember { mutableStateOf<String?>(null) }

    // Состояние OTP-верификации корпоративной почты @itmoscow.pro
    var generatedOtpCode by remember { mutableStateOf<String?>(null) }
    var enteredOtpCode by remember { mutableStateOf("") }

    var errorText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val regPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val savedPath = copyUriToInternalStorage(context, uri)
            if (savedPath != null) {
                selectedPhotoUri = savedPath
                Toast.makeText(context, "Фото добавлено к анкете!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val regPreviewBitmap = rememberAvatarBitmap(selectedPhotoUri)

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = FlagmanSurface,
        unfocusedContainerColor = FlagmanSurface,
        focusedBorderColor = FlagmanBorderSelected,
        unfocusedBorderColor = FlagmanBorder,
        focusedTextColor = FlagmanTextPrimary,
        unfocusedTextColor = FlagmanTextPrimary,
        focusedLabelColor = FlagmanPrimary,
        unfocusedLabelColor = FlagmanTextSecondary
    )

    // Диалог ввода 4-значного кода подтверждения почты @itmoscow.pro
    if (generatedOtpCode != null) {
        AlertDialog(
            onDismissRequest = { generatedOtpCode = null },
            containerColor = FlagmanSurface,
            titleContentColor = FlagmanTextPrimary,
            textContentColor = FlagmanTextSecondary,
            title = {
                Text(
                    text = "Подтверждение почты колледжа",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "Мы отправили 4-значный код верификации на корпоративную почту:\n${email.trim().lowercase()}",
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(FlagmanSurfaceSelected)
                            .border(1.dp, FlagmanBorderSelected, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Демо-уведомление почты: ваш код $generatedOtpCode",
                            color = FlagmanPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = enteredOtpCode,
                        onValueChange = { if (it.length <= 4) enteredOtpCode = it },
                        label = { Text("4-значный код") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = textFieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (enteredOtpCode.trim() != generatedOtpCode) {
                            Toast.makeText(context, "Неверный код подтверждения!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val codeOk = generatedOtpCode
                        generatedOtpCode = null
                        if (codeOk != null) {
                            isLoading = true
                            scope.launch {
                                val result = repository.register(
                                    email = email,
                                    password = password,
                                    name = fullName,
                                    faculty = faculty,
                                    studyGroup = studyGroup,
                                    age = ageText.toIntOrNull() ?: 18,
                                    bio = bio,
                                    interests = selectedInterests.joinToString(", ").ifEmpty { "Рисование, Чтение" },
                                    lookingFor = selectedGoal,
                                    photoUri = selectedPhotoUri
                                )
                                isLoading = false
                                if (result.isSuccess) {
                                    val student = result.getOrNull()!!
                                    Toast.makeText(
                                        context,
                                        "Почта подтверждена! Добро пожаловать, ${student.full_name}!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onLoginSuccess(student)
                                } else {
                                    errorText = result.exceptionOrNull()?.message ?: "Ошибка регистрации"
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FlagmanPrimary,
                        contentColor = FlagmanOnPrimary
                    )
                ) {
                    Text("Подтвердить и войти", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { generatedOtpCode = null }) {
                    Text("Отмена", color = FlagmanPrimary)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FlagmanBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Meet-and-Greet",
            color = FlagmanTextPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "знакомства",
            color = FlagmanTextSecondary,
            fontSize = 15.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Блок подключения к Java-серверу на ПК (для общения с другом по мобильной сети)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(FlagmanSurface)
                .border(1.dp, FlagmanBorder, RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showServerPanel = !showServerPanel },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (repository.isRemoteMode()) "Режим: Общий сервер ПК"
                        else "Режим: Локально (один телефон)",
                        color = FlagmanTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (repository.isRemoteMode()) {
                        Text(
                            text = repository.serverBaseUrl,
                            color = FlagmanAccentBlue,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = if (showServerPanel) "Свернуть ▲" else "Настроить ▼",
                    color = FlagmanPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (showServerPanel) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = serverUrlInput,
                    onValueChange = {
                        serverUrlInput = it
                        serverStatusText = ""
                    },
                    label = { Text("Адрес сервера (например, https://xxx.trycloudflare.com)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                serverStatusText = "Проверка соединения..."
                                serverStatusOk = null
                                val res = repository.checkServerHealth(serverUrlInput)
                                if (res.isSuccess) {
                                    onServerUrlSaved(serverUrlInput)
                                    serverStatusOk = true
                                    serverStatusText = res.getOrNull() ?: "Подключено!"
                                } else {
                                    serverStatusOk = false
                                    serverStatusText = res.exceptionOrNull()?.message ?: "Ошибка подключения"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FlagmanPrimary,
                            contentColor = FlagmanOnPrimary
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("Подключить", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            serverUrlInput = ""
                            onServerUrlSaved("")
                            serverStatusOk = true
                            serverStatusText = "Включен автономный режим (локальная БД телефона)"
                            scope.launch { repository.seedTestDataIfNeeded() }
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, FlagmanBorder),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Оффлайн", color = FlagmanTextSecondary, fontSize = 12.sp)
                    }
                }

                if (serverStatusText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = serverStatusText,
                        color = when (serverStatusOk) {
                            true -> Color(0xFF81C784)
                            false -> Color(0xFFFF8A9B)
                            null -> FlagmanAccentBlue
                        },
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Переключатель «Вход» / «Регистрация»
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(FlagmanSurface)
                .border(1.dp, FlagmanBorder, RoundedCornerShape(16.dp))
                .padding(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (!isRegisterMode) FlagmanSurfaceSelected else Color.Transparent)
                    .border(
                        width = if (!isRegisterMode) 1.dp else 0.dp,
                        color = if (!isRegisterMode) FlagmanBorderSelected else Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable {
                        isRegisterMode = false
                        errorText = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Вход",
                    color = if (!isRegisterMode) FlagmanTextPrimary else FlagmanTextSecondary,
                    fontWeight = if (!isRegisterMode) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isRegisterMode) FlagmanSurfaceSelected else Color.Transparent)
                    .border(
                        width = if (isRegisterMode) 1.dp else 0.dp,
                        color = if (isRegisterMode) FlagmanBorderSelected else Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable {
                        isRegisterMode = true
                        errorText = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Регистрация",
                    color = if (isRegisterMode) FlagmanTextPrimary else FlagmanTextSecondary,
                    fontWeight = if (isRegisterMode) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(FlagmanSurfaceSelected)
                .border(1.dp, FlagmanBorderSelected, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Доступ только для студентов колледжа с почтой @itmoscow.pro",
                color = FlagmanPrimary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Корпоративная почта (например, student@itmoscow.pro)") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = textFieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль (минимум 4 символа)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(16.dp),
            colors = textFieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        if (isRegisterMode) {
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Повторите пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(16.dp),
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = FlagmanBorder)
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Заполните вашу анкету",
                color = FlagmanTextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Загрузка фотографии при регистрации
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(FlagmanSurface)
                    .border(1.dp, FlagmanBorder, RoundedCornerShape(16.dp))
                    .clickable {
                        regPhotoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(FlagmanSurfaceSelected)
                        .border(1.dp, FlagmanBorderSelected, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (regPreviewBitmap != null) {
                        Image(
                            bitmap = regPreviewBitmap.asImageBitmap(),
                            contentDescription = "Фото",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = "+",
                            color = FlagmanPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selectedPhotoUri != null) "Фотография выбрана ✓" else "Добавить фото из галереи",
                        color = FlagmanTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Нажмите, чтобы выбрать портрет для карточки анкеты",
                        color = FlagmanTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("Ваше имя") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = textFieldColors,
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = ageText,
                    onValueChange = { ageText = it },
                    label = { Text("Возраст") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = textFieldColors,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = faculty,
                    onValueChange = { faculty = it },
                    label = { Text("Факультет") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = textFieldColors,
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = studyGroup,
                    onValueChange = { studyGroup = it },
                    label = { Text("Группа") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = textFieldColors,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text("О себе (короткое описание для карточки)") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Кого ты ищешь?",
                color = FlagmanTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GoalSelectionCard(
                    modifier = Modifier.weight(1f),
                    title = "Вторую половинку",
                    subtitle = "Для тёплых отношений",
                    isHeartIcon = true,
                    isSelected = selectedGoal == "relationship",
                    onClick = { selectedGoal = "relationship" }
                )
                GoalSelectionCard(
                    modifier = Modifier.weight(1f),
                    title = "Друзей",
                    subtitle = "Для общения и встреч",
                    isHeartIcon = false,
                    isSelected = selectedGoal == "friends",
                    onClick = { selectedGoal = "friends" }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Общие интересы",
                    color = FlagmanTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${selectedInterests.size} выбрано",
                    color = FlagmanAccentBlue,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                allInterests.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { interest ->
                            val isSelected = selectedInterests.contains(interest)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) FlagmanSurfaceSelected else FlagmanSurface)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) FlagmanBorderSelected else FlagmanBorder,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        selectedInterests = if (isSelected) {
                                            selectedInterests - interest
                                        } else {
                                            selectedInterests + interest
                                        }
                                    }
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isSelected) "✓ $interest" else interest,
                                    color = if (isSelected) Color(0xFFDCE3FF) else Color(0xFF939BB5),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        if (errorText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2D161E))
                    .border(1.dp, Color(0xFF8C3A4B), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = errorText,
                    color = Color(0xFFFF8A9B),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (isLoading) {
            CircularProgressIndicator(color = FlagmanPrimary)
        } else {
            Button(
                onClick = {
                    errorText = ""
                    if (email.isBlank() || password.isEmpty()) {
                        errorText = "Введите корпоративный Email (@itmoscow.pro) и пароль"
                        return@Button
                    }
                    if (!repository.isCollegeEmail(email)) {
                        errorText = "Доступ запрещён: используйте почту колледжа @itmoscow.pro"
                        return@Button
                    }
                    if (isRegisterMode) {
                        if (fullName.isBlank()) {
                            errorText = "Укажите ваше имя для анкеты"
                            return@Button
                        }
                        if (password != confirmPassword) {
                            errorText = "Пароли не совпадают"
                            return@Button
                        }
                        // Проверяем уникальность почты и открываем окно ввода 4-значного OTP-кода
                        isLoading = true
                        scope.launch {
                            val check = repository.validateBeforeOtp(email, password, fullName)
                            isLoading = false
                            if (check.isSuccess) {
                                val code = repository.generateVerificationCode()
                                enteredOtpCode = code // подставляем для удобства тестирования или можно изменить
                                generatedOtpCode = code
                            } else {
                                errorText = check.exceptionOrNull()?.message ?: "Ошибка проверки данных"
                            }
                        }
                    } else {
                        isLoading = true
                        scope.launch {
                            val result = repository.login(email, password)
                            isLoading = false
                            if (result.isSuccess) {
                                val student = result.getOrNull()!!
                                Toast.makeText(
                                    context,
                                    "С возвращением, ${student.full_name}!",
                                    Toast.LENGTH_SHORT
                                ).show()
                                onLoginSuccess(student)
                            } else {
                                errorText = result.exceptionOrNull()?.message ?: "Ошибка авторизации"
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FlagmanPrimary,
                    contentColor = FlagmanOnPrimary
                )
            ) {
                Text(
                    text = if (isRegisterMode) "Получить код и зарегистрироваться" else "Войти в аккаунт",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (!isRegisterMode) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    isLoading = true
                    errorText = ""
                    scope.launch {
                        repository.seedTestDataIfNeeded()
                        val demoResult = repository.login("anna@itmoscow.pro", "password123")
                        isLoading = false
                        if (demoResult.isSuccess) {
                            val student = demoResult.getOrNull()!!
                            Toast.makeText(context, "Вход в демо-аккаунт: ${student.full_name}", Toast.LENGTH_SHORT).show()
                            onLoginSuccess(student)
                        } else {
                            errorText = demoResult.exceptionOrNull()?.message ?: "Ошибка демо-входа"
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, FlagmanBorderSelected)
            ) {
                Text(
                    text = "Быстрый вход (демо: anna@itmoscow.pro)",
                    color = FlagmanAccentBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        TextButton(
            onClick = {
                isRegisterMode = !isRegisterMode
                errorText = ""
            }
        ) {
            Text(
                text = if (isRegisterMode) "Уже зарегистрированы? Перейти ко входу"
                else "Впервые здесь? Создать свою анкету",
                color = FlagmanAccentBlue,
                fontSize = 13.sp
            )
        }
    }
}
