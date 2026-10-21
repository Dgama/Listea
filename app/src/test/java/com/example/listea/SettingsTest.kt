package com.example.listea

import com.example.listea.data.AssetDecisions
import com.example.listea.data.ItemAction
import com.example.listea.domain.ButtonType
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.Templates
import com.example.listea.domain.decodeTemplateButtons
import com.example.listea.domain.encodeTemplateButtons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display side of a control's buttons: what a chip says and what a payload sends.
 *
 * The wire side of the same question lives in [WebhookPayloadTest]; the point of both is that a
 * button's identity is its id, and never either of its names.
 *
 * **What this file used to be, and why it shrank.** Its control was built from `AppSettings` — an
 * app-wide tag list per kind, with a fallback to the shipped defaults whenever a row said nothing —
 * so most of what it asserted was about that fallback and that codec. A control is a stored
 * template's buttons now: [TemplateResolver] answers it, `''` resolves to nothing rather than to the
 * defaults, and both of those answers live in [TemplateAttachmentTest]. What is left here is the
 * half that was always about *naming*: an id is what a record holds, a name and a record value are
 * what a surface reads off the control, and the three never have to agree.
 *
 * Which is the property that survived the release untouched, and the reason this file still has a
 * subject at all: it was never about where the buttons came from.
 */
class SettingsTest {

    private val custom1 = ItemAction("custom1")
    private val custom2 = ItemAction("custom2")

    private fun item(vararg actions: String) = AssetDecisions(selections = actions.toSet())

    /**
     * A control holding `★` and the two out-of-the-box buttons, renamed.
     *
     * Built as buttons rather than read off a template, because what is under test is the reading of
     * a button's two names — the resolution that produces the list is
     * [TemplateAttachmentTest]'s question, and a fixture that went through it would be testing two
     * things and reporting one.
     */
    private fun control(
        c1Name: String = "C1",
        c1Value: String = "cust1",
        c2Name: String = "C2",
        c2Value: String = "cust2"
    ) = ResolvedAssetControl(
        listOf(
            TemplateButton(Templates.FAVOURITE_BUTTON_ID, "★", ButtonType.BOOLEAN, "favorite"),
            TemplateButton("custom1", c1Name, ButtonType.TAG, c1Value),
            TemplateButton("custom2", c2Name, ButtonType.TAG, c2Value)
        )
    )

    @Test
    fun `defaults are the factory labels and values`() {
        val control = control()
        assertEquals("C1", control.labelOf(custom1))
        assertEquals("C2", control.labelOf(custom2))
        assertEquals("cust1", control.wireOf(custom1))
        assertEquals("cust2", control.wireOf(custom2))
    }

    @Test
    fun `a Todo's two standings start where an untouched install already was`() {
        // `docs/settings.md`'s rule, asserted rather than assumed: both of these describe behaviour
        // that already existed (a re-import changed no state, and no run ever started on its own),
        // so a factory settings object has to say `keep` and off. A default of `reset` here would
        // silently clear the judgments of every Task in every Todo on the next refresh.
        val settings = AppSettings()
        assertEquals(ContentPolicy.KEEP, settings.defaultContentPolicy)
        assertEquals(false, settings.followUpstreamNewTodos)
    }

    @Test
    fun `configured display names are what the review chips show`() {
        val control = control(c1Name = "Save", c2Name = "Later")
        assertEquals("Save", control.labelOf(custom1))
        assertEquals("Later", control.labelOf(custom2))
    }

    @Test
    fun `favorite keeps its own label and value whatever the button beside it is called`() {
        // Not "whatever the actions are called" any more, and the change is the release in one
        // sentence: `★` used to be a flag on the control and is a button of it now, so it can be
        // renamed like any other — and the fixture renaming a *tag* to `Favorite` no longer has
        // anything to do with it.
        val control = control(c1Name = "Favorite", c1Value = "favorite")
        assertEquals("★", control.labelOf(ItemAction.Favourite))
        assertEquals("favorite", control.wireOf(ItemAction.Favourite))
    }

    @Test
    fun `display name and webhook value are independent of each other`() {
        val control = control(c1Name = "Save", c1Value = "tag1")
        assertEquals("Save", control.labelOf(custom1))
        assertEquals("tag1", control.wireOf(custom1))
    }

    @Test
    fun `the row offers the buttons in the template's own order`() {
        // Order is the *version's* and not this class's, which is what the class note says; what is
        // asserted here is only that nothing reorders what it is given.
        assertEquals(
            listOf(ItemAction.Favourite, custom1, custom2),
            control().actions
        )
        assertEquals(
            listOf(ItemAction.Favourite),
            ResolvedAssetControl(
                listOf(
                    TemplateButton(Templates.FAVOURITE_BUTTON_ID, "★", ButtonType.BOOLEAN, "favorite")
                )
            ).actions
        )
        assertEquals(emptyList<ItemAction>(), ResolvedAssetControl.Empty.actions)
    }

