package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.AppSettings
import com.example.listea.AssetControlConfig
import com.example.listea.CustomAction
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.assetKindOf
import com.example.listea.childRelativePath
import com.example.listea.decodeAssetControls
import com.example.listea.encodeAssetControls
import com.example.listea.plan
import com.example.listea.domain.AssetKind
import com.example.listea.domain.Templates
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What an import run does to a Task's asset control, and what a screen reading it back gets.
 *
 * The tier *rule* is pinned in `AssetControlTest`, which is pure and has no database. This is the
 * other half and the one that could not be pure, because the guarantee this release rests on is
 * about two writes rather than about one value:
 *
 * > both records — the Todo's and each Task's — come from `options.assetControls`, and neither is
 * > read back from the row the other one just wrote.
 *
 * That is weaker than "one transaction" and cannot be checked by any compiler. A new Todo's
 * `sources` row is written *before* the run that writes its Tasks, so the two are in different
 * transactions on different sides of the run; what keeps them consistent is that neither asks the
 * database what the answer was. An implementation that stamped the Tasks from the row it had just
 * written would still pass every test that only ever imports once — which is why the first test
 * here imports once, with an answer that deliberately disagrees with the row.
 *
 * Everything is real SQL through Room, for the reason [ListsDaoTest] gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetControlRunTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.listsDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    // --- which answer a run stamps ---------------------------------------------------------------

    @Test
    fun `a run stamps the answer it was handed, not the one the Todo stores`() = runTest {
        // The dialog's answer and the Todo's row legitimately differ for the length of one run: the
        // row is only written back after the run returns. A run that read the row would apply the
        // user's answer one run late — they choose, press Refresh, and watch the previous answer
        // happen, with nothing on screen to say why.
        val stored = mapOf(AssetKind.IMAGE to config(tag("old", "Old", "old")))
        val todo = todoWith(stored)
        val task = taskFor(todo, "photos/a.jpg")
        assertEquals(encodeAssetControls(stored), controlsOf(task))

        val handed = mapOf(
            AssetKind.IMAGE to config(tag("new", "New", "new")),
            AssetKind.VIDEO to config(tag("v1", "Watch", "watch"))
        )
        // Once. A second run over the same folder would stamp the same answer whether the run read
        // the argument or the row, and would hide exactly the failure this test is for.
        dao.runImport(todo, "photos", filePlan("photos", listOf("a.jpg")), 3000, assetControls = handed)

        assertEquals(encodeAssetControls(handed), controlsOf(task))
        assertNotEquals(encodeAssetControls(stored), controlsOf(task))
        // And the Todo's own row is not a second writer: recording the answer belongs to whoever
        // asked the question, and a run that also wrote its Todo's column would fight the caller.
        assertEquals(
            encodeAssetControls(stored),
            rawText("SELECT assetControls FROM sources WHERE id = '$todo'")
        )
    }

    @Test
    fun `a run over the same folder stamps the same answer again`() = runTest {
        // Re-stamping on every run is deliberate rather than incidental: a run over an unchanged
        // folder is still a run, and the user asked for it. What matters is that it is idempotent,
        // so a folder watched over weeks does not drift a Task's record one run at a time.
        val answer = mapOf(AssetKind.IMAGE to config(tag("i1", "I1", "i1")))
        val todo = todoWith(answer)
        val task = taskFor(todo, "photos/a.jpg")

        dao.runImport(todo, "photos", filePlan("photos", listOf("a.jpg")), 3000, assetControls = answer)

        assertEquals(encodeAssetControls(answer), controlsOf(task))
    }

    @Test
    fun `a run given no answer clears the record rather than keeping the old one`() = runTest {
        // The honest description of every existing caller: `assetControls` defaults to empty, so a
        // run that says nothing about a control stamps the absence of an answer — which resolves as
        // the app's current tags, the same reading `''` has always had. Pinned because the
        // alternative (fall back to the row) is the one-run-late bug wearing different clothes.
        val stored = mapOf(AssetKind.IMAGE to config(tag("old", "Old", "old")))
        val todo = todoWith(stored)
        val task = taskFor(todo, "photos/a.jpg")

        dao.runImport(todo, "photos", filePlan("photos", listOf("a.jpg")), 3000)

        assertEquals("", controlsOf(task))
        assertEquals(
            listOf("i1", "i2"),
            AppSettings(assetControlTags = mapOf(AssetKind.IMAGE to listOf(tag("i1"), tag("i2"))))
                .controlFor(controlsOf(task), AssetKind.IMAGE).tags.map { it.id }
        )
    }

    @Test
    fun `editing settings changes nothing until the next run`() = runTest {
        // Re-stamping happens on a run and nowhere else, so there is no path from a Settings edit to
        // a row: a Task keeps what it was imported under however the app's own tags move underneath
        // it. The two therefore genuinely disagree in between, which is the friction the Todo card
        // exists to make legible — and the last half of this test is the run that closes it.
        val answer = mapOf(AssetKind.IMAGE to config(tag("i1", "I1", "i1")))
        val todo = todoWith(answer)
        val task = taskFor(todo, "photos/a.jpg")

        // The app's tags have moved on, so what is recorded is no longer what a new run would offer.
        val moved = AppSettings(
            assetControlTags = mapOf(AssetKind.IMAGE to listOf(tag("i9", "I9", "i9")))
        )

        assertEquals(encodeAssetControls(answer), controlsOf(task))
        assertEquals(
            encodeAssetControls(answer),
            rawText("SELECT assetControls FROM sources WHERE id = '$todo'")
        )
        assertEquals(listOf("i1"), moved.controlFor(controlsOf(task), AssetKind.IMAGE).tags.map { it.id })
        assertEquals(listOf("i9"), moved.controlFor("", AssetKind.IMAGE).tags.map { it.id })

        // And the run, not the edit, is what reaches the Task.
        dao.runImport(
            todo,
            "photos",
            filePlan("photos", listOf("a.jpg")),
            4000,
            assetControls = moved.controlConfigsFor("")
        )

        assertEquals(listOf("i9"), moved.controlFor(controlsOf(task), AssetKind.IMAGE).tags.map { it.id })
    }

    @Test
    fun `a run over one Todo leaves another Todo's Tasks alone`() = runTest {
        val one = mapOf(AssetKind.IMAGE to config(tag("i1", "I1", "i1")))
        val two = mapOf(AssetKind.IMAGE to config(tag("i2", "I2", "i2")))
        val first = todoWith(one, path = "photos")
        val second = todoWith(two, path = "clips", displayName = "Clips")
        val taskOfSecond = taskFor(second, "clips/a.jpg")

        // The first Todo runs again, under a third answer entirely.
        dao.runImport(
            first,
            "photos",
            filePlan("photos", listOf("a.jpg")),
            4000,
            assetControls = mapOf(AssetKind.IMAGE to config(tag("i3", "I3", "i3")))
        )

        assertEquals(encodeAssetControls(two), controlsOf(taskOfSecond))
    }

    // --- what a screen reading it back gets -------------------------------------------------------

    @Test
    fun `one folder's Tasks carry both kinds, and paging finds each one's own tags`() = runTest {
        // The acceptance list, one level below the screen: three image tags, two video tags, and a
        // folder holding both kinds. What the review screen does with this is `Settings.controlFor`
        // per file — the middle band — and `controlForTask` for the badge row.
        val images = listOf(
            tag("i1", "Quality", "quality"),
            tag("i2", "Price", "price"),
            tag("i3", "Archive", "archive")
        )
        val videos = listOf(tag("v1", "Watch", "watch"), tag("v2", "Later", "later"))
        val answer = mapOf(
            AssetKind.IMAGE to config(*images.toTypedArray()),
            AssetKind.VIDEO to config(*videos.toTypedArray())
        )
        val pages = listOf("a.jpg", "b.jpg", "clip.mp4")
        val todo = todoWith(answer, files = pages)

        // One run, one answer, and every Task it touched reads the same — whatever each Task's own
        // folder happened to hold.
        val recorded = pages.map { controlsOf(taskFor(todo, "photos/$it")) }
        assertEquals(1, recorded.toSet().size)

        val settings = AppSettings()
        val control = recorded.first()
        // The order the user pages in: two photographs, then the video.
        assertEquals(
            listOf(3, 3, 2),
            pages.map { settings.controlFor(control, assetKindOf(it)).tags.size }
        )
        // And back again, because the answer is per file and nothing about it is stateful.
        assertEquals(3, settings.controlFor(control, AssetKind.IMAGE).tags.size)
        // The badge row speaks about the Task, so it names all five.
        assertEquals(5, settings.controlForTask(control).tags.size)
    }

    @Test
    fun `a Task with no import row reads as never having chosen`() = runTest {
        // The manual and legacy shape: a Task in a List with no `task_imports` row at all, which is
        // also why the List's own read joins rather than looking each item up on its own. The caller's
        // `orEmpty()` is therefore not a defensive default — it is the answer, resolving the way
        // every Task that predates this version does.
        val todo = todoWith(mapOf(AssetKind.IMAGE to config(tag("i1", "I1", "i1"))))
        val imported = taskFor(todo, "photos/a.jpg")
        val listId = dao.createListFromTasks(
            title = "Trip",
            taskIds = dao.getCatalogTasks(todo).map { it.taskId },
            webhookEnabled = false,
            webhookUrl = "",
            now = 3000
        )
        val manual = database.writeLegacyManualItem(listId = listId, title = "Buy milk", now = 4000)

        // The imported Task beside it is in the read, so the manual Task's absence is a fact about
        // rows rather than about a query that finds nothing at all.
        assertEquals(listOf(imported), dao.getAssetControlsForList(listId).map { it.taskId })
        assertTrue(dao.getAssetControls(listOf(manual)).isEmpty())
        // A Task id nothing has ever heard of is the same read one level further out.
        assertTrue(dao.getAssetControls(listOf("task-that-does-not-exist")).isEmpty())

        // And what that resolves to, through the very `.orEmpty()` the screens use.
        val recorded = dao.getAssetControls(listOf(manual)).firstOrNull()?.assetControls
        assertEquals(
            listOf("i9"),
            AppSettings(assetControlTags = mapOf(AssetKind.IMAGE to listOf(tag("i9", "I9", "i9"))))
                .controlFor(recorded.orEmpty(), AssetKind.IMAGE).tags.map { it.id }
        )
    }

    @Test
    fun `a Task's record is a record, so a kind it never held is still resolvable`() = runTest {
        // The video kind is stamped even though the folder held only a photograph, and that is the
        // point of the run being handed a complete configuration rather than the partial column: a
        // video added to the folder tomorrow is offered the video tags the user configured, rather
        // than the app's default or nothing.
        val answer = mapOf(
            AssetKind.IMAGE to config(tag("i1", "I1", "i1")),
            AssetKind.VIDEO to config(tag("v1", "V1", "v1"))
        )
        val todo = todoWith(answer, files = listOf("a.jpg"))

        val decoded = decodeAssetControls(controlsOf(taskFor(todo, "photos/a.jpg")))
        assertEquals(setOf(AssetKind.IMAGE, AssetKind.VIDEO), decoded.keys)
        assertEquals(listOf("v1"), decoded.getValue(AssetKind.VIDEO).tags.map { it.id })
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun tag(id: String, name: String = id, value: String = id) =
        CustomAction(id = id, displayName = name, webhookValue = value)

    /** Both kinds name the same built-in in this release, and one helper says so once. */
    private fun config(vararg tags: CustomAction) =
        AssetControlConfig(Templates.MediaAssetControl.ref, tags.toList())

    /** A Todo registered and imported once, under [answer] on both its own row and its run. */
    private suspend fun todoWith(
        answer: Map<AssetKind, AssetControlConfig>,
        path: String = "photos",
        displayName: String = "Photos",
        files: List<String> = listOf("a.jpg")
    ): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = path,
            displayName = displayName,
            importerKind = ImporterKind.FILE_AS_TASK,
            assetControls = answer,
            now = 1000
        )
        dao.runImport(source.id, path, filePlan(path, files), 2000, assetControls = answer)
        return source.id
    }

    private fun filePlan(importRoot: String, names: List<String>) = plan(
        ImporterKind.FILE_AS_TASK,
        importRoot,
        FolderSnapshot(names.map { scanned(importRoot, it) }, emptyList())
    )

    private fun scanned(importRoot: String, relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/${childRelativePath(importRoot, relativePath)}"
    )

    /** The id of the Task whose `groupPath` is [groupPath], read the way a screen would find it. */
    private suspend fun taskFor(sourceId: String, groupPath: String): String =
        dao.getCatalogTasks(sourceId).single { it.groupPath == groupPath }.taskId

    /** One Task's recorded column, through the query review reads it with. */
    private suspend fun controlsOf(taskId: String): String =
        dao.getAssetControls(listOf(taskId)).single().assetControls

    private fun rawText(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getString(0)
        }

    private companion object {
        const val ROOT = "content://root"
    }
}
