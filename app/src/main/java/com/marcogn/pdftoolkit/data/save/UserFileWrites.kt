package com.marcogn.pdftoolkit.data.save

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Writes of a user's file that run in the app process rather than in WorkManager (the scan flow's copy
 * to its `CreateDocument` file). The update installer waits for them like for the tagged jobs
 * (`FILE_WRITE_WORK_TAG`), because installing ends the process (plan A).
 */
@Singleton
class UserFileWrites @Inject constructor() {
    private val running = AtomicInteger(0)
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    suspend fun <T> track(block: suspend () -> T): T {
        _active.value = running.incrementAndGet() > 0
        try {
            return block()
        } finally {
            _active.value = running.decrementAndGet() > 0
        }
    }
}
