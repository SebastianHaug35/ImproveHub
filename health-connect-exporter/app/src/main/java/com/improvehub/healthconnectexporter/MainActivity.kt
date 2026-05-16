package com.improvehub.healthconnectexporter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.firebase.firestore.FirebaseFirestore
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    companion object {
        private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
        private const val FIRESTORE_USER_ID = "default"
        private const val FIRESTORE_COLLECTION = "healthConnectUsers"
    }

    private lateinit var statusText: TextView
    private lateinit var previewText: TextView
    private lateinit var healthConnectClient: HealthConnectClient
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private var latestJson: String = "{}"
    private val zoneId: ZoneId = ZoneId.systemDefault()

    private val permissions =
        setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
        )

    private val requestPermissions =
        registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(HEALTH_CONNECT_PACKAGE)
        ) { granted ->
            if (granted.containsAll(permissions)) {
                setStatus("Permissions granted. Reading Health Connect data...")
                readAndRender()
            } else {
                val missing = permissions.size - granted.intersect(permissions).size
                setStatus("Permissions incomplete. Missing: $missing")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                healthConnectClient = HealthConnectClient.getOrCreate(this, HEALTH_CONNECT_PACKAGE)
                setStatus("Health Connect is available.")
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                setStatus("Health Connect needs to be installed or updated.")
                openHealthConnectInStore()
            }
            else -> {
                setStatus("Health Connect is not available on this device.")
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }

        val title = TextView(this).apply {
            text = "ImproveHUB Health Exporter"
            textSize = 24f
            gravity = Gravity.START
        }
        statusText = TextView(this).apply {
            text = "Starting..."
            textSize = 15f
            setPadding(0, 24, 0, 24)
        }

        val requestButton = Button(this).apply {
            text = "Request Health Connect access"
            setOnClickListener { requestHealthPermissions() }
        }
        val readButton = Button(this).apply {
            text = "Read last 14 days"
            setOnClickListener { readAndRender() }
        }
        val shareButton = Button(this).apply {
            text = "Share JSON export"
            setOnClickListener { shareLatestJson() }
        }
        val uploadButton = Button(this).apply {
            text = "Upload to Firebase"
            setOnClickListener { uploadLatestJsonToFirebase() }
        }
        val settingsButton = Button(this).apply {
            text = "Open Health Connect settings"
            setOnClickListener { openHealthConnectSettings() }
        }
        val permissionsButton = Button(this).apply {
            text = "Open app permissions in Health Connect"
            setOnClickListener { openHealthConnectPermissions() }
        }
        val appButton = Button(this).apply {
            text = "Open Health Connect app"
            setOnClickListener { openHealthConnectApp() }
        }

        previewText = TextView(this).apply {
            text = "No export yet."
            textSize = 13f
            setPadding(0, 24, 0, 0)
            setTextIsSelectable(true)
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(requestButton)
        root.addView(readButton)
        root.addView(uploadButton)
        root.addView(shareButton)
        root.addView(permissionsButton)
        root.addView(appButton)
        root.addView(settingsButton)
        root.addView(previewText)

        val scrollView = ScrollView(this).apply { addView(root) }
        setContentView(scrollView)
    }

    private fun requestHealthPermissions() {
        if (!::healthConnectClient.isInitialized) {
            setStatus("Health Connect client is not ready.")
            return
        }
        lifecycleScope.launch {
            val granted = healthConnectClient.permissionController.getGrantedPermissions()
            if (granted.containsAll(permissions)) {
                setStatus("Permissions already granted. Reading data...")
                readAndRender()
            } else {
                requestPermissions.launch(permissions)
            }
        }
    }

    private fun readAndRender() {
        if (!::healthConnectClient.isInitialized) {
            setStatus("Health Connect client is not ready.")
            return
        }

        lifecycleScope.launch {
            try {
                val end = Instant.now()
                val start = LocalDate.now(zoneId).minusDays(13).atStartOfDay(zoneId).toInstant()
                val export = readHealthExport(start, end)
                latestJson = export.toJson()
                previewText.text = latestJson
                setStatus("Export ready: ${export.days.size} days, ${export.sources.size} source apps.")
            } catch (error: Exception) {
                setStatus("Read failed: ${error.message}")
            }
        }
    }

    private suspend fun readHealthExport(start: Instant, end: Instant): HealthExport {
        val days = linkedMapOf<String, DailyHealth>()

        fun dayKey(instant: Instant): String =
            instant.atZone(zoneId).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun sleepDayKey(endInstant: Instant): String =
            endInstant.minusSeconds(1).atZone(zoneId).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun daily(key: String): DailyHealth = days.getOrPut(key) { DailyHealth(day = key) }

        val sources = linkedSetOf<String>()

        readRecords<StepsRecord>(start, end).forEach { record ->
            val key = dayKey(record.startTime)
            daily(key).steps += record.count
            daily(key).addSource(record)
            sources.addSource(record)
        }

        readRecords<DistanceRecord>(start, end).forEach { record ->
            val key = dayKey(record.startTime)
            daily(key).distanceMeters += record.distance.inMeters
            daily(key).distanceSamples += 1
            daily(key).addSource(record)
            sources.addSource(record)
        }

        readRecords<ActiveCaloriesBurnedRecord>(start, end).forEach { record ->
            val key = dayKey(record.startTime)
            daily(key).activeCaloriesKcal += record.energy.inKilocalories
            daily(key).activeCaloriesSamples += 1
            daily(key).addSource(record)
            sources.addSource(record)
        }

        readRecords<TotalCaloriesBurnedRecord>(start, end).forEach { record ->
            val key = dayKey(record.startTime)
            daily(key).totalCaloriesKcal += record.energy.inKilocalories
            daily(key).totalCaloriesSamples += 1
            daily(key).addSource(record)
            sources.addSource(record)
        }

        readRecords<HeartRateRecord>(start, end).forEach { record ->
            val key = dayKey(record.startTime)
            val values = record.samples.map { it.beatsPerMinute }
            daily(key).heartRateSamples.addAll(values)
            daily(key).heartRateRecordCount += 1
            daily(key).addSource(record)
            sources.addSource(record)
        }

        readRecords<RestingHeartRateRecord>(start, end).forEach { record ->
            val key = dayKey(record.time)
            daily(key).restingHeartRateSamples.add(record.beatsPerMinute)
            daily(key).restingHeartRateRecordCount += 1
            daily(key).addSource(record)
            sources.addSource(record)
        }

        readRecords<HeartRateVariabilityRmssdRecord>(start, end).forEach { record ->
            val key = dayKey(record.time)
            daily(key).hrvRmssdMillisSamples.add(record.heartRateVariabilityMillis)
            daily(key).hrvRecordCount += 1
            daily(key).addSource(record)
            sources.addSource(record)
        }

        readRecords<WeightRecord>(start, end).forEach { record ->
            val key = dayKey(record.time)
            daily(key).weightKgSamples.add(record.weight.inKilograms)
            daily(key).weightRecordCount += 1
            daily(key).addSource(record)
            sources.addSource(record)
        }

        readRecords<SleepSessionRecord>(start, end).forEach { record ->
            val key = sleepDayKey(record.endTime)
            val durationMinutes = (record.endTime.epochSecond - record.startTime.epochSecond) / 60
            daily(key).sleepMinutes += durationMinutes
            daily(key).sleepSessionCount += 1
            daily(key).latestSleepStart = maxOfNullableString(daily(key).latestSleepStart, record.startTime.toString())
            daily(key).latestSleepEnd = maxOfNullableString(daily(key).latestSleepEnd, record.endTime.toString())
            val stageDurations = summarizeSleepStages(record)
            daily(key).sleepAwakeMinutes += stageDurations.awakeMinutes
            daily(key).sleepLightMinutes += stageDurations.lightMinutes
            daily(key).sleepDeepMinutes += stageDurations.deepMinutes
            daily(key).sleepRemMinutes += stageDurations.remMinutes
            daily(key).sleepUnknownMinutes += stageDurations.unknownMinutes
            daily(key).sleepSessions.add(
                SleepSessionDebug(
                    start = record.startTime.toString(),
                    end = record.endTime.toString(),
                    title = record.title,
                    notes = record.notes,
                    stages = stageDurations.stages,
                )
            )
            daily(key).addSource(record)
            sources.addSource(record)
        }

        return HealthExport(
            exportedAt = Instant.now().toString(),
            zoneId = zoneId.id,
            start = start.toString(),
            end = end.toString(),
            sources = sources.sorted(),
            days = days.values.sortedBy { it.day },
        )
    }

    private suspend inline fun <reified T : Record> readRecords(start: Instant, end: Instant): List<T> {
        val response =
            healthConnectClient.readRecords(
                ReadRecordsRequest<T>(
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
        return response.records
    }

private fun MutableSet<String>.addSource(record: Record) {
    add(record.metadata.dataOrigin.packageName)
}

    private fun shareLatestJson() {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "ImproveHUB Health Connect export")
            putExtra(Intent.EXTRA_TEXT, latestJson)
        }
        startActivity(Intent.createChooser(sendIntent, "Share Health Connect JSON"))
    }

    private fun uploadLatestJsonToFirebase() {
        lifecycleScope.launch {
            try {
                val export = parseLatestExport() ?: run {
                    setStatus("No export available yet. Read Health Connect data first.")
                    return@launch
                }
                uploadExportToFirestore(export)
                setStatus("Firebase upload finished without deleting existing data.")
            } catch (error: Exception) {
                setStatus("Firebase upload failed: ${error.message}")
            }
        }
    }

    private fun parseLatestExport(): ParsedExport? {
        if (latestJson.isBlank() || latestJson == "{}") {
            return null
        }
        return ParsedExport.fromJson(latestJson)
    }

    private suspend fun uploadExportToFirestore(export: ParsedExport) {
        val userRef = firestore.collection(FIRESTORE_COLLECTION).document(FIRESTORE_USER_ID)
        val rootPayload =
            hashMapOf(
                "healthConnect" to
                    hashMapOf(
                        "lastExportAt" to export.exportedAt,
                        "lastZoneId" to export.zoneId,
                        "lastRange" to hashMapOf("start" to export.start, "end" to export.end),
                        "sourceApps" to export.sources,
                    )
            )
        userRef.set(rootPayload, com.google.firebase.firestore.SetOptions.merge()).await()

        val importId = export.exportedAt.replace(":", "-")
        val importPayload =
            hashMapOf(
                "exportedAt" to export.exportedAt,
                "zoneId" to export.zoneId,
                "start" to export.start,
                "end" to export.end,
                "sources" to export.sources,
                "daysCount" to export.days.size,
                "raw" to export.rawMap,
            )
        userRef
            .collection("healthConnectImports")
            .document(importId)
            .set(importPayload, com.google.firebase.firestore.SetOptions.merge())
            .await()

        val batch = firestore.batch()
        export.days.forEach { day ->
            val docRef = userRef.collection("healthConnectDaily").document(day.day)
            val dayPayload =
                hashMapOf(
                    "day" to day.day,
                    "provider" to "health_connect",
                    "exportedAt" to export.exportedAt,
                    "zoneId" to export.zoneId,
                    "metrics" to
                        hashMapOf(
                            "steps" to day.steps,
                            "distanceMeters" to day.distanceMeters,
                            "distanceSamples" to day.distanceSamples,
                            "activeCaloriesKcal" to day.activeCaloriesKcal,
                            "activeCaloriesSamples" to day.activeCaloriesSamples,
                            "totalCaloriesKcal" to day.totalCaloriesKcal,
                            "totalCaloriesSamples" to day.totalCaloriesSamples,
                            "sleepMinutes" to day.sleepMinutes,
                            "sleepSessionCount" to day.sleepSessionCount,
                            "latestSleepStart" to day.latestSleepStart,
                            "latestSleepEnd" to day.latestSleepEnd,
                            "sleepAwakeMinutes" to day.sleepAwakeMinutes,
                            "sleepLightMinutes" to day.sleepLightMinutes,
                            "sleepDeepMinutes" to day.sleepDeepMinutes,
                            "sleepRemMinutes" to day.sleepRemMinutes,
                            "sleepUnknownMinutes" to day.sleepUnknownMinutes,
                            "heartRateAvgBpm" to day.heartRateAvgBpm,
                            "heartRateRecordCount" to day.heartRateRecordCount,
                            "restingHeartRateAvgBpm" to day.restingHeartRateAvgBpm,
                            "restingHeartRateRecordCount" to day.restingHeartRateRecordCount,
                            "hrvRmssdAvgMillis" to day.hrvRmssdAvgMillis,
                            "hrvRecordCount" to day.hrvRecordCount,
                            "weightLatestKg" to day.weightLatestKg,
                            "weightRecordCount" to day.weightRecordCount,
                            "sourceApps" to day.sourceApps.sorted(),
                            "sleepSessions" to day.sleepSessions.map { it.toMap() },
                        ),
                    "raw" to day.rawMap,
                )
            batch.set(docRef, dayPayload, com.google.firebase.firestore.SetOptions.merge())
        }
        batch.commit().await()
    }

    private fun openHealthConnectSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:com.google.android.apps.healthdata")
        }
        startActivity(intent)
    }

    private fun openHealthConnectApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(HEALTH_CONNECT_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            openHealthConnectSettings()
        }
    }

    private fun openHealthConnectPermissions() {
        val intent = HealthConnectClient.getHealthConnectManageDataIntent(this, HEALTH_CONNECT_PACKAGE).apply {
            putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
        }
        startActivity(intent)
    }

    private fun openHealthConnectInStore() {
        val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }
}

