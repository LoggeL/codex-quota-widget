package top.logge.codexquota

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.Future

class QuotaRefreshJob : JobService() {
    private class Run(var future: Future<*>? = null)
    private val tasks = mutableMapOf<Int, Run>()
    override fun onStartJob(params: JobParameters): Boolean {
        val runtime = QuotaRuntime.get(this)
        val run = Run()
        tasks[params.jobId] = run
        run.future = runtime.executor.submit {
            val retry = runCatching { runtime.repository.refreshAll() }.getOrElse {
                CodexQuotaLog.append(this, "refresh failed: ${it.javaClass.simpleName}")
                true
            }
            mainExecutorCompat {
                if (tasks[params.jobId] === run) {
                    tasks.remove(params.jobId)
                    jobFinished(params, retry && params.jobId == MANUAL)
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        tasks.remove(params.jobId)?.future?.cancel(true)
        return true
    }
    private fun mainExecutorCompat(block: () -> Unit) = android.os.Handler(mainLooper).post(block)

    companion object {
        private const val PERIODIC = 100
        private const val MANUAL = 101
        fun schedule(context: Context, immediately: Boolean = false) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val component = ComponentName(context, QuotaRefreshJob::class.java)
            if (scheduler.getPendingJob(PERIODIC) == null) scheduler.schedule(JobInfo.Builder(PERIODIC, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setPeriodic(30 * 60_000L).build())
            if (immediately && scheduler.getPendingJob(MANUAL) == null) scheduler.schedule(JobInfo.Builder(MANUAL, component)
                .setMinimumLatency(0).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
    }
}
