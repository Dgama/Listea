package com.example.listea

import com.example.listea.data.ItemAction
import com.example.listea.data.ReviewDecisions
import com.example.listea.domain.AssetKind
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The asset control as a stored thing: the column's format, and what a reader does with a column it
 * cannot fully understand.
 *
 * Separate from [SettingsTest] because the two codecs are separate: that file is about one *action*
 * list, this one about a *control* — several kinds, each with its own action list and its own
 * template reference, written into one column. The characters each codec has to refuse are therefore
 * different, and a test that only ever exercised the older one would not notice.
 *
 * The load-bearing case is at the bottom. `''` is not an empty tag set, it is the absence of an
 * answer, and a reader that conflates them strips the chip row off every Asset of every Todo that
 * predates this version — silently, with no error and no row changing. Every test here that asserts
 * a fallback is standing on that one.
 */
class AssetControlTest {

    private fun tag(id: String, name: String = id, value: String = id) =
        CustomAction(id = id, displayName = name, webhookValue = value)

    private fun config(vararg tags: CustomAction) = AssetControlConfig(
        ref = Templates.MediaAssetControl.ref,
        tags = tags.toList()
    )

    private fun settings(tags: Map<AssetKind, List<CustomAction>>) =
        AppSettings(assetControlTags = tags)

    // --- the column ------------------------------------------------------------------------------

    @Test
    fun `a configuration survives a round trip at every size`() {
        assertEquals(
            emptyMap<AssetKind, AssetControlConfig>(),
            decodeAssetControls(encodeAssetControls(emptyMap()))
        )

        val one = mapOf(AssetKind.IMAGE to config(tag("a1", "Price", "price")))
        assertEquals(one, decodeAssetControls(encodeAssetControls(one)))

        val both = mapOf(
            AssetKind.IMAGE to config(tag("a1", "Price", "price"), tag("a2", "Quality", "quality")),
            AssetKind.VIDEO to config(tag("a3", "Fresh", "fresh"))
        )
        assertEquals(both, decodeAssetControls(encodeAssetControls(both)))
    }

    @Test
    fun `a kind with no tags is a configured kind, not an absent one`() {
        // The middle state, and the one `''` is most easily confused with: a user who cleared the
        // image row on purpose has an image configuration holding nothing, and it must survive
        // storage as exactly that rather than decaying into "never chose".
        val cleared = mapOf(AssetKind.IMAGE to config())
        val decoded = decodeAssetControls(encodeAssetControls(cleared))

        assertEquals(setOf(AssetKind.IMAGE), decoded.keys)
        assertEquals(emptyList<CustomAction>(), decoded.getValue(AssetKind.IMAGE).tags)
    }

    @Test
    fun `an empty configuration writes the column default`() {
        // `""` is what the migration puts on every existing row, so writing it here is what keeps a
        // Todo that has never been configured from looking like one that has.
        assertEquals("", encodeAssetControls(emptyMap()))
    }

    @Test
    fun `the same configuration always writes the same string`() {
        // Built in two different orders. Storage that varies with a map's iteration order reads as
        // changed when nothing has, which is the worst kind of row diff.
        val image = AssetKind.IMAGE to config(tag("a1"))
        val video = AssetKind.VIDEO to config(tag("v1"))

        assertEquals(
            encodeAssetControls(mapOf(image, video)),
            encodeAssetControls(mapOf(video, image))
        )
    }

    @Test
    fun `the template a configuration names is stored with it`() {
        // Not a formality: a configuration outlives the definition it was chosen for, so "which
        // control were these tags picked for" is a question the column has to be able to answer
        // once a second version exists.
        val other = AssetControlConfig(
            ref = TemplateVersionRef(TemplateId("builtin.media.asset-control"), 2),
            tags = listOf(tag("a1"))
        )
        val decoded = decodeAssetControls(
            encodeAssetControls(mapOf(AssetKind.IMAGE to other))
        ).getValue(AssetKind.IMAGE)

        assertEquals(other.ref, decoded.ref)
        assertNotEquals(Templates.MediaAssetControl.ref.version, decoded.ref.version)
    }

    // --- what a reader refuses -------------------------------------------------------------------

    @Test
    fun `a line this build cannot read costs itself and nothing else`() {
        val text = listOf(
            // A kind this build has never written.
            "audio\t${CONTROL_ID}\t1\ta0:A:a",
            // No template id, so not even the kind survives: a configuration that cannot say what
            // governs it is not one.
            "video\t\tv0:V:v",
            // The good line, last so an over-eager parse cannot be mistaken for tolerance.
            "image\t${CONTROL_ID}\t1\ta1:A:a"
        ).joinToString("\n")

        val decoded = decodeAssetControls(text)

        assertEquals(setOf(AssetKind.IMAGE), decoded.keys)
        assertEquals(listOf(tag("a1", "A", "a")), decoded.getValue(AssetKind.IMAGE).tags)
    }

