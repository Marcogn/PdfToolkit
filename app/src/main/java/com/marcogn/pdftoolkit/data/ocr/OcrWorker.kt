package com.marcogn.pdftoolkit.data.ocr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.edit.SaveException
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Runs a text recognition outside the UI (plan 10b, ADR 0003): survives the app going to the background
 * and can be cancelled. Input: the path of an [OcrRequest] JSON file. Progress: [KEY_PROGRESS] (0..100).
 * Result: [KEY_RESULT] (an [OcrResult] name) and [KEY_DESTINATION], or the [SaveFailure] name in [KEY_FAILURE].
 */
class OcrWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun job(): OcrJob
    }

    override suspend fun doWork(): Result {
        val requestFile = inputData.getString(KEY_REQUEST)?.let(::File)
        val request = try {
            requestFile?.takeIf { it.exists() }?.readText()?.let { Json.decodeFromString<OcrRequest>(it) }
        } catch (e: SerializationException) {
            null
        } ?: return failure(SaveFailure.FAILED)

        val job = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).job()
        var stopped = false
        return try {
            var last = -1
            val result = job.run(request) { fraction ->
                val percent = (fraction * 100).toInt()
                if (percent != last) {
                    last = percent
                    setProgressAsync(workDataOf(KEY_PROGRESS to percent))
                }
            }
            Result.success(workDataOf(KEY_RESULT to result.name, KEY_DESTINATION to request.destinationUri))
        } catch (e: SaveException) {
            failure(e.failure)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelled by the user or stopped by the system, which may run it again: keep the request then.
            stopped = true
            throw e
        } finally {
            if (!stopped) requestFile?.delete()
        }
    }

    private fun failure(reason: SaveFailure): Result = Result.failure(Data.Builder().putString(KEY_FAILURE, reason.name).build())

    /** Needed for expedited work before Android 12, and shown if the system promotes the work to the foreground. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.ocr_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.ocr_notification_title))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_REQUEST = "request"
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT = "result"
        const val KEY_DESTINATION = "destination"
        const val KEY_FAILURE = "failure"
        private const val CHANNEL_ID = "ocr"
        private const val NOTIFICATION_ID = 2
    }
}