data class HealthExport(
    val exportedAt: String,
    val zoneId: String,
    val start: String,
    val end: String,
    val sources: List<String>,
    val days: List<DailyHealth>,
) {
    fun toJson(): String =
        buildString {
            append("{\n")
            append("  \"exportedAt\": \"").append(exportedAt).append("\",\n")
            append("  \"zoneId\": \"").append(zoneId).append("\",\n")
            append("  \"start\": \"").append(start).append("\",\n")
            append("  \"end\": \"").append(end).append("\",\n")
            append("  \"sources\": [")
            append(sources.joinToString(", ") { "\"${it.escapeJson()}\"" })
            append("],\n")
            append("  \"days\": [\n")
            append(days.joinToString(",\n") { it.toJson(indent = "    ") })
            append("\n  ]\n")
            append("}\n")
        }
}

data class DailyHealth(
    val day: String,
    var steps: Long = 0,
    var distanceMeters: Double = 0.0,
    var distanceSamples: Int = 0,
    var activeCaloriesKcal: Double = 0.0,
    var activeCaloriesSamples: Int = 0,
    var totalCaloriesKcal: Double = 0.0,
    var totalCaloriesSamples: Int = 0,
    var sleepMinutes: Long = 0,
    var sleepSessionCount: Int = 0,
    var latestSleepStart: String? = null,
    var latestSleepEnd: String? = null,
    var sleepAwakeMinutes: Long = 0,
    var sleepLightMinutes: Long = 0,
    var sleepDeepMinutes: Long = 0,
    var sleepRemMinutes: Long = 0,
    var sleepUnknownMinutes: Long = 0,
    val heartRateSamples: MutableList<Long> = mutableListOf(),
    var heartRateRecordCount: Int = 0,
    val restingHeartRateSamples: MutableList<Long> = mutableListOf(),
    var restingHeartRateRecordCount: Int = 0,
    val hrvRmssdMillisSamples: MutableList<Double> = mutableListOf(),
    var hrvRecordCount: Int = 0,
    val weightKgSamples: MutableList<Double> = mutableListOf(),
    var weightRecordCount: Int = 0,
    val sourceApps: MutableSet<String> = linkedSetOf(),
    val sleepSessions: MutableList<SleepSessionDebug> = mutableListOf(),
) {
    fun addSource(record: Record) {
        sourceApps.add(record.metadata.dataOrigin.packageName)
    }

    fun toJson(indent: String): String {
        val heartAverage = heartRateSamples.averageLongOrNull()
        val restingAverage = restingHeartRateSamples.averageLongOrNull()
        val hrvAverage = hrvRmssdMillisSamples.averageDoubleOrNull()
        val weightLatest = weightKgSamples.lastOrNull()

        return buildString {
            append(indent).append("{\n")
            appendMetric(indent, "day", day, comma = true)
            appendMetric(indent, "steps", steps, comma = true)
            appendMetric(indent, "distanceMeters", distanceMeters.roundForJson(), comma = true)
            appendMetric(indent, "distanceSamples", distanceSamples, comma = true)
            appendMetric(indent, "activeCaloriesKcal", activeCaloriesKcal.roundForJson(), comma = true)
            appendMetric(indent, "activeCaloriesSamples", activeCaloriesSamples, comma = true)
            appendMetric(indent, "totalCaloriesKcal", totalCaloriesKcal.roundForJson(), comma = true)
            appendMetric(indent, "totalCaloriesSamples", totalCaloriesSamples, comma = true)
            appendMetric(indent, "sleepMinutes", sleepMinutes, comma = true)
            appendMetric(indent, "sleepSessionCount", sleepSessionCount, comma = true)
            appendMetric(indent, "latestSleepStart", latestSleepStart, comma = true)
            appendMetric(indent, "latestSleepEnd", latestSleepEnd, comma = true)
            appendMetric(indent, "sleepAwakeMinutes", sleepAwakeMinutes, comma = true)
            appendMetric(indent, "sleepLightMinutes", sleepLightMinutes, comma = true)
            appendMetric(indent, "sleepDeepMinutes", sleepDeepMinutes, comma = true)
            appendMetric(indent, "sleepRemMinutes", sleepRemMinutes, comma = true)
            appendMetric(indent, "sleepUnknownMinutes", sleepUnknownMinutes, comma = true)
            appendMetric(indent, "heartRateAvgBpm", heartAverage?.roundForJson(), comma = true)
            appendMetric(indent, "heartRateRecordCount", heartRateRecordCount, comma = true)
            appendMetric(indent, "restingHeartRateAvgBpm", restingAverage?.roundForJson(), comma = true)
            appendMetric(indent, "restingHeartRateRecordCount", restingHeartRateRecordCount, comma = true)
            appendMetric(indent, "hrvRmssdAvgMillis", hrvAverage?.roundForJson(), comma = true)
            appendMetric(indent, "hrvRecordCount", hrvRecordCount, comma = true)
            appendMetric(indent, "weightLatestKg", weightLatest?.roundForJson(), comma = true)
            appendMetric(indent, "weightRecordCount", weightRecordCount, comma = true)
            appendMetric(indent, "sourceApps", sourceApps.sorted(), comma = true)
            appendMetric(indent, "sleepSessions", sleepSessions.map { it.toMap() }, comma = false)
            append("\n").append(indent).append("}")
        }
    }
}

