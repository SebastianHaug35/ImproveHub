package com.improvehub.healthconnectexporter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await

object AutoSyncScheduler {
    private const val AUTO_SYNC_PERIODIC_WORK = "improvehub-health-auto-sync"
    private const val AUTO_SYNC_IMMEDIATE_WORK = "improvehub-health-auto-sync-immediate"

    fun schedule(context: Context, runImmediately: Boolean = false) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val periodicRequest =
            PeriodicWorkRequestBuilder<HealthAutoSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            AUTO_SYNC_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest,
        )

        if (runImmediately) {
            val immediateRequest =
                OneTimeWorkRequestBuilder<HealthAutoSyncWorker>()
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                AUTO_SYNC_IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                immediateRequest,
            )
        }
    }
}

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AutoSyncScheduler.schedule(context, runImmediately = true)
        }
    }
}

class HealthAutoSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    companion object {
        private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
        private const val FIRESTORE_COLLECTION = "healthConnectUsers"
        private const val LEGACY_FALLBACK_USER_ID = "default"
        private const val HEART_RATE_WINDOW_DAYS = 30L
        private const val GENERAL_WINDOW_DAYS = 14L
        private const val HEART_RATE_CHUNK_SIZE = 500
        private const val FIRESTORE_MAX_BATCH_WRITES = 450

