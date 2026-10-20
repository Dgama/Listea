package com.example.listea

import com.example.listea.data.AssetDecisions
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
 * Covers the two pure pieces of the Task/Asset payload: the folder an Asset resolves to, and the
 * action names the control governing that file's kind resolves it to.
 *
 * The JSON assembly itself is in `WebhookBodyTest`. It used to live on the device, because org.json
 * was not available to host tests — Robolectric, added in v0.2 step 0, is what changed that.
 */
class WebhookPayloadTest {

    private fun item(vararg actions: String, favorite: Boolean = false) = ListItemEntity(
        id = 173,
        publicId = "0AT9K3QWMB-4H7ZP2-XC5N0V",
        listId = 1,
        taskId = "task-173",
        title = "example.jpg",
        sortOrder = 0,
        createdAt = 0,
        sourceRelativePath = "a.jpg",
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
     * conversion the payload's own Asset entry does, so these tests exercise the path the payload
     * takes rather than a decision record assembled for them.
     */
    private fun ListItemEntity.actionDecisions(): AssetDecisions = decisions.assetDecisions

    /**
     * The control a payload resolves an Asset under: what governs one **file's kind** on a Task
     * attached to [LINEAGE] version 1.
     *
     * Per kind rather than the old Task-wide union, because v0.6.0's `actions` belong to an Asset
     * and the chip row has always drawn them from the kind's own control. A Task-wide union would
     * report a tag the file could not have been marked with.
     */
    private fun control(
        templates: TemplateResolver = resolver(),
        kind: AssetKind? = AssetKind.IMAGE
    ): ResolvedAssetControl =
        kind?.let { templates.controlFor(ATTACHMENT, it) } ?: ResolvedAssetControl.Empty

    @Test
    fun `the folder is the Asset's own containing folder`() {
        // Relative to the Upstream root, and independent of packaging: a file in `cos` says `cos`
        // whether its Task is the file or the whole folder.
        assertEquals("cos", assetFolderOf("cos/a.jpg"))
        assertEquals("cos/2026", assetFolderOf("cos/2026/a.jpg"))
    }

    @Test
    fun `a file directly under the root has an empty folder, not a missing one`() {
        assertEquals("", assetFolderOf("a.jpg"))
    }

    @Test
    fun `the file name is never part of the folder`() {
        // A file and a folder that share a name must still resolve to the same parent.
        assertEquals("sub", assetFolderOf("sub/sub"))
    }

    @Test
    fun `stray separators cannot produce a doubled slash`() {
        assertEquals("cos/2026", assetFolderOf("/cos/2026/a.jpg"))
    }

    @Test
    fun `a pathless Asset has no folder at all`() {
        assertNull(assetFolderOf(null))
    }

    @Test
    fun `actions serialize in a fixed order, whatever is set`() {
        val control = control()
        assertEquals(emptyList<String>(), itemActionNames(item().actionDecisions(), control))
        assertEquals(
            listOf("favorite"),
            itemActionNames(item(favorite = true).actionDecisions(), control)
        )
        assertEquals(
            listOf("favorite", "cust2"),
            itemActionNames(item("custom2", favorite = true).actionDecisions(), control)
        )
        assertEquals(
            listOf("favorite", "cust1", "cust2"),
            itemActionNames(
                item("custom1", "custom2", favorite = true).actionDecisions(),
                control
            )
        )
    }

    @Test
    fun `a kind the attached template does not admit sends no actions`() {
        // The attachment governs images. A video under the same Task has no chip row to have been
        // marked from, so it cannot claim the image template's tags — the per-file rule in practice.
        assertEquals(
            emptyList<String>(),
            itemActionNames(item("custom1", favorite = true).actionDecisions(), control(kind = AssetKind.VIDEO))
        )
        // A pathless Asset is empty for the same reason: no path, no kind, no control.
        assertEquals(
            emptyList<String>(),
            itemActionNames(item("custom1").actionDecisions(), control(kind = null))
        )
    }

    @Test
    fun `completion and actions are independent`() {
        // The row carries both halves and the tags are read off the pair's half alone, so flipping
        // the Task's completion moves nothing in the actions. Since v0.5.1 that is also a fact
        // about the schema — the two are separate tables — and this is the assertion that would
        // fail if a reader went looking for either of them in the other's place.
        val control = control()
        val unchecked = item("custom1")
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
                item("custom1", "custom2", favorite = true).actionDecisions(),
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
            itemActionNames(item(favorite = true).actionDecisions(), control)
        )
    }

    @Test
    fun `two actions sharing a wire value emit it once`() {
        val control = control(resolver(c1Value = "tag", c2Value = "tag"))
        assertEquals(
            listOf("tag"),
            itemActionNames(item("custom1", "custom2").actionDecisions(), control)
        )
    }

    @Test
    fun `renaming an action does not change which actions are selected`() {
        // The item holds the id, so it is untouched and only the emitted string moves. This is
        // the whole point of keeping names out of the item row.
        val marked = item("custom1").actionDecisions()
        assertEquals(listOf("cust1"), itemActionNames(marked, control()))
        assertEquals(listOf("archive"), itemActionNames(marked, control(resolver(c1Value = "archive"))))
    }

    @Test
    fun `an action the user deleted is carried but not sent`() {
        // Deleting a button is a decision about the bar, not about the files already marked with
        // it: the id stays on the row, and stops resolving to anything the webhook can name.
        val marked = item("custom1", favorite = true).actionDecisions()
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
