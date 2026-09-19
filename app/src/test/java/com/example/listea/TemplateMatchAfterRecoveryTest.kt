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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a restored mark follows when the template has moved on.
 *
 * A history carries **ids**. A record is a set of [TemplateButton.id], and neither a name nor a
 * record value is stored anywhere on it — `TemplateButton`'s own note says so: *"[id] is the whole of
 * it, and everything else about a button is answered by the version the id came from."*
 *
 * So a recovery writes the ids it carried and asks nothing about the template that is waiting for
 * them; the question *does this button still exist* is answered later, at the surface, by
 * [TemplateResolver.controlFor], which is built **from** the stored version. An id the version does
 * not define is never offered, never labelled and never drawn — see `ResolvedAssetControl`'s note on
 * `wireOf`, and [AssetPairResolutionTest], which is the same resolution asked about a live Task
 * rather than about a recovered one.
 *
 * The two halves of that are tested here, and they point in opposite directions on purpose, because
 * "matched by id" is only meaningful if renaming is a non-event *and* renaming-into-a-new-slot is a
 * miss. A test with one half would pass for an implementation that matched on the label.
 *
 * **What changed with v0.5.4, and why these tests survived it.** The two imports used to differ by
 * their *configuration* — a tag list written onto the Todo — and now differ by the *template they
 * attach*, which is the same statement one level up. What did not change is the property under test,
 * and that is the point: a record names an id, a template answers for it, and a recovery is the
 * moment the two can most easily disagree.
 *
 * Nothing here is about the star being special, and the last test says so: `★` is one id in the same
 * set as any other, carried by the version's own button list rather than by a flag beside it.
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

    // --- the same button, renamed ------------------------------------------------------------------

    @Test
    fun `a button renamed under the same id comes back, and answers to its new name`() = runTest {
        val todo = folderTask(LINEAGE, button("custom1", "C1", "cust1"))
        mark(todo, ItemAction("custom1"))
        dao.deleteTodo(todo, keepState = true, now = 5000)

        // The user renames the slot and changes what it sends. The id is untouched, which is what
        // the template editor does — an edit appends a version and keeps the buttons' ids.
        val fresh = reimport(todo, LINEAGE, 2, button("custom1", "Urgent", "urgent"))

        val decisions = fileOf(fresh).decisions
        assertEquals(setOf("custom1"), decisions.selections)

        // And the surface names it the new way. This is the half that makes the storage choice
        // legible: the record kept its word, the label moved, and no row was migrated.
        val control = controlOf(fresh)
        assertEquals(listOf("Urgent"), control.actions.filter { it.isSetOn(decisions) }
            .map { control.labelOf(it) })
        assertEquals(listOf("urgent"), control.actions.filter { it.isSetOn(decisions) }
            .map { control.wireOf(it) })
    }

    // --- a different button, sharing the name ------------------------------------------------------

    @Test
    fun `a button that only looks the same is a different button, and carries nothing`() = runTest {
        val todo = folderTask(LINEAGE, button("custom1", "C1", "cust1"))
        mark(todo, ItemAction("custom1"))
        dao.deleteTodo(todo, keepState = true, now = 5000)

        // Deleted from the template, and a new one added and called `C1` again. It is a fresh button
        // with a fresh id, so the restored mark points at a button this version does not define.
        val fresh = reimport(todo, OTHER, 1, button("a4f9c2b7e1d0", "C1", "cust1"))

        // The id is still on the row — a recovery does not consult the template and has no business
        // dropping what it carried — and nothing draws it. The chip row under the file is unticked,
        // the badge row is empty and the payload omits it, all by the one omission.
        val decisions = fileOf(fresh).decisions
        assertEquals(setOf("custom1"), decisions.selections)

        val control = controlOf(fresh)
        assertEquals(
            emptyList<ItemAction>(),
            control.actions.filter { it.isSetOn(decisions) }
        )
        // Named the same, sent the same, and still not the same button: what the user sees is an
        // unticked row, which is the honest answer to a question they asked in a different place.
        // The `★` leads because every version this fixture writes offers one — it is a button of the
        // version now rather than a column beside it, so it is listed here like any other button and
        // the row reads `★ C1`, with neither of them ticked.
        assertEquals(listOf("★", "C1"), control.buttons.map { it.name })
    }

    // --- and the one id that used to be a column of its own ------------------------------------------

    @Test
    fun `the star comes back as one id in the set, and is answerable like any other`() = runTest {
        val todo = folderTask(LINEAGE, button("custom1", "C1", "cust1"))
        mark(todo, ItemAction("custom1"), ItemAction.Favourite)
        dao.deleteTodo(todo, keepState = true, now = 5000)

        // A version sharing nothing with the old one but the star. Until v0.5.4 `★` was a boolean
        // column and came back whatever the tags said; it is a button of the version now, so it
        // comes back because *this* version offers it — which is the same rule every other id is
        // read by, and the whole of what decision 5 changed.
        val fresh = reimport(todo, LINEAGE, 2, button("a4f9c2b7e1d0", "Later", "later"))

        val decisions = fileOf(fresh).decisions
        assertEquals(
            AssetDecisions(selections = setOf("custom1", Templates.FAVOURITE_BUTTON_ID)),
            decisions
        )

        val control = controlOf(fresh)
        assertEquals(
            listOf(ItemAction.Favourite),
            control.actions.filter { it.isSetOn(decisions) }
        )
    }

    // --- the fixture ------------------------------------------------------------------------------

    /**
     * A folder-as-Task Todo over one file, governed by a one-button template.
     *
     * One file, because what is being asked is about the button rather than about which file it
     * landed on — that is [com.example.listea.data.HistoryRecordsTest]'s question, and a second file
     * here would only make the failure modes harder to read.
     *
     * The template is written straight into the two tables rather than through the editor, because
     * what is under test is what a *reader* does with a stored answer; `TemplateStoreTest` is where
     * the write path is asked about.
     */
    private suspend fun folderTask(lineage: String, button: TemplateButton): String =
        import(lineage, version = 1, button, recoverFrom = null)

    /**
     * The same Todo again, born knowing whatever [recoverFrom] has to say, under [lineage] at
     * [version] with [button].
     *
     * The lineage and the version are the test's to name rather than the fixture's to derive, because
     * they are what a test is *about*: renaming a button appends a version of the lineage it was in,
     * while replacing one outright is a different lineage — and a fixture that guessed would make the
     * two tests identical.
     */
    private suspend fun reimport(
        recoverFrom: String,
        lineage: String,
        version: Int,
        button: TemplateButton
    ): String = import(lineage, version, button, recoverFrom)

    private suspend fun import(
        lineage: String,
        version: Int,
        button: TemplateButton,
        recoverFrom: String?
    ): String {
        val attachments = seedTemplate(lineage, version, button)
        val now = if (recoverFrom == null) 1000L else 6000L
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = FOLDER,
            displayName = "Photos",
            importerKind = ImporterKind.FOLDER_AS_TASK,
            keepTree = true,
            now = now
        )
        dao.runImport(
            sourceId = source.id,
            coverage = FOLDER,
            plan = plan(
                ImporterKind.FOLDER_AS_TASK,
                FOLDER,
                FolderSnapshot(FILES.map { scanned(it) }, listOf("alice"))
            ),
            now = now + 1000,
            recoverFrom = recoverFrom,
            assetControls = attachments
        )
        return source.id
    }

    /**
     * `★` and [button] as [lineage] version [version], written into the two tables and answered as
     * the attachment string a Todo records.
     *
     * Version 1 is the only one that writes the lineage, because a second version of a lineage
     * already there is exactly what an edit appends — and a fixture that inserted the lineage twice
     * would be failing on the schema rather than on what it asked.
     */
    private suspend fun seedTemplate(
        lineage: String,
        version: Int,
        button: TemplateButton
    ): String {
        if (version == 1) {
            database.templateWriteDao().insertTemplate(
                TemplateEntity(id = lineage, name = "Test $lineage", createdAt = 1)
            )
        }
        database.templateWriteDao().insertVersion(
            TemplateVersionEntity(
                templateId = lineage,
                version = version,
                scope = encodeTemplateScope(TemplateScope.ofKinds(setOf(AssetKind.IMAGE))),
                buttons = encodeTemplateButtons(listOf(star, button)),
                buttonsVersion = TEMPLATE_BUTTONS_VERSION,
                createdAt = 1
            )
        )
        return encodeTemplateAttachments(listOf(TemplateVersionRef(TemplateId(lineage), version)))
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
    private suspend fun controlOf(sourceId: String): ResolvedAssetControl {
        val resolver = TemplateResolver.of(
            database.templateDao().templatesOnce(),
            database.templateDao().versionsOnce()
        )
        return resolver.controlFor(rowOf(sourceId).assetControls, AssetKind.IMAGE)
            ?: ResolvedAssetControl.Empty
    }

    private val star = TemplateButton(
        id = Templates.FAVOURITE_BUTTON_ID,
        name = "★",
        type = ButtonType.BOOLEAN,
        recordValue = Templates.FAVOURITE_BUTTON_ID
    )

    /**
     * A button as a template holds it. The id is the first argument everywhere it is used, because
     * it is the only one of the four that a record ever keeps.
     */
    private fun button(id: String, name: String, recordValue: String) =
        TemplateButton(id = id, name = name, type = ButtonType.TAG, recordValue = recordValue)

    private fun scanned(relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/$FOLDER/$relativePath"
    )

    private companion object {
        const val ROOT = "content://root"
        const val FOLDER = "photos"

        /** The lineage a rename stays inside, and the one a replacement moves to. */
        const val LINEAGE = "test.media"
        const val OTHER = "test.other"

        val FILES = listOf("alice/a.jpg")
    }
}
