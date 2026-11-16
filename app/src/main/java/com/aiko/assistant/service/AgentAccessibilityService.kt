package com.aiko.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun pressKey(key: String): String {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return "unknown key: $key"
        }
        return if (performGlobalAction(action)) "Pressed $key" else "failed to press $key"
    }

    fun tap(x: Int, y: Int, onDone: (Boolean) -> Unit = {}) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { onDone(true) }
            override fun onCancelled(g: GestureDescription?) { onDone(false) }
        }, handler)
    }

    private fun setTextOn(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Error: no active window (is a screen open?)"
        val sb = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 18) return
            val txt = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = txt.ifEmpty { desc }
            if (label.isNotEmpty() && label.length < 200 && !sb.contains(" $label\n")) {
                if (sb.length < 5000) sb.append("$label\n")
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString().ifEmpty { "(no readable text on screen)" }
    }

    fun screenshotText(): String {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: "unknown"
        return "Screen: $pkg\n" + readScreen()
    }

    // ---------- WhatsApp auto-send flow ----------

    fun sendWhatsAppMessage(contact: String, text: String, onResult: (String) -> Unit) {
        val launch = Intent().apply {
            setClassName("com.whatsapp", "com.whatsapp.HomeActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try { startActivity(launch) } catch (e: Exception) { onResult("Error: WhatsApp not installed"); return }
        handler.postDelayed({ stepSearchContact(contact, text, 0, onResult) }, 2500)
    }

    private fun stepSearchContact(contact: String, text: String, attempt: Int, onResult: (String) -> Unit) {
        if (attempt >= 6) { onResult("Error: couldn't find search field; open WhatsApp and try again"); return }
        val root = rootInActiveWindow ?: run {
            handler.postDelayed({ stepSearchContact(contact, text, attempt + 1, onResult) }, 1200); return
        }
        val searchNode = findClickableAncestor(findNodesByContentDesc(root, "Search"))
        if (searchNode != null) {
            val r = Rect(); searchNode.getBoundsInScreen(r)
            if (r.centerX() > 0) {
                tap(r.centerX(), r.centerY()) {
                    handler.postDelayed({ stepTypeContact(contact, text, 0, onResult) }, 900)
                }
                return
            }
        }
        handler.postDelayed({ stepSearchContact(contact, text, attempt + 1, onResult) }, 1000)
    }

    private fun stepTypeContact(contact: String, text: String, attempt: Int, onResult: (String) -> Unit) {
        if (attempt >= 6) { onResult("Error: search box didn't open"); return }
        val root = rootInActiveWindow ?: run {
            handler.postDelayed({ stepTypeContact(contact, text, attempt + 1, onResult) }, 1200); return
        }
        val searchField = root.findAccessibilityNodeInfosByViewId("android:id/search_src_text")?.firstOrNull()
            ?: findEditable(root)
        if (searchField == null) {
            handler.postDelayed({ stepTypeContact(contact, text, attempt + 1, onResult) }, 1000); return
        }
        setTextOn(searchField, contact)
        handler.postDelayed({ stepOpenChat(contact, text, 0, onResult) }, 1800)
    }

    private fun stepOpenChat(contact: String, text: String, attempt: Int, onResult: (String) -> Unit) {
        if (attempt >= 8) { onResult("Error: contact '$contact' not found in WhatsApp search"); return }
        val root = rootInActiveWindow ?: run {
            handler.postDelayed({ stepOpenChat(contact, text, attempt + 1, onResult) }, 1200); return
        }
        val names = root.findAccessibilityNodeInfosByText(contact)
        val chatRow = names.firstOrNull { it.text?.toString()?.contains(contact, true) == true && hasClickable(it) }
            ?: names.firstOrNull()?.let { findClickableAncestor(listOf(it)) }
        if (chatRow == null) {
            handler.postDelayed({ stepOpenChat(contact, text, attempt + 1, onResult) }, 900); return
        }
        val r = Rect(); chatRow.getBoundsInScreen(r)
        tap(r.centerX(), r.centerY()) {
            handler.postDelayed({ stepTypeAndSend(text, 0, onResult) }, 2000)
        }
    }

    private fun stepTypeAndSend(text: String, attempt: Int, onResult: (String) -> Unit) {
        if (attempt >= 6) { onResult("Error: couldn't find message box"); return }
        val root = rootInActiveWindow ?: run {
            handler.postDelayed({ stepTypeAndSend(text, attempt + 1, onResult) }, 1200); return
        }
        val msgBox = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")?.firstOrNull { it.isEditable }
            ?: findEditable(root)
        if (msgBox == null) {
            handler.postDelayed({ stepTypeAndSend(text, attempt + 1, onResult) }, 1000); return
        }
        setTextOn(msgBox, text)
        handler.postDelayed({ stepPressSend(0, onResult) }, 800)
    }

    private fun stepPressSend(attempt: Int, onResult: (String) -> Unit) {
        if (attempt >= 6) {
            onResult("Typed the message but couldn't find the send button - please press send")
            return
        }
        val root = rootInActiveWindow ?: run {
            handler.postDelayed({ stepPressSend(attempt + 1, onResult) }, 1000); return
        }
        val send = findClickableAncestor(findNodesByContentDesc(root, "Send"))
            ?: root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")?.firstOrNull()
        if (send == null) {
            handler.postDelayed({ stepPressSend(attempt + 1, onResult) }, 800); return
        }
        val r = Rect(); send.getBoundsInScreen(r)
        tap(r.centerX(), r.centerY()) { onResult("Message sent") }
    }

    // ---------- Helpers ----------

    private fun findNodesByContentDesc(root: AccessibilityNodeInfo, vararg words: String): List<AccessibilityNodeInfo> {
        val found = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            if (words.any { desc.contains(it.lowercase()) }) found.add(node)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return found
    }

    private fun hasClickable(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) { if (n.isClickable) return true; n = n.parent }
        return false
    }

    private fun findClickableAncestor(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        for (node in nodes) {
            var n: AccessibilityNodeInfo? = node
            while (n != null) {
                if (n.isClickable) return n
                n = n.parent
            }
        }
        return null
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || result != null) return
            if (node.isEditable) { result = node; return }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return result
    }
}
