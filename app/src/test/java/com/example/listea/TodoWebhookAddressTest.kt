package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import com.example.listea.domain.encodeExportChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two address editors' write path: a Todo's own address, and a List's override.
 *
 * Both are simple columns, and that is why they are worth a test of their own: the rules around
 * them live in dialogs and in the delivery planner, so the only thing this layer can get wrong is
 * *which columns move*. A Todo's address and its `fromDefault` bit must move together, a blank is a
 * legitimate answer (it means *no address set*), and a List's empty override must genuinely clear
 * rather than store a space that a later reader would see as an address.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodoWebhookAddressTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao
    private val dispatcher = StandardTestDispatcher()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(application as Context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.listsDao()
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a Todo address edit moves both columns, trimmed`() = runTest(dispatcher) {
        val source = todo()

        viewModel().setTodoWebhookAddress(source.id, "  https://example.com/hook ", false)
        // Room resumes its queries on its own executor, so waiting on the observed row is the only
        // reliable signal that the write landed; `advanceUntilIdle` returns before it does.
        val updated = dao.observeSourceSummaries()
            .first { rows -> rows.any { it.id == source.id && it.webhookUrl == "https://example.com/hook" } }
            .single { it.id == source.id }

        assertEquals("https://example.com/hook", updated.webhookUrl)
        assertEquals(false, updated.webhookFromDefault)
    }

    @Test
    fun `a Todo can be cleared back to no address`() = runTest(dispatcher) {
        val source = todo(webhookUrl = "https://example.com/hook", webhookFromDefault = true)

        viewModel().setTodoWebhookAddress(source.id, "", false)
        val cleared = dao.observeSourceSummaries()
            .first { rows ->
                rows.any {
                    it.id == source.id && it.webhookUrl.isEmpty() && !it.webhookFromDefault
                }
            }
            .single { it.id == source.id }

        assertEquals("", cleared.webhookUrl)
        assertEquals(false, cleared.webhookFromDefault)
    }

    @Test
    fun `a List override writes and clears`() = runTest(dispatcher) {
        val listId = dao.insertList(
            com.example.listea.data.ListEntity(
                title = "Demo",
                exportOnCompletion = true,
                createdAt = 0
            )
        )
        val viewModel = viewModel()

        viewModel.setListWebhookUrl(listId, " https://list.example.com/hook ")
        val written = dao.observeList(listId)
            .first { it?.webhookUrl == "https://list.example.com/hook" }
        assertEquals("https://list.example.com/hook", written!!.webhookUrl)

        viewModel.setListWebhookUrl(listId, "")
        val cleared = dao.observeList(listId).first { it?.webhookUrl?.isEmpty() == true }
        assertEquals("", cleared!!.webhookUrl)
    }

    // --- reaching the states above ---------------------------------------------------------------

    private suspend fun todo(
        webhookUrl: String = "",
        webhookFromDefault: Boolean = false
    ) = dao.registerSource(
        rootUri = "content://root",
        relativePath = "",
        displayName = "Photos",
        importerKind = ImporterKind.FILE_AS_TASK,
        exportKind = encodeExportChoice(ExportChoice.By(ExportKind.WEBHOOK)),
        webhookUrl = webhookUrl,
        webhookFromDefault = webhookFromDefault,
        now = 0
    )

    private fun viewModel() = ListsViewModel(application, dao, FakeFolderSource(), dispatcher)
}