data class SleepSessionDebug(
    val start: String,
    val end: String,
    val title: String?,
    val notes: String?,
    val stages: List<Map<String, Any?>>,
) {
    fun toMap(): Map<String, Any?> =
        linkedMapOf(
            "start" to start,
            "end" to end,
            "title" to title,
            "notes" to notes,
            "stages" to stages,
        )
}

data class SleepStageSummary(
    val awakeMinutes: Long,
    val lightMinutes: Long,
    val deepMinutes: Long,
    val remMinutes: Long,
    val unknownMinutes: Long,
    val stages: List<Map<String, Any?>>,
)

private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

private fun summarizeSleepStages(record: SleepSessionRecord): SleepStageSummary {
    var awakeMinutes = 0L
    var lightMinutes = 0L
    var deepMinutes = 0L
    var remMinutes = 0L
    var unknownMinutes = 0L

    val stageMaps =
        record.stages.map { stage ->
            val minutes = (stage.endTime.epochSecond - stage.startTime.epochSecond) / 60
            when (stage.stage) {
                SleepSessionRecord.STAGE_TYPE_AWAKE,
                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> awakeMinutes += minutes
                SleepSessionRecord.STAGE_TYPE_LIGHT -> lightMinutes += minutes
                SleepSessionRecord.STAGE_TYPE_DEEP -> deepMinutes += minutes
                SleepSessionRecord.STAGE_TYPE_REM -> remMinutes += minutes
                else -> unknownMinutes += minutes
            }
            linkedMapOf(
                "start" to stage.startTime.toString(),
                "end" to stage.endTime.toString(),
                "stage" to sleepStageLabel(stage.stage),
                "minutes" to minutes,
            )
        }

    return SleepStageSummary(
        awakeMinutes = awakeMinutes,
        lightMinutes = lightMinutes,
        deepMinutes = deepMinutes,
        remMinutes = remMinutes,
        unknownMinutes = unknownMinutes,
        stages = stageMaps,
    )
}

