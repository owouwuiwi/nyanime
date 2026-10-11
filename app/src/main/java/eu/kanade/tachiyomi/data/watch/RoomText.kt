package eu.kanade.tachiyomi.data.watch

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate

/** Injected at the Android boundary so room coordination stays independent of Android resources. */
fun interface RoomText {
    fun get(@StringRes resource: Int, args: Array<out Any>): String

    operator fun invoke(@StringRes resource: Int, vararg args: Any): String = get(resource, args)

    companion object {
        fun from(context: Context): RoomText {
            var cachedTags: String? = null
            var localizedContext = context
            return RoomText { resource, args ->
                val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                if (tags != cachedTags) {
                    cachedTags = tags
                    localizedContext = if (tags.isEmpty()) {
                        context
                    } else {
                        val configuration = Configuration(context.resources.configuration)
                        configuration.setLocales(LocaleList.forLanguageTags(tags))
                        context.createConfigurationContext(configuration)
                    }
                }
                localizedContext.getString(resource, *args)
            }
        }
    }
}

/** Validation errors carry a resource identifier, never translated protocol data. */
class RoomValidationException(@StringRes val resource: Int) : IllegalArgumentException()

internal fun roomRequire(value: Boolean, @StringRes resource: Int) {
    if (!value) throw RoomValidationException(resource)
}

fun Throwable.roomMessage(text: RoomText): String? =
    if (this is RoomValidationException) text(resource) else message
