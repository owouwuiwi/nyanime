package eu.kanade.tachiyomi.data.rin

import kotlinx.coroutines.CancellationException
import java.lang.reflect.InvocationTargetException

/** Package failures are recoverable. Cancellation and fatal VM failures retain their normal semantics. */
internal object RinLoading {
    fun <T> guard(block: () -> T): T = try {
        block()
    } catch (failure: Throwable) {
        var cause = failure
        while (cause is InvocationTargetException && cause.targetException != null) {
            cause = cause.targetException
        }
        when (cause) {
            is CancellationException -> throw cause
            is VirtualMachineError -> throw cause
            is ThreadDeath -> throw cause
            is Exception -> throw cause
            else -> throw RinLoadException(cause)
        }
    }
}

internal class RinLoadException(cause: Throwable) : Exception("Incompatible RIN implementation", cause)
