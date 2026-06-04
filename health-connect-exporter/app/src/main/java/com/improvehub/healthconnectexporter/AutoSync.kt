package com.improvehub.healthconnectexporter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await

object AutoSyncScheduler {
    const val AUTO_SYNC_PERIODIC_WORK = "improvehub-health-auto-sync"
    const val AUTO_SYNC_IMMEDIATE_WORK = "improvehub-health-auto-sync-immediate"

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
            enqueueImmediate(context)
        }
    }

    fun enqueueImmediate(context: Context): UUID {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val immediateRequest =
            OneTimeWorkRequestBuilder<HealthAutoSyncWorker>()
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            AUTO_SYNC_IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            immediateRequest,
        )
        return immediateRequest.id
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
        private const val PREF_FULL_BACKFILL_COMPLETED = "full_backfill_completed"
        private const val PREF_SYNC_IN_PROGRESS = "sync_in_progress"
        private const val PREF_SYNC_STARTED_AT_EPOCH = "sync_started_at_epoch"
        private const val PREF_SYNC_LOG = "sync_log"
        private const val LOG_LINE_LIMIT = 300
        private const val SYNC_LOCK_STALE_SECONDS = 2L * 60L * 60L
        private const val RATE_LIMIT_ERROR_TOKEN = "Rate limited request quota has been exceeded"

        private const val NOTIFICATION_CHANNEL_ID = "improvehub_sync"
        private const val NOTIFICATION_ID = 7301
        private const val LOG_TAG = "ImproveHUBSync"

        const val PROGRESS_PERCENT_KEY = "progress_percent"
        const val PROGRESS_STAGE_KEY = "progress_stage"
        const val PROGRESS_DETAILS_KEY = "progress_details"

        const val OUTPUT_DAYS_KEY = "synced_days"
        const val OUTPUT_HEART_RATE_SAMPLES_KEY = "synced_heart_rate_samples"
        const val OUTPUT_ERROR_KEY = "sync_error"

        private val permissions =
            setOf(
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(DistanceRecord::class),
                HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
                HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
                HealthPermission.getReadPermission(FloorsClimbedRecord::class),
                HealthPermission.getReadPermission(HydrationRecord::class),
                HealthPermission.getReadPermission(HeightRecord::class),
                HealthPermission.getReadPermission(OxygenSaturationRecord::class),
                HealthPermission.getReadPermission(RespiratoryRateRecord::class),
                HealthPermission.getReadPermission(ExerciseSessionRecord::class),
                HealthPermission.getReadPermission(RestingHeartRateRecord::class),
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getReadPermission(WheelchairPushesRecord::class),
                HealthPermission.getReadPermission(WeightRecord::class),
            )
    }

    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val prefs by lazy { applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val fullHistoryStart: Instant = Instant.EPOCH
    @Volatile
    private var sawRateLimitError: Boolean = false

    override suspend fun doWork(): Result {
        if (!acquireSyncLock()) {
            appendLog("Another sync is already running; skipping duplicate worker")
            return Result.success()
        }

        clearSyncLog()
        sawRateLimitError = false
        var activeUserId: String? = null

        return try {
            updateStatus(5, "Preparing sync", "Starting worker")
            if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
                appendLog("Health Connect SDK unavailable; skipping")
                return Result.success()
            }

            val client = HealthConnectClient.getOrCreate(applicationContext, HEALTH_CONNECT_PACKAGE)
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(permissions)) {
                appendLog("Missing Health Connect permissions; skipping background sync")
                return Result.success()
            }

            val userId = resolveUploadUserId()
            activeUserId = userId
            appendLog("Using Firestore user id: $userId")

            val now = Instant.now()
            val runFullBackfill = !prefs.getBoolean(PREF_FULL_BACKFILL_COMPLETED, false)
            if (runFullBackfill) {
                appendLog("Full history backfill mode enabled")
            }

            val previousDailyEnd =
                prefs.getString(PREF_DAILY_SYNC_END, null).toInstantOrNull()
                    ?: readPreviousDailyEndFromFirestore(userId)
            val dailyStart =
                if (runFullBackfill) {
                    fullHistoryStart
                } else {
                    previousDailyEnd?.minusSeconds(24L * 60L * 60L) ?: fullHistoryStart
                }

            updateStatus(15, "Reading daily health data", "Range: $dailyStart -> $now")
            val dailyExport = readDailyExport(client, dailyStart, now)
            updateStatus(50, "Uploading daily health data", "Days: ${dailyExport.days.size}")
            uploadDailyData(userId, dailyExport)

            val previousHeartRateSample =
                prefs.getString(PREF_HEART_RATE_LAST_SAMPLE, null).toInstantOrNull()
                    ?: readPreviousHeartRateFromFirestore(userId)
            val heartRateBaseline = if (runFullBackfill) null else previousHeartRateSample
            val heartRateStart =
                if (runFullBackfill) {
                    fullHistoryStart
                } else {
                    previousHeartRateSample?.minusSeconds(5L * 60L) ?: fullHistoryStart
                }

            updateStatus(65, "Reading heart-rate samples", "Range: $heartRateStart -> $now")
            val heartRateDelta = readHeartRateDelta(client, heartRateStart, now, heartRateBaseline)

            if (heartRateDelta.points.isNotEmpty()) {
                updateStatus(80, "Uploading heart-rate samples", "Samples: ${heartRateDelta.points.size}")
                uploadHeartRateDelta(userId, heartRateDelta)
            } else {
                appendLog("No new heart-rate samples")
            }

            if (sawRateLimitError) {
                val message = "Health Connect rate limit reached during backfill. Retrying automatically."
                appendLog(message)
                updateStatus(100, "Deferred", message)
                return Result.retry()
            }

            updateSummary(
                userId = userId,
                now = now,
                dailyExport = dailyExport,
                heartRateDelta = heartRateDelta,
                status = "success",
                errorMessage = null,
            )

            prefs.edit()
                .putString(PREF_DAILY_SYNC_END, now.toString())
                .putString(
                    PREF_HEART_RATE_LAST_SAMPLE,
                    heartRateDelta.newLastSampleTime?.toString() ?: previousHeartRateSample?.toString(),
                )
                .putBoolean(PREF_FULL_BACKFILL_COMPLETED, true)
                .apply()

            updateStatus(100, "Completed", "Days: ${dailyExport.days.size}; new HR: ${heartRateDelta.points.size}")
            appendLog("Sync completed successfully")
            Result.success(
                workDataOf(
                    OUTPUT_DAYS_KEY to dailyExport.days.size,
                    OUTPUT_HEART_RATE_SAMPLES_KEY to heartRateDelta.points.size,
                )
            )
        } catch (error: Exception) {
            val message = error.message ?: error.toString()
            appendLog("Sync failed: $message")
            updateStatus(100, "Failed", message)
            logError("Worker failed", error)

            if (activeUserId != null) {
                runCatching {
                    updateSummary(
                        userId = activeUserId,
                        now = Instant.now(),
                        dailyExport = null,
                        heartRateDelta = null,
                        status = "failed",
                        errorMessage = message,
                    )
                }
            }

            Result.failure(workDataOf(OUTPUT_ERROR_KEY to message))
        } finally {
            releaseSyncLock()
        }
    }

    private fun acquireSyncLock(): Boolean {
        val nowEpoch = Instant.now().epochSecond
        val inProgress = prefs.getBoolean(PREF_SYNC_IN_PROGRESS, false)
        val startedAtEpoch = prefs.getLong(PREF_SYNC_STARTED_AT_EPOCH, 0L)
        val lockIsStale = startedAtEpoch <= 0L || (nowEpoch - startedAtEpoch) > SYNC_LOCK_STALE_SECONDS

        if (inProgress && !lockIsStale) {
            return false
        }

        return prefs.edit()
            .putBoolean(PREF_SYNC_IN_PROGRESS, true)
            .putLong(PREF_SYNC_STARTED_AT_EPOCH, nowEpoch)
            .commit()
    }

    private fun releaseSyncLock() {
        prefs.edit()
            .putBoolean(PREF_SYNC_IN_PROGRESS, false)
            .remove(PREF_SYNC_STARTED_AT_EPOCH)
            .apply()
    }

    private suspend fun updateSummary(
        userId: String,
        now: Instant,
        dailyExport: AutoDailyExport?,
        heartRateDelta: AutoHeartRateDelta?,
        status: String,
        errorMessage: String?,
    ) {
        val summaryRef =
            firestore.collection(FIRESTORE_COLLECTION)
                .document(userId)
                .collection("data")
                .document("summary")

        summaryRef.set(
            hashMapOf(
                "provider" to "health_connect",
                "lastAutoSyncAt" to now.toString(),
                "lastSyncStatus" to status,
                "lastError" to errorMessage,
                "zoneId" to zoneId.id,
                "dailyRange" to hashMapOf(
                    "start" to dailyExport?.start,
                    "end" to dailyExport?.end,
                ),
                "dailyDaysCount" to (dailyExport?.days?.size ?: 0),
                "heartRateNewSamples" to (heartRateDelta?.points?.size ?: 0),
                "lastHeartRateSampleTime" to heartRateDelta?.newLastSampleTime?.toString(),
                "sourceApps" to ((dailyExport?.sources ?: emptyList()) + (heartRateDelta?.sources ?: emptyList())).distinct().sorted(),
                "syncLog" to (prefs.getString(PREF_SYNC_LOG, "") ?: ""),
            ),
            com.google.firebase.firestore.SetOptions.merge(),
        ).await()
    }

    private suspend fun updateStatus(percent: Int, stage: String, details: String) {
        val safePercent = percent.coerceIn(0, 100)
        setForeground(createForegroundInfo(safePercent, "$stage - $details"))
        setProgress(
            workDataOf(
                PROGRESS_PERCENT_KEY to safePercent,
                PROGRESS_STAGE_KEY to stage,
                PROGRESS_DETAILS_KEY to details,
            )
        )
        appendLog("[$safePercent%] $stage | $details")
    }

    private fun createForegroundInfo(percent: Int, contentText: String): ForegroundInfo {
        ensureNotificationChannel()
        val notification =
            NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ImproveHUB sync")
                .setContentText(contentText)
                .setOnlyAlertOnce(true)
                .setOngoing(percent < 100)
                .setProgress(100, percent, false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "ImproveHUB Sync",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows ongoing Health Connect sync progress"
            }
        manager.createNotificationChannel(channel)
    }

    private fun clearSyncLog() {
        prefs.edit().remove(PREF_SYNC_LOG).apply()
    }

    private fun appendLog(message: String) {
        Log.i(LOG_TAG, message)
        val existing = prefs.getString(PREF_SYNC_LOG, "") ?: ""
        val line = "${Instant.now()} | $message"
        val lines = (existing.split("\n").filter { it.isNotBlank() } + line).takeLast(LOG_LINE_LIMIT)
        prefs.edit().putString(PREF_SYNC_LOG, lines.joinToString("\n")).apply()
    }

    private fun logError(message: String, throwable: Throwable) {
        Log.e(LOG_TAG, message, throwable)
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

    private suspend fun readPreviousDailyEndFromFirestore(userId: String): Instant? {
        return runCatching {
            val dataSummary =
                firestore.collection(FIRESTORE_COLLECTION)
                    .document(userId)
                    .collection("data")
                    .document("summary")
                    .get()
                    .await()

            val dataRange = dataSummary.get("dailyRange") as? Map<*, *>
            val dataEnd = (dataRange?.get("end") as? String).toInstantOrNull()
            if (dataEnd != null) {
                return@runCatching dataEnd
            }

            // Legacy path from earlier app versions.
            val rootDoc =
                firestore.collection(FIRESTORE_COLLECTION)
                    .document(userId)
                    .get()
                    .await()
            val healthConnect = rootDoc.get("healthConnect") as? Map<*, *>
            val lastRange = healthConnect?.get("lastRange") as? Map<*, *>
            (lastRange?.get("end") as? String).toInstantOrNull()
        }.getOrNull()
    }

    private suspend fun readPreviousHeartRateFromFirestore(userId: String): Instant? {
        return runCatching {
            val dataSummary =
                firestore.collection(FIRESTORE_COLLECTION)
                    .document(userId)
                    .collection("data")
                    .document("summary")
                    .get()
                    .await()

            val fromData = dataSummary.getString("lastHeartRateSampleTime").toInstantOrNull()
            if (fromData != null) {
                return@runCatching fromData
            }

            // Legacy fallback: read old import metadata and estimate via export end.
            val rootDoc =
                firestore.collection(FIRESTORE_COLLECTION)
                    .document(userId)
                    .get()
                    .await()
            val healthConnect = rootDoc.get("healthConnect") as? Map<*, *>
            val lastTimelineRange = healthConnect?.get("lastHeartRateTimelineRange") as? Map<*, *>
            (lastTimelineRange?.get("end") as? String).toInstantOrNull()
        }.getOrNull()
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
        val totalSeconds = (end.epochSecond - start.epochSecond).coerceAtLeast(1L)

        while (cursor < end) {
            val windowEnd = minOf(cursor.plusSeconds(windowSeconds), end)
            val covered = (windowEnd.epochSecond - start.epochSecond).coerceAtLeast(0L)
            val progress = 15 + ((covered.toDouble() / totalSeconds.toDouble()) * 30.0).toInt().coerceIn(0, 30)
            updateStatus(progress, "Reading daily health data", "Window: $cursor -> $windowEnd")

            readRecordsSafe<StepsRecord>(client, cursor, windowEnd, "steps").forEach { record ->
                val key = dayKey(record.startTime)
                daily(key).steps += record.count
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<DistanceRecord>(client, cursor, windowEnd, "distance").forEach { record ->
                val key = dayKey(record.startTime)
                daily(key).distanceMeters += record.distance.inMeters
                daily(key).distanceSamples += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<ActiveCaloriesBurnedRecord>(client, cursor, windowEnd, "active_calories").forEach { record ->
                val key = dayKey(record.startTime)
                daily(key).activeCaloriesKcal += record.energy.inKilocalories
                daily(key).activeCaloriesSamples += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<TotalCaloriesBurnedRecord>(client, cursor, windowEnd, "total_calories").forEach { record ->
                val key = dayKey(record.startTime)
                daily(key).totalCaloriesKcal += record.energy.inKilocalories
                daily(key).totalCaloriesSamples += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<HeartRateRecord>(client, cursor, windowEnd, "heart_rate").forEach { record ->
                val key = dayKey(record.startTime)
                daily(key).heartRateSamples.addAll(record.samples.map { it.beatsPerMinute })
                daily(key).heartRateRecordCount += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<RestingHeartRateRecord>(client, cursor, windowEnd, "resting_heart_rate").forEach { record ->
                val key = dayKey(record.time)
                daily(key).restingHeartRateSamples.add(record.beatsPerMinute)
                daily(key).restingHeartRateRecordCount += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<HeartRateVariabilityRmssdRecord>(client, cursor, windowEnd, "hrv").forEach { record ->
                val key = dayKey(record.time)
                daily(key).hrvRmssdMillisSamples.add(record.heartRateVariabilityMillis)
                daily(key).hrvRecordCount += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<FloorsClimbedRecord>(client, cursor, windowEnd, "floors").forEach { record ->
                val key = dayKey(record.startTime)
                daily(key).floorsClimbed += record.floors
                daily(key).floorsSamples += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<HydrationRecord>(client, cursor, windowEnd, "hydration").forEach { record ->
                val key = dayKey(record.startTime)
                daily(key).hydrationLiters += record.volume.inLiters
                daily(key).hydrationSamples += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<OxygenSaturationRecord>(client, cursor, windowEnd, "oxygen_saturation").forEach { record ->
                val key = dayKey(record.time)
                daily(key).oxygenSaturationSamples.add(record.percentage.value)
                daily(key).oxygenSaturationRecordCount += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<RespiratoryRateRecord>(client, cursor, windowEnd, "respiratory_rate").forEach { record ->
                val key = dayKey(record.time)
                daily(key).respiratoryRateSamples.add(record.rate)
                daily(key).respiratoryRateRecordCount += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<ExerciseSessionRecord>(client, cursor, windowEnd, "exercise_session").forEach { record ->
                val key = dayKey(record.startTime)
                val durationMinutes = (record.endTime.epochSecond - record.startTime.epochSecond) / 60
                daily(key).exerciseMinutes += durationMinutes
                daily(key).exerciseSessionCount += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<WheelchairPushesRecord>(client, cursor, windowEnd, "wheelchair_pushes").forEach { record ->
                val key = dayKey(record.startTime)
                daily(key).wheelchairPushes += record.count
                daily(key).wheelchairPushSamples += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<HeightRecord>(client, cursor, windowEnd, "height").forEach { record ->
                val key = dayKey(record.time)
                daily(key).heightLatestMeters = record.height.inMeters
                daily(key).heightSamples += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<WeightRecord>(client, cursor, windowEnd, "weight").forEach { record ->
                val key = dayKey(record.time)
                daily(key).weightKgSamples.add(record.weight.inKilograms)
                daily(key).weightRecordCount += 1
                daily(key).sourceApps.add(record.metadata.dataOrigin.packageName)
                sources.add(record.metadata.dataOrigin.packageName)
            }

            readRecordsSafe<SleepSessionRecord>(client, cursor, windowEnd, "sleep").forEach { record ->
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

        appendLog("Daily export complete. Days=${days.size}")
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
        val totalSeconds = (end.epochSecond - start.epochSecond).coerceAtLeast(1L)

        var cursor = start
        var newestSample: Instant? = previousLastSample

        while (cursor < end) {
            val windowEnd = minOf(cursor.plusSeconds(windowSeconds), end)
            val covered = (windowEnd.epochSecond - start.epochSecond).coerceAtLeast(0L)
            val progress = 65 + ((covered.toDouble() / totalSeconds.toDouble()) * 14.0).toInt().coerceIn(0, 14)
            updateStatus(progress, "Reading heart-rate samples", "Window: $cursor -> $windowEnd")

            val records = readRecordsSafe<HeartRateRecord>(client, cursor, windowEnd, "heart_rate_delta")
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

    private suspend inline fun <reified T : Record> readRecordsSafe(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        label: String,
    ): List<T> {
        return try {
            readRecords(client, start, end)
        } catch (error: Exception) {
            if ((error.message ?: "").contains(RATE_LIMIT_ERROR_TOKEN, ignoreCase = true)) {
                sawRateLimitError = true
            }
            appendLog("Skipped $label for $start -> $end: ${error.message}")
            emptyList()
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
                    timeRangeFilter = TimeRangeFilter.between(start, end),
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
    var floorsClimbed: Double = 0.0,
    var floorsSamples: Int = 0,
    var hydrationLiters: Double = 0.0,
    var hydrationSamples: Int = 0,
    val oxygenSaturationSamples: MutableList<Double> = mutableListOf(),
    var oxygenSaturationRecordCount: Int = 0,
    val respiratoryRateSamples: MutableList<Double> = mutableListOf(),
    var respiratoryRateRecordCount: Int = 0,
    var exerciseMinutes: Long = 0,
    var exerciseSessionCount: Int = 0,
    var wheelchairPushes: Long = 0,
    var wheelchairPushSamples: Int = 0,
    var heightLatestMeters: Double? = null,
    var heightSamples: Int = 0,
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
            "floorsClimbed" to roundForJson(floorsClimbed),
            "floorsSamples" to floorsSamples,
            "hydrationLiters" to roundForJson(hydrationLiters),
            "hydrationSamples" to hydrationSamples,
            "oxygenSaturationAvgPercent" to averageDoubleValues(oxygenSaturationSamples),
            "oxygenSaturationRecordCount" to oxygenSaturationRecordCount,
            "respiratoryRateAvg" to averageDoubleValues(respiratoryRateSamples),
            "respiratoryRateRecordCount" to respiratoryRateRecordCount,
            "exerciseMinutes" to exerciseMinutes,
            "exerciseSessionCount" to exerciseSessionCount,
            "wheelchairPushes" to wheelchairPushes,
            "wheelchairPushSamples" to wheelchairPushSamples,
            "heightLatestMeters" to heightLatestMeters,
            "heightSamples" to heightSamples,
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
