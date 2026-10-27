package com.example.listea

import com.example.listea.data.ListItemEntity
import com.example.listea.data.PayloadAssetRow
import com.example.listea.data.PayloadPromptRow
import com.example.listea.data.PayloadTaskRow
import com.example.listea.data.TemplateEntity
import com.example.listea.data.TemplateVersionEntity
import com.example.listea.data.encodeActionIds
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [exportTaskViews], the one assembly point both renderers walk.
 *
 * The delivery paths must never disagree about what a Task is, so the facts are assembled once, from
 * the rows the database returns, and this is where each rule is pinned: first-appearance dedupe, the
 * title the round showed, a file's own folder, the control that governs its kind, and the attached
 * template version rather than the current one.
 */
class ExportTaskAssemblyTest {

    // --- rows -------------------------------------------------------------------------------------

    private fun row(
        taskId: String,
        title: String = "a.jpg",
        completed: Boolean = false,
        sortOrder: Int = 0
    ) = ListItemEntity(
        id = sortOrder.toLong(),
        publicId = "ROW-$taskId",
        listId = 1,
        taskId = taskId,
        title = title,
        isCompleted = completed,
        sortOrder = sortOrder,
        createdAt = 0
    )

    private fun taskRow(
        taskId: String,
        publicId: String = "TASK-$taskId",
        displayName: String? = null,
        attachments: String? = null
    ) = PayloadTaskRow(taskId, publicId, displayName, attachments)

    private fun assetRow(
        taskId: String,
        assetId: String,
        path: String?,
        publicId: String = "ASSET-$assetId",
        sourceMissing: Boolean = false,
        actions: Set<String> = emptySet()
    ) = PayloadAssetRow(
        taskId = taskId,
        assetId = assetId,
        publicId = publicId,
        relativePath = path,
        sourceMissing = sourceMissing,
        sortOrder = 0,
        selections = encodeActionIds(actions)
    )

    // --- tests ------------------------------------------------------------------------------------

    @Test
    fun `a Task appears once, in the order its first row did`() {
        // A List holds one membership per Task, but the assembly does not rely on that: a duplicate
        // would be a duplicated judgment on the wire.
        val views = exportTaskViews(
            rows = listOf(row("b"), row("a"), row("b", completed = true)),
            tasks = mapOf("a" to taskRow("a"), "b" to taskRow("b")),
            assetsByTask = emptyMap(),
            resolver = TemplateResolver.Empty
        )

        assertEquals(listOf("TASK-b", "TASK-a"), views.map { it.publicId })
        // First appearance wins, including its completion flag.
        assertEquals(false, views.first().isCompleted)
    }

    @Test
    fun `the title is the one the round showed, with the imported name as fallback`() {
        // A List membership may carry its own name; the snapshot describes what the user saw. When
        // the row has no name at all, the imported display name is the only name there is.
        val views = exportTaskViews(
            rows = listOf(row("a", title = "Shown"), row("b", title = "")),
            tasks = mapOf(
                "a" to taskRow("a", displayName = "Imported"),
                "b" to taskRow("b", displayName = "Imported")
            ),
            assetsByTask = emptyMap(),
            resolver = TemplateResolver.Empty
        )

        assertEquals(listOf("Shown", "Imported"), views.map { it.title })
    }

    @Test
    fun `an Asset's path is its folder, its title the file name`() {
        val views = exportTaskViews(
            rows = listOf(row("a")),
            tasks = mapOf("a" to taskRow("a")),
            assetsByTask = mapOf(
                "a" to listOf(
                    assetRow("a", "1", "cos/2026/Shiina_01.webp"),
                    assetRow("a", "2", "root.jpg"),
                    assetRow("a", "3", null)
                )
            ),
            resolver = TemplateResolver.Empty
        )

        val assets = views.single().assets
        assertEquals(listOf("cos/2026", "", null), assets.map { it.relativePath })
        assertEquals(listOf("Shiina_01.webp", "root.jpg", ""), assets.map { it.title })
    }

    @Test
    fun `sourceMissing is the Asset's own flag`() {
        val views = exportTaskViews(
            rows = listOf(row("a")),
            tasks = mapOf("a" to taskRow("a")),
            assetsByTask = mapOf("a" to listOf(assetRow("a", "1", "a.jpg", sourceMissing = true))),
            resolver = TemplateResolver.Empty
        )

        assertEquals(true, views.single().assets.single().sourceMissing)
    }

    @Test
    fun `actions resolve against the control for that file's kind`() {
        // One Task, two kinds: the attachment governs images only. The image's mark is sent; the
        // video carries the same stored id but no control admits it, so it sends nothing. A
        // Task-wide union would have reported the mark on both.
        val views = exportTaskViews(
            rows = listOf(row("a")),
            tasks = mapOf("a" to taskRow("a", attachments = ATTACHMENT)),
            assetsByTask = mapOf(
                "a" to listOf(
                    assetRow("a", "img", "cos/pic.jpg", actions = setOf(TAG_ID, Templates.FAVOURITE_BUTTON_ID)),
                    assetRow("a", "vid", "cos/clip.mp4", actions = setOf(TAG_ID))
                )
            ),
            resolver = resolver()
        )

        assertEquals(listOf("favorite", "cust1"), views.single().assets[0].actions)
        assertEquals(emptyList<String>(), views.single().assets[1].actions)
    }

