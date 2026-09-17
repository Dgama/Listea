package com.example.listea

import com.example.listea.data.CatalogRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a Source's catalog is cut into the thing a scope shows.
 *
 * Two shapes come out of one read of `task_imports`: a tree of folders with the Tasks that lie
 * exactly in each, and — for a Todo whose Create Tasks run was told not to keep one — the whole
 * catalog as a single flat list. Both are derived from `groupPath` and nothing else, which is what
 * makes the second a presentation rule rather than a second import.
 *
 * The tree side is asserted through the one claim that matters: a Task appears once, either as a
 * folder to open or as a Task at the scope, never as both and never twice, however deep it sits.
 */
class CatalogScopeTest {

    private fun row(groupPath: String, displayName: String = groupPath) = CatalogRow(
        taskId = "task:$groupPath",
        groupPath = groupPath,
        displayName = displayName,
        isCompleted = false,
        isFavorite = false,
        customActions = "",
        assetCount = 1,
        missingAssets = 0,
        sizeBytes = null,
        lastModified = null,
        hasUnviewableAsset = false
    )

    private val flatCatalog = listOf(
        row("a.jpg"),
        row("bilibili/x.jpg"),
        row("bilibili/y.jpg"),
        row("bilibili/sub/z.jpg")
    )

    // --- the tree ---------------------------------------------------------------------------------

    @Test
    fun `the root shows the Tasks that lie in it and one folder for everything below`() {
        val view = catalogAtScope(flatCatalog, "")

        assertEquals(listOf("a.jpg"), view.tasks.map { it.groupPath })
        assertEquals(listOf("bilibili"), view.scopes.map { it.name })
        // The count is everything below, not just the folder's own: it is how the user knows the
        // folder is worth opening before opening it.
        assertEquals(3, view.scopes.single().taskCount)
    }

    @Test
    fun `a folder shows its own Tasks and its subfolders, and nothing else`() {
        val view = catalogAtScope(flatCatalog, "bilibili")

        assertEquals(listOf("bilibili/x.jpg", "bilibili/y.jpg"), view.tasks.map { it.groupPath })
        assertEquals(listOf("bilibili/sub"), view.scopes.map { it.path })
        assertEquals(1, view.scopes.single().taskCount)
    }

    @Test
    fun `the deepest scope has Tasks and nothing to descend into`() {
        val view = catalogAtScope(flatCatalog, "bilibili/sub")

        assertEquals(listOf("bilibili/sub/z.jpg"), view.tasks.map { it.groupPath })
        assertTrue(view.scopes.isEmpty())
    }

    @Test
    fun `a scope inside a Task reports the whole Task it is part of`() {
        // A Task whose groupPath is a single file sits at the root and cannot be entered, so the
        // enclosing case needs a folder-as-Task row: its groupPath names a directory, and a scope
        // below that directory is a scope inside the Task.
        val rows = listOf(row("alice", "Alice"), row("alice/papers/scan.pdf"))
        val view = catalogAtScope(rows, "alice/papers/deep")

        assertEquals("alice", view.enclosing?.groupPath)
        assertTrue(view.tasks.isEmpty())
    }

    // --- the flat Todo ----------------------------------------------------------------------------

    @Test
    fun `a flat catalog is every Task at once, in catalog order, with nothing to open`() {
        val view = catalogAtScope(flatCatalog, "", flat = true)

        assertEquals(4, view.tasks.size)
        // Case-insensitive by path, the same order the folder it came from reads in.
        assertEquals(
            listOf("a.jpg", "bilibili/sub/z.jpg", "bilibili/x.jpg", "bilibili/y.jpg"),
            view.tasks.map { it.groupPath }
        )
        assertTrue(view.scopes.isEmpty())
        assertEquals(null, view.enclosing)
    }

    @Test
    fun `a flat catalog ignores the scope it is asked about`() {
        // A flat Todo has one scope, and it is the root. A scope left over from the shape the Todo
        // used to have must not quietly show a subset of a list that has no subsets.
        val atRoot = catalogAtScope(flatCatalog, "", flat = true)
        val deeper = catalogAtScope(flatCatalog, "bilibili", flat = true)

        assertEquals(atRoot.tasks, deeper.tasks)
    }

    // --- what a level is called, and what it starts at --------------------------------------------

    @Test
    fun `the Todo's own root is named after the Todo, however deep in the grant it sits`() {
        // A run started inside a subfolder leaves every Task under that subfolder, so a Todo's root
        // is not the empty path. Named by the Todo and not by the folder it happens to share a name
        // with: the grant is how the app finds the material, not what the user is looking at.
        assertEquals("xvideo-jp", catalogScopeTitle("xvideo-jp", "xvideo-jp", "xvideo-jp"))
        assertEquals("sub", catalogScopeTitle("xvideo-jp", "xvideo-jp/sub", "xvideo-jp"))
        // And a Todo made from the Upstream itself is unchanged: its root is the empty path.
        assertEquals("Demo", catalogScopeTitle("Demo", "", ""))
        assertEquals("Camera", catalogScopeTitle("Demo", "Camera", ""))
    }

    @Test
    fun `a List made at a Todo's root is named after the Todo`() {
        assertEquals("xvideo-jp", defaultListTitle("xvideo-jp", "xvideo-jp", "xvideo-jp"))
        assertEquals("sub", defaultListTitle("xvideo-jp", "xvideo-jp/sub", "xvideo-jp"))
    }

    @Test
    fun `the level is the one reading of a scope, and the flat case is why it exists`() {
        // Three consumers — the catalog's list, Quick Review's queue and the preview's sequence —
        // and one function they all read. A level that meant one thing to the list and another to
        // the round started from it is exactly the defect this replaced.
        assertEquals(catalogAtScope(flatCatalog, "").tasks, catalogLevel(flatCatalog, ""))
        assertEquals(
            catalogAtScope(flatCatalog, "bilibili/sub").tasks,
            catalogLevel(flatCatalog, "bilibili/sub")
        )
        // A flat Todo shows every Task it holds at one level, whatever depth their paths sit at.
        assertEquals(4, catalogLevel(flatCatalog, "bilibili", flat = true).size)
    }
}
