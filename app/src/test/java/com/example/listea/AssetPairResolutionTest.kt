package com.example.listea

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.AssetDecisions
import com.example.listea.data.CatalogRow
import com.example.listea.data.ItemAction
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.ScannedFile
import com.example.listea.data.SourceEntity
import com.example.listea.data.TemplateEntity
import com.example.listea.data.TemplateVersionEntity
import com.example.listea.domain.AssetKind
import com.example.listea.domain.ButtonType
import com.example.listea.domain.TEMPLATE_BUTTONS_VERSION
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateScope
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.Templates
import com.example.listea.domain.encodeTemplateButtons
import com.example.listea.domain.encodeTemplateScope
import com.example.listea.encodeTemplateAttachments
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which control governs a page, once a Task can be paged through.
 *
 * This is the resolution half of v0.5.1 — [TemplateAttachmentTest] is the storage half — and it
 * exists because the release moved the question from the Task to the file. The page the user is
 * looking at is a file, so the chip row under a photograph and the chip row under the video beside
 * it are two answers out of one recorded column. The resolution is
 * [TemplateResolver.controlFor] on the file's own basename, exactly as `ReviewScreen` does it, so
 * what is asserted here is the composition the surface actually performs rather than a paraphrase
 * of it.
 *
 * The fixture is a folder-as-Task import holding one photograph and one video, which is the only
 * shape in which the question is askable: with one file per Task, "the page's kind" and "the Task's
 * kind" are the same answer and a test could not tell them apart. The two kinds are given disjoint
 * button sets so the two resolutions cannot coincide by luck.
 *
 * **What v0.5.4 changed here, and it is the reverse of what v0.5.1 argued for.** The last two tests
 * used to pin a *fallback*: a kind the Task's record did not mention read as the app's current
 * default for that kind, and a kind configured to hold nothing read as "no tags" rather than as
 * "never chose". The second of those is still the rule and is still here. The first is gone —
 * decision 9 — and its replacement is asserted in its place: a kind no attached template admits
 * draws no chips at all. The old assertion would pass against a resolver that still fell back,
 * which is exactly why it had to be rewritten rather than deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetPairResolutionTest {

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

    // --- which control a page gets ----------------------------------------------------------------

    @Test
    fun `each page of a Task is governed by the template that admits its own kind`() = runTest {
        val source = picturesAndVideo()
        val row = catalogRow(source.id)

        // One Task, one recorded column, two answers. `assetKindOf` reads the basename, which is
        // why paging changes the row at all — resolving from the Task's name or from its first
        // Asset would give both pages the photograph's buttons and look perfectly reasonable.
        assertEquals(listOf("p1", "p2"), tagIds(controlFor(row, "a.jpg")))
        assertEquals(listOf("v1"), tagIds(controlFor(row, "b.mp4")))
    }

    @Test
    fun `the badge row over the whole Task offers every attached template's buttons`() = runTest {
        val source = picturesAndVideo()
        val row = catalogRow(source.id)

        // The other resolution, and the reason there are two. A Task's own row is drawn under the
        // Task's name with no particular file on screen, so there is no file to scope by and it
        // takes them all, in attachment order — image before video, each template's buttons in
        // their own order. It is a different question asked of the same column, not a wider version
        // of the first answer.
        assertEquals(
            listOf("p1", "p2", "v1"),
            tagIds(resolver().controlForTask(row.assetControls))
        )
    }

    @Test
    fun `the star is carried by each template's own buttons rather than by the screen`() = runTest {
        val source = picturesAndVideo()
        val row = catalogRow(source.id)

        // `★` is a button of a version, so it is per page exactly as every other button is — and it
        // is read off the stored buttons rather than drawn as a constant, so a build that offered
        // it for one kind and not the other would be visible here rather than only in the app.
        assertTrue(ItemAction.Favourite in controlFor(row, "a.jpg")!!.actions)
        assertTrue(ItemAction.Favourite in controlFor(row, "b.mp4")!!.actions)
    }

    // --- and what a page's marks belong to ---------------------------------------------------------

    @Test
    fun `a button put on one page is not on the page beside it`() = runTest {
        val source = picturesAndVideo()
        val before = catalogRow(source.id)
        val image = before.assets.first { it.name == "a.jpg" }

        // The button's id is taken from the control the page itself offers, which is how the surface
        // gets one: it hands the chip's action straight to the write.
        val offered = controlFor(before, image.name)!!
            .actions.filter { it != ItemAction.Favourite }
            .first()
        dao.setAssetAction(before.taskId, image.assetId, offered, enabled = true, now = 2000)

        val after = catalogRow(source.id)
        assertEquals(
            setOf(offered.id),
            after.assets.first { it.name == "a.jpg" }.decisions.selections
        )
        // The video page is untouched, and its own button is not on the photograph either — the two
        // button sets are disjoint, so a leak in either direction is legible. That second half is
        // what a per-Task answer would fail: the video's button is offered by a template the Task is
        // attached to, so nothing but the page's own kind keeps it off this file.
        assertEquals(
            AssetDecisions(),
            after.assets.first { it.name == "b.mp4" }.decisions
        )
        assertTrue(
            "v1" !in after.assets.first { it.name == "a.jpg" }.decisions.selections
        )
    }

    // --- a kind no attached template admits ---------------------------------------------------------

    @Test
    fun `a kind no template admits draws no chips at all`() = runTest {
        // The acceptance case, and the whole of decision 9 seen from a page: a Task attached only to
        // an image-scoped template, looking at the video beside the photograph. Until this release
        // the video read as the app's current default for video, which is what a chip row nobody
        // chose looks like — and the old assertion here passed against exactly that.
        val attachments = template(
            "Photos",
            setOf(AssetKind.IMAGE),
            button("p1")
        )
        val row = catalogRow(folderTask(attachments).id)

        assertNull(controlFor(row, "b.mp4"))
        // And the composition the surface performs: null is drawn as an empty bar rather than as
        // somebody else's buttons.
        assertEquals(
            ResolvedAssetControl.Empty,
            controlFor(row, "b.mp4") ?: ResolvedAssetControl.Empty
        )
        // The page the template *does* admit is unaffected, so this is a scope and not a failure to
        // resolve at all.
        assertEquals(listOf("p1"), tagIds(controlFor(row, "a.jpg")))
    }

    @Test
    fun `a template that holds no buttons is still a template, and is not nothing`() = runTest {
        // The middle state, and the one a fallback is most likely to swallow: a template attached on
        // purpose to hold nothing but `★`. It resolves — the scope admits the file — so the two
        // answers stay distinguishable: an empty control is a choice, and a null one is the absence
        // of one. Only the first draws `★`.
        val attachments = template("Photos", setOf(AssetKind.IMAGE))
        val row = catalogRow(folderTask(attachments).id)

        assertEquals(emptyList<String>(), tagIds(controlFor(row, "a.jpg")))
        assertEquals(listOf(ItemAction.Favourite), controlFor(row, "a.jpg")!!.actions)
        // Nothing was attached for video, and that is a different answer rather than the same one.
        assertNull(controlFor(row, "b.mp4"))
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** The resolution the review screen performs, on one of a Task's files. */
    private suspend fun controlFor(row: CatalogRow, assetName: String): ResolvedAssetControl? =
        resolver().controlFor(row.assetControls, assetKindOf(assetName))

    /** Just the buttons of a control, with `★` left out — the ids the chip row would draw. */
    private fun tagIds(control: ResolvedAssetControl?): List<String> =
        control?.actions.orEmpty().filter { it != ItemAction.Favourite }.map { it.id }

    private suspend fun resolver(): TemplateResolver = TemplateResolver.of(
        database.templateDao().templatesOnce(),
        database.templateDao().versionsOnce()
    )

    private suspend fun catalogRow(sourceId: String): CatalogRow =
        dao.getCatalogTasks(sourceId).single()

    /** One Task over a folder holding a photograph and a video, governed by two scoped templates. */
    private suspend fun picturesAndVideo(): SourceEntity = folderTask(
        answer(
            template("Photos", setOf(AssetKind.IMAGE), button("p1"), button("p2")),
            template("Clips", setOf(AssetKind.VIDEO), button("v1"))
        )
    )

    private suspend fun folderTask(attachments: String): SourceEntity {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "",
            displayName = "Root",
            importerKind = ImporterKind.FOLDER_AS_TASK,
            keepTree = true,
            assetControls = attachments,
            now = 1000
        )
        dao.runImport(
            sourceId = source.id,
            coverage = "",
            plan = plan(
                ImporterKind.FOLDER_AS_TASK,
                "",
                FolderSnapshot(FILES.map { scanned(it) }, listOf("alice"))
            ),
            now = 2000,
            assetControls = attachments
        )
        return source
    }

    /**
     * `★` and [tags] over [kinds], written into the two tables and answered as an attachment line.
     *
     * `★` on every template here for the same reason the built-in carries it: it has to be *possible*
     * for a control to hold it, or the tests that separate it from the tags below would be asserting
     * about a button no template ever had.
     */
    private suspend fun template(
        name: String,
        kinds: Set<AssetKind>,
        vararg tags: TemplateButton
    ): String {
        val lineage = "test.$name"
        database.templateWriteDao().insertTemplate(
            TemplateEntity(id = lineage, name = name, createdAt = 1)
        )
        database.templateWriteDao().insertVersion(
            TemplateVersionEntity(
                templateId = lineage,
                version = 1,
                scope = encodeTemplateScope(TemplateScope.ofKinds(kinds)),
                buttons = encodeTemplateButtons(listOf(star) + tags),
                buttonsVersion = TEMPLATE_BUTTONS_VERSION,
                createdAt = 1
            )
        )
        return encodeTemplateAttachments(listOf(TemplateVersionRef(TemplateId(lineage), 1)))
    }

    private fun answer(vararg lines: String): String = lines.joinToString("\n")

    private fun button(id: String) =
        TemplateButton(id = id, name = id, type = ButtonType.TAG, recordValue = id)

    private val star = TemplateButton(
        id = Templates.FAVOURITE_BUTTON_ID,
        name = "★",
        type = ButtonType.BOOLEAN,
        recordValue = Templates.FAVOURITE_BUTTON_ID
    )

    private fun scanned(relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/$relativePath"
    )

    private companion object {
        const val ROOT = "content://root"

        /** One photograph, one video, under a single child folder so both land on one Task. */
        val FILES = listOf("alice/a.jpg", "alice/b.mp4")
    }
}
