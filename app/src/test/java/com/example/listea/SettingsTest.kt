package com.example.listea

import com.example.listea.data.ItemAction
import com.example.listea.data.ReviewDecisions
import com.example.listea.domain.AssetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display side of configurable actions. The wire side lives in [WebhookPayloadTest]; the
 * point of both is that an action's identity is its id, and never either of its names.
 */
class SettingsTest {

    private val custom1 = ItemAction.Custom("custom1")
    private val custom2 = ItemAction.Custom("custom2")

    private fun item(vararg actions: String, favorite: Boolean = false) =
        ReviewDecisions(isFavorite = favorite, customActions = actions.toSet())

    /** The two out-of-the-box actions, renamed. Ids stay put — that is what is being tested. */
    private fun settings(
        c1Name: String = "C1",
        c1Value: String = "cust1",
        c2Name: String = "C2",
        c2Value: String = "cust2"
    ) = AppSettings(
        assetControlTags = mapOf(
            AssetKind.IMAGE to listOf(
                CustomAction(id = "custom1", displayName = c1Name, webhookValue = c1Value),
                CustomAction(id = "custom2", displayName = c2Name, webhookValue = c2Value)
            )
        )
    )

    /**
     * The control an image is offered, over whatever the app's tags are.
     *
     * An empty record, deliberately: `""` is what every Todo and Task that predates v0.5.0 holds,
     * and what it has to resolve to is this app's own tags. So this is not a way of dodging the
     * recorded-column path — it *is* that path, and the case it covers is the one the whole upgrade
     * rests on.
     */
    private fun imageControl(settings: AppSettings = AppSettings()): ResolvedAssetControl =
        settings.controlFor("", AssetKind.IMAGE)

    @Test
    fun `defaults are the factory labels and values`() {
        val control = imageControl()
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
        val control = imageControl(settings(c1Name = "Save", c2Name = "Later"))
        assertEquals("Save", control.labelOf(custom1))
        assertEquals("Later", control.labelOf(custom2))
    }

    @Test
    fun `favorite keeps its own label and value whatever the actions are called`() {
        val control = imageControl(settings(c1Name = "Favorite", c1Value = "favorite"))
        assertEquals("★", control.labelOf(ItemAction.Favourite))
        assertEquals("favorite", control.wireOf(ItemAction.Favourite))
    }

    @Test
    fun `display name and webhook value are independent of each other`() {
        val control = imageControl(settings(c1Name = "Save", c1Value = "tag1"))
        assertEquals("Save", control.labelOf(custom1))
        assertEquals("tag1", control.wireOf(custom1))
    }

    @Test
    fun `the row offers favourite first and the configured actions in their own order`() {
        assertEquals(
            listOf(ItemAction.Favourite, custom1, custom2),
            imageControl().actions
        )
        assertEquals(
            listOf(ItemAction.Favourite),
            imageControl(AppSettings(assetControlTags = mapOf(AssetKind.IMAGE to emptyList()))).actions
        )
    }

    @Test
    fun `an action the user deleted is neither offered nor labelled`() {
        // The id stays on the item; the control is what forgot it, so nothing here resolves it.
        // It answers with the id rather than with null, which is what keeps a tag deleted from a
        // control *carried* by the items that hold it and out of the payload — see
        // `ResolvedAssetControl.wireOf`.
        val control = imageControl(
            AppSettings(
                assetControlTags = mapOf(
                    AssetKind.IMAGE to listOf(CustomAction("custom2", "C2", "cust2"))
                )
            )
        )
        assertEquals(listOf(ItemAction.Favourite, custom2), control.actions)
        assertNull(control.tags.firstOrNull { it.id == "custom1" })
        assertEquals("custom1", control.wireOf(custom1))
        assertNull(itemActionLabel(item("custom1"), control))
    }

    @Test
    fun `the row summary follows the configured names`() {
        val marked = item("custom1", favorite = true)
        assertEquals("★ · C1", itemActionLabel(marked, imageControl()))
        assertEquals("★ · Save", itemActionLabel(marked, imageControl(settings(c1Name = "Save"))))
    }

    @Test
    fun `an item with no actions has no summary`() {
        assertNull(itemActionLabel(item(), imageControl()))
        assertNotNull(itemActionLabel(item("custom2"), imageControl()))
    }

    @Test
    fun `an action list survives a round trip`() {
        val actions = listOf(
            CustomAction(id = "custom1", displayName = "Save", webhookValue = "tag1"),
            CustomAction(id = "a0123456789b", displayName = "Später", webhookValue = "later")
        )
        assertEquals(actions, decodeCustomActions(encodeCustomActions(actions)))
    }

    @Test
    fun `a record that cannot be read costs its own line and no more`() {
        val text = listOf(
            "\tNo id\tnone",
            "has,comma\tComma\tcomma",
            "keeper\tKeeper\tkeep",
            "sparse"
        ).joinToString("\n")

        assertEquals(
            listOf(
                CustomAction(id = "keeper", displayName = "Keeper", webhookValue = "keep"),
                CustomAction(id = "sparse", displayName = "", webhookValue = "")
            ),
            decodeCustomActions(text)
        )
    }

    @Test
    fun `typed text cannot break the record it is stored in`() {
        val typed = sanitizeActionText("  two\tlines\nworth  ")
        assertEquals("two lines worth", typed)

        val actions = listOf(CustomAction(id = "keeper", displayName = typed, webhookValue = "k"))
        assertEquals(actions, decodeCustomActions(encodeCustomActions(actions)))
    }

    @Test
    fun `a new action is named from the first free slot`() {
        val defaults = DefaultCustomActions
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
