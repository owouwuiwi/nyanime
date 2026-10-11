package eu.kanade.tachiyomi.data.migration

import android.content.Context
import android.util.Log

internal object MigrationFailure {
    class Remote(val safeCode: String) : Exception()
    fun code(phase: String, error: Throwable): String = (error as? Remote)?.safeCode ?: (
        phase +
            ":" +
            generateSequence(error) { it.cause }.take(6).joinToString("/") { it.javaClass.simpleName }
        )

    fun record(context: Context, phase: String, error: Throwable) {
        val detail = generateSequence(error) { it.cause }.take(6).joinToString("\n") { cause ->
            cause.javaClass.name +
                "\n" +
                cause.stackTrace.take(24).joinToString("\n") {
                    "${it.className}.${it.methodName}:${it.lineNumber}"
                }
        }
        // A full filesystem must not make the error handler fail and leave the UI working forever.
        runCatching {
            MigrationState.update(context) {
                it.put("lastError", code(phase, error)).put("lastErrorFrames", detail)
            }
        }
        Log.e("NyanimeMigration", code(phase, error) + "\n" + detail)
    }
}
