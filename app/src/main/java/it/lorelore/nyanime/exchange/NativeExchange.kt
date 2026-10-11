package it.lorelore.nyanime.exchange

import android.content.Context
import androidx.annotation.Keep

/** Referenced only by the private exchange worker; never load native networking at app startup. */
@Keep
internal object NativeExchange {
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        System.loadLibrary("nyanime_source_exchange")
        check(initialize(context.applicationContext))
        loaded = true
    }

    @JvmStatic private external fun initialize(context: Context): Boolean

    @JvmStatic external fun inspect(ticket: String): String

    @JvmStatic external fun open(directory: String, sharing: Boolean, internet: Boolean): Long

    @JvmStatic external fun share(handle: Long, file: String, name: String): String

    @JvmStatic external fun receive(handle: Long, ticket: String, directory: String): String

    @JvmStatic external fun progress(handle: Long): Long

    @JvmStatic external fun cancel(handle: Long)

    @JvmStatic external fun close(handle: Long)
}