        private const val PREFS_NAME = "improvehub_auto_sync"
        private const val PREF_DAILY_SYNC_END = "daily_sync_end"
        private const val PREF_HEART_RATE_LAST_SAMPLE = "heart_rate_last_sample"

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
    }

    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }

    override suspend fun doWork(): Result {
        return try {
            if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
                return Result.success()
            }

            val client = HealthConnectClient.getOrCreate(applicationContext, HEALTH_CONNECT_PACKAGE)
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(permissions)) {
                return Result.success()
            }

            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val userId = resolveUploadUserId()
            val now = Instant.now()

            val previousDailyEnd = prefs.getString(PREF_DAILY_SYNC_END, null).toInstantOrNull()
            val dailyStart =
                previousDailyEnd?.minusSeconds(24L * 60L * 60L)
                    ?: LocalDate.of(2000, 1, 1).atStartOfDay(zoneId).toInstant()

            val dailyExport = readDailyExport(client, dailyStart, now)
            uploadDailyData(userId, dailyExport)

            val previousHeartRateSample = prefs.getString(PREF_HEART_RATE_LAST_SAMPLE, null).toInstantOrNull()
            val heartRateStart =
                previousHeartRateSample?.minusSeconds(5L * 60L)
                    ?: LocalDate.of(2000, 1, 1).atStartOfDay(zoneId).toInstant()

            val heartRateDelta = readHeartRateDelta(client, heartRateStart, now, previousHeartRateSample)
            if (heartRateDelta.points.isNotEmpty()) {
                uploadHeartRateDelta(userId, heartRateDelta)
            }

            val summaryRef =
                firestore.collection(FIRESTORE_COLLECTION)
                    .document(userId)
                    .collection("data")
                    .document("summary")

            summaryRef.set(
                hashMapOf(
                    "provider" to "health_connect",
                    "lastAutoSyncAt" to now.toString(),
                    "zoneId" to zoneId.id,
                    "dailyRange" to hashMapOf("start" to dailyExport.start, "end" to dailyExport.end),
                    "dailyDaysCount" to dailyExport.days.size,
                    "heartRateNewSamples" to heartRateDelta.points.size,
                    "sourceApps" to (dailyExport.sources + heartRateDelta.sources).distinct().sorted(),
                ),
                com.google.firebase.firestore.SetOptions.merge(),
            ).await()

            prefs.edit()
                .putString(PREF_DAILY_SYNC_END, now.toString())
                .putString(PREF_HEART_RATE_LAST_SAMPLE, heartRateDelta.newLastSampleTime?.toString() ?: previousHeartRateSample?.toString())
                .apply()

            Result.success()
        } catch (error: Exception) {
            Result.retry()
        }
    }

    private suspend fun resolveUploadUserId(): String {
        val currentUser = firebaseAuth.currentUser
        if (currentUser != null) {
            return currentUser.uid
        }

        return try {
            val result = firebaseAuth.signInAnonymously().await()
            result.user?.uid ?: LEGACY_FALLBACK_USER_ID
        } catch (_: Exception) {
            LEGACY_FALLBACK_USER_ID
        }
    }

    private suspend fun readDailyExport(client: HealthConnectClient, start: Instant, end: Instant): AutoDailyExport {
        val days = linkedMapOf<String, AutoDaily>()
        val sources = linkedSetOf<String>()

        fun dayKey(instant: Instant): String =
            instant.atZone(zoneId).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun sleepDayKey(endInstant: Instant): String =
            endInstant.minusSeconds(1).atZone(zoneId).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun daily(key: String): AutoDaily = days.getOrPut(key) { AutoDaily(day = key) }

        var cursor = start
        val windowSeconds = GENERAL_WINDOW_DAYS * 24L * 60L * 60L

        while (cursor < end) {
            val windowEnd = minOf(cursor.plusSeconds(windowSeconds), end)

            readRecords<StepsRecord>(client, cursor, windowEnd).forEach { record ->
                val key = dayKey(record.startTime)
                daily(key).steps += record.count
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecords<DistanceRecord>(client, cursor, windowEnd).forEach { record ->
                val key = dayKey(record.startTime)
                daily(key).distanceMeters += record.distance.inMeters
                daily(key).distanceSamples += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecords<ActiveCaloriesBurnedRecord>(client, cursor, windowEnd).forEach { record ->
                val key = dayKey(record.startTime)
                daily(key).activeCaloriesKcal += record.energy.inKilocalories
                daily(key).activeCaloriesSamples += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecords<TotalCaloriesBurnedRecord>(client, cursor, windowEnd).forEach { record ->
                val key = dayKey(record.startTime)
                daily(key).totalCaloriesKcal += record.energy.inKilocalories
                daily(key).totalCaloriesSamples += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecords<HeartRateRecord>(client, cursor, windowEnd).forEach { record ->
                val key = dayKey(record.startTime)
                daily(key).heartRateSamples.addAll(record.samples.map { it.beatsPerMinute })
                daily(key).heartRateRecordCount += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecords<RestingHeartRateRecord>(client, cursor, windowEnd).forEach { record ->
                val key = dayKey(record.time)
                daily(key).restingHeartRateSamples.add(record.beatsPerMinute)
                daily(key).restingHeartRateRecordCount += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecords<HeartRateVariabilityRmssdRecord>(client, cursor, windowEnd).forEach { record ->
                val key = dayKey(record.time)
                daily(key).hrvRmssdMillisSamples.add(record.heartRateVariabilityMillis)
                daily(key).hrvRecordCount += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecords<WeightRecord>(client, cursor, windowEnd).forEach { record ->
                val key = dayKey(record.time)
                daily(key).weightKgSamples.add(record.weight.inKilograms)
                daily(key).weightRecordCount += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecords<SleepSessionRecord>(client, cursor, windowEnd).forEach { record ->
                val key = sleepDayKey(record.endTime)
                val durationMinutes = (record.endTime.epochSecond - record.startTime.epochSecond) / 60
                val day = daily(key)
                day.sleepMinutes += durationMinutes
                day.sleepSessionCount += 1
                day.latestSleepStart = maxOfNullableString(day.latestSleepStart, record.startTime.toString())
                day.latestSleepEnd = maxOfNullableString(day.latestSleepEnd, record.endTime.toString())
                val stageDurations = summarizeStages(record)
                day.sleepAwakeMinutes += stageDurations.awakeMinutes
                day.sleepLightMinutes += stageDurations.lightMinutes
                day.sleepDeepMinutes += stageDurations.deepMinutes
                day.sleepRemMinutes += stageDurations.remMinutes
                day.sleepUnknownMinutes += stageDurations.unknownMinutes
                day.sleepSessions.add(
                    linkedMapOf(
                        "start" to record.startTime.toString(),
                        "end" to record.endTime.toString(),
                        "title" to record.title,
                        "notes" to record.notes,
                        "stages" to stageDurations.stageMaps,
                    )
                )
                day.sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            if (windowEnd == end) {
                break
            }
            cursor = windowEnd.plusMillis(1)
        }

        return AutoDailyExport(
            exportedAt = Instant.now().toString(),
            zoneId = zoneId.id,
            start = start.toString(),
            end = end.toString(),
            sources = sources.sorted(),
            days = days.values.sortedBy { it.day },
        )
    }

    private suspend fun uploadDailyData(userId: String, export: AutoDailyExport) {
        val userRef = firestore.collection(FIRESTORE_COLLECTION).document(userId)
        val summaryRef = userRef.collection("data").document("summary")

        summaryRef.set(
            hashMapOf(
                "provider" to "health_connect",
                "lastDailyExportAt" to export.exportedAt,
                "lastDailyRange" to hashMapOf("start" to export.start, "end" to export.end),
                "sourceApps" to export.sources,
            ),
            com.google.firebase.firestore.SetOptions.merge(),
        ).await()

        val batch = firestore.batch()
        export.days.forEach { day ->
            val dayRef = userRef.collection("data").document("daily").collection("entries").document(day.day)
            batch.set(
                dayRef,
                hashMapOf(
                    "day" to day.day,
                    "provider" to "health_connect",
                    "exportedAt" to export.exportedAt,
                    "zoneId" to export.zoneId,
                    "metrics" to day.metricsMap(),
                ),
                com.google.firebase.firestore.SetOptions.merge(),
            )
        }
        batch.commit().await()
    }

    private suspend fun readHeartRateDelta(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        previousLastSample: Instant?,
    ): AutoHeartRateDelta {
        val points = mutableListOf<HeartRatePoint>()
        val sources = linkedSetOf<String>()
        val windowSeconds = HEART_RATE_WINDOW_DAYS * 24L * 60L * 60L
        var cursor = start
        var newestSample: Instant? = previousLastSample

        while (cursor < end) {
            val windowEnd = minOf(cursor.plusSeconds(windowSeconds), end)
            val records = readRecords<HeartRateRecord>(client, cursor, windowEnd)
            records.forEach { record ->
                val source = record.metadata.dataOrigin.packageName
                sources.add(source)
                record.samples.forEach { sample ->
                    val sampleTime = sample.time
                    if (previousLastSample == null || sampleTime > previousLastSample) {
                        points.add(
                            HeartRatePoint(
                                time = sampleTime.toString(),
                                bpm = sample.beatsPerMinute,
                                sourceApp = source,
                            )
                        )
                    }
                    if (newestSample == null || sampleTime > newestSample) {
                        newestSample = sampleTime
                    }
                }
            }

            if (windowEnd == end) {
                break
            }
            cursor = windowEnd.plusMillis(1)
        }

        return AutoHeartRateDelta(
            exportedAt = Instant.now().toString(),
            zoneId = zoneId.id,
            start = start.toString(),
            end = end.toString(),
            sources = sources.sorted(),
            points = points.sortedBy { it.time },
            newLastSampleTime = newestSample,
        )
    }

    private suspend fun uploadHeartRateDelta(userId: String, delta: AutoHeartRateDelta) {
        val userRef = firestore.collection(FIRESTORE_COLLECTION).document(userId)
        val importId = delta.exportedAt.replace(":", "-")
        val importRef =
            userRef.collection("data")
                .document("heartRate")
                .collection("imports")
                .document(importId)

        importRef.set(
            hashMapOf(
                "exportedAt" to delta.exportedAt,
                "zoneId" to delta.zoneId,
                "start" to delta.start,
                "end" to delta.end,
                "sources" to delta.sources,
                "newSamples" to delta.points.size,
                "chunkSize" to HEART_RATE_CHUNK_SIZE,
                "lastSampleTime" to delta.newLastSampleTime?.toString(),
            ),
            com.google.firebase.firestore.SetOptions.merge(),
        ).await()

        val chunks = delta.points.chunked(HEART_RATE_CHUNK_SIZE)
        var batch = firestore.batch()
        var pendingWrites = 0

        chunks.forEachIndexed { index, chunk ->
            val sampleRef = importRef.collection("samples").document(index.toString().padStart(6, '0'))
            batch.set(
                sampleRef,
                hashMapOf(
                    "index" to index,
                    "count" to chunk.size,
                    "startTime" to chunk.firstOrNull()?.time,
                    "endTime" to chunk.lastOrNull()?.time,
                    "samples" to chunk.map { it.toMap() },
                ),
                com.google.firebase.firestore.SetOptions.merge(),
            )
            pendingWrites += 1
            if (pendingWrites >= FIRESTORE_MAX_BATCH_WRITES) {
                batch.commit().await()
                batch = firestore.batch()
                pendingWrites = 0
            }
        }

        if (pendingWrites > 0) {
            batch.commit().await()
        }
    }

    private suspend inline fun <reified T : Record> readRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): List<T> {
        val response =
            client.readRecords(
                ReadRecordsRequest<T>(
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
        return response.records
    }
}

data class AutoDailyExport(
    val exportedAt: String,
    val zoneId: String,
    val start: String,
    val end: String,
    val sources: List<String>,
    val days: List<AutoDaily>,
)

data class AutoHeartRateDelta(
    val exportedAt: String,
    val zoneId: String,
    val start: String,
    val end: String,
    val sources: List<String>,
    val points: List<HeartRatePoint>,
    val newLastSampleTime: Instant?,
)

data class AutoDaily(
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
    val sleepSessions: MutableList<Map<String, Any?>> = mutableListOf(),
) {
    fun metricsMap(): Map<String, Any?> =
        hashMapOf(
            "steps" to steps,
            "distanceMeters" to roundForJson(distanceMeters),
            "distanceSamples" to distanceSamples,
            "activeCaloriesKcal" to roundForJson(activeCaloriesKcal),
            "activeCaloriesSamples" to activeCaloriesSamples,
            "totalCaloriesKcal" to roundForJson(totalCaloriesKcal),
            "totalCaloriesSamples" to totalCaloriesSamples,
            "sleepMinutes" to sleepMinutes,
            "sleepSessionCount" to sleepSessionCount,
            "latestSleepStart" to latestSleepStart,
            "latestSleepEnd" to latestSleepEnd,
            "sleepAwakeMinutes" to sleepAwakeMinutes,
            "sleepLightMinutes" to sleepLightMinutes,
            "sleepDeepMinutes" to sleepDeepMinutes,
            "sleepRemMinutes" to sleepRemMinutes,
            "sleepUnknownMinutes" to sleepUnknownMinutes,
            "heartRateAvgBpm" to averageLongValues(heartRateSamples),
            "heartRateRecordCount" to heartRateRecordCount,
            "restingHeartRateAvgBpm" to averageLongValues(restingHeartRateSamples),
            "restingHeartRateRecordCount" to restingHeartRateRecordCount,
            "hrvRmssdAvgMillis" to averageDoubleValues(hrvRmssdMillisSamples),
            "hrvRecordCount" to hrvRecordCount,
            "weightLatestKg" to weightKgSamples.lastOrNull(),
            "weightRecordCount" to weightRecordCount,
            "sourceApps" to sourceApps.sorted(),
            "sleepSessions" to sleepSessions,
        )
}

data class AutoSleepStageSummary(
    val awakeMinutes: Long,
    val lightMinutes: Long,
    val deepMinutes: Long,
    val remMinutes: Long,
    val unknownMinutes: Long,
    val stageMaps: List<Map<String, Any?>>,
)

private fun summarizeStages(record: SleepSessionRecord): AutoSleepStageSummary {
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
                "stage" to stage.stage,
                "minutes" to minutes,
            )
        }

    return AutoSleepStageSummary(
        awakeMinutes = awakeMinutes,
        lightMinutes = lightMinutes,
        deepMinutes = deepMinutes,
        remMinutes = remMinutes,
        unknownMinutes = unknownMinutes,
        stageMaps = stageMaps,
    )
}

private fun roundForJson(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

private fun averageLongValues(values: List<Long>): Double? = if (values.isEmpty()) null else values.average()

private fun averageDoubleValues(values: List<Double>): Double? = if (values.isEmpty()) null else values.average()

private fun maxOfNullableString(current: String?, candidate: String): String =
    if (current == null || candidate > current) candidate else current

private fun String?.toInstantOrNull(): Instant? =
    if (this.isNullOrBlank()) {
        null
    } else {
        runCatching { Instant.parse(this) }.getOrNull()
    }
