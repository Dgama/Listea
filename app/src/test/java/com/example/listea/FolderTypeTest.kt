package com.example.listea

import com.example.listea.data.FileClaim
import com.example.listea.data.FolderListScope
import com.example.listea.data.SharedFileState
import com.example.listea.data.SourceItemRef
import com.example.listea.domain.EXPORT_CHOICE_INHERIT
import com.example.listea.domain.ExportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder display semantics V4 will render: what a folder *is*, what progress it shows, and
 * who may show a webhook.
 */
class FolderTypeTest {

    private fun scope(
        id: Long,
        path: String,
        total: Int = 4,
        completed: Int = 2,
        exportOnCompletion: Boolean = true,
        exportKind: String = EXPORT_CHOICE_INHERIT
    ) = FolderListScope(
        id = id,
        title = "list-$id",
        relativePath = path,
        completedAt = if (total > 0 && completed == total) 1L else null,
        totalItems = total,
        completedItems = completed,
        exportOnCompletion = exportOnCompletion,
        exportKind = exportKind,
        lastDeliveryStatus = null,
        lastDeliveryCode = null
    )

    private fun ownership(scopes: List<FolderListScope>, items: List<SourceItemRef>) =
        buildFolderOwnership(scopes, items)

    private fun ownership(vararg claims: FileClaim) =
        buildFolderOwnership(emptyList(), emptyList(), emptyList(), claims.toList())

    /**
     * One Todo's claim on [relativePath]. [groupPath] defaults to the same path, which is what "the
     * row *is* that Todo's Task" means; pass it differently for a Task over a folder.
     */
    private fun claim(
        relativePath: String,
        sourceName: String,
        groupPath: String = relativePath,
        sourceId: String = sourceName,
        taskId: String = "$sourceName:$groupPath"
    ) = FileClaim(
        relativePath = relativePath,
        groupPath = groupPath,
        sourceId = sourceId,
        sourceName = sourceName,
        taskId = taskId,
        taskName = groupPath.substringAfterLast('/')
    )

    @Test
    fun `a folder owning its list is a List`() {
        val own = ownership(listOf(scope(1, "2026-07-11")), emptyList())
        assertEquals(FolderType.LIST, folderTypeOf(folderListStatus(own, "2026-07-11")))
    }

    @Test
    fun `a folder under an ancestor list is a Sublist`() {
        val own = ownership(
            listOf(scope(1, "2026-07-11")),
            listOf(SourceItemRef(1, "bilibili/a.jpg", true))
        )
        val status = folderListStatus(own, "2026-07-11/bilibili")
        assertEquals(FolderType.SUBLIST, folderTypeOf(status))
    }

    @Test
    fun `a folder with lists only beneath it is still None`() {
        // parent-folder owns nothing and no ancestor covers it; child-a and child-b own lists.
        val own = ownership(
            listOf(scope(1, "parent-folder/child-a"), scope(2, "parent-folder/child-b")),
            emptyList()
        )
        val status = folderListStatus(own, "parent-folder")
        assertEquals(FolderType.NONE, folderTypeOf(status))
        assertNull(folderProgressLabel(status))
        assertNull(statusExportLabel(status))
    }

    @Test
    fun `only a List may show webhook status`() {
        val own = ownership(
            listOf(scope(1, "2026-07-11", exportKind = ExportKind.WEBHOOK.key)),
            listOf(SourceItemRef(1, "bilibili/a.jpg", true))
        )
        val direct = folderListStatus(own, "2026-07-11")
        val sub = folderListStatus(own, "2026-07-11/bilibili")

        assertEquals(FolderType.LIST, folderTypeOf(direct))
        assertEquals(FolderType.SUBLIST, folderTypeOf(sub))
        // The webhook belongs to the owning list and is shown only where it is configured.
        assertNull(statusExportLabel(sub))
        assertEquals("Webhook · On · Never exported", statusExportLabel(direct))
    }

    @Test
    fun `a Sublist reports its own subtree, not the whole list`() {
        val own = ownership(
            listOf(scope(1, "root", total = 3, completed = 1)),
            listOf(
                SourceItemRef(1, "bilibili/a.jpg", true),
                SourceItemRef(1, "bilibili/b.jpg", true),
                SourceItemRef(1, "danbooru/c.jpg", false)
            )
        )
        assertEquals("2 / 2 ✓", folderProgressLabel(folderListStatus(own, "root/bilibili")))
        assertEquals("0 / 1", folderProgressLabel(folderListStatus(own, "root/danbooru")))
    }

    @Test
    fun `progress reads as a number without the word complete`() {
        assertEquals("3 / 5", progressLabel(3, 5, false))
        assertEquals("5 / 5 ✓", progressLabel(5, 5, true))
    }

    // --- which Todo holds a row, and in which capacity ---------------------------------------------

    @Test
    fun `a row that is one Todo's own Task is named without a hint`() {
        val own = ownership(claim("xvideo-jp/a.jpg", sourceName = "2026-09-11"))

        // No "asset" and no count: the name is the whole answer, and for a file-as-Task Todo the
        // row saying the Todo's name is the row saying the file is one of its Tasks.
        assertEquals("Todo · 2026-09-11", fileOwnerLabel(ownersOf(own, "xvideo-jp/a.jpg")))
        assertNull(fileOwnerMark(ownersOf(own, "xvideo-jp/a.jpg")))
    }

