package com.example.meet_and_greet

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.meet_and_greet.data.AppDatabase
import com.example.meet_and_greet.data.AuthRepository
import com.example.meet_and_greet.ui.theme.MeetandGreetTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = AppDatabase.getDatabase(this)
        val repository = AuthRepository(db.authDao())

        setContent {
            MeetandGreetTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LoginScreen(repository)
                }
            }
        }
    }
}

@Composable
fun LoginScreen(repository: AuthRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    // ВРЕМЕННЫЙ КОД: Создаем тестового пользователя при первом запуске
    LaunchedEffect(Unit) {
        scope.launch {
            if (repository.login("anna@college.ru", "wrong_pass").isFailure) {
                repository.register("anna@college.ru", "password123", "Анна Иванова")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    if (email.isEmpty() || password.isEmpty()) {
                        errorText = "Заполните все поля"
                        return@Button
                    }
                    isLoading = true
                    errorText = ""

                    scope.launch {
                        val result = repository.login(email, password)
                        isLoading = false
                        if (result.isSuccess) {
                            Toast.makeText(context, "Привет, ${result.getOrNull()?.full_name}!", Toast.LENGTH_LONG).show()
                        } else {
                            errorText = result.exceptionOrNull()?.message ?: "Ошибка"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Войти")
            }
        }

        if (errorText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = errorText, color = Color.Red)
        }
    }
}