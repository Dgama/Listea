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
 * What a restored mark follows when the configuration has moved on.
 *
 * A history carries **ids**. `AssetDecisions.customActions` is a set of [CustomAction.id], a
 * `★` is one boolean, and neither a display name nor a webhook value is stored on a record
 * anywhere — `CustomAction`'s own note says so: *"`[id]` is the only part an item ever holds.
 * `[displayName]` and `[webhookValue]` are free to change under it — that separation is the whole
 * reason renaming an action does not touch a single row."*
 *
 * So a recovery writes the ids it carried and asks nothing about the template that is waiting for
 * them; the question *does this action still exist* is answered later, at the surface, by
 * `ResolvedAssetControl.actions`, which is built **from** the current tags. An id the current
 * configuration does not define is never offered, never labelled and never drawn — see
 * `ResolvedAssetControl`'s note on `wireOf`, and [AssetPairResolutionTest], which is the same
 * resolution asked about a live Task rather than about a recovered one.
 *
 * The two halves of that are tested here, and they point in opposite directions on purpose, because
 * "matched by id" is only meaningful if renaming is a non-event *and* renaming-into-a-new-slot is a
 * miss. A test with one half would pass for an implementation that matched on the label.
 *
 * Nothing here is about `★`: it has no id to match on, so it is carried by the definition rather
 * than by the configuration and comes back whatever the tags say. The last test says so out loud.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemplateMatchAfterRecoveryTest {

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

    // --- the same action, renamed -----------------------------------------------------------------

    @Test
    fun `an action renamed under the same id comes back, and answers to its new name`() = runTest {
        val todo = folderTask(tag(id = "custom1", displayName = "C1", webhookValue = "cust1"))
        mark(todo, ItemAction.Custom("custom1"))
        dao.deleteTodo(todo, keepState = true, now = 5000)

        // The user renames the slot and changes what it sends. The id is untouched, which is what
        // the Settings editor does — `nextCustomActionDefaults` mints an id only for an action that
        // did not exist before, and an edit keeps the one it has.
        val fresh = reimport(
            todo,
            tag(id = "custom1", displayName = "Urgent", webhookValue = "urgent")
        )

        val decisions = fileOf(fresh).decisions
        assertEquals(setOf("custom1"), decisions.customActions)

        // And the surface names it the new way. This is the half that makes the storage choice
        // legible: the record kept its word, the label moved, and no row was migrated.
        val control = controlOf(fresh)
        assertEquals(listOf("Urgent"), control.actions.filter { it.isSetOn(decisions) }
            .map { control.labelOf(it) })
        assertEquals(listOf("urgent"), control.actions.filter { it.isSetOn(decisions) }
            .map { control.wireOf(it) })
    }

    // --- a different action, sharing the name ------------------------------------------------------

    @Test
    fun `an action that only looks the same is a different action, and carries nothing`() = runTest {
        val todo = folderTask(tag(id = "custom1", displayName = "C1", webhookValue = "cust1"))
        mark(todo, ItemAction.Custom("custom1"))
        dao.deleteTodo(todo, keepState = true, now = 5000)

        // Deleted in Settings, and a new one added and called `C1` again. It is a fresh action with
        // a fresh id, so the restored mark points at an action this configuration does not define.
        val fresh = reimport(
            todo,
            tag(id = "a4f9c2b7e1d0", displayName = "C1", webhookValue = "cust1")
        )

        // The id is still on the row — a recovery does not consult the template and has no business
        // dropping what it carried — and nothing draws it. The chip row under the file is unticked,
        // the badge row is empty and the payload omits it, all by the one omission.
        val decisions = fileOf(fresh).decisions
        assertEquals(setOf("custom1"), decisions.customActions)

        val control = controlOf(fresh)
        assertEquals(
            emptyList<ItemAction>(),
            control.actions.filter { it.isSetOn(decisions) }
        )
        // Named the same, sent the same, and still not the same action: what the user sees is an
        // unticked row, which is the honest answer to a question they asked in a different place.
        assertEquals(listOf("C1"), control.tags.map { it.displayName })
    }

    // --- and the one action that has no id at all --------------------------------------------------

    @Test
    fun `the star comes back whatever the tags became`() = runTest {
        val todo = folderTask(tag(id = "custom1", displayName = "C1", webhookValue = "cust1"))
        mark(todo, ItemAction.Custom("custom1"), ItemAction.Favourite)
        dao.deleteTodo(todo, keepState = true, now = 5000)

        // A configuration that shares nothing with the old one, not even a name. `★` is carried by
        // the *definition* rather than by the tags — v0.5.0 put it there so a future control may
        // omit it — so there is nothing for it to fail to match.
        val fresh = reimport(todo, tag(id = "a4f9c2b7e1d0", displayName = "Later", webhookValue = "later"))

        val decisions = fileOf(fresh).decisions
        assertEquals(AssetDecisions(true, setOf("custom1")), decisions)
        assertTrue(controlOf(fresh).favourite)
    }

    // --- the fixture ------------------------------------------------------------------------------

    /**
     * A folder-as-Task Todo over one file, governed by a single tag.
     *
     * One file, because what is being asked is about the action rather than about which file it
     * landed on — that is [com.example.listea.data.HistoryRecordsTest]'s question, and a second file
     * here would only make the failure modes harder to read.
     */
    private suspend fun folderTask(tag: CustomAction): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = FOLDER,
            displayName = "Photos",
            importerKind = ImporterKind.FOLDER_AS_TASK,
            keepTree = true,
            now = 1000
        )
        dao.runImport(
            sourceId = source.id,
            coverage = FOLDER,
            plan = plan(
                ImporterKind.FOLDER_AS_TASK,
                FOLDER,
                FolderSnapshot(FILES.map { scanned(it) }, listOf("alice"))
            ),
            now = 2000,
            assetControls = mapOf(AssetKind.IMAGE to config(tag))
        )
        return source.id
    }

    /** The same Todo again, born knowing whatever [recoverFrom] has to say, under a new [tag]. */
    private suspend fun reimport(recoverFrom: String, tag: CustomAction): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = FOLDER,
            displayName = "Photos",
            importerKind = ImporterKind.FOLDER_AS_TASK,
            keepTree = true,
            now = 6000
        )
        dao.runImport(
            sourceId = source.id,
            coverage = FOLDER,
            plan = plan(
                ImporterKind.FOLDER_AS_TASK,
                FOLDER,
                FolderSnapshot(FILES.map { scanned(it) }, listOf("alice"))
            ),
            now = 6000,
            assetControls = mapOf(AssetKind.IMAGE to config(tag)),
            recoverFrom = recoverFrom
        )
        return source.id
    }

    /** Marks the Task's one file through the write a chip tap reaches. */
    private suspend fun mark(sourceId: String, vararg actions: ItemAction) {
        val row = rowOf(sourceId)
        val asset = row.assets.single()
        for (action in actions) {
            dao.setAssetAction(row.taskId, asset.assetId, action, enabled = true, now = 1500)
        }
    }

    private suspend fun rowOf(sourceId: String): CatalogRow =
        dao.getCatalogTasks(sourceId).single()

    private suspend fun fileOf(sourceId: String) = rowOf(sourceId).assets.single()

    /** The resolution the review screen performs on a page, from the Task's own recorded column. */
    private suspend fun controlOf(sourceId: String): ResolvedAssetControl =
        AppSettings().controlFor(rowOf(sourceId).assetControls, AssetKind.IMAGE)

    private fun config(tag: CustomAction) = AssetControlConfig(
        ref = Templates.MediaAssetControl.ref,
        tags = listOf(tag)
    )

    /**
     * An action as a configuration holds it. The id is the first argument everywhere it is used,
     * because it is the only one of the three that a record ever keeps.
     */
    private fun tag(id: String, displayName: String, webhookValue: String) =
        CustomAction(id = id, displayName = displayName, webhookValue = webhookValue)

    private fun scanned(relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/$FOLDER/$relativePath"
    )

    private companion object {
        const val ROOT = "content://root"
        const val FOLDER = "photos"

        val FILES = listOf("alice/a.jpg")
    }
}
