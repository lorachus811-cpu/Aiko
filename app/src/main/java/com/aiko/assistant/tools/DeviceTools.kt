package com.aiko.assistant.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Environment
import android.provider.AlarmClock
import android.provider.Settings
import com.aiko.assistant.service.AgentAccessibilityService
import java.io.File

object DeviceTools {

    fun resolvePath(path: String): File {
        val p = path.trim()
        return if (p.startsWith("/")) File(p)
        else File(Environment.getExternalStorageDirectory().absolutePath, p)
    }

    fun fileList(directory: String): String {
        val dir = resolvePath(directory)
        if (!dir.exists()) return "Error: directory not found: ${dir.absolutePath}"
        if (!dir.isDirectory) return "Error: not a directory"
        val items = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: return "Error: cannot read directory (check All files access)"
        if (items.isEmpty()) return "Empty directory: ${dir.absolutePath}"
        return items.joinToString("\n") { f ->
            (if (f.isDirectory) "[DIR]  " else "       ") + f.name + (if (!f.isDirectory) "  (${f.length()} B)" else "")
        }.let { "Directory ${dir.absolutePath}:\n$it" }
    }

    fun fileRead(path: String): String {
        val f = resolvePath(path)
        if (!f.exists()) return "Error: file not found: ${f.absolutePath}"
        return try { f.readText().take(6000) } catch (e: Exception) { "Error reading: ${e.message}" }
    }

    fun fileWrite(path: String, content: String): String {
        return try {
            val f = resolvePath(path)
            f.parentFile?.mkdirs()
            f.writeText(content)
            "Saved ${content.length} chars to ${f.absolutePath}"
        } catch (e: Exception) { "Error writing: ${e.message}" }
    }

    fun fileDelete(path: String): String {
        val f = resolvePath(path)
        if (!f.exists()) return "Error: file not found"
        return if (f.delete()) "Deleted ${f.absolutePath}"
        else if (f.isDirectory) "Error: not deleting directories in v1 - delete folder files individually"
        else "Error: delete failed"
    }

    fun spckWriteCode(project: String, filePath: String, content: String, openEditor: Boolean): String {
        val root = File(Environment.getExternalStorageDirectory(), "AikoSpck/$project")
        val target = File(root, filePath)
        target.parentFile?.mkdirs()
        target.writeText(content)
        val summary = "Wrote ${content.length} chars to ${target.absolutePath}"
        if (openEditor) {
            openAppStatic("Spck")
            return "$summary; launched Spck - use Spck's Import (file icon -> Import project) and pick the AikoSpck/$project folder."
        }
        return summary
    }

    private var appContext: Context? = null
    fun init(context: Context) { appContext = context.applicationContext }

    fun openAppStatic(appName: String): String {
        val context = appContext ?: return "Error: app context not initialized"
        return openApp(context, appName)
    }

    fun openApp(context: Context, appName: String): String {
        val pm = context.packageManager
        val target = appName.trim().lowercase()
        val launchables = pm.getInstalledApplications(0).filter {
            pm.getLaunchIntentForPackage(it.packageName) != null
        }
        val match = launchables.firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase() == target
        } ?: launchables.firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase().contains(target)
        } ?: launchables.firstOrNull {
            it.packageName.lowercase().contains(target)
        }
        return if (match != null) {
            pm.getLaunchIntentForPackage(match.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
            "Opened ${pm.getApplicationLabel(match)} (${match.packageName})"
        } else "Error: app not found: $appName"
    }

    fun closeApp(context: Context): String {
        val svc = AgentAccessibilityService.instance
        if (svc != null) {
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            return "Went home (app closed/minimized)"
        }
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME); flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(home)
        return "Went home (accessibility off - app only minimized)"
    }

    fun toggleSetting(context: Context, setting: String, value: String): String {
        return try {
            when (setting) {
                "wifi" -> {
                    val i = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                    "Opened internet settings panel - toggle WiFi there"
                }
                "bluetooth" -> {
                    val i = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                    "Opened Bluetooth settings"
                }
                "flashlight" ->
                    "Flashlight needs quick-settings (Android restriction) - I can open settings though."
                "ringer" -> {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    when (value) {
                        "on" -> { am.ringerMode = AudioManager.RINGER_MODE_NORMAL; "Ringer normal (sound on)" }
                        "off" -> { am.ringerMode = AudioManager.RINGER_MODE_SILENT; "Ringer silent" }
                        else -> "value must be 'on' or 'off'"
                    }
                }
                "brightness" -> {
                    val pct = value.toIntOrNull() ?: return "brightness needs a number 0-100"
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        (255 * pct.coerceIn(0, 100) / 100).coerceIn(1, 255)
                    )
                    "Brightness set to $pct%"
                }
                "volume" -> {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val pct = value.toIntOrNull() ?: return "volume needs a number 0-100"
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * pct.coerceIn(0, 100) / 100), 0)
                    "Media volume set to $pct%"
                }
                "dnd" -> {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    when (value) {
                        "on" -> { am.ringerMode = AudioManager.RINGER_MODE_SILENT; "Silent mode on" }
                        "off" -> { am.ringerMode = AudioManager.RINGER_MODE_NORMAL; "Silent mode off" }
                        else -> "value must be 'on' or 'off'"
                    }
                }
                else -> "Unknown setting: $setting"
            }
        } catch (e: Exception) {
            "Error: ${e.message} (some settings need more permissions on Android 13+)"
        }
    }

    fun setClipboard(context: Context, text: String): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Aiko", text))
        return "Copied to clipboard"
    }

    fun webSearch(context: Context, queryOrUrl: String): String {
        val intent = if (queryOrUrl.startsWith("http"))
            Intent(Intent.ACTION_VIEW, Uri.parse(queryOrUrl))
        else Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra("query", queryOrUrl) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); "Opened: $queryOrUrl" }
        catch (e: Exception) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=${Uri.encode(queryOrUrl)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            "Opened web search for: $queryOrUrl"
        }
    }

    fun setAlarm(context: Context, label: String, hour: Int, minute: Int): String {
        return try {
            val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
            "Alarm set screen opened for $hour:${minute.toString().padStart(2, '0')} - $label"
        } catch (e: Exception) { "Error setting alarm: ${e.message}" }
    }
}
