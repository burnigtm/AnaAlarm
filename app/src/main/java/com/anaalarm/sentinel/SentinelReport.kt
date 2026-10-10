package com.anaalarm.sentinel

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.anaalarm.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

internal const val APK_DOWNLOAD_URL = "https://github.com/burnigtm/AnaAlarm/releases/latest/download/AnaAlarm-internal.apk"

@Serializable
internal data class SentinelReport(
    val schemaVersion: Int = 1,
    val generatedAt: Long = System.currentTimeMillis(),
    val applicationId: String = BuildConfig.APPLICATION_ID,
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val androidApi: Int = Build.VERSION.SDK_INT,
    val manufacturer: String = Build.MANUFACTURER.take(100),
    val model: String = Build.MODEL.take(100),
    val health: HealthSnapshot,
    val events: List<SentinelEvent>
)

internal fun interface SentinelReportExporter {
    suspend fun shareIntent(report: SentinelReport): Intent
}

internal class AndroidSentinelReportExporter(private val context: Context) : SentinelReportExporter {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    override suspend fun shareIntent(report: SentinelReport): Intent = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "sentinel-reports").apply { mkdirs() }
        // Keep yesterday's attachments readable for receivers, then expire cached copies.
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }
            ?.forEach { it.delete() }
        val file = File.createTempFile("anaalarm-sentinel-", ".json", directory)
        file.writeText(json.encodeToString(report))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.sentinel.files", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Sentinel", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
