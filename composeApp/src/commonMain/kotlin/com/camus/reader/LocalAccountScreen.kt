package com.camus.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LocalAccountScreen(
    profileExists: Boolean,
    profileName: String,
    onCreateProfile: (String, CharArray) -> Boolean,
    onUnlock: (CharArray) -> Boolean,
    onAuthenticated: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val submitLabel = when {
        busy && profileExists -> "Unlocking…"
        busy -> "Creating profile…"
        profileExists -> "Unlock Camus Reader"
        else -> "Create profile"
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(22.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 430.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("camus reader", fontFamily = LocalTitleFont.current, fontSize = 34.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(19.dp))
            Text(if (profileExists) "Unlock your library" else "Create your local profile", fontFamily = LocalTitleFont.current, fontSize = 25.sp, textAlign = TextAlign.Center)
            Text(
                if (profileExists) "Enter your profile password to open your books and reading data." else "Your profile stays on this device. Camus Reader encrypts library details and reading notes. Source book files stay where you chose them.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 19.dp),
            )

            if (!profileExists) {
                OutlinedTextField(name, { name = it; error = "" }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Profile name") })
            } else {
                Text(profileName, fontFamily = LocalTitleFont.current, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
            }
            OutlinedTextField(
                password,
                { password = it; error = "" },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Password") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
            )
            if (!profileExists) {
                OutlinedTextField(
                    confirmation,
                    { confirmation = it; error = "" },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    singleLine = true,
                    label = { Text("Confirm password") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 9.dp))
            Button(
                onClick = {
                    error = ""
                    if (profileExists) {
                        busy = true
                        val enteredPassword = password.toCharArray()
                        password = ""
                        scope.launch {
                            val accepted = withContext(Dispatchers.Default) { onUnlock(enteredPassword) }
                            busy = false
                            if (accepted) onAuthenticated() else error = "That password did not unlock this profile."
                        }
                    } else when {
                        name.isBlank() -> error = "Enter a profile name."
                        password.length < 8 -> error = "Use a password with at least 8 characters."
                        password != confirmation -> error = "The passwords do not match."
                        else -> {
                            busy = true
                            val enteredPassword = password.toCharArray()
                            password = ""
                            confirmation = ""
                            scope.launch {
                                val created = withContext(Dispatchers.Default) { onCreateProfile(name.trim(), enteredPassword) }
                                busy = false
                                if (created) onAuthenticated() else error = "Camus Reader could not create the local profile. Try again."
                            }
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            ) { Text(submitLabel) }
            if (!profileExists) Text(
                "Keep this password safe. Camus Reader cannot recover encrypted data if it is forgotten.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
