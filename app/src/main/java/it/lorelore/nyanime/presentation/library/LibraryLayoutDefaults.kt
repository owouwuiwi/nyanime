package it.lorelore.nyanime.presentation.library

import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences

/** Adopt the approved layout once; subsequent user choices always win, including after restart. */
internal fun initializeLibraryLayout(preferences: LibraryPreferences) {
    if (preferences.modernLayoutIntroduced().get()) return
    preferences.displayMode().set(LibraryDisplayMode.Shelf)
    preferences.allDisplayMode().set(LibraryDisplayMode.Shelf)
    preferences.modernLayoutIntroduced().set(true)
}
