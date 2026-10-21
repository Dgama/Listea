package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mark rows a viewer writes and reads back, without a renderer in the way.
 *
 * Two cascades are the point. An Asset's deletion takes its marks with it — the row is about a file
 * that no longer exists. A template's deletion takes nothing: the rows keep their authored reference
 * and simply stop being visible, which is the rule the whole feature is built around and the one a
 * foreign key added "for tidiness" would silently break.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetMarkDaoTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.listsDao()
        exec("INSERT INTO tasks (id, createdAt, publicId) VALUES ('t-1', 1, 'task-public')")
        exec(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing, " +
                "publicId) VALUES ('a-1', 't-1', 0, 'content://root', 'doc.pdf', 0, 'asset-public')"
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a mark is observed in creation order and round-trips its anchor`() = runBlocking {
        dao.insertMark(mark(id = "m-2", createdAt = 20))
        dao.insertMark(mark(id = "m-1", createdAt = 10))

        val rows = dao.observeMarksForAsset("a-1").first()

        assertEquals(listOf("m-1", "m-2"), rows.map { it.id })
        val anchor = rows.first().toAssetMark()!!.anchor
        assertEquals("the passage", anchor.exactText)
        assertEquals("before", anchor.prefix)
        assertEquals("after", anchor.suffix)
        assertEquals(4, anchor.pageIndex)
        assertEquals("sha-256", anchor.documentFingerprint)
    }

    @Test
    fun `editing a value keeps the anchor and moves only the timestamp`() = runBlocking {
        dao.insertMark(mark(id = "m-1", createdAt = 10))

        dao.updateMarkValue("m-1", "a better note", updatedAt = 99)

        val row = dao.getMark("m-1")!!
        assertEquals("a better note", row.value)
        assertEquals(99, row.updatedAt)
        assertEquals(10, row.createdAt)
        assertEquals("the passage", row.exactText)
        assertEquals(4, row.pageIndex)
    }

    @Test
    fun `relocating rewrites the anchor and leaves the quote's value alone`() = runBlocking {
        dao.insertMark(mark(id = "m-1", createdAt = 10))

        dao.updateMarkAnchor(
            id = "m-1",
            documentFingerprint = "new-sha",
            pageIndex = 7,
            charStart = 20,
            charEnd = 31,
            exactText = "moved passage",
            prefix = "now before",
            suffix = "now after",
            charParser = "nucleus-pdfium/158.0.8111.0",
            updatedAt = 50
        )

        val row = dao.getMark("m-1")!!
        assertEquals("new-sha", row.documentFingerprint)
        assertEquals(7, row.pageIndex)
        assertEquals("moved passage", row.exactText)
        assertEquals("the note", row.value)
        assertEquals(10, row.createdAt)
    }

    @Test
    fun `deleting a mark removes only that row`() = runBlocking {
        dao.insertMark(mark(id = "m-1", createdAt = 10))
        dao.insertMark(mark(id = "m-2", createdAt = 20))

        dao.deleteMark("m-1")

        assertNull(dao.getMark("m-1"))
        assertNotNull(dao.getMark("m-2"))
    }

    @Test
    fun `deleting its Asset takes a mark with it`() = runBlocking {
        dao.insertMark(mark(id = "m-1", createdAt = 10))

        exec("DELETE FROM assets WHERE id = 'a-1'")

        assertEquals(emptyList<AssetMarkEntity>(), dao.observeMarksForAsset("a-1").first())
    }

    @Test
    fun `deleting the authoring template leaves the mark in place`() = runBlocking {
        exec("INSERT INTO templates (id, name, createdAt) VALUES ('tpl', 'PDF', 1)")
        dao.insertMark(mark(id = "m-1", createdAt = 10, templateId = "tpl"))

        exec("DELETE FROM templates WHERE id = 'tpl'")

        val row = dao.getMark("m-1")
        assertNotNull(row)
        assertEquals("tpl", row!!.authoredTemplateId)
    }

    private fun exec(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

    private fun mark(
        id: String,
        createdAt: Long,
        templateId: String = "tpl"
    ) = AssetMarkEntity(
        id = id,
        assetId = "a-1",
        toolId = "tool-comment",
        authoredTemplateId = templateId,
        authoredTemplateVersion = 1,
        kind = "comment",
        value = "the note",
        anchorVersion = 1,
        documentFingerprint = "sha-256",
        pageIndex = 4,
        charStart = 10,
        charEnd = 22,
        charParser = "nucleus-pdfium/158.0.8111.0",
        exactText = "the passage",
        prefix = "before",
        suffix = "after",
        createdAt = createdAt,
        updatedAt = createdAt
    )
}
