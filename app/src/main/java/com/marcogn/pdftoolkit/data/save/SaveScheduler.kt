package com.marcogn.pdftoolkit.data.save

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.marcogn.pdftoolkit.domain.edit.SaveFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of a background save, as the UI sees it. */
sealed interface SaveProgress {
    data class Running(val fraction: Float) : SaveProgress
    data class Done(val destinationUri: String) : SaveProgress
    data class Failed(val failure: SaveFailure) : SaveProgress
}

/** Hands saves to WorkManager (ADR 0003) and reports their progress. */
@Singleton
class SaveScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    /** Queues the save and returns the id to [observe]. */
    suspend fun enqueue(request: SaveRequest): UUID {
        val file = withContext(Dispatchers.IO) {
            File(PdfSaver.workDir(context), "${UUID.randomUUID()}-request.json").also { it.writeText(Json.encodeToString(request)) }
        }
        val work = OneTimeWorkRequestBuilder<SaveWorker>()
            .setInputData(workDataOf(SaveWorker.KEY_REQUEST to file.absolutePath))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        return work.id
    }

    fun observe(id: UUID): Flow<SaveProgress?> = WorkManager.getInstance(context).getWorkInfoByIdFlow(id).map { info ->
        when (info?.state) {
            null -> null
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> SaveProgress.Running(0f)
            WorkInfo.State.RUNNING -> SaveProgress.Running(info.progress.getInt(SaveWorker.KEY_PROGRESS, 0) / PERCENT)
            WorkInfo.State.SUCCEEDED -> SaveProgress.Done(info.outputData.getString(SaveWorker.KEY_DESTINATION).orEmpty())
            WorkInfo.State.FAILED -> SaveProgress.Failed(
                info.outputData.getString(SaveWorker.KEY_FAILURE)?.let { runCatching { SaveFailure.valueOf(it) }.getOrNull() }
                    ?: SaveFailure.FAILED,
            )
            WorkInfo.State.CANCELLED -> SaveProgress.Failed(SaveFailure.FAILED)
        }
    }

    private companion object {
        const val UNIQUE_NAME = "save-pdf"
        const val PERCENT = 100f
    }
}
