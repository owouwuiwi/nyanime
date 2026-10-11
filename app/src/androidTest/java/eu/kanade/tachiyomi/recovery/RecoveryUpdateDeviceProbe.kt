package eu.kanade.tachiyomi.recovery

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import eu.kanade.tachiyomi.data.recovery.PreventiveRecoveryBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Opt-in write test; the build must use a disposable application ID. */
class RecoveryUpdateDeviceProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            require(targetContext.packageName.endsWith(".recoveryfixture"))
            runBlocking(Dispatchers.IO) {
                repeat(2) {
                    val phases = mutableListOf<Int>()
                    check(PreventiveRecoveryBackup.create(targetContext) { phases.add(it) })
                    check(phases.first() == 0 && phases.last() == 2)
                }
            }
            output.putString("stream", "Preventive backup verified twice through the real host-to-addon IPC.\n")
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("stream", error.javaClass.simpleName + " at " + error.stackTrace.take(8).joinToString())
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
