package com.aiko.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiko.assistant.service.AgentAccessibilityService
import com.aiko.assistant.ui.ChatScreen
import com.aiko.assistant.ui.ChatViewModel

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                var storageGranted by remember { mutableStateOf(hasAllFilesAccess()) }
                var a11yGranted by remember { mutableStateOf(AgentAccessibilityService.instance != null) }

                if (!storageGranted) {
                    GrantStorageScreen { storageGranted = hasAllFilesAccess() }
                } else {
                    val vm: ChatViewModel = viewModel()
                    ChatScreen(
                        vm = vm,
                        a11yGranted = a11yGranted,
                        onOpenA11y = { openA11ySettings() },
                        onCheckA11y = { a11yGranted = AgentAccessibilityService.instance != null }
                    )
                }
            }
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun openA11ySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

@Composable
private fun GrantStorageScreen(onRetry: () -> Unit) {
    Surface(color = Color(0xFF141021)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Welcome!", color = Color(0xFFFF6FA5), fontSize = 28.sp)
            Text(
                "Aiko needs access to your files so she can create and edit them for you. " +
                "Tap the button, enable All files access for Aiko, then come back.",
                color = Color(0xFFEDE7F6),
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 16)
            )
            Button(
                onClick = {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:com.aiko.assistant")
                            )
                        )
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                    onRetry()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6FA5))
            ) { Text("Grant file access") }
        }
    }
}