    @Test
    fun `a comment is a passage tool, not a file-level action`() {
        // A Comment button is offered by the PDF selection menu; a chip row, a Task badge or an
        // exported `actions` array has no form for it, so [ResolvedAssetControl.actions] omits it
        // while [ResolvedAssetControl.buttons] keeps it for the viewer.
        val note = TemplateButton("note", "Comment", ButtonType.COMMENT, "")
        val control = ResolvedAssetControl(
            listOf(
                TemplateButton("star", "★", ButtonType.BOOLEAN, "favorite"),
                note,
                TemplateButton("tag", "C", ButtonType.TAG, "cust1")
            )
        )

        assertEquals(listOf(ItemAction("star"), ItemAction("tag")), control.actions)
        assertTrue(note in control.buttons)
        assertNull(itemActionLabel(item("note"), control))
    }

    @Test
    fun `an action the user deleted is neither offered nor labelled`() {
        // The id stays on the item; the control is what forgot it, so nothing here resolves it.
        // It answers with the id rather than with null, which is what keeps a button deleted from a
        // template *carried* by the items that hold it and out of the payload — see
        // `ResolvedAssetControl.wireOf`.
        val control = ResolvedAssetControl(
            listOf(TemplateButton("custom2", "C2", ButtonType.TAG, "cust2"))
        )
        assertEquals(listOf(custom2), control.actions)
        assertEquals("custom1", control.wireOf(custom1))
        assertNull(itemActionLabel(item("custom1"), control))
    }

    @Test
    fun `the row summary follows the configured names`() {
        val marked = item("custom1", Templates.FAVOURITE_BUTTON_ID)
        assertEquals("★ · C1", itemActionLabel(marked, control()))
        assertEquals("★ · Save", itemActionLabel(marked, control(c1Name = "Save")))
    }

    @Test
    fun `an item with no actions has no summary`() {
        assertNull(itemActionLabel(item(), control()))
        assertNotNull(itemActionLabel(item("custom2"), control()))
    }

    @Test
    fun `typed text cannot break the record it is stored in`() {
        // The record is a button's own line now — `id \t name \t type \t value` — so the two
        // separators that matter are the tab and the newline, and both are still taken out.
        val typed = sanitizeActionText("  two\tlines\nworth  ")
        assertEquals("two lines worth", typed)

        val buttons = listOf(TemplateButton("keeper", typed, ButtonType.TAG, typed))
        assertEquals(buttons, decodeTemplateButtons(encodeTemplateButtons(buttons), 1))
    }

    @Test
    fun `and the two separators it no longer has to refuse are free to be typed`() {
        // `|` and `:` separated the parts of an asset-control tag, which nested a record one level
        // inside the field above it. A template's buttons are flat, so a name may hold either — and
        // this is the assertion that would fail if the old rule were kept "for safety", which is
        // how a sanitiser outlives the format it was written for.
        val typed = sanitizeActionText("a|b:c")
        assertEquals("a|b:c", typed)

        val buttons = listOf(TemplateButton("keeper", typed, ButtonType.TAG, typed))
        assertEquals(buttons, decodeTemplateButtons(encodeTemplateButtons(buttons), 1))
    }

    @Test
    fun `a new action is named from the first free slot`() {
        val defaults = listOf(
            CustomAction(id = "custom1", displayName = "C1", webhookValue = "cust1"),
            CustomAction(id = "custom2", displayName = "C2", webhookValue = "cust2")
        )
        assertEquals("C3", nextCustomActionDefaults(defaults).displayName)

        // C1 deleted: the next one is C3 as well, because C3 is what is free rather than what
        // the count says.
        val afterDelete = defaults.filterNot { it.id == "custom1" }
        assertEquals("C3", nextCustomActionDefaults(afterDelete).displayName)

        // And a name already taken by hand is stepped over rather than duplicated.
        val taken = defaults + CustomAction(id = "a1", displayName = "C3", webhookValue = "c3")
        assertEquals("C4", nextCustomActionDefaults(taken).displayName)
    }

    @Test
    fun `a new action gets an id of its own, never a reserved one`() {
        val first = nextCustomActionDefaults(emptyList()).id
        val second = nextCustomActionDefaults(emptyList()).id

        assertNotEquals(first, second)
        assertTrue(first.isNotBlank())
        assertTrue(first !in setOf("custom1", "custom2"))
        // Ids are the item-side separator's business: one carrying a comma would split in two.
        assertTrue(',' !in first)
    }

    @Test
    fun `blank settings text is rejected, ordinary values are not`() {
        assertNotNull(settingsTextError(""))
        assertNotNull(settingsTextError("   "))
        assertNull(settingsTextError("tag1"))
        assertNull(settingsTextError("favorite-source"))
        assertNull(settingsTextError("archive"))
    }
}
