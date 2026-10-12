package com.marcogn.pdftoolkit.data.update

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.marcogn.pdftoolkit.data.save.FILE_WRITE_WORK_TAG
import com.marcogn.pdftoolkit.data.save.UserFileWrites
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Whether a background job is writing a user's file. Installing an update replaces the app and ends
 * its process, which would cut a save or a text recognition off while it copies over the destination
 * (ADR 0003), so `UpdateManager.install()` waits until this is false (plan A).
 */
interface FileWriteGate {
    /** `true` while a save, a recognition or the scan flow's copy is queued or running. */
    val busy: Flow<Boolean>
}

/**
 * Every WorkManager job that writes a document carries [FILE_WRITE_WORK_TAG]; writes in the app process
 * go through [UserFileWrites].
 */
@Singleton
class WorkManagerFileWriteGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userFileWrites: UserFileWrites,
) : FileWriteGate {

    override val busy: Flow<Boolean> by lazy {
        val query = WorkQuery.Builder.fromTags(listOf(FILE_WRITE_WORK_TAG))
            .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED))
            .build()
        val jobs = WorkManager.getInstance(context).getWorkInfosFlow(query).map { it.isNotEmpty() }
        combine(jobs, userFileWrites.active) { job, inProcess -> job || inProcess }.distinctUntilChanged()
    }
}
