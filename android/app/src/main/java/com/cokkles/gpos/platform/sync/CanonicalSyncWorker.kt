package com.cokkles.gpos.platform.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * A0 scheduling skeleton only.
 *
 * This worker intentionally performs no backend call and no mutation. Scheduling is also not
 * enabled in A0. A future phase may inject a read-only repository refresh once production auth,
 * transport compatibility and freshness policy are validated.
 */
class CanonicalSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "gpos-canonical-read-sync"
    }
}