    @Test
    fun `a row a Todo covers as content of a folder's Task says so`() {
        val own = ownership(
            claim("Demo/Camera/a.jpg", sourceName = "Demo", groupPath = "Demo/Camera")
        )

        assertEquals("Todo · Demo · asset", fileOwnerLabel(ownersOf(own, "Demo/Camera/a.jpg")))
    }

    @Test
    fun `several Todos over one file are summarised, never listed`() {
        val own = ownership(
            claim("Whatsapp/IMG.jpg", sourceName = "Whatsapp"),
            claim("Whatsapp/IMG.jpg", sourceName = "Whatsapp（duplicate）"),
            claim("Whatsapp/IMG.jpg", sourceName = "Demo", groupPath = "Whatsapp")
        )

        val owners = ownersOf(own, "Whatsapp/IMG.jpg")
        assertEquals(3, owners.size)
        // Three names plus the hint would not fit on a phone line, and what ellipsizes away is the
        // tail — which is the one thing the line exists to say.
        assertEquals("Todo · 3 Todos · 1 asset", fileOwnerLabel(owners))
        assertEquals(3, fileOwnerMark(owners))
    }

    @Test
    fun `a file two Todos both made a Task of adds no second line`() {
        val own = ownership(
            claim("a.jpg", sourceName = "Chilli fried egg"),
            claim("a.jpg", sourceName = "Tomato and egg")
        )

        val owners = ownersOf(own, "a.jpg")
        // The mark's own count is the whole of what there is to say, so a line repeating it would
        // be noise on every shared row.
        assertNull(fileOwnerLabel(owners))
        assertEquals(2, fileOwnerMark(owners))
    }

    @Test
    fun `a row that is the Task wins over the same Todo's Task over its folder`() {
        val own = ownership(
            // The covering claim is first, so "the first one wins" would fail this.
            claim("Whatsapp/IMG.jpg", sourceName = "Demo", groupPath = "Whatsapp", taskId = "folder"),
            claim("Whatsapp/IMG.jpg", sourceName = "Demo", groupPath = "Whatsapp/IMG.jpg", taskId = "own")
        )

        val owners = ownersOf(own, "Whatsapp/IMG.jpg")
        assertEquals(1, owners.size)
        assertTrue("the Task itself is the stronger claim", owners.single().isOwnTask)
        assertEquals("own", owners.single().taskId)
        assertEquals("Todo · Demo", fileOwnerLabel(owners))
    }

    @Test
    fun `one Todo holding one path through two folder Tasks is still one owner`() {
        val own = ownership(
            claim("Whatsapp/IMG.jpg", sourceName = "Demo", groupPath = "Whatsapp", taskId = "first"),
            claim("Whatsapp/IMG.jpg", sourceName = "Demo", groupPath = "Backup", taskId = "second")
        )

        // Neither is the row's own Task, so neither wins on capacity and the first stands. The user
        // acts on the Todo, and the Todo is one.
        val owners = ownersOf(own, "Whatsapp/IMG.jpg")
        assertEquals(1, owners.size)
        assertEquals("first", owners.single().taskId)
    }

    @Test
    fun `a folder is marked only when its own path is a Task`() {
        val own = ownership(
            claim("Demo/Camera", sourceName = "Demo"),
            claim("Demo/Camera/a.jpg", sourceName = "Demo")
        )

        assertEquals("Todo · Demo", folderOwnerLabel(ownersOf(own, "Demo/Camera")))
        // The file is content of that same Task, so its parent is not itself a Task anywhere, and
        // ancestry is not a relationship the folder has — the rule FolderType.NONE already keeps.
        assertNull(folderOwnerLabel(ownersOf(own, "Demo")))
    }

    @Test
    fun `a path no Todo holds has nothing to say`() {
        val own = ownership(claim("a.jpg", sourceName = "Chilli fried egg"))

        assertTrue(ownersOf(own, "b.jpg").isEmpty())
        assertNull(fileOwnerLabel(ownersOf(own, "b.jpg")))
        assertNull(fileOwnerMark(ownersOf(own, "b.jpg")))
        assertNull(folderOwnerLabel(ownersOf(own, "b.jpg")))
    }

    @Test
    fun `no claims at all leaves the ownership map empty`() {
        // The defaulted fourth parameter, pinned: an ownership built without claims must not carry
        // anything, or every folder in an unimported tree would grow a line.
        val own = ownership(listOf(scope(1, "2026-07-11")), emptyList())

        assertTrue(own.owners.isEmpty())
        assertNull(folderOwnerLabel(ownersOf(own, "2026-07-11")))
    }

    @Test
    fun `the drill-in says which capacity each Todo holds the file in`() {
        fun state(name: String, isOwnTask: Boolean) = SharedFileState(
            sourceId = name,
            sourceName = name,
            taskId = name,
            taskName = name,
            isOwnTask = isOwnTask,
            isCompleted = false,
            selections = ""
        )

        // Three sentences and not one, because "each has a Task of its own" is false for a Todo
        // that made its Task over the folder rather than over the file.
        assertEquals(
            "This file is held by 3 Todos. Some have a Task of their own and some cover it as one " +
                "of a folder's files, so what one decides does not reach the others.",
            fileOwnersNote(listOf(state("a", true), state("b", true), state("c", false)))
        )
        assertTrue(fileOwnersNote(listOf(state("a", true), state("b", true)))
            .endsWith("Each has a Task of its own, so what one decides does not reach the others."))
        assertTrue(fileOwnersNote(listOf(state("a", false)))
            .endsWith("Each covers it as one of a folder's files, so what one decides does not reach the others."))
    }
}
