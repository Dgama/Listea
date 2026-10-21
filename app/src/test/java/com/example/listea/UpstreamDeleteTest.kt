package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Forgetting an Upstream removes the registration and nothing else.
 *
 * The table is a list of places material comes from, and everything made from a place — its Todos,
 * their Tasks, the Lists that hold them — is a decision taken afterwards. A delete that cascaded
 * would turn *stop listing this folder* into *destroy what you made from it*, which is a different
 * act with a different confirmation, and the test pins the boundary rather than the SQL.
 *
 * The second claim is about sharing: two Upstreams may sit under one grant, so removing one says
 * nothing about the other even when they name the same root. The third is the quiet one — an id
 * that no longer exists must not take the table with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpstreamDeleteTest {

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
    fun `removing an Upstream keeps its Todos, Tasks and Lists`() = runTest(dispatcher) {
        val upstream = dao.registerUpstream(ROOT, "photos", "Photos", now = 0)
        val (sourceId, listId) = importedTodo()
        val viewModel = viewModel()
        // Wait for the row to be visible first: the flow's initial value is empty, so a predicate
        // looking for its absence would be satisfied before the write was even attempted.
        viewModel.upstreams.first { rows -> rows.any { it.id == upstream.id } }

        viewModel.deleteUpstream(upstream.id)
        // Room resumes its queries on its own executor, so the observed list going quiet is the
        // signal that the write committed; `advanceUntilIdle` returns before it does.
        viewModel.upstreams.first { rows -> rows.none { it.id == upstream.id } }

        assertEquals("the registration is gone", emptyList<Any>(), dao.upstreamsUnder(ROOT))
        assertEquals("its Todo stays", 1, dao.sourcesAt(ROOT, "photos").size)
        assertNotNull(dao.getSource(sourceId))
        assertEquals("its Tasks stay", 1, dao.getCatalogTasks(sourceId).size)
        assertNotNull("its List stays", dao.observeList(listId).first())
        assertEquals(
            "and so does the List's membership",
            1,
            dao.listsHolding(sourceId).single().itemCount
        )
    }

    @Test
    fun `a second Upstream under the same root is untouched`() = runTest(dispatcher) {
        val photos = dao.registerUpstream(ROOT, "photos", "Photos", now = 0)
        val videos = dao.registerUpstream(ROOT, "videos", "Videos", now = 0)
        val viewModel = viewModel()
        viewModel.upstreams.first { rows -> rows.any { it.id == photos.id } }

        viewModel.deleteUpstream(photos.id)
        viewModel.upstreams.first { rows -> rows.none { it.id == photos.id } }

        assertEquals(listOf(videos.id), dao.upstreamsUnder(ROOT).map { it.id })
    }

    @Test
    fun `removing an id that is already gone changes nothing`() = runTest {
        val photos = dao.registerUpstream(ROOT, "photos", "Photos", now = 0)
        importedTodo()

        dao.deleteUpstream("not-an-upstream")

        assertEquals(listOf(photos.id), dao.upstreamsUnder(ROOT).map { it.id })
        assertEquals(1, dao.sourcesAt(ROOT, "photos").size)
    }

    // --- reaching the states above ---------------------------------------------------------------

    /** A registered Todo with one Task, and a List holding it. */
    private suspend fun importedTodo(): Pair<String, Long> {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "photos",
            displayName = "Photos",
            importerKind = ImporterKind.FILE_AS_TASK,
            keepTree = true,
            now = 1000
        )
        dao.runImport(
            source.id,
            "photos",
            plan(
                ImporterKind.FILE_AS_TASK,
                "photos",
                FolderSnapshot(
                    listOf(
                        com.example.listea.data.ScannedFile(
                            name = "a.jpg",
                            relativePath = "a.jpg",
                            uri = "$ROOT/photos/a.jpg"
                        )
                    ),
                    emptyList()
                )
            ),
            2000
        )
        val listId = dao.createListFromTasks(
            title = "Trip",
            taskIds = dao.getCatalogTasks(source.id).map { it.taskId },
            exportOnCompletion = false,
            now = 3000
        )
        return source.id to listId
    }

    private fun viewModel() = ListsViewModel(application, dao, FakeFolderSource(), dispatcher)

    private companion object {
        const val ROOT = "content://root"
    }
}
