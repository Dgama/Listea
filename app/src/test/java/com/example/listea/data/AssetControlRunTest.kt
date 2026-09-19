package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.ResolvedAssetControl
import com.example.listea.TemplateResolver
import com.example.listea.assetKindOf
import com.example.listea.childRelativePath
import com.example.listea.decodeTemplateAttachments
import com.example.listea.encodeTemplateAttachments
import com.example.listea.plan
import com.example.listea.domain.AssetKind
import com.example.listea.domain.ButtonType
import com.example.listea.domain.TEMPLATE_BUTTONS_VERSION
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateScope
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.encodeTemplateButtons
import com.example.listea.domain.encodeTemplateScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What an import run does to a Task's asset control, and what a screen reading it back gets.
 *
 * The *codec and the lookup* are pinned in [com.example.listea.TemplateAttachmentTest], which is pure
 * and has no database. This is the other half and the one that could not be pure, because the
 * guarantee this release rests on is about two writes rather than about one value:
 *
 * > both records — the Todo's and each Task's — come from `options.templates`, and neither is read
 * > back from the row the other one just wrote.
 *
 * That is weaker than "one transaction" and cannot be checked by any compiler. A new Todo's
 * `sources` row is written *before* the run that writes its Tasks, so the two are in different
 * transactions on different sides of the run; what keeps them consistent is that neither asks the
 * database what the answer was. An implementation that stamped the Tasks from the row it had just
 * written would still pass every test that only ever imports once — which is why the first test
 * here imports once, with an answer that deliberately disagrees with the row.
 *
 * **What the answer is changed; what is guaranteed about it did not.** It used to be a per-kind
 * configuration and it is now a string of attachments, which is why every fixture below writes a
 * template into the two tables before it names one. The property under test is the same one, and it
 * is the one that would be worth nothing if the release had moved the value without moving the
 * guarantee.
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
        val stored = template("Old", setOf(AssetKind.IMAGE), listOf(button("old", "Old", "old")))
        val todo = todoWith(stored)
        val task = taskFor(todo, "photos/a.jpg")
        assertEquals(stored, controlsOf(task))

        val handed = answer(
            template("New", setOf(AssetKind.IMAGE), listOf(button("new", "New", "new"))),
            template("Watch", setOf(AssetKind.VIDEO), listOf(button("v1", "Watch", "watch")))
        )
        // Once. A second run over the same folder would stamp the same answer whether the run read
        // the argument or the row, and would hide exactly the failure this test is for.
        dao.runImport(todo, "photos", filePlan("photos", listOf("a.jpg")), 3000, assetControls = handed)

        assertEquals(handed, controlsOf(task))
        assertNotEquals(stored, controlsOf(task))
        // And the Todo's own row is not a second writer: recording the answer belongs to whoever
        // asked the question, and a run that also wrote its Todo's column would fight the caller.
        assertEquals(
            stored,
            rawText("SELECT assetControls FROM sources WHERE id = '$todo'")
        )
    }

    @Test
    fun `a run over the same folder stamps the same answer again`() = runTest {
        // Re-stamping on every run is deliberate rather than incidental: a run over an unchanged
        // folder is still a run, and the user asked for it. What matters is that it is idempotent,
        // so a folder watched over weeks does not drift a Task's record one run at a time.
        val answer = template("Shared", setOf(AssetKind.IMAGE), listOf(button("i1", "I1", "i1")))
        val todo = todoWith(answer)
        val task = taskFor(todo, "photos/a.jpg")

        dao.runImport(todo, "photos", filePlan("photos", listOf("a.jpg")), 3000, assetControls = answer)

        assertEquals(answer, controlsOf(task))
    }

    @Test
    fun `a run given no answer clears the record rather than keeping the old one`() = runTest {
        // The honest description of every existing caller: `templates` defaults to empty, so a run
        // that says nothing about a control stamps the absence of an answer.
        //
        // **And what that absence resolves to is not what it was.** Under v0.5.0 it meant *the app's
        // current tags*, so a Task whose record was cleared quietly offered whatever Settings had
        // been left holding. It means *no chips at all* now, which is decision 9 and the difference
        // this test was rewritten to pin: the old assertion here would pass against a resolver that
        // still fell back.
        val stored = template("Old", setOf(AssetKind.IMAGE), listOf(button("old", "Old", "old")))
        val todo = todoWith(stored)
        val task = taskFor(todo, "photos/a.jpg")

        dao.runImport(todo, "photos", filePlan("photos", listOf("a.jpg")), 3000)

        assertEquals("", controlsOf(task))
        assertNull(resolver().controlFor(controlsOf(task), AssetKind.IMAGE))
    }

    @Test
    fun `editing a template changes nothing until the next run`() = runTest {
        // Re-stamping happens on a run and nowhere else, so there is no path from a template edit to
        // a row: a Task keeps the *version* it was imported under however the template moves on
        // underneath it. That is stronger than the claim this test used to make, and it is the whole
        // reason a version number is written down beside the id — an edit appends, it does not
        // revise, so the old answer stays readable and the two genuinely disagree in between.
        val first = template("Shared", setOf(AssetKind.IMAGE), listOf(button("i1", "I1", "i1")))
        val todo = todoWith(first)
        val task = taskFor(todo, "photos/a.jpg")

        // The user edits the template: the tag is renamed and rewired, and the version moves on.
        val second = appendVersion(
            "test.Shared",
            setOf(AssetKind.IMAGE),
            listOf(button("i1", "I9", "i9"))
        )
        val resolver = resolver()

        assertEquals(first, controlsOf(task))
        assertEquals(first, rawText("SELECT assetControls FROM sources WHERE id = '$todo'"))
        // The Task was imported under version 1, and version 1 still says what it said. Read as
        // `id→wire` rather than as the id alone, because the id is the half an edit deliberately
        // keeps: a resolver that ignored the version and answered from the lineage's newest would
        // return this same id, and only the value it sends would say so.
        assertEquals("i1" to "i1", answeredBy(resolver, controlsOf(task)))
        assertEquals("i1" to "i1", answeredBy(resolver, first))
        assertEquals("i1" to "i9", answeredBy(resolver, second))

        // And the run, not the edit, is what reaches the Task.
        dao.runImport(todo, "photos", filePlan("photos", listOf("a.jpg")), 4000, assetControls = second)

        assertEquals(second, controlsOf(task))
        assertEquals("i1" to "i9", answeredBy(resolver, controlsOf(task)))
    }

    @Test
    fun `a run over one Todo leaves another Todo's Tasks alone`() = runTest {
        val one = template("One", setOf(AssetKind.IMAGE), listOf(button("i1", "I1", "i1")))
        val two = template("Two", setOf(AssetKind.IMAGE), listOf(button("i2", "I2", "i2")))
        val first = todoWith(one, path = "photos")
        val second = todoWith(two, path = "clips", displayName = "Clips")
        val taskOfSecond = taskFor(second, "clips/a.jpg")

        // The first Todo runs again, under a third answer entirely.
        val third = template("Three", setOf(AssetKind.IMAGE), listOf(button("i3", "I3", "i3")))
        dao.runImport(first, "photos", filePlan("photos", listOf("a.jpg")), 4000, assetControls = third)

        assertEquals(two, controlsOf(taskOfSecond))
    }

    // --- what a screen reading it back gets -------------------------------------------------------

    @Test
    fun `one folder's Tasks carry both kinds, and paging finds each one's own buttons`() = runTest {
        // The acceptance list, one level below the screen: three image buttons, two video buttons,
        // and a folder holding both kinds. What the review screen does with this is
        // [TemplateResolver.controlFor] per file — the middle band — and [TemplateResolver.controlForTask]
        // for the badge row.
        val images = listOf(
            button("i1", "Quality", "quality"),
            button("i2", "Price", "price"),
            button("i3", "Archive", "archive")
        )
        val videos = listOf(button("v1", "Watch", "watch"), button("v2", "Later", "later"))
        // Two templates rather than one, because a template's scope is what decides which of them
        // answers a given file — the shape change `v0.5.4.md` §The scope describes, and the reason
        // the old per-kind configuration had to become an ordered list of attachments.
        val answer = answer(
            template("Photos", setOf(AssetKind.IMAGE), images),
            template("Clips", setOf(AssetKind.VIDEO), videos)
        )
        val pages = listOf("a.jpg", "b.jpg", "clip.mp4")
        val todo = todoWith(answer, files = pages)

        // One run, one answer, and every Task it touched reads the same — whatever each Task's own
        // folder happened to hold.
        val recorded = pages.map { controlsOf(taskFor(todo, "photos/$it")) }
        assertEquals(1, recorded.toSet().size)

        val resolver = resolver()
        val control = recorded.first()
        // The order the user pages in: two photographs, then the video.
        assertEquals(
            listOf(3, 3, 2),
            pages.map { resolver.controlFor(control, assetKindOf(it))?.buttons?.size }
        )
        // And back again, because the answer is per file and nothing about it is stateful.
        assertEquals(3, resolver.controlFor(control, AssetKind.IMAGE)?.buttons?.size)
        // The badge row speaks about the Task, so it names all five.
        assertEquals(5, resolver.controlForTask(control).buttons.size)
    }

    @Test
    fun `a Task with no import row reads as never having chosen`() = runTest {
        // The manual and legacy shape: a Task in a List with no `task_imports` row at all, which is
        // also why the List's own read joins rather than looking each item up on its own. The caller's
        // `orEmpty()` is therefore not a defensive default — it is the answer, and what it resolves
        // to is no chips at all. See `a run given no answer clears the record` for the same reading
        // reached from the other side.
        val todo = todoWith(template("Photos", setOf(AssetKind.IMAGE), listOf(button("i1", "I1", "i1"))))
        val imported = taskFor(todo, "photos/a.jpg")
        val listId = dao.createListFromTasks(
            title = "Trip",
            taskIds = dao.getCatalogTasks(todo).map { it.taskId },
            exportOnCompletion = false,
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
        assertNull(resolver().controlFor(recorded.orEmpty(), AssetKind.IMAGE))
    }

    @Test
    fun `a Task's record is a record, so a kind it never held is still resolvable`() = runTest {
        // The video template is attached even though the folder held only a photograph, and that is
        // the point of the run being handed a complete answer rather than the part of it the folder
        // happened to exercise: a video added to the folder tomorrow is offered the video buttons
        // the user attached, rather than nothing at all.
        val answer = answer(
            template("Photos", setOf(AssetKind.IMAGE), listOf(button("i1", "I1", "i1"))),
            template("Clips", setOf(AssetKind.VIDEO), listOf(button("v1", "V1", "v1")))
        )
        val todo = todoWith(answer, files = listOf("a.jpg"))

        val recorded = controlsOf(taskFor(todo, "photos/a.jpg"))
        assertEquals(
            listOf("test.Photos", "test.Clips"),
            decodeTemplateAttachments(recorded).map { it.templateId.value }
        )
        assertEquals(
            listOf(ResolvedAssetControl(listOf(button("v1", "V1", "v1")))),
            listOf(resolver().controlFor(recorded, AssetKind.VIDEO))
        )
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /**
     * One template written into the two tables, answered as the attachment line that names it.
     *
     * A *line* rather than a whole column, because several tests here attach two templates that
     * govern different kinds — which is what a Todo configured two ways becomes — and joining them
     * is the caller's one job.
     */
    private suspend fun template(
        name: String,
        kinds: Set<AssetKind>,
        tags: List<TemplateButton>
    ): String {
        database.templateWriteDao().insertTemplate(
            TemplateEntity(id = "test.$name", name = name, createdAt = 1)
        )
        return appendVersion("test.$name", kinds, tags)
    }

    /**
     * One more version of a lineage already written, answered as the attachment line that names it.
     *
     * The second half of an edit, and separate from [template] because a version is appended to a
     * lineage rather than minted beside it — the same distinction the store makes, and the reason
     * the template the Task was imported under stays readable.
     */
    private suspend fun appendVersion(
        lineage: String,
        kinds: Set<AssetKind>,
        tags: List<TemplateButton>
    ): String {
        val version = database.templateDao().versionsOf(lineage).size + 1
        database.templateWriteDao().insertVersion(
            TemplateVersionEntity(
                templateId = lineage,
                version = version,
                scope = encodeTemplateScope(TemplateScope.ofKinds(kinds)),
                buttons = encodeTemplateButtons(tags),
                buttonsVersion = TEMPLATE_BUTTONS_VERSION,
                createdAt = version.toLong()
            )
        )
        return encodeTemplateAttachments(listOf(TemplateVersionRef(TemplateId(lineage), version)))
    }

    /** Several attachment lines as one column, in the order given. */
    private fun answer(vararg lines: String): String = lines.joinToString("\n")

    /** The resolver for everything written so far — what a screen would hold. */
    private suspend fun resolver(): TemplateResolver = TemplateResolver.of(
        database.templateDao().templatesOnce(),
        database.templateDao().versionsOnce()
    )

    private fun button(id: String, name: String = id, value: String = id) =
        TemplateButton(id = id, name = name, type = ButtonType.TAG, recordValue = value)

    /** A Todo registered and imported once, under [answer] on both its own row and its run. */
    private suspend fun todoWith(
        answer: String,
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

    /**
     * What an attachment resolves to for an image, as `id→wire` — or null when it resolves to
     * nothing at all.
     *
     * Both halves, because they move independently: an edit keeps a button's id and changes what it
     * sends, so an assertion on the id alone cannot tell two versions of one lineage apart. `single`
     * because every call site here attaches a one-button template; a second button would make the
     * answer ambiguous rather than longer.
     */
    private suspend fun answeredBy(resolver: TemplateResolver, attachments: String) =
        resolver.controlFor(attachments, AssetKind.IMAGE)?.buttons?.single()
            ?.let { it.id to it.recordValue }

    private fun rawText(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { row ->
            assertTrue("no row for: $sql", row.moveToFirst())
            row.getString(0)
        }

    private companion object {
        const val ROOT = "content://root"
    }
}
