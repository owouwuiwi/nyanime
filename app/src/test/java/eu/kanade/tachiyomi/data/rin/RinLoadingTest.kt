package eu.kanade.tachiyomi.data.rin

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

class RinLoadingTest {
    @Test fun `linkage failures from factories become recoverable package errors`() {
        listOf(
            LinkageError("Shared parameter has a different class identity"),
            AbstractMethodError("Unavailable extension method"),
            NoClassDefFoundError("Missing package dependency"),
            ExceptionInInitializerError(IllegalStateException("Invalid initializer")),
            AssertionError("Invalid factory"),
        ).forEach { failure ->
            val error = assertThrows(RinLoadException::class.java) { RinLoading.guard { throw failure } }
            assertSame(failure, error.cause)
        }
    }

    @Test fun `reflection cannot hide linkage errors or cancellation`() {
        val linkage = NoSuchMethodError("Incompatible constructor")
        val error = assertThrows(RinLoadException::class.java) {
            RinLoading.guard { throw InvocationTargetException(linkage) }
        }
        assertSame(linkage, error.cause)
        val cancellation = CancellationException("Closed")
        assertSame(
            cancellation,
            assertThrows(CancellationException::class.java) {
                RinLoading.guard { throw InvocationTargetException(cancellation) }
            },
        )
    }

    @Test fun `fatal VM errors are never converted to a retry or a successful load`() {
        val fatal = OutOfMemoryError("VM exhausted")
        assertSame(
            fatal,
            assertThrows(OutOfMemoryError::class.java) {
                RinLoading.guard { throw InvocationTargetException(fatal) }
            },
        )
        val exception = IllegalArgumentException("Invalid manifest")
        assertSame(
            exception,
            assertThrows(IllegalArgumentException::class.java) {
                RinLoading.guard { throw exception }
            },
        )
    }
}
