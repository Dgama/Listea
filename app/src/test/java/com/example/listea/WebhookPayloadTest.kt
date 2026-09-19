package com.example.listea

import com.example.listea.data.AssetDecisions
import com.example.listea.data.ListEntity
import com.example.listea.data.ListItemEntity
import com.example.listea.data.TemplateEntity
import com.example.listea.data.TemplateVersionEntity
import com.example.listea.data.assetDecisions
import com.example.listea.data.decisions
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
 * Covers the two pure pieces of the webhook item payload: the folder path an item resolves to, and
 * the action names the template governing that Task resolves it to.
 *
 * The JSON assembly itself is in `WebhookBodyTest`. It used to live on the device, because org.json
 * was not available to host tests — Robolectric, added in v0.2 step 0, is what changed that.
 */
class WebhookPayloadTest {

    private fun list(relativePath: String?) = ListEntity(
        id = 1,
        title = "bilibili",
        createdAt = 0,
        sourceRootUri = relativePath?.let { "content://tree/Listea" },
        sourceRelativePath = relativePath
    )

    private fun item(
        sourcePath: String?,
        vararg actions: String,
        favorite: Boolean = false
    ) = ListItemEntity(
        id = 173,
        publicId = "0AT9K3QWMB-4H7ZP2-XC5N0V",
        listId = 1,
        taskId = "task-173",
        title = "example.jpg",
        sortOrder = 0,
        createdAt = 0,
        sourceRelativePath = sourcePath,
        selections = encodeActionIds(
            actions.toSet() + if (favorite) setOf(Templates.FAVOURITE_BUTTON_ID) else emptySet()
        )
    )

    /**
     * `★` as a template holds it, since v0.5.4 the button with the id
     * [Templates.FAVOURITE_BUTTON_ID] rather than a field beside the tags.
     */
    private val star = TemplateButton(
        id = Templates.FAVOURITE_BUTTON_ID,
        name = "★",
        type = ButtonType.BOOLEAN,
        recordValue = Templates.FAVOURITE_BUTTON_ID
    )

    /** A configured tag, whose name and wire value are what a test is about. */
    private fun tag(id: String, name: String, recordValue: String) =
        TemplateButton(id = id, name = name, type = ButtonType.TAG, recordValue = recordValue)

    /** `★` and the two out-of-the-box tags, with whatever wire values a test is about. */
    private fun resolver(c1Value: String = "cust1", c2Value: String = "cust2"): TemplateResolver =
        resolverOf(
            listOf(
                star,
                tag("custom1", "C1", c1Value),
                tag("custom2", "C2", c2Value)
            )
        )

    /** [LINEAGE] version 1 offering exactly [buttons], as the two tables hold one. */
    private fun resolverOf(buttons: List<TemplateButton>): TemplateResolver = TemplateResolver.of(
        listOf(TemplateEntity(id = LINEAGE, name = "Test control", createdAt = 0)),
        listOf(
            TemplateVersionEntity(
                templateId = LINEAGE,
                version = 1,
                scope = encodeTemplateScope(TemplateScope.ofKinds(setOf(AssetKind.IMAGE))),
                buttons = encodeTemplateButtons(buttons),
                buttonsVersion = TEMPLATE_BUTTONS_VERSION,
                createdAt = 0
            )
        )
    )

    /**
     * The action half of a row, which is what every assertion below is about.
     *
     * A list row reports both halves — see [ReviewDecisions] — and goes through the same
     * conversion the webhook's own item entry does, so these tests exercise the path the payload
     * takes rather than a decision record assembled for them.
     */
    private fun ListItemEntity.actionDecisions(): AssetDecisions = decisions.assetDecisions

    /**
     * The control a payload is built under: what governs a Task attached to [LINEAGE] version 1.
     *
     * The *whole Task's* control rather than one file's, because that is the question a payload
     * asks — `TemplateResolver.controlForTask`, one entry per Task. The per-kind and per-file paths
     * are `SettingsTest`'s and `TemplateAttachmentTest`'s business.
     *
     * It used to be read off the settings page, with a fallback to the app's shipped tags whenever
     * a record said nothing. Nothing falls back any more — an ungoverned file gets no chips rather
     * than the built-in standing in — so the attachment is named here rather than left empty.
     */
    private fun control(templates: TemplateResolver = resolver()): ResolvedAssetControl =
        templates.controlForTask(ATTACHMENT)

    @Test
    fun `path is relative to the sync root, not to the list folder`() {
        assertEquals(
            "2026-07-11/bilibili",
            webhookRelativePath(list("2026-07-11/bilibili"), item("example.jpg"))
        )
    }

    @Test
    fun `nested item keeps its subfolders`() {
        assertEquals(
            "2026-07-11/bilibili/sub",
            webhookRelativePath(list("2026-07-11/bilibili"), item("sub/a.jpg"))
        )
    }

