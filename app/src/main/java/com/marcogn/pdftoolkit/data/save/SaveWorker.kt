package com.marcogn.pdftoolkit.data.save

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
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Runs a save outside the UI, so it survives the app going to the background (spec §6.7, ADR 0003).
 * Input: the path of a [SaveRequest] JSON file. Progress: [KEY_PROGRESS] (0..100). Result: the
 * destination in [KEY_DESTINATION], or the [SaveFailure] name in [KEY_FAILURE].
 */
class SaveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun saver(): PdfSaver
    }

    override suspend fun doWork(): Result {
        val requestFile = inputData.getString(KEY_REQUEST)?.let(::File)
        val request = try {
            requestFile?.takeIf { it.exists() }?.readText()?.let { Json.decodeFromString<SaveRequest>(it) }
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        } ?: return failure(SaveFailure.FAILED)

        val saver = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).saver()
        return try {
            var last = -1
            saver.save(request) { fraction ->
                val percent = (fraction * 100).toInt()
                if (percent != last) {
                    last = percent
                    setProgressAsync(workDataOf(KEY_PROGRESS to percent))
                }
            }
            Result.success(workDataOf(KEY_DESTINATION to request.destinationUri))
        } catch (e: SaveException) {
            failure(e.failure)
        } finally {
            requestFile?.delete()
        }
    }

    private fun failure(reason: SaveFailure): Result = Result.failure(Data.Builder().putString(KEY_FAILURE, reason.name).build())

    /** Needed for expedited work before Android 12, and shown if the system promotes the work to the foreground. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.save_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.save_notification_title))
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
        const val KEY_DESTINATION = "destination"
        const val KEY_FAILURE = "failure"
        private const val CHANNEL_ID = "save"
        private const val NOTIFICATION_ID = 1
    }
}
