package it.lorelore.nyanime.novel.source

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NovelSourceAvailabilityTest {
    private class MetadataProvider(override val id: String) : NovelSourceProvider {
        val rows = MutableStateFlow<List<NovelSourceDeclaration>>(emptyList())

        override fun declarations() = rows

        override fun observe(): StateFlow<NovelSourceInventory> =
            error("Must not instantiate sources for a category")

        override suspend fun resolve(id: Long): NovelSourceRegistration =
            error("Must not execute a source for a category")
    }

    @Test
    fun `a Studio source alone exposes novels without a library or APK instance and reacts to disable`() =
        runTest {
            val studio = MetadataProvider("studio")
            val values = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                NovelSourceAvailability.observe(
                    listOf(studio),
                    MutableStateFlow(setOf("it")),
                    MutableStateFlow(false),
                )
                    .distinctUntilChanged()
                    .collect { values += it }
            }
            runCurrent()
            studio.rows.value = listOf(NovelSourceDeclaration(73, "it", false))
            runCurrent()
            studio.rows.value = emptyList()
            runCurrent()
            studio.rows.value = listOf(NovelSourceDeclaration(74, "it", false))
            runCurrent()
            assertEquals(listOf(false, true, false, true), values)
        }

    @Test
    fun `declarations honor language adult visibility and ambiguous source identities`() = runTest {
        val native = MetadataProvider("apk")
        val studio = MetadataProvider("studio")
        val languages = MutableStateFlow(setOf("it"))
        val adult = MutableStateFlow(false)
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            NovelSourceAvailability.observe(listOf(native, studio), languages, adult)
                .distinctUntilChanged()
                .collect { values += it }
        }
        runCurrent()
        studio.rows.value = listOf(NovelSourceDeclaration(73, "en", true))
        runCurrent()
        languages.value = setOf("en")
        runCurrent()
        assertEquals(listOf(false), values)
        adult.value = true
        runCurrent()
        native.rows.value = listOf(NovelSourceDeclaration(73, "all", false))
        runCurrent()
        native.rows.value = emptyList()
        runCurrent()
        assertEquals(listOf(false, true, false, true), values)
    }
}