    @Test
    fun `a tag that cannot be stored costs its own record, not the line it sits on`() {
        // A tag with no id is unreachable, and one whose id holds a comma would split in two when an
        // item records which tags it carries — see `encodeActionIds`. Both are dropped; their
        // siblings are not.
        val text = "image\t$CONTROL_ID\t1\t:Nameless:v|a,b:Comma:c|a2:A:a"

        assertEquals(
            listOf(tag("a2", "A", "a")),
            decodeAssetControls(text).getValue(AssetKind.IMAGE).tags
        )
    }

    @Test
    fun `a tag with no name is kept rather than invented or dropped`() {
        // An unlabelled tag is odd and harmless, and the id and wire value are the parts that carry
        // meaning — so the empty name is preserved as what the user left, not filled in.
        val text = "image\t$CONTROL_ID\t1\ta1::"

        assertEquals(
            listOf(CustomAction(id = "a1", displayName = "", webhookValue = "")),
            decodeAssetControls(text).getValue(AssetKind.IMAGE).tags
        )
    }

    @Test
    fun `a version that cannot be read is assumed rather than losing its tags`() {
        // 1 is the only version any shipped build has written, and a control with no tags is worse
        // than one whose version is wrong but whose tags survive.
        val text = "image\t$CONTROL_ID\tnot-a-number\ta1:A:a"

        assertEquals(
            TemplateVersionRef(TemplateId(CONTROL_ID), 1),
            decodeAssetControls(text).getValue(AssetKind.IMAGE).ref
        )
    }

    @Test
    fun `a kind that exists but may not be configured is readable and inert`() {
        // `OTHER` is a key this build knows, so its line is not refused — but nothing writes one,
        // nothing offers one, and a file of that kind cannot be reviewed. Asserted so the boundary
        // is a stated one rather than an accident of `fromKey`.
        val text = "other\t$CONTROL_ID\t1\to1:O:o"

        assertTrue(AssetKind.OTHER in decodeAssetControls(text).keys)
        assertTrue(AssetKind.OTHER !in configurableAssetKinds())
    }

    @Test
    fun `text a user types cannot break the record a control is stored in`() {
        // Same rule as the actions codec, over one more separator: a control stores tags joined by
        // `|` with `:` between a tag's three fields, so both have to go along with the tab and the
        // newline that end a field and a line.
        val typed = sanitizeActionText("  a|b:c\td\ne  ")
        assertEquals("a b c d e", typed)

        val stored = mapOf(AssetKind.IMAGE to config(tag("a1", typed, typed)))
        assertEquals(stored, decodeAssetControls(encodeAssetControls(stored)))
    }

    // --- `''` is an absence, not an empty set ----------------------------------------------------

    @Test
    fun `a column that says nothing resolves to this app's own tags`() {
        // The upgrade path in one assertion: every row on the device reads `''` after v20, and what
        // it has to mean is what the app would offer a new Asset today.
        val settings = settings(
            mapOf(
                AssetKind.IMAGE to listOf(tag("i1", "I1", "i1")),
                AssetKind.VIDEO to listOf(tag("v1", "V1", "v1"))
            )
        )

        assertEquals(
            listOf(ItemAction.Favourite, ItemAction.Custom("i1")),
            settings.controlFor("", AssetKind.IMAGE).actions
        )
        assertEquals(
            listOf(ItemAction.Favourite, ItemAction.Custom("v1")),
            settings.controlFor("", AssetKind.VIDEO).actions
        )
    }

    @Test
    fun `a column that cannot be read resolves exactly as one that says nothing`() {
        val settings = settings(mapOf(AssetKind.IMAGE to listOf(tag("i1", "I1", "i1"))))

        // Every one of these fails to name a kind *and* a template, so none of them is a record of
        // anything: there is nothing to say the user ever configured image, and the app's own tags
        // are the only answer that does not invent a choice.
        listOf("", "garbage", "image", "image\t\t", "\n\t\n", "audio\t$CONTROL_ID\t1\ta1:A:a")
            .forEach { column ->
                assertEquals(
                    "column `${column.replace("\n", "\\n")}`",
                    settings.controlFor("", AssetKind.IMAGE),
                    settings.controlFor(column, AssetKind.IMAGE)
                )
            }
    }

    @Test
    fun `a kind whose tags all failed still counts as configured`() {
        // The other side of `''`, and the reason the two readings cannot be conflated. This line
        // names a kind *and* a template, so the user did configure image — with the tags that
        // survived, which is none. It resolves as an image control offering nothing, which is a
        // different screen from one offering the app's default, and rightly so: it is what the user
        // asked for. A reader that fell back here would resurrect tags they had deleted.
        val column = "image\t$CONTROL_ID\t1\t:nameless|a,b:comma"
        val settings = settings(mapOf(AssetKind.IMAGE to listOf(tag("i1", "I1", "i1"))))

        val resolved = settings.controlFor(column, AssetKind.IMAGE)

        assertEquals(emptyList<CustomAction>(), resolved.tags)
        assertEquals(listOf(ItemAction.Favourite), resolved.actions)
        // And the fallback stays per kind: the video kind this line does not name is still the app's
        // video tags, because a record about one kind says nothing about another.
        assertEquals(DefaultCustomActions, settings.controlFor(column, AssetKind.VIDEO).tags)
    }

