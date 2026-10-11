package eu.kanade.tachiyomi.data.track

import java.io.IOException

/** One renewal per account; a logout cannot be overwritten by an in-flight renewal. */
internal class TrackerTokenSession<T>(
    initial: T?,
    private val persist: (T?) -> Unit,
) {
    private var token = initial

    @Synchronized
    fun set(value: T?) {
        persist(value)
        token = value
    }

    @Synchronized
    fun valid(expired: (T) -> Boolean, renew: (T) -> T): T {
        val current = token ?: throw IOException("Account is not authenticated")
        if (!expired(current)) return current
        val renewed = try {
            renew(current)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            // Asynchronous OkHttp calls only report IOExceptions to their callback.
            throw IOException("Could not renew account authentication", error)
        }
        set(renewed)
        return renewed
    }
}
