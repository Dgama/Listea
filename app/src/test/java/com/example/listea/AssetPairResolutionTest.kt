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
import com.example.listea.domain.AssetKind
import com.example.listea.domain.Templates
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which control governs a page, once a Task can be paged through.
 *
 * This is the resolution half of v0.5.1 — [AssetControlTest] is the storage half — and it exists
 * because the release moved the question from the Task to the file. A Task stamps one control
 * *per file kind*, and the page the user is looking at is a file, so the chip row under a photograph
 * and the chip row under the video beside it are resolved from two different entries of the same
 * column. The resolution is done by [AppSettings.controlFor] on the file's own basename, exactly as
 * `ReviewScreen` does it, so what is asserted here is the composition the surface actually performs
 * rather than a paraphrase of it.
 *
 * The fixture is a folder-as-Task import holding one photograph and one video, which is the only
 * shape in which the question is askable: with one file per Task, "the page's kind" and " the Task's
 * kind" are the same answer and a test could not tell them apart. The two kinds are given disjoint
 * tag sets so the two resolutions cannot coincide by luck.
 *
 * The last test is the one v0.5.0 argued for and this release must not lose: a kind the record does
 * not mention resolves to the app's *current default* for that kind rather than to nothing. It is
 * here rather than in the codec tests because that is where the difference would be felt — on a page
 * whose kind the Task has no answer for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetPairResolutionTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao
    private lateinit var settings: AppSettings

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.listsDao()
        // Tags per kind, which is what an unanswered kind falls back to. Non-default on purpose:
        // a fallback assertion against the app's own default would pass on an implementation that
        // read the default from somewhere else.
        settings = AppSettings(
            assetControlTags = mapOf(
                AssetKind.IMAGE to listOf(tag("app-image")),
                AssetKind.VIDEO to listOf(tag("app-video"))
            )
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    // --- which control a page gets ----------------------------------------------------------------

    @Test
    fun `each page of a Task is governed by the control for its own kind`() = runTest {
        val source = picturesAndVideo()
        val row = catalogRow(source.id)

        val image = row.assets.first { it.name == "a.jpg" }
        val video = row.assets.first { it.name == "b.mp4" }

        // One Task, one recorded column, two answers. `assetKindOf` reads the basename, which is
        // why paging changes the row at all — resolving from the Task's name or from its first
        // Asset would give both pages the photograph's tags and look perfectly reasonable.
        assertEquals(
            listOf("p1", "p2"),
            controlFor(row, image.name).tags.map { it.id }
        )
        assertEquals(
            listOf("v1"),
            controlFor(row, video.name).tags.map { it.id }
        )
    }

    @Test
    fun `the badge row over the whole Task offers every kind it is configured for`() = runTest {
        val source = picturesAndVideo()
        val row = catalogRow(source.id)

        // The other resolution, and the reason there are two. A Task's own row is drawn under the
        // Task's name with no particular file on screen, so there is no kind to resolve by and it
        // takes them all, in declaration order — image before video, each kind's tags in their
        // configured order. It is a different question asked of the same column, not a wider
        // version of the first answer.
        assertEquals(
            listOf("p1", "p2", "v1"),
            settings.controlForTask(row.assetControls).tags.map { it.id }
        )
    }

    @Test
    fun `the star follows the definition, and is a property of every page's control`() = runTest {
        val source = picturesAndVideo()
        val row = catalogRow(source.id)

        // `★` is carried by the control, so it is per pair from v0.5.1 — and it is read from the
        // definition the configuration names rather than from a constant, so a build that offered
        // it for one kind and not the other would be visible here rather than only in the app.
        assertTrue(controlFor(row, "a.jpg").favourite)
        assertTrue(controlFor(row, "b.mp4").favourite)
    }

    // --- and what a page's marks belong to ---------------------------------------------------------

    @Test
    fun `a tag put on one page is not on the page beside it`() = runTest {
        val source = picturesAndVideo()
        val before = catalogRow(source.id)
        val image = before.assets.first { it.name == "a.jpg" }

        // The tag's id is taken from the control the page itself offers, which is how the surface
        // gets one: it hands the chip's action straight to the write.
        val offered = controlFor(before, image.name).tags.first().id
        dao.setAssetAction(before.taskId, image.assetId, ItemAction.Custom(offered), enabled = true, now = 2000)

        val after = catalogRow(source.id)
        assertEquals(
            setOf(offered),
            after.assets.first { it.name == "a.jpg" }.decisions.customActions
        )
        // The video page is untouched, and its own control's tag is not on the photograph either —
        // the two tag sets are disjoint, so a leak in either direction is legible. That second
        // half is what a per-Task answer would fail: the video's tag is offered by a control the
        // Task records, so nothing but the page's kind keeps it off this file.
        assertEquals(
            AssetDecisions(),
            after.assets.first { it.name == "b.mp4" }.decisions
        )
        assertTrue(
            "v1" !in after.assets.first { it.name == "a.jpg" }.decisions.customActions
        )
    }

    // --- a kind the record has no answer for --------------------------------------------------------

    @Test
    fun `a kind the Task was never configured for resolves to the app's default for that kind`() =
        runTest {
            // A Task imported when its folder held only photographs, and a video that joined it
            // later — the case `controlFor`'s fallback exists for.
            val source = folderTask(controls = mapOf(AssetKind.IMAGE to config(tag("p1"))))
            val row = catalogRow(source.id)
            val video = row.assets.first { it.name == "b.mp4" }

            // Not empty: a chip row showing nothing but `★` is an answer nobody chose. The
            // fallback is the app's current default, which is the same answer the Create Tasks
            // dialog would have pre-filled, and the same one `defaultControlConfigs` reports.
            assertEquals(
                listOf("app-video"),
                controlFor(row, video.name).tags.map { it.id }
            )
            // And the kind that *is* answered keeps its recorded answer rather than the default.
            assertEquals(
                listOf("p1"),
                controlFor(row, "a.jpg").tags.map { it.id }
            )
        }

    @Test
    fun `a kind the record answers with no tags offers none, and is not the default`() = runTest {
        // The middle state, and the one a fallback is most likely to swallow: a kind configured on
        // purpose to hold nothing. It must read as "no tags", not as "never chose" — otherwise a
        // user who cleared the image row gets the app's defaults back the next time they look.
        val source = folderTask(controls = mapOf(AssetKind.IMAGE to config()))
        val row = catalogRow(source.id)

        assertEquals(emptyList<CustomAction>(), controlFor(row, "a.jpg").tags)
        // Still a configured control, so `★` still comes from its definition rather than from the
        // app's default for a kind nobody answered.
        assertTrue(controlFor(row, "a.jpg").favourite)
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** The resolution the review screen performs, on one of a Task's files. */
    private fun controlFor(row: CatalogRow, assetName: String): ResolvedAssetControl =
        settings.controlFor(row.assetControls, assetKindOf(assetName))

    private suspend fun catalogRow(sourceId: String): CatalogRow =
        dao.getCatalogTasks(sourceId).single()

    /** One Task over a folder holding a photograph and a video, governed per kind. */
    private suspend fun picturesAndVideo(): SourceEntity = folderTask(
        controls = mapOf(
            AssetKind.IMAGE to config(tag("p1"), tag("p2")),
            AssetKind.VIDEO to config(tag("v1"))
        )
    )

    private suspend fun folderTask(
        controls: Map<AssetKind, AssetControlConfig>
    ): SourceEntity {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "",
            displayName = "Root",
            importerKind = ImporterKind.FOLDER_AS_TASK,
            keepTree = true,
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
            assetControls = controls
        )
        return source
    }

    private fun scanned(relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/$relativePath"
    )

    private fun config(vararg tags: CustomAction) = AssetControlConfig(
        ref = Templates.MediaAssetControl.ref,
        tags = tags.toList()
    )

    private fun tag(id: String) = CustomAction(id = id, displayName = id, webhookValue = id)

    private companion object {
        const val ROOT = "content://root"

        /** One photograph, one video, under a single child folder so both land on one Task. */
        val FILES = listOf("alice/a.jpg", "alice/b.mp4")
    }
}
