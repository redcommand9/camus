package com.camus.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Hosts the fully native Camus Reader UI: [AndroidNativeApp] (library, settings, catalog, reader), with
 * [LocalAccountScreen] gating access while an optional device-encryption vault is locked. There is
 * no WebView anywhere in this activity - the previous WebView-hosted website build has been
 * retired (see AndroidNativeApp.kt / AndroidBookPage.kt / NativeEpubBook.kt).
 */
class MainActivity : ComponentActivity() {
    private var onStopAction: (() -> Unit)? = null
    private var documentPickerActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = LocalAccountVault(getSharedPreferences("camus-reader-native", MODE_PRIVATE))
        setContent {
            // Encryption is opt-in: a fresh install (no profile yet) opens straight into the app.
            var unlocked by remember { mutableStateOf(!vault.hasProfile()) }
            var encryptionOn by remember { mutableStateOf(vault.encryptionEnabled()) }
            var showEnableEncryption by remember { mutableStateOf(false) }
            var showDisableConfirm by remember { mutableStateOf(false) }

            if (!unlocked) {
                LocalAccountScreen(
                    profileExists = vault.hasProfile(),
                    profileName = vault.profileName(),
                    onCreateProfile = { name, password ->
                        try { vault.createProfile(name, password); true }
                        catch (_: Exception) { false }
                        finally { password.fill('\u0000') }
                    },
                    onUnlock = { password ->
                        try { vault.unlock(password) }
                        finally { password.fill('\u0000') }
                    },
                    onAuthenticated = { unlocked = true },
                )
            } else {
                val leaveForeground = {
                    vault.flushPending()
                    if (vault.encryptionEnabled()) {
                        vault.lock()
                        unlocked = false
                    }
                    onStopAction = null
                }
                SideEffect { onStopAction = leaveForeground }
                Box {
                    AndroidNativeApp(
                        vault = vault,
                        onLock = leaveForeground,
                        onPickerActive = { documentPickerActive = it },
                        encryptionEnabled = encryptionOn,
                        onRequestEnableEncryption = { showEnableEncryption = true },
                        onRequestDisableEncryption = { showDisableConfirm = true },
                    )
                    if (showEnableEncryption) {
                        Dialog(
                            onDismissRequest = { showEnableEncryption = false },
                            properties = DialogProperties(usePlatformDefaultWidth = false),
                        ) {
                            LocalAccountScreen(
                                profileExists = false,
                                profileName = "",
                                onCreateProfile = { name, password ->
                                    try { vault.createProfile(name, password); encryptionOn = true; true }
                                    catch (_: Exception) { false }
                                    finally { password.fill('\u0000') }
                                },
                                onUnlock = { false },
                                onAuthenticated = { showEnableEncryption = false },
                            )
                        }
                    }
                    if (showDisableConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDisableConfirm = false },
                            title = { Text("Turn off device encryption?") },
                            text = { Text("Your library, bookmarks, and notes will be stored on this device without encryption. You won't need a password to open Camus Reader.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    vault.disableEncryption()
                                    encryptionOn = false
                                    showDisableConfirm = false
                                }) { Text("Turn off") }
                            },
                            dismissButton = { TextButton(onClick = { showDisableConfirm = false }) { Text("Cancel") } },
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        if (!documentPickerActive) onStopAction?.invoke()
        super.onStop()
    }
}
