package eu.kanade.tachiyomi.ui.privacy

import android.view.View
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

fun PrivacyDisplayController.registerNsfwView(view: View, scope: PrivacyArea, content: () -> Any?) {
    val metadata by lazy { Injekt.get<PrivacyContentIndex>() }
    var previous: Any? = null
    var previousRatings: PrivacySourceRatings? = null
    var flagged = false
    registerView(view, PrivacyArea.NSFW, scope) {
        val item = content()
        val ratings = metadata.ratings.value
        if (item !== previous || ratings !== previousRatings) {
            flagged = metadata.isNsfw(item, ratings)
            previous = item
            previousRatings = ratings
        }
        flagged
    }
}