    @Test
    fun `a kind the record leaves out is this app's default, not an empty row`() {
        // Decision 9, and the difference between a *partial* record and an *empty* one. A user
        // configures image, imports, and later adds a video to the folder: the video's kind is
        // named by nothing, and it must offer the app's video tags rather than nothing at all.
        // Reading the column as a complete statement is what would strip them.
        val recorded = encodeAssetControls(
            mapOf(AssetKind.IMAGE to config(tag("i1", "I1", "i1")))
        )
        val settings = settings(
            mapOf(
                AssetKind.IMAGE to listOf(tag("i1", "I1", "i1")),
                AssetKind.VIDEO to listOf(tag("v1", "V1", "v1"))
            )
        )

        assertEquals(
            listOf("i1"),
            settings.controlFor(recorded, AssetKind.IMAGE).tags.map { it.id }
        )
        assertEquals(
            listOf("v1"),
            settings.controlFor(recorded, AssetKind.VIDEO).tags.map { it.id }
        )
    }

    @Test
    fun `a configuration is complete before a run is given it`() {
        // `controlConfigsFor` is what a refresh hands to `runImport`, and it has to be complete for
        // the reason above: a run that stamped only the kinds named in the column would delete the
        // configuration of every kind the user had not touched yet, one run at a time.
        val encoded = encodeAssetControls(mapOf(AssetKind.IMAGE to config(tag("i1", "I1", "i1"))))
        val configs = AppSettings().controlConfigsFor(encoded)

        assertEquals(setOf(AssetKind.IMAGE, AssetKind.VIDEO), configs.keys)
        assertTrue(AssetKind.OTHER !in configs.keys)
        // And an unconfigured app hands a run exactly the pre-fill its dialog would have shown.
        assertEquals(AppSettings().defaultControlConfigs(), AppSettings().controlConfigsFor(""))
    }

    // --- the task-level union --------------------------------------------------------------------

    @Test
    fun `a Task's row is the union of its kinds, star first and in a stable order`() {
        val settings = settings(
            mapOf(
                AssetKind.IMAGE to listOf(tag("i1"), tag("i2")),
                AssetKind.VIDEO to listOf(tag("v1"))
            )
        )

        val control = settings.controlForTask("")

        assertEquals(
            listOf(
                ItemAction.Favourite,
                ItemAction.Custom("i1"),
                ItemAction.Custom("i2"),
                ItemAction.Custom("v1")
            ),
            control.actions
        )
        // Same answer every time: this list decides the order of a webhook's `actions` array, and a
        // receiver diffing bodies cannot use one that reshuffles itself.
        assertEquals(control, settings.controlForTask(""))
    }

    @Test
    fun `a tag two kinds both define is named once`() {
        val settings = settings(
            mapOf(
                AssetKind.IMAGE to listOf(tag("shared"), tag("i1")),
                AssetKind.VIDEO to listOf(tag("shared"))
            )
        )

        assertEquals(
            listOf(ItemAction.Favourite, ItemAction.Custom("shared"), ItemAction.Custom("i1")),
            settings.controlForTask("").actions
        )
    }

    @Test
    fun `a tag only the video kind defines is still nameable on a photograph`() {
        // The two resolutions the release needs, side by side and disagreeing on purpose. The chip
        // row follows the file on screen; the badge row and the payload speak about the Task, so a
        // video's tag has to resolve while a photograph is in front of the user — otherwise a chip
        // they can see is reported as an id.
        val settings = settings(
            mapOf(
                AssetKind.IMAGE to emptyList(),
                AssetKind.VIDEO to listOf(tag("v1", "Later", "later"))
            )
        )
        val marked = ReviewDecisions(customActions = setOf("v1"))

        assertEquals(listOf("later"), itemActionNames(marked, settings.controlForTask("")))
        assertEquals(listOf("later"), itemActionNames(marked, settings.controlFor("", AssetKind.VIDEO)))
        assertEquals(emptyList<String>(), itemActionNames(marked, settings.controlFor("", AssetKind.IMAGE)))
    }

    // --- the star is the control's, not the screen's ----------------------------------------------

    @Test
    fun `star is offered only when the control in force carries it`() {
        // No shipped definition lacks it, so the false arm is unreachable from the registry today —
        // and that is exactly why it is asserted here rather than left to a screen. What is being
        // pinned is that the star is read off the control rather than drawn as a constant; the
        // release that adds a control without it should find this already correct.
        assertEquals(listOf(ItemAction.Favourite), ResolvedAssetControl(true, emptyList()).actions)
        assertEquals(emptyList<ItemAction>(), ResolvedAssetControl(false, emptyList()).actions)
        assertEquals(
            listOf(ItemAction.Custom("a1")),
            ResolvedAssetControl(false, listOf(tag("a1"))).actions
        )
        // And the one that does ship carries it, so nothing about this release's screen changed.
        assertTrue(settings(emptyMap()).controlFor("", AssetKind.IMAGE).favourite)
    }

    private companion object {
        const val CONTROL_ID = "builtin.media.asset-control"
    }
}