private fun sleepStageLabel(stage: Int): String =
    when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
        else -> "unknown"
    }

private fun List<Long>.averageLongOrNull(): Double? =
    if (isEmpty()) null else average()

private fun List<Double>.averageDoubleOrNull(): Double? =
    if (isEmpty()) null else average()

private fun Double.roundForJson(): Double =
    kotlin.math.round(this * 100.0) / 100.0

private fun StringBuilder.appendMetric(indent: String, name: String, value: Any?, comma: Boolean) {
    append(indent).append("  \"").append(name).append("\": ")
    when (value) {
        null -> append("null")
        is String -> append("\"").append(value.escapeJson()).append("\"")
        is List<*> -> {
            append("[")
            append(value.joinToString(", ") { item ->
                jsonValue(item)
            })
            append("]")
        }
        is Map<*, *> -> append(jsonValue(value))
        else -> append(value)
    }
    if (comma) append(",")
    append("\n")
}

private fun jsonValue(value: Any?): String =
    when (value) {
        null -> "null"
        is String -> "\"${value.escapeJson()}\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> ->
            value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
                "\"${key.toString().escapeJson()}\": ${jsonValue(item)}"
            }
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { item -> jsonValue(item) }
        else -> "\"${value.toString().escapeJson()}\""
    }

