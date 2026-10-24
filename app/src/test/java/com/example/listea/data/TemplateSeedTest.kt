package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.domain.TEMPLATE_BUTTONS_VERSION
import com.example.listea.domain.Templates
import com.example.listea.domain.encodeTemplateButtons
import com.example.listea.domain.encodeTemplateScope
import kotlinx.coroutines.flow.first
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
 * The built-in, for the database that was never upgraded to get it.
 *
 * [MigrationV23ToV24Test] covers the other half of this and covers it well: a device already holding
 * a database at 23 is migrated, and the migration seeds the two rows. What that leaves out is
 * everybody else — a fresh install runs no migration at all, because Room *creates* the database at
 * the version the app ships, and a template that only exists for people who upgraded is not a
 * built-in.
 *
 * The rows are asserted against the same golden strings the migration test uses rather than against
 * each other, so the two paths agreeing is something a reader can see rather than something this
 * file assumes. That matters more than it looks: they are written through two different SQLite
 * bindings — a migration's `SQLiteConnection` and Android's `SupportSQLiteDatabase` — and a value
 * that survives one binding and not the other is exactly the kind of difference a single golden
 * would report as a mystery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemplateSeedTest {

    private lateinit var database: ListeaDatabase

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val seedId = Templates.AssetControlSeed.ref.templateId.value

    @Before
    fun deleteAnyLeftBehind() {
        context.deleteDatabase(DB)
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `a database that is created rather than upgraded starts with the built-in`() = runTest {
        open()

        val templates = database.templateDao().observeTemplates().first()
        // The Asset Control and the built-in Task Reviews. Read from the same list the seed writes,
        // so a fourth built-in is a row here rather than a number to remember to edit.
        assertEquals(1 + Templates.StoredTaskReviews.size, templates.size)
        val seedRow = templates.single { it.id == seedId }
        assertEquals(Templates.AssetControlSeed.name, seedRow.name)
        assertEquals(
            Templates.StoredTaskReviews.map { it.ref.templateId.value }.toSet(),
            templates.map { it.id }.toSet() - seedId
        )

        val versions = database.templateDao().observeVersions().first()
        assertEquals(1 + Templates.StoredTaskReviews.size, versions.size)

        val seed = Templates.AssetControlSeed
        val seedVersion = versions.single {
            it.templateId == seedId && it.version == seed.ref.version
        }
        assertEquals(encodeTemplateScope(seed.scope), seedVersion.scope)
        assertEquals(encodeTemplateButtons(seed.buttons), seedVersion.buttons)
        assertEquals(TEMPLATE_BUTTONS_VERSION, seedVersion.buttonsVersion)

        // The same two strings `MigrationV23ToV24Test` pins, because a device that installed this
        // release and a device that upgraded into it have to hold one template between them.
        assertEquals("kind:image,video", seedVersion.scope)
        assertEquals(
            "favorite\t★\tboolean\tfavorite\ncustom1\tC1\ttag\tcust1\ncustom2\tC2\ttag\tcust2",
            seedVersion.buttons
        )
    }

    @Test
    fun `the seed is not applied again to a database that already exists`() = runTest {
        open()
        database.templateWriteDao().renameTemplate(seedId, "Renamed by the user")
        database.close()

        // The guard is *the table is empty*, and this is why it is not *the built-in is missing*: a
        // rename is the user's, and an open that put the name back would be an app that undoes an
        // edit on every launch. Read the other way round, this is the delete case the store's note
        // names — a table with rows in it is a table somebody is using.
        open()
        assertEquals(
            "Renamed by the user",
            database.templateDao().template(seedId)?.name
        )
    }

    @Test
    fun `an untouched built-in is moved to the headless vocabulary on open`() = runTest {
        open()
        val choiceId = Templates.StructuredSingleChoice.ref.templateId.value
        assertEquals(
            Templates.StructuredSingleChoice.name,
            database.templateDao().template(choiceId)?.name
        )

        // The name an earlier build seeded. A device that upgraded from it still holds this row,
        // and the open is where it learns what the template is called now.
        database.templateWriteDao().renameTemplate(choiceId, "Single Choice")
        database.close()

        open()
        assertEquals(
            Templates.StructuredSingleChoice.name,
            database.templateDao().template(choiceId)?.name
        )
    }

    @Test
    fun `a built-in renamed by its owner keeps that name`() = runTest {
        open()
        val sampleId = Templates.ImageSampleReview.ref.templateId.value
        database.templateWriteDao().renameTemplate(sampleId, "Colour picker")
        database.close()

        open()
        assertEquals(
            "Colour picker",
            database.templateDao().template(sampleId)?.name
        )
    }

    @Test
    fun `a database that has the tables and no templates at all is given the built-in`() = runTest {
        // The state this was written for, and the one that was found on a real device: v24, both
        // tables present, nothing in them. No migration will visit this database again and no
        // creation callback is ever going to run for it, so without the repair it is a device whose
        // template list is empty for good — and whose Todos would all resolve to no buttons at all.
        //
        // Built without the callback first, which is the only difference between this fixture and a
        // fresh install: both are databases Room created, and only one of them was created by a
        // build that had the built-in.
        Room.databaseBuilder(context, ListeaDatabase::class.java, DB)
            .allowMainThreadQueries()
            .build()
            .also {
                it.openHelper.writableDatabase
                it.close()
            }

        open()

        assertEquals(
            1 + Templates.StoredTaskReviews.size,
            database.templateDao().observeTemplates().first().size
        )
        assertTrue(
            database.templateDao().observeVersions().first()
                .any { it.templateId == seedId }
        )
    }

    /** Opens the database the way the app opens it, seeding and all. */
    private fun open() {
        database = Room.databaseBuilder(context, ListeaDatabase::class.java, DB)
            .addCallback(ListeaDatabase.SeedBuiltIn)
            .allowMainThreadQueries()
            .build()
        // Opening is what creates it, and what runs the callback.
        database.openHelper.writableDatabase
    }

    private companion object {
        const val DB = "template-seed-test.db"
    }
}
