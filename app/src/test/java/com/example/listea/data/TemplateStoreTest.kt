package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.domain.AssetKind
import com.example.listea.domain.ButtonType
import com.example.listea.domain.TEMPLATE_BUTTONS_VERSION
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.Templates
import com.example.listea.domain.decodeTemplateButtons
import com.example.listea.domain.decodeTemplateScope
import com.example.listea.domain.encodeTemplateButtons
import com.example.listea.domain.encodeTemplateScope
import kotlinx.coroutines.flow.first
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
 * The one thing that writes a template: an edit appends a version, and nothing revises one.
 *
 * That sentence is the release's promise about history — *the version a Task was imported under is
 * kept* — and it is a promise about a table, so it can only be checked against a table. Every test
 * here therefore reads the rows back rather than the values the store was handed, and the row it
 * edited is compared whole: a version that came back with its payload intact and its `createdAt`
 * rewritten was rewritten, however equal the two look on a screen.
 *
 * **The absence of an update path is asserted by consequence, not by inspection.** A test that
 * looked for a missing `@Update` would be reading the annotation and calling it behaviour; what is
 * checked here is that three edits leave three original rows and a fourth, each holding exactly what
 * it held when it was written — which is the property, and which a store that rewrote a row would
 * fail no matter how it was implemented.
 *
 * Everything is real SQL through Room, for the reason [ListsDaoTest] gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemplateStoreTest {

    private lateinit var database: ListeaDatabase
    private lateinit var store: TemplateStore
    private lateinit var writes: TemplateWriteDao

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val seedId = Templates.AssetControlSeed.ref.templateId.value

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        writes = database.templateWriteDao()
        store = TemplateStore(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    // --- reading ------------------------------------------------------------------------------------

    @Test
    fun `the store hands out what the tables hold`() = runTest {
        seed()

        assertEquals(listOf(seedId), store.templates.first().map { it.id })
        assertEquals(1, store.versions.first().size)
        assertEquals(seedId, store.versions.first().single().templateId)
    }

    // --- an edit appends ----------------------------------------------------------------------------

    @Test
    fun `an edit appends a version and leaves the one it edited exactly as it was`() = runTest {
        seed()
        val before = versions()

        store.update(seedId, name(), scope(), listOf(button("custom1", "Kept", "kept")))

        val after = versions()
        assertEquals(2, after.size)
        // The whole row, not its payload: a version rewritten in place — even into the same text —
        // is a version whose `createdAt` moved, and that is the thing being ruled out.
        assertEquals(before.single(), after.first())

        assertEquals(2, after.last().version)
        // Written through the encoder rather than stored as handed: the scope comes back as the
        // kinds the template governs, which is the form every reader of the column reads.
        assertEquals(setOf(AssetKind.IMAGE, AssetKind.VIDEO), decodeScope(after.last()))
        assertEquals(
            listOf(button("custom1", "Kept", "kept")),
            decodeButtons(after.last())
        )
    }

    @Test
    fun `three edits leave three versions of the template and nothing else`() = runTest {
        seed()

        store.update(seedId, name(), scope(), listOf(button("custom1", "One", "one")))
        store.update(seedId, name(), scope(), listOf(button("custom1", "Two", "two")))
        store.update(seedId, name(), scope(), listOf(button("custom1", "Three", "three")))

        val rows = versions()
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.version })
        // Oldest first, and each one still says what it said: the bar on the template's page offers
        // version n + 1 over a version n that is still readable, which is the whole of what "an
        // earlier version is kept" has to mean to be worth saying to a user.
        assertEquals(listOf("One", "Two", "Three"), rows.drop(1).map { decodeButtons(it).single().name })
    }

    @Test
    fun `every version it writes is stamped with the buttons version this build reads`() = runTest {
        seed()
        store.update(seedId, name(), scope(), listOf(button("custom1", "One", "one")))

        // Without this the payload would be a string nobody had said how to read, and a later build
        // could not tell a bar it understands from one it does not.
        assertTrue(versions().all { it.buttonsVersion == TEMPLATE_BUTTONS_VERSION })
    }

    // --- a rename is not a version ------------------------------------------------------------------

    @Test
    fun `renaming a template writes no version at all`() = runTest {
        seed()
        val before = versions()

        store.update(seedId, "My Templates", scope(), buttons())

        assertEquals("My Templates", lineage()?.name)
        // §Identity: a name is not part of identity, so a rename must not mint an answer a Todo
        // could be holding. It is the one column on a template's page that changes in place.
        assertEquals(before, versions())
    }

    @Test
    fun `an Update that says what the last version said writes nothing`() = runTest {
        seed()
        val before = versions()

        store.update(seedId, name(), scope(), buttons())

        // The bottom bar's disabled Update, enforced where it counts. A version identical to the one
        // before it is a history entry nobody can use, and it would be indistinguishable from an
        // edit — which is the one thing a version number is for.
        assertEquals(before, versions())
        assertEquals(Templates.AssetControlSeed.name, lineage()?.name)
    }

    @Test
    fun `a rename and an edit in one visit write one version and one name`() = runTest {
        seed()

        store.update(seedId, "Renamed", scope(), listOf(button("custom1", "One", "one")))

        assertEquals("Renamed", lineage()?.name)
        assertEquals(2, versions().size)
    }

    // --- what it refuses ----------------------------------------------------------------------------

    @Test
    fun `a version written by a newer build is not written over, name and all`() = runTest {
        seed(buttonsVersion = TEMPLATE_BUTTONS_VERSION + 1)
        val before = versions()

        store.update(seedId, "Renamed", scope(), listOf(button("custom1", "One", "one")))

        // Refused whole rather than in part. The payload is one this build cannot read, so it cannot
        // tell whether the edit it was handed is the same answer written differently or a different
        // answer — and appending version 2 over an unread version 1 is how a history gets a step
        // nothing can explain. The page behind this does not reach the store at all in this state;
        // this is the store not trusting it to be the only caller.
        assertEquals(before, versions())
        assertEquals(Templates.AssetControlSeed.name, lineage()?.name)
    }

    @Test
    fun `an update to a template that is not there creates nothing`() = runTest {
        store.update("no.such.template", "Anything", scope(), listOf(button("custom1", "One", "one")))

        // There is no create path, and this is what that means when something asks for one anyway:
        // a lineage is minted by whatever has something to name — the upgrade, and an import — and
        // never by an editor. A store that inserted one here would put a template on the list that
        // nothing has ever attached.
        assertNull(lineage("no.such.template"))
        assertTrue(store.templates.first().isEmpty())
    }

    @Test
    fun `a template with no version is left alone rather than given one`() = runTest {
        // A lineage somebody renamed is not a template that can be edited: there is no version to
        // append to, and starting at 1 from here would be inventing the first answer rather than
        // recording the next one.
        writes.insertTemplate(TemplateEntity("stub", "Stub", 1L))

        store.update("stub", "Renamed", scope(), listOf(button("custom1", "One", "one")))

        assertEquals("Stub", lineage("stub")?.name)
        assertTrue(versions("stub").isEmpty())
    }

    // --- and what it keeps out of the payload -------------------------------------------------------

    @Test
    fun `a name and a value are kept to one line before they are stored`() = runTest {
        seed()

        store.update(
            seedId,
            "My\tTemplates",
            scope(),
            listOf(button("custom1", "  A\tB  ", "v\r1"))
        )

        // The same sanitiser the tag editor uses, applied here because this is where the text stops
        // being text: a tab in a name would end its field early and hand the rest of the line to the
        // value beside it.
        assertEquals("My Templates", lineage()?.name)
        assertEquals(
            listOf(button("custom1", "A B", "v 1")),
            decodeButtons(versions().last())
        )
    }

    // --- the fixture --------------------------------------------------------------------------------

    /** The built-in, as the migration and the create callback both write it. */
    private suspend fun seed(buttonsVersion: Int = TEMPLATE_BUTTONS_VERSION) {
        val seed = Templates.AssetControlSeed
        writes.insertTemplate(TemplateEntity(seed.ref.templateId.value, seed.name, 1L))
        writes.insertVersion(
            TemplateVersionEntity(
                templateId = seed.ref.templateId.value,
                version = seed.ref.version,
                scope = encodeTemplateScope(seed.scope),
                buttons = encodeTemplateButtons(seed.buttons),
                buttonsVersion = buttonsVersion,
                createdAt = 1L
            )
        )
    }

    private suspend fun lineage(templateId: String = seedId): TemplateEntity? =
        database.templateDao().template(templateId)

    private suspend fun versions(templateId: String = seedId): List<TemplateVersionEntity> =
        database.templateDao().versionsOf(templateId)

    /**
     * The built-in's own answer, so a test that is not about the name, the scope or the buttons
     * leaves all three where they are and edits exactly the one thing it names.
     */
    private val builtIn get() = Templates.AssetControlSeed

    /** The built-in's own name, scope and buttons, reached as `builtIn.name` and so on. */
    private fun name() = builtIn.name
    private fun scope() = builtIn.scope
    private fun buttons() = builtIn.buttons

    private fun button(id: String, name: String, value: String) =
        TemplateButton(id, name, ButtonType.TAG, value)

    private fun decodeScope(version: TemplateVersionEntity): Set<AssetKind> =
        decodeTemplateScope(version.scope).kinds

    private fun decodeButtons(version: TemplateVersionEntity): List<TemplateButton> =
        decodeTemplateButtons(version.buttons, version.buttonsVersion).orEmpty()
}
