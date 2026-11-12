package com.example.meet_and_greet

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meet_and_greet.data.*
import com.example.meet_and_greet.ui.theme.MeetandGreetTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = AppDatabase.getDatabase(this)
        val repository = AuthRepository(db.authDao())

        setContent {
            MeetandGreetTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MeetAndGreetApp(repository)
                }
            }
        }
    }
}

@Composable
fun MeetAndGreetApp(repository: AuthRepository) {
    var currentStudent by remember { mutableStateOf<StudentEntity?>(null) }

    LaunchedEffect(Unit) {
        repository.seedTestDataIfNeeded()
    }

    if (currentStudent == null) {
        LoginScreen(
            repository = repository,
            onLoginSuccess = { student -> currentStudent = student }
        )
    } else {
        MainDatingScreen(
            currentStudent = currentStudent!!,
            repository = repository,
            onStudentUpdated = { updated -> currentStudent = updated },
            onLogout = { currentStudent = null }
        )
    }
}

@Composable
fun LoginScreen(
    repository: AuthRepository,
    onLoginSuccess: (StudentEntity) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isRegisterMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var faculty by remember { mutableStateOf("Информационные технологии") }
    var studyGroup by remember { mutableStateOf("ИС-21") }
    var errorText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "❤",
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Meet & Greet",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (isRegisterMode) "Регистрация студента колледжа" else "Знакомства внутри колледжа",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (isRegisterMode) {
            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it },
                label = { Text("Имя и фамилия") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = faculty,
                onValueChange = { faculty = it },
                label = { Text("Факультет / Отделение") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = studyGroup,
                onValueChange = { studyGroup = it },
                label = { Text("Учебная группа (например, ИС-21)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    if (email.isBlank() || password.isEmpty() || (isRegisterMode && fullName.isBlank())) {
                        errorText = "Заполните все обязательные поля"
                        return@Button
                    }
                    isLoading = true
                    errorText = ""

                    scope.launch {
                        val result = if (isRegisterMode) {
                            repository.register(
                                email = email.trim(),
                                password = password,
                                name = fullName.trim(),
                                faculty = faculty.trim().ifEmpty { "Информационные технологии" },
                                studyGroup = studyGroup.trim().ifEmpty { "ИС-21" }
                            )
                        } else {
                            repository.login(email.trim(), password)
                        }
                        isLoading = false
                        if (result.isSuccess) {
                            val student = result.getOrNull()!!
                            Toast.makeText(
                                context,
                                "Привет, ${student.full_name}!",
                                Toast.LENGTH_LONG
                            ).show()
                            onLoginSuccess(student)
                        } else {
                            errorText = result.exceptionOrNull()?.message ?: "Ошибка"
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(if (isRegisterMode) "Зарегистрироваться" else "Войти")
            }
        }

        if (errorText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = errorText,
                color = Color.Red,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = {
                isRegisterMode = !isRegisterMode
                errorText = ""
            }
        ) {
            Text(
                if (isRegisterMode) "Уже есть аккаунт? Войти"
                else "Нет аккаунта? Зарегистрироваться"
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDatingScreen(
    currentStudent: StudentEntity,
    repository: AuthRepository,
    onStudentUpdated: (StudentEntity) -> Unit,
    onLogout: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var activeChatMatch by remember { mutableStateOf<MatchWithPartner?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when {
                                activeChatMatch != null -> activeChatMatch!!.partner.full_name
                                selectedTab == 0 -> "Анкеты колледжа"
                                selectedTab == 1 -> "Взаимные симпатии"
                                else -> "Мой профиль"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${currentStudent.full_name} (${currentStudent.study_group})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (activeChatMatch != null) {
                        TextButton(onClick = { activeChatMatch = null }) {
                            Text("Назад")
                        }
                    } else {
                        TextButton(onClick = onLogout) {
                            Text("Выйти")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (activeChatMatch == null) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Text("🔥", fontSize = 18.sp) },
                        label = { Text("Анкеты") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Text("💬", fontSize = 18.sp) },
                        label = { Text("Мэтчи и чаты") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Text("👤", fontSize = 18.sp) },
                        label = { Text("Профиль") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (activeChatMatch != null) {
                ChatScreen(
                    currentStudent = currentStudent,
                    matchWithPartner = activeChatMatch!!,
                    repository = repository
                )
            } else {
                when (selectedTab) {
                    0 -> DiscoverTab(
                        currentStudent = currentStudent,
                        repository = repository,
                        onOpenMatches = { selectedTab = 1 }
                    )
                    1 -> MatchesTab(
                        currentStudent = currentStudent,
                        repository = repository,
                        onOpenChat = { matchItem -> activeChatMatch = matchItem }
                    )
                    2 -> ProfileTab(
                        currentStudent = currentStudent,
                        repository = repository,
                        onStudentUpdated = onStudentUpdated,
                        onLogout = onLogout
                    )
                }
            }
        }
    }
}

@Composable
fun DiscoverTab(
    currentStudent: StudentEntity,
    repository: AuthRepository,
    onOpenMatches: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf<List<StudentEntity>>(emptyList()) }
    var selectedFaculty by remember { mutableStateOf("Все") }
    var matchedPartnerDialog by remember { mutableStateOf<StudentEntity?>(null) }

    val faculties = listOf("Все", "Информационные технологии", "Дизайн", "Экономика", "Право")

    fun loadCandidates() {
        scope.launch {
            candidates = repository.getCandidates(currentStudent.id)
        }
    }

    LaunchedEffect(currentStudent.id) {
        loadCandidates()
    }

    val filteredCandidates = remember(candidates, selectedFaculty) {
        if (selectedFaculty == "Все") candidates
        else candidates.filter { it.faculty.equals(selectedFaculty, ignoreCase = true) }
    }

    val currentCandidate = filteredCandidates.firstOrNull()

    if (matchedPartnerDialog != null) {
        AlertDialog(
            onDismissRequest = { matchedPartnerDialog = null },
            title = { Text("🎉 Взаимная симпатия!") },
            text = {
                Text(
                    "Вы и ${matchedPartnerDialog!!.full_name} (${matchedPartnerDialog!!.study_group}) " +
                        "понравились друг другу! Теперь вы можете начать общение в чате."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        matchedPartnerDialog = null
                        onOpenMatches()
                    }
                ) {
                    Text("Перейти к чатам")
                }
            },
            dismissButton = {
                TextButton(onClick = { matchedPartnerDialog = null }) {
                    Text("Продолжить просмотр")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Фильтр по факультетам колледжа
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            faculties.forEach { faculty ->
                FilterChip(
                    selected = selectedFaculty == faculty,
                    onClick = { selectedFaculty = faculty },
                    label = { Text(faculty) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (currentCandidate == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Новых анкет в этой категории пока нет",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Вы просмотрели все доступные профили студентов.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                repository.resetSwipes(currentStudent.id)
                                loadCandidates()
                            }
                        }
                    ) {
                        Text("Сбросить просмотренные анкеты")
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Аватар с инициалами студента
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        val initials = currentCandidate.full_name
                            .split(" ")
                            .mapNotNull { it.firstOrNull()?.uppercase() }
                            .take(2)
                            .joinToString("")
                        Text(
                            text = initials,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "${currentCandidate.full_name}, ${currentCandidate.age}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "${currentCandidate.faculty} • Группа ${currentCandidate.study_group}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "О себе",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentCandidate.bio,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "Интересы",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        currentCandidate.interests.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { interest ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(interest) }
                                )
                            }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            repository.swipeStudent(
                                currentStudentId = currentStudent.id,
                                targetStudentId = currentCandidate.id,
                                isLike = false
                            )
                            loadCandidates()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                ) {
                    Text("✕ Пропустить", fontSize = 16.sp)
                }

                Button(
                    onClick = {
                        scope.launch {
                            val matchedPartner = repository.swipeStudent(
                                currentStudentId = currentStudent.id,
                                targetStudentId = currentCandidate.id,
                                isLike = true
                            )
                            if (matchedPartner != null) {
                                matchedPartnerDialog = matchedPartner
                            }
                            loadCandidates()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                ) {
                    Text("❤ Нравится", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun MatchesTab(
    currentStudent: StudentEntity,
    repository: AuthRepository,
    onOpenChat: (MatchWithPartner) -> Unit
) {
    var matches by remember { mutableStateOf<List<MatchWithPartner>>(emptyList()) }

    LaunchedEffect(currentStudent.id) {
        matches = repository.getMatchesWithPartners(currentStudent.id)
    }

    if (matches.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Пока нет взаимных симпатий",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ставьте отметку «Нравится» анкетам студентов во вкладке «Анкеты», чтобы начать диалог!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(matches) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenChat(item) },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = item.partner.full_name.take(1).uppercase(),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${item.partner.full_name} (${item.partner.study_group})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = item.partner.faculty,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.lastMessage ?: "Нажмите, чтобы написать сообщение...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
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
    repository: AuthRepository
) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }

    fun loadMessages() {
        scope.launch {
            messages = repository.getMessages(matchWithPartner.match.id)
        }
    }

    LaunchedEffect(matchWithPartner.match.id) {
        loadMessages()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
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
                    Surface(
                        color = if (isMe) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Text(
                            text = msg.body,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            color = if (isMe) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
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
                placeholder = { Text("Написать сообщение...") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        val textToSend = inputText
                        inputText = ""
                        scope.launch {
                            repository.sendMessage(
                                matchId = matchWithPartner.match.id,
                                senderId = currentStudent.id,
                                text = textToSend
                            )
                            loadMessages()
                        }
                    }
                }
            ) {
                Text("Отпр.")
            }
        }
    }
}

@Composable
fun ProfileTab(
    currentStudent: StudentEntity,
    repository: AuthRepository,
    onStudentUpdated: (StudentEntity) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fullName by remember(currentStudent) { mutableStateOf(currentStudent.full_name) }
    var faculty by remember(currentStudent) { mutableStateOf(currentStudent.faculty) }
    var studyGroup by remember(currentStudent) { mutableStateOf(currentStudent.study_group) }
    var ageText by remember(currentStudent) { mutableStateOf(currentStudent.age.toString()) }
    var bio by remember(currentStudent) { mutableStateOf(currentStudent.bio) }
    var interests by remember(currentStudent) { mutableStateOf(currentStudent.interests) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = fullName.take(1).uppercase(),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = currentStudent.email,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = fullName,
            onValueChange = { fullName = it },
            label = { Text("Имя и фамилия") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = studyGroup,
                onValueChange = { studyGroup = it },
                label = { Text("Группа") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = ageText,
                onValueChange = { ageText = it },
                label = { Text("Возраст") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = faculty,
            onValueChange = { faculty = it },
            label = { Text("Факультет / Отделение") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = bio,
            onValueChange = { bio = it },
            label = { Text("О себе") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = interests,
            onValueChange = { interests = it },
            label = { Text("Интересы (через запятую)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                scope.launch {
                    val updated = repository.updateProfile(
                        currentStudent.copy(
                            full_name = fullName.trim().ifEmpty { currentStudent.full_name },
                            faculty = faculty.trim().ifEmpty { currentStudent.faculty },
                            study_group = studyGroup.trim().ifEmpty { currentStudent.study_group },
                            age = ageText.toIntOrNull() ?: currentStudent.age,
                            bio = bio.trim(),
                            interests = interests.trim()
                        )
                    )
                    onStudentUpdated(updated)
                    Toast.makeText(context, "Профиль сохранен в БД", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text("Сохранить изменения")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Выйти из аккаунта")
        }
    }
}
