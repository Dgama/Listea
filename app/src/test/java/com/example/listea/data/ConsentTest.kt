package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * Import consent, pinned at the write boundary: looking is not writing.
 *
 * §11.2's confirmation asks before anything is registered, imported or created, and a decline
 * leaves the database exactly as it was. The ViewModel's consent gate stands on top of this, but
 * the guarantee it leans on is that the reads which precede consent — checking coverage, reading the
 * catalog — create no Source, no Task, no Asset and no provenance. That is what this holds down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsentTest {

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

    // §11.2, AR-6
    @Test
    fun `the reads that precede consent write nothing, and declining leaves the database unchanged`() =
        runTest {
            // Un-imported material: no Source covers the folder, and the catalog is empty.
            assertEquals(null, dao.findCoveringSource(ROOT, "holiday"))
            assertEquals(emptyList<CatalogRow>(), dao.getCatalogTasks("no-such-source"))

            // Merely looking registered nothing, imported nothing and created nothing. The only
            // thing that writes is an explicit import, which the consent dialog is the gate before.
            assertEquals(0, rawInt("SELECT COUNT(*) FROM sources"))
            assertEquals(0, rawInt("SELECT COUNT(*) FROM tasks"))
            assertEquals(0, rawInt("SELECT COUNT(*) FROM assets"))
            assertEquals(0, rawInt("SELECT COUNT(*) FROM task_imports"))
            assertEquals(0, rawInt("SELECT COUNT(*) FROM lists"))
        }

    private fun rawInt(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val ROOT = "content://root"
    }
}