data class ParsedExport(
    val exportedAt: String,
    val zoneId: String,
    val start: String,
    val end: String,
    val sources: List<String>,
    val days: List<ParsedDay>,
    val rawMap: Map<String, Any?>,
) {
    companion object {
        fun fromJson(json: String): ParsedExport {
            val root = org.json.JSONObject(json)
            val sources = mutableListOf<String>()
            val sourceArray = root.optJSONArray("sources")
            if (sourceArray != null) {
                for (index in 0 until sourceArray.length()) {
                    sources.add(sourceArray.getString(index))
                }
            }

            val days = mutableListOf<ParsedDay>()
            val daysArray = root.optJSONArray("days")
            if (daysArray != null) {
                for (index in 0 until daysArray.length()) {
                    val item = daysArray.getJSONObject(index)
                    days.add(
                        ParsedDay(
                            day = item.getString("day"),
                            steps = item.optLong("steps"),
                            distanceMeters = item.optDoubleOrNull("distanceMeters"),
                            distanceSamples = item.optInt("distanceSamples"),
                            activeCaloriesKcal = item.optDoubleOrNull("activeCaloriesKcal"),
                            activeCaloriesSamples = item.optInt("activeCaloriesSamples"),
                            totalCaloriesKcal = item.optDoubleOrNull("totalCaloriesKcal"),
                            totalCaloriesSamples = item.optInt("totalCaloriesSamples"),
                            sleepMinutes = item.optLong("sleepMinutes"),
                            sleepSessionCount = item.optInt("sleepSessionCount"),
                            latestSleepStart = item.optStringOrNull("latestSleepStart"),
                            latestSleepEnd = item.optStringOrNull("latestSleepEnd"),
                            sleepAwakeMinutes = item.optLong("sleepAwakeMinutes"),
                            sleepLightMinutes = item.optLong("sleepLightMinutes"),
                            sleepDeepMinutes = item.optLong("sleepDeepMinutes"),
                            sleepRemMinutes = item.optLong("sleepRemMinutes"),
                            sleepUnknownMinutes = item.optLong("sleepUnknownMinutes"),
                            heartRateAvgBpm = item.optDoubleOrNull("heartRateAvgBpm"),
                            heartRateRecordCount = item.optInt("heartRateRecordCount"),
                            restingHeartRateAvgBpm = item.optDoubleOrNull("restingHeartRateAvgBpm"),
                            restingHeartRateRecordCount = item.optInt("restingHeartRateRecordCount"),
                            hrvRmssdAvgMillis = item.optDoubleOrNull("hrvRmssdAvgMillis"),
                            hrvRecordCount = item.optInt("hrvRecordCount"),
                            weightLatestKg = item.optDoubleOrNull("weightLatestKg"),
                            weightRecordCount = item.optInt("weightRecordCount"),
                            sourceApps = item.optStringList("sourceApps"),
                            sleepSessions = item.optMapList("sleepSessions"),
                            rawMap = item.toMap(),
                        )
                    )
                }
            }

            return ParsedExport(
                exportedAt = root.getString("exportedAt"),
                zoneId = root.getString("zoneId"),
                start = root.getString("start"),
                end = root.getString("end"),
                sources = sources,
                days = days,
                rawMap = root.toMap(),
            )
        }
    }
}

