package com.marcogn.pdftoolkit.data.ocr

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.marcogn.pdftoolkit.data.save.PdfSaver
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

/** Progress of a background recognition, as the UI sees it. */
sealed interface OcrProgress {
    data class Running(val fraction: Float) : OcrProgress
    data class Done(val destinationUri: String, val result: OcrResult) : OcrProgress
    data class Failed(val failure: SaveFailure) : OcrProgress
    data object Cancelled : OcrProgress
}

/** What [com.marcogn.pdftoolkit.ui.ocr.OcrRunner] needs from the queue; WorkManager in the app, a fake in tests. */
interface OcrQueue {
    /** Queues the recognition and returns the id to [observe]. */
    suspend fun enqueue(request: OcrRequest): UUID

    fun observe(id: UUID): Flow<OcrProgress?>

    /** Stops the recognition; it ends as [OcrProgress.Cancelled]. */
    fun cancel(id: UUID)
}

/** Hands recognitions to WorkManager (ADR 0003, plan 10b) and reports their progress. */
@Singleton
class OcrScheduler @Inject constructor(@ApplicationContext private val context: Context) : OcrQueue {

    override suspend fun enqueue(request: OcrRequest): UUID {
        val file = withContext(Dispatchers.IO) {
            File(PdfSaver.workDir(context), "${UUID.randomUUID()}-ocr-request.json").also { it.writeText(Json.encodeToString(request)) }
        }
        val work = OneTimeWorkRequestBuilder<OcrWorker>()
            .setInputData(workDataOf(OcrWorker.KEY_REQUEST to file.absolutePath))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // Not a unique chain: a failed or cancelled run must not take another screen's run down with it.
        WorkManager.getInstance(context).enqueue(work)
        return work.id
    }

    override fun cancel(id: UUID) {
        WorkManager.getInstance(context).cancelWorkById(id)
    }

    override fun observe(id: UUID): Flow<OcrProgress?> = WorkManager.getInstance(context).getWorkInfoByIdFlow(id).map { info ->
        when (info?.state) {
            null -> null
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> OcrProgress.Running(0f)
            WorkInfo.State.RUNNING -> OcrProgress.Running(info.progress.getInt(OcrWorker.KEY_PROGRESS, 0) / PERCENT)
            WorkInfo.State.SUCCEEDED -> OcrProgress.Done(
                info.outputData.getString(OcrWorker.KEY_DESTINATION).orEmpty(),
                info.outputData.getString(OcrWorker.KEY_RESULT)?.let { runCatching { OcrResult.valueOf(it) }.getOrNull() } ?: OcrResult.WRITTEN,
            )
            WorkInfo.State.FAILED -> OcrProgress.Failed(
                info.outputData.getString(OcrWorker.KEY_FAILURE)?.let { runCatching { SaveFailure.valueOf(it) }.getOrNull() }
                    ?: SaveFailure.FAILED,
            )
            WorkInfo.State.CANCELLED -> OcrProgress.Cancelled
        }
    }

    private companion object {
        const val PERCENT = 100f
    }
}
