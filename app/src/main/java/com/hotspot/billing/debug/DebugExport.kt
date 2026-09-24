package com.hotspot.billing.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gets the log out of the phone: clipboard, share sheet (WhatsApp/Telegram), or a
 * plain .txt file the operator can keep.
 *
 * Big reports are written to a file and shared as a *file*, because an
 * `ACTION_SEND` text extra over a few hundred KB can be dropped by the receiving
 * app and the clipboard is not a place for 300 KB either.
 */
object DebugExport {

    const val AUTHORITY = "com.hotspot.billing.fileprovider"
    private const val MAX_CLIPBOARD_CHARS = 200_000
    private const val MAX_SHARE_TEXT_CHARS = 100_000

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Writes [text] to app-private external storage (no permission needed). */
    fun saveToFile(context: Context, text: String, prefix: String = "RNS-debug"): File? {
        val name = "$prefix-${synchronized(this) { stamp.format(Date()) }}.txt"
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return try {
            File(dir, name).apply { writeText(text) }
        } catch (e: Exception) {
            AppLog.e(AppLog.TAG_SERVICE, "could not write $name: ${e.message}")
            null
        }
    }

    /** Copies to the clipboard, trimming if the text is huge. Returns chars copied. */
    fun copyToClipboard(context: Context, text: String, label: String = "RNS debug log"): Int {
        val clipped = if (text.length > MAX_CLIPBOARD_CHARS) {
            text.takeLast(MAX_CLIPBOARD_CHARS)
                .let { "...(older lines trimmed to fit the clipboard)...\n$it" }
        } else {
            text
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, clipped))
        return clipped.length
    }

    /**
     * Share sheet. Text up to [MAX_SHARE_TEXT_CHARS] goes inline (so a chat app
     * can paste it straight in); anything bigger is attached as a file.
     */
    fun share(context: Context, text: String, subject: String = "RNS hotspot debug report") {
        if (text.length <= MAX_SHARE_TEXT_CHARS) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(intent, subject).newTask(context))
            return
        }
        val file = saveToFile(context, text, "RNS-report")
        if (file == null) {
            // Last resort: share the tail as text so the operator is never stuck.
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(
                    Intent.EXTRA_TEXT,
                    "...(report too large to attach, sharing the last part)...\n" +
                        text.takeLast(MAX_SHARE_TEXT_CHARS)
                )
            }
            context.startActivity(Intent.createChooser(intent, subject).newTask(context))
            return
        }
        shareFile(context, file, subject)
    }

    fun shareFile(context: Context, file: File, subject: String = "RNS hotspot debug report") {
        try {
            val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, subject).newTask(context))
        } catch (e: Exception) {
            AppLog.e(AppLog.TAG_SERVICE, "sharing ${file.name} failed: ${e.message}")
        }
    }

    /** `FLAG_ACTIVITY_NEW_TASK` is required when starting from a Service context. */
    private fun Intent.newTask(context: Context): Intent = apply {
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
