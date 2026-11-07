package com.ynk.virtualdisplay.ui.feedback

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.ynk.virtualdisplay.BuildConfig
import com.ynk.virtualdisplay.ui.data.PetEvidenceRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The two ways a zip leaves the phone. Neither needs network access in this app: QQ sends the shared
 * file, and the web form runs in the phone's browser, which can only pick the zip from Downloads.
 */
class FeedbackChannels(private val context: Context) {

    val qqGroup: String get() = BuildConfig.FEEDBACK_QQ_GROUP
    val formUrl: String get() = BuildConfig.FEEDBACK_FORM_URL

    /** Blocking file work; call it from a background dispatcher. */
    fun buildZip(description: String, includeScreenshots: Boolean): File {
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        val directory = File(context.cacheDir, "feedback").apply {
            deleteRecursively()
            mkdirs()
        }
        val report = FeedbackReport(
            description = description,
            appVersion = BuildConfig.VERSION_NAME,
            gitHash = BuildConfig.GIT_HASH,
            device = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            qqVersion = qqVersion(),
            createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now),
        )
        val log = File(context.filesDir, "pet_mvp.log").takeIf { it.isFile }?.readLines() ?: emptyList()
        val sessions = PetEvidenceRepository(context).sessionDirectories(limit = FeedbackBundle.MAX_SESSIONS)
        return File(directory, "qqpet-feedback-$stamp.zip").also { zip ->
            zip.outputStream().use { FeedbackBundle.write(it, report, log, sessions, includeScreenshots) }
        }
    }

    /** Opens the system share sheet; the tester picks QQ and then the group. */
    fun shareToQq(zip: File, description: String) {
        copyGroupNumber()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.feedback", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, description)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "发送反馈包").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Saves the zip to Downloads only, for exporting logs without sending them. Returns the file name. */
    fun exportToDownloads(zip: File): String {
        saveToDownloads(zip)
        return zip.name
    }

    /** Puts the zip in Downloads, then opens the form. Returns the name shown in the file picker. */
    fun openWebForm(zip: File): String {
        saveToDownloads(zip)
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(formUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return zip.name
    }

    private fun copyGroupNumber() {
        if (qqGroup.isBlank()) return
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("内测群号", qqGroup))
    }

    private fun saveToDownloads(zip: File) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, zip.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        checkNotNull(resolver.openOutputStream(uri)).use { out -> zip.inputStream().use { it.copyTo(out) } }
    }

    private fun qqVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo("com.tencent.mobileqq", 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("未安装")
}
