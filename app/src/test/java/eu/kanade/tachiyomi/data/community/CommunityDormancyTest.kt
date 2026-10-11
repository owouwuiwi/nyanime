package eu.kanade.tachiyomi.data.community

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import eu.kanade.tachiyomi.BuildConfig
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CommunityDormancyTest {
    @Test
    fun `dormant runtimes cannot access a context or initialize injection on foreground or playback`() {
        assertFalse(BuildConfig.COMMUNITY_ENABLED)
        assertFalse(BuildConfig.PERSONAL_SYNC_ENABLED)
        val context = mockk<Context>()
        CommunityManager.lifecycle(context, true)
        CommunityManager.lifecycle(context, false)
        assertNull(CommunityManager.existing())
        assertThrows(IllegalStateException::class.java) { CommunityManager.personal(context) }
        assertThrows(IllegalStateException::class.java) { CommunityManager.get(context) }
        verify { context wasNot Called }
    }

    @Test
    fun `opening an upgraded library always disables the persisted capture flag without reading old keys`() {
        val database = mockk<SupportSQLiteDatabase>()
        every { database.execSQL(any(), any<Array<out Any?>>()) } just Runs
        every { database.execSQL(any()) } just Runs
        CommunityDormancy.onDatabaseOpen(database)
        verify(exactly = 1) {
            database.execSQL("UPDATE community_capture SET enabled = ?", match { it.contentEquals(arrayOf(0)) })
            database.execSQL("DELETE FROM community_changes")
            database.execSQL("DELETE FROM community_category_ids")
        }
    }
}