    @Test
    fun `the file name is never part of the path`() {
        // A file and a folder that share a name must still resolve to the same parent.
        assertEquals(
            "2026-07-11/bilibili/sub",
            webhookRelativePath(list("2026-07-11/bilibili"), item("sub/sub"))
        )
    }

    @Test
    fun `an item directly in a list linked to the root has an empty path`() {
        assertEquals("", webhookRelativePath(list(""), item("a.jpg")))
    }

    @Test
    fun `a list linked to the root itself adds no prefix`() {
        assertEquals("sub", webhookRelativePath(list(""), item("sub/a.jpg")))
    }

    @Test
    fun `stray separators cannot produce a doubled slash`() {
        assertEquals(
            "2026-07-11/bilibili/sub",
            webhookRelativePath(list("/2026-07-11/bilibili/"), item("/sub/a.jpg"))
        )
    }

    @Test
    fun `manual items have no path at all`() {
        assertNull(webhookRelativePath(list(null), item(null)))
    }

    @Test
    fun `actions serialize in a fixed order, whatever is set`() {
        val control = control()
        assertEquals(emptyList<String>(), itemActionNames(item("a.jpg").actionDecisions(), control))
        assertEquals(
            listOf("favorite"),
            itemActionNames(item("a.jpg", favorite = true).actionDecisions(), control)
        )
        assertEquals(
            listOf("favorite", "cust2"),
            itemActionNames(item("a.jpg", "custom2", favorite = true).actionDecisions(), control)
        )
        assertEquals(
            listOf("favorite", "cust1", "cust2"),
            itemActionNames(
                item("a.jpg", "custom1", "custom2", favorite = true).actionDecisions(),
                control
            )
        )
    }

    @Test
    fun `completion and actions are independent`() {
        // The row carries both halves and the tags are read off the pair's half alone, so flipping
        // the Task's completion moves nothing in the actions. Since v0.5.1 that is also a fact
        // about the schema — the two are separate tables — and this is the assertion that would
        // fail if a reader went looking for either of them in the other's place.
        val control = control()
        val unchecked = item("a.jpg", "custom1")
        assertEquals(listOf("cust1"), itemActionNames(unchecked.actionDecisions(), control))
        assertEquals(
            listOf("cust1"),
            itemActionNames(unchecked.copy(isCompleted = true).actionDecisions(), control)
        )
    }

    @Test
    fun `configured wire values replace the defaults`() {
        val control = control(resolver(c1Value = "tag1", c2Value = "tag2"))
        assertEquals(
            listOf("favorite", "tag1", "tag2"),
            itemActionNames(
                item("a.jpg", "custom1", "custom2", favorite = true).actionDecisions(),
                control
            )
        )
    }

    @Test
    fun `favorite is not configurable`() {
        // Renaming a custom action must never touch the one fixed action.
        val control = control(resolver(c1Value = "favorite"))
        assertEquals(
            listOf("favorite"),
            itemActionNames(item("a.jpg", favorite = true).actionDecisions(), control)
        )
    }

    @Test
    fun `two actions sharing a wire value emit it once`() {
        val control = control(resolver(c1Value = "tag", c2Value = "tag"))
        assertEquals(
            listOf("tag"),
            itemActionNames(item("a.jpg", "custom1", "custom2").actionDecisions(), control)
        )
    }

    @Test
    fun `renaming an action does not change which actions are selected`() {
        // The item holds the id, so it is untouched and only the emitted string moves. This is
        // the whole point of keeping names out of the item row.
        val marked = item("a.jpg", "custom1").actionDecisions()
        assertEquals(listOf("cust1"), itemActionNames(marked, control()))
        assertEquals(listOf("archive"), itemActionNames(marked, control(resolver(c1Value = "archive"))))
    }

    @Test
    fun `an action the user deleted is carried but not sent`() {
        // Deleting a button is a decision about the bar, not about the files already marked with
        // it: the id stays on the row, and stops resolving to anything the webhook can name.
        val marked = item("a.jpg", "custom1", favorite = true).actionDecisions()
        // The template as it stands once its tags are deleted: the version offers `★` and nothing
        // else. Nothing falls back to the shipped pair any more — see `TemplateResolver` — so the
        // deleted id is not quietly read through some other template's buttons.
        val control = control(resolverOf(listOf(star)))

        assertEquals(setOf("custom1", Templates.FAVOURITE_BUTTON_ID), marked.selections)
        assertEquals(listOf("favorite"), itemActionNames(marked, control))
    }

    private companion object {
        /** The one lineage these tests resolve against. */
        const val LINEAGE = "test.control"

        /** A Task attached to it at version 1, as `task_imports.assetControls` holds it. */
        val ATTACHMENT =
            encodeTemplateAttachments(listOf(TemplateVersionRef(TemplateId(LINEAGE), 1)))
    }
}