    @Test
    fun `templates are the attached versions, in attachment order`() {
        // Two lineages, because an attachment names each lineage once: re-attaching the same one at
        // a newer version replaces the entry rather than adding a second. Order is the order they
        // were attached in. And [LINEAGE] is attached at version 1 while the resolver also holds
        // version 2: what is sent is what the Task was imported under, never the current version.
        val attachments = encodeTemplateAttachments(
            listOf(TemplateVersionRef(TemplateId(OTHER), 1), TemplateVersionRef(TemplateId(LINEAGE), 1))
        )
        val views = exportTaskViews(
            rows = listOf(row("a")),
            tasks = mapOf("a" to taskRow("a", attachments = attachments)),
            assetsByTask = emptyMap(),
            resolver = resolver()
        )

        assertEquals(listOf(OTHER, LINEAGE), views.single().templates.map { it.id })
        assertEquals(listOf(1, 1), views.single().templates.map { it.version })
        assertEquals(listOf("Other control", "Test control"), views.single().templates.map { it.name })
    }

    @Test
    fun `a Prompt Task sends no templates and no file actions, even holding a stale attachment`() {
        // The dating Todos imported before v0.8.2f carry Media Asset Control on the Source and on
        // their Tasks. A Prompt is answered in a Viewer; sending the stale template told a receiver
        // its Prompt was marked up, and offered actions its files never took.
        val views = exportTaskViews(
            rows = listOf(row("p")),
            tasks = mapOf("p" to taskRow("p", attachments = ATTACHMENT)),
            assetsByTask = mapOf(
                "p" to listOf(
                    assetRow("p", "img", "media/a.png", actions = setOf(TAG_ID, Templates.FAVOURITE_BUTTON_ID))
                )
            ),
            resolver = resolver(),
            promptsByTask = mapOf(
                "p" to PayloadPromptRow(
                    taskId = "p",
                    packageKey = "dating",
                    promptKey = "alice",
                    kind = "profile",
                    schemaVersion = 1,
                    revision = 1
                )
            )
        )

        val view = views.single()
        assertEquals(emptyList<ExportTemplateView>(), view.templates)
        assertEquals(emptyList<String>(), view.assets.single().actions)
    }

    @Test
    fun `a version this build cannot read is still named, with a null name`() {
        val views = exportTaskViews(
            rows = listOf(row("a")),
            tasks = mapOf("a" to taskRow("a", attachments = UNREADABLE)),
            assetsByTask = emptyMap(),
            resolver = resolver()
        )

        val template = views.single().templates.single()
        assertEquals(LINEAGE, template.id)
        assertEquals(9, template.version)
        assertNull(template.name)
    }

    @Test
    fun `a manual Task has no templates and no assets`() {
        val views = exportTaskViews(
            rows = listOf(row("manual", title = "Buy milk")),
            tasks = mapOf("manual" to taskRow("manual", displayName = null, attachments = null)),
            assetsByTask = emptyMap(),
            resolver = resolver()
        )

        val view = views.single()
        assertEquals("Buy milk", view.title)
        assertEquals(emptyList<ExportTemplateView>(), view.templates)
        assertEquals(emptyList<ExportAssetView>(), view.assets)
    }

    @Test
    fun `the same file under two Tasks is two Assets with two ids`() {
        val views = exportTaskViews(
            rows = listOf(row("a"), row("b")),
            tasks = mapOf("a" to taskRow("a"), "b" to taskRow("b")),
            assetsByTask = mapOf(
                "a" to listOf(assetRow("a", "a", "cos/e.jpg", publicId = "ASSET-A")),
                "b" to listOf(assetRow("b", "b", "cos/e.jpg", publicId = "ASSET-B"))
            ),
            resolver = TemplateResolver.Empty
        )

        assertEquals("ASSET-A", views[0].assets.single().publicId)
        assertEquals("ASSET-B", views[1].assets.single().publicId)
        assertEquals("cos", views[0].assets.single().relativePath)
        assertEquals("cos", views[1].assets.single().relativePath)
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun resolver(): TemplateResolver = TemplateResolver.of(
        listOf(
            TemplateEntity(id = LINEAGE, name = "Test control", createdAt = 0),
            TemplateEntity(id = OTHER, name = "Other control", createdAt = 0)
        ),
        (listOf(1, 2).map { LINEAGE to it } + (OTHER to 1)).map { (lineage, version) ->
            TemplateVersionEntity(
                templateId = lineage,
                version = version,
                scope = encodeTemplateScope(TemplateScope.ofKinds(setOf(AssetKind.IMAGE))),
                buttons = encodeTemplateButtons(
                    listOf(
                        TemplateButton(
                            id = Templates.FAVOURITE_BUTTON_ID,
                            name = "★",
                            type = ButtonType.BOOLEAN,
                            recordValue = Templates.FAVOURITE_BUTTON_ID
                        ),
                        TemplateButton(TAG_ID, "C1", ButtonType.TAG, "cust1")
                    )
                ),
                buttonsVersion = TEMPLATE_BUTTONS_VERSION,
                createdAt = 0
            )
        }
    )

    private companion object {
        const val LINEAGE = "test.control"
        const val OTHER = "other.control"
        const val TAG_ID = "custom1"

        val ATTACHMENT = encodeTemplateAttachments(listOf(TemplateVersionRef(TemplateId(LINEAGE), 1)))
        val UNREADABLE = encodeTemplateAttachments(listOf(TemplateVersionRef(TemplateId(LINEAGE), 9)))
    }
}
