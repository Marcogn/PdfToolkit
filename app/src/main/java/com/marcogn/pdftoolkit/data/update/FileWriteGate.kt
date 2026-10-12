package com.marcogn.pdftoolkit.data.update

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.marcogn.pdftoolkit.data.save.FILE_WRITE_WORK_TAG
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Whether a background job is writing a user's file. Installing an update replaces the app and ends
 * its process, which would cut a save or a text recognition off while it copies over the destination
 * (ADR 0003), so `UpdateManager.install()` waits until this is false (plan A).
 */
interface FileWriteGate {
    /** `true` while a save or a recognition is queued or running. */
    val busy: Flow<Boolean>
}

/** WorkManager implementation: every job that writes a document carries [FILE_WRITE_WORK_TAG]. */
@Singleton
class WorkManagerFileWriteGate @Inject constructor(
    @ApplicationContext private val context: Context,
) : FileWriteGate {

    override val busy: Flow<Boolean> by lazy {
        val query = WorkQuery.Builder.fromTags(listOf(FILE_WRITE_WORK_TAG))
            .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED))
            .build()
        WorkManager.getInstance(context).getWorkInfosFlow(query).map { it.isNotEmpty() }
    }
}
