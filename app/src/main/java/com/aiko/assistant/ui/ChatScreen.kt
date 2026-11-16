package com.aiko.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Bg = Color(0xFF141021)
private val BubbleAiko = Color(0xFF2A2440)
private val BubbleUser = Color(0xFFFF6FA5)
private val Accent = Color(0xFFFF6FA5)
private val TextMain = Color(0xFFEDE7F6)
private val TextDim = Color(0xFF9E93B8)

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    a11yGranted: Boolean,
    onOpenA11y: () -> Unit,
    onCheckA11y: () -> Unit
) {
    val state by vm.ui.collectAsState()
    val context = LocalContext.current
    var settingsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.ensureHistoryLoaded(context)
        AppContextHolder.context = context.applicationContext
    }

    Column(Modifier.fillMaxSize().background(Bg)) {

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarFace(mood = state.mood, speaking = state.isSpeaking, thinking = state.thinking, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.padding(start = 4.dp)) {
                Text("Aiko", color = TextMain, fontSize = 20.sp)
                Text(
                    when {
                        state.isSpeaking -> "speaking..."
                        state.thinking -> "soch rahi hoon..."
                        else -> "online"
                    },
                    color = TextDim, fontSize = 12.sp
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCheckA11y) {
                Icon(
                    Icons.Filled.Accessibility,
                    contentDescription = "accessibility",
                    tint = if (a11yGranted) Color(0xFF66BB6A) else Color(0xFF777777)
                )
            }
            IconButton(onClick = { settingsOpen = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "settings", tint = TextDim)
            }
        }

        if (!a11yGranted) {
            Surface(color = Color(0x33FF6FA5)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Phone control ke liye Accessibility enable karo",
                        color = Accent, fontSize = 13.sp
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onOpenA11y) { Text("Enable", color = Accent) }
                }
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(state.messages.size) {
            if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.messages) { msg -> MessageBubble(msg) }
            if (state.thinking) item {
                Text("Aiko is thinking...", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
            }
        }

        var input by remember { mutableStateOf("") }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Kuch bolo...", color = TextDim) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = BubbleAiko,
                    unfocusedContainerColor = BubbleAiko,
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain,
                    cursorColor = Accent
                )
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (input.isNotBlank()) { vm.send(input.trim()); input = "" } },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(">", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
            }
        }
    }

    if (settingsOpen) SettingsDialog(vm) { settingsOpen = false }
}