data class ParsedDay(
    val day: String,
    val steps: Long,
    val distanceMeters: Double?,
    val distanceSamples: Int,
    val activeCaloriesKcal: Double?,
    val activeCaloriesSamples: Int,
    val totalCaloriesKcal: Double?,
    val totalCaloriesSamples: Int,
    val sleepMinutes: Long,
    val sleepSessionCount: Int,
    val latestSleepStart: String?,
    val latestSleepEnd: String?,
    val sleepAwakeMinutes: Long,
    val sleepLightMinutes: Long,
    val sleepDeepMinutes: Long,
    val sleepRemMinutes: Long,
    val sleepUnknownMinutes: Long,
    val heartRateAvgBpm: Double?,
    val heartRateRecordCount: Int,
    val restingHeartRateAvgBpm: Double?,
    val restingHeartRateRecordCount: Int,
    val hrvRmssdAvgMillis: Double?,
    val hrvRecordCount: Int,
    val weightLatestKg: Double?,
    val weightRecordCount: Int,
    val sourceApps: List<String>,
    val sleepSessions: List<Map<String, Any?>>,
    val rawMap: Map<String, Any?>,
)

private fun org.json.JSONObject.toMap(): Map<String, Any?> {
    val map = linkedMapOf<String, Any?>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        map[key] = valueToAny(opt(key))
    }
    return map
}

private fun org.json.JSONArray.toList(): List<Any?> {
    val list = mutableListOf<Any?>()
    for (index in 0 until length()) {
        list.add(valueToAny(opt(index)))
    }
    return list
}

private fun valueToAny(value: Any?): Any? =
    when (value) {
        null, org.json.JSONObject.NULL -> null
        is org.json.JSONObject -> value.toMap()
        is org.json.JSONArray -> value.toList()
        else -> value
    }

private fun org.json.JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key)

private fun org.json.JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, "")

private fun org.json.JSONObject.optStringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return List(array.length()) { index -> array.optString(index) }
}

private fun org.json.JSONObject.optMapList(key: String): List<Map<String, Any?>> {
    val array = optJSONArray(key) ?: return emptyList()
    return List(array.length()) { index ->
        val item = array.optJSONObject(index)
        item?.toMap() ?: emptyMap()
    }
}

private fun maxOfNullableString(current: String?, candidate: String): String =
    if (current == null || candidate > current) candidate else current
