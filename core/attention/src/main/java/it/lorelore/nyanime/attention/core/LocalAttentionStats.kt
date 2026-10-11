package it.lorelore.nyanime.attention.core

import com.google.mediapipe.tasks.core.logging.TasksStatsLogger

/** Used by the verified build transform. Does not construct a transport or collect metrics. */
class LocalAttentionStats : TasksStatsLogger {
    override fun logSessionStart() = Unit
    override fun logSessionClone() = Unit
    override fun recordCpuInputArrival(timestamp: Long) = Unit
    override fun recordGpuInputArrival(timestamp: Long) = Unit
    override fun recordInvocationEnd(timestamp: Long) = Unit
    override fun logInvocationReport(snapshot: TasksStatsLogger.StatsSnapshot) = Unit
    override fun logSessionEnd() = Unit
    override fun logInitError() = Unit
}