@Composable
private fun MessageBubble(msg: ChatUiMessage) {
    if (msg.role == "status") {
        Text(msg.text, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
        return
    }
    val mine = msg.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (mine) BubbleUser else BubbleAiko,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (mine) 16.dp else 4.dp,
                bottomEnd = if (mine) 4.dp else 16.dp
            )
        ) {
            Text(
                msg.text,
                color = if (mine) Color.White else TextMain,
                fontSize = 15.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
fun AvatarFace(mood: Mood, speaking: Boolean, thinking: Boolean, size: Dp) {

    var blink by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay((2000L..5000L).random())
            blink = true
            delay(150)
            blink = false
            delay(150)
        }
    }

    val mouthAnim = rememberInfiniteTransition(label = "mouth")
    val mouthOpen by mouthAnim.animateFloat(
        initialValue = 0.15f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(160, easing = LinearEasing), RepeatMode.Reverse),
        label = "open"
    )

    val bob = rememberInfiniteTransition(label = "bob")
    val bobY by bob.animateFloat(
        initialValue = -2f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "y"
    )

    val think = rememberInfiniteTransition(label = "think")
    val thinkPhase by think.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "phase"
    )

    val skin = Color(0xFFFFE0B2)
    val hair = Color(0xFF3E2723)
    val eyeColor = Color(0xFF4E342E)
    val blush = Color(0x33FF8A9E)
    val lipColor = if (mood == Mood.LOVING || mood == Mood.EXCITED) Color(0xFFE91E63) else Color(0xFFD81B60)

    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2
        val cy = h / 2 + bobY
        val headR = w * 0.42f

        drawCircle(hair, radius = headR * 1.12f, center = Offset(cx, cy - headR * 0.10f))
        drawCircle(skin, radius = headR, center = Offset(cx, cy))
        drawArc(
            color = hair,
            startAngle = 180f, sweepAngle = 180f, useCenter = true,
            topLeft = Offset(cx - headR * 1.05f, cy - headR * 1.28f),
            size = Size(headR * 2.1f, headR * 1.3f)
        )
        drawCircle(hair, radius = headR * 0.32f, center = Offset(cx - headR * 0.95f, cy - headR * 0.75f))
        drawCircle(hair, radius = headR * 0.32f, center = Offset(cx + headR * 0.95f, cy - headR * 0.75f))

        val eyeY = cy - headR * 0.05f
        val eyeDX = headR * 0.42f
        val eyeR = headR * 0.13f

        if (blink) {
            drawLine(eyeColor, Offset(cx - eyeDX - eyeR, eyeY), Offset(cx - eyeDX + eyeR, eyeY), strokeWidth = headR * 0.05f)
            drawLine(eyeColor, Offset(cx + eyeDX - eyeR, eyeY), Offset(cx + eyeDX + eyeR, eyeY), strokeWidth = headR * 0.05f)
        } else {
            drawCircle(eyeColor, radius = eyeR, center = Offset(cx - eyeDX, eyeY))
            drawCircle(eyeColor, radius = eyeR, center = Offset(cx + eyeDX, eyeY))
            drawCircle(Color.White, radius = eyeR * 0.35f, center = Offset(cx - eyeDX - eyeR * 0.3f, eyeY - eyeR * 0.3f))
            drawCircle(Color.White, radius = eyeR * 0.35f, center = Offset(cx + eyeDX - eyeR * 0.3f, eyeY - eyeR * 0.3f))
        }

        drawCircle(blush, radius = headR * 0.14f, center = Offset(cx - headR * 0.62f, cy + headR * 0.28f))
        drawCircle(blush, radius = headR * 0.14f, center = Offset(cx + headR * 0.62f, cy + headR * 0.28f))

        val mouthY = cy + headR * 0.42f
        when {
            speaking -> drawCircle(lipColor, radius = headR * (0.08f + mouthOpen * 0.16f), center = Offset(cx, mouthY))
            mood == Mood.HAPPY || mood == Mood.LOVING || mood == Mood.EXCITED -> drawArc(
                lipColor, startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(cx - headR * 0.28f, mouthY - headR * 0.22f),
                size = Size(headR * 0.56f, headR * 0.44f),
                style = Stroke(width = headR * 0.07f)
            )
            mood == Mood.SAD || mood == Mood.SLEEPY -> drawArc(
                lipColor, startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(cx - headR * 0.28f, mouthY - headR * 0.02f),
                size = Size(headR * 0.56f, headR * 0.44f),
                style = Stroke(width = headR * 0.07f)
            )
            mood == Mood.SURPRISED -> drawCircle(lipColor, radius = headR * 0.13f, center = Offset(cx, mouthY))
            else -> drawLine(
                lipColor, Offset(cx - headR * 0.18f, mouthY), Offset(cx + headR * 0.18f, mouthY),
                strokeWidth = headR * 0.06f
            )
        }

        if (thinking) {
            val idx = thinkPhase.toInt() % 3
            for (i in 0..2) {
                val a = if (i == idx) Color(0xFFFF6FA5) else Color(0x55FF6FA5)
                drawCircle(a, radius = headR * 0.06f, center = Offset(cx + (i - 1) * headR * 0.22f, cy - headR * 1.35f))
            }
        }
    }
}

@Composable
fun SettingsDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(vm.displayApiKey()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BubbleAiko,
        title = { Text("Settings", color = TextMain) },
        text = {
            Column {
                Text("Anthropic API Key", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("sk-ant-...", color = TextDim) },
                    textStyle = TextStyle(color = TextMain, fontSize = 14.sp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Key sirf tumhare phone me save hoti hai, seedha Anthropic ko jaati hai.",
                    color = TextDim, fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.saveApiKey(key); onDismiss() }) { Text("Save", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextDim) } }
    )
}
