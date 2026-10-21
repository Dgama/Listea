package com.example.listea

import com.example.listea.data.AssetDecisions
import com.example.listea.data.ItemAction
import com.example.listea.data.TemplateEntity
import com.example.listea.data.TemplateVersionEntity
import com.example.listea.domain.AssetKind
import com.example.listea.domain.ButtonType
import com.example.listea.domain.TEMPLATE_BUTTONS_COMMENT_VERSION
import com.example.listea.domain.TEMPLATE_BUTTONS_VERSION
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.TemplateId
import com.example.listea.domain.StoredTemplateVersion
import com.example.listea.domain.TemplateScope
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.Templates
import com.example.listea.domain.encodeTemplateButtons
import com.example.listea.domain.encodeTemplateScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Todo or a Task is attached to, and what that resolves to.
 *
 * **The successor to the old `AssetControlTest`, and the release is legible in the difference.** That
 * file tested a codec for a per-kind configuration — several kinds, each with its own tag list and
 * its own template reference, written into one column — and a reader that fell back to the app's own
 * tags whenever a row said nothing. There is no configuration any more. An attachment is a
 * `templateId \t version` reference, and [TemplateResolver] answers what a reference offers; the
 * fallback is gone with the thing that needed it.
 *
 * **`''` is still the load-bearing case, and it points the other way now.** It used to mean *nothing
 * was ever chosen, so use the app's own tags*; it means *nothing is attached, so this file gets no
 * chips at all*. That is decision 9 and the largest behaviour change of the release — a file no
 * template governs draws an empty bar rather than somebody else's buttons — so it is asserted from
 * several directions below rather than once.
 *
 * The column's format and the lookup over it are one file because they are one question asked twice:
 * what a row says, and what saying it does. The two-shape decoder at the bottom is the seam between
 * this release and the last one, and it is the only place in the app that still knows what a
 * configuration looked like.
 */
class TemplateAttachmentTest {

    // --- the column --------------------------------------------------------------------------------

    @Test
    fun `a list of attachments survives a round trip, and keeps its order`() {
        assertEquals("", encodeTemplateAttachments(emptyList()))
        assertEquals(emptyList<TemplateVersionRef>(), decodeTemplateAttachments(""))

        val one = listOf(TemplateVersionRef(TemplateId("a"), 1))
        assertEquals(one, decodeTemplateAttachments(encodeTemplateAttachments(one)))

        // Order is the user's and is what first-match resolution reads, so it is the one thing this
        // pair of functions must not be clever about. Sorted here rather than written in order, so a
        // codec that sorted would be caught rather than flattered.
        val two = listOf(
            TemplateVersionRef(TemplateId("b"), 2),
            TemplateVersionRef(TemplateId("a"), 1)
        )
        assertEquals(two, decodeTemplateAttachments(encodeTemplateAttachments(two)))
        assertEquals("b\t2\na\t1", encodeTemplateAttachments(two))
    }

    @Test
    fun `attaching the same template twice is one attachment said twice`() {
        // Not an error and not worth reporting: nothing can be attached twice, and a list holding
        // one twice would make first-match resolution depend on how many times a user tapped.
        val once = TemplateVersionRef(TemplateId("a"), 1)
        assertEquals(listOf(once), decodeTemplateAttachments("a\t1\na\t1"))
    }

    @Test
    fun `a line this build cannot read costs its own line and never the column`() {
        val text = listOf(
            // A line with one field names no version this build can check.
            "image",
            // An empty id, and a version that is not a number, and one that is not a version.
            "\t1",
            "a\tnot-a-number",
            "a\t0",
            // The good line, last, so an over-eager parse cannot be mistaken for tolerance.
            "a\t2"
        ).joinToString("\n")

        assertEquals(
            listOf(TemplateVersionRef(TemplateId("a"), 2)),
            decodeTemplateAttachments(text)
        )
    }

    // --- the two shapes on real devices ------------------------------------------------------------

    @Test
    fun `the per-kind configuration this release replaces still names its template`() {
        // A Todo imported before the upgrade holds `kind \t templateId \t version \t tags`, and what
        // it must go on resolving to is the template it named. The tags it also carried are dropped:
        // a template is where tags live now, and the version it names has its own.
        val old = "image\tbuiltin.media.asset-control\t1\ta1:Price:price"
        assertEquals(
            listOf(TemplateVersionRef(TemplateId("builtin.media.asset-control"), 1)),
            decodeTemplateAttachments(old)
        )

        // Both kinds configured the same way is one attachment written twice, which is exactly what
        // the new shape would have written for it.
        val both = old + "\nvideo\tbuiltin.media.asset-control\t1\ta1:Price:price"
        assertEquals(
            listOf(TemplateVersionRef(TemplateId("builtin.media.asset-control"), 1)),
            decodeTemplateAttachments(both)
        )
    }

    @Test
    fun `the third field is what tells the two shapes apart`() {
        // The rule rather than a guess, and the one line that would be misread if the shapes were
        // told apart by position: reading them that way takes `image` for a template id here.
        //
        // Two *different* lineages, so that what is being read is the shape and nothing else. Two
        // versions of one lineage would be the collapse rule's business — see the test below.
        val mixed = "image\ta\t2\ta1:A:a\nb\t3"
        assertEquals(
            listOf(TemplateVersionRef(TemplateId("a"), 2), TemplateVersionRef(TemplateId("b"), 3)),
            decodeTemplateAttachments(mixed)
        )
    }

    @Test
    fun `a Todo on an older version of a template is attached to it, and the run moves it`() {
        // The defect, at its narrowest. The attach step asked whether the Todo held *this
        // reference*, the Todo held `a@1`, the picker offered `a@2`, and the answer came back *no* —
        // so the row drew unticked and ticking it appended a second reference for one template.
        // First-match resolution then handed every file `a@1`, and the button the user had just
        // added to the template was unreachable with nothing on screen to say so.
        val held = listOf(TemplateVersionRef(TemplateId("a"), 1))
        val offered = stored("a", version = 2)

        assertEquals(1, heldVersionOf(held, TemplateId("a")))
        assertNull(heldVersionOf(held, TemplateId("b")))
        assertEquals(listOf(offered.ref), withAttachment(held, offered, on = true))
    }

    @Test
    fun `a replaced template keeps its place, and a new one goes last`() {
        // Order is what decides which of two templates governs a file, so it is the user's and not
        // this function's to sort — a template already in the list does not move when it is brought
        // up to date, and one just added joins the end.
        val attached = listOf(
            TemplateVersionRef(TemplateId("a"), 1),
            TemplateVersionRef(TemplateId("b"), 1)
        )

        assertEquals(
            listOf(TemplateVersionRef(TemplateId("a"), 2), TemplateVersionRef(TemplateId("b"), 1)),
            withAttachment(attached, stored("a", version = 2), on = true)
        )
        assertEquals(
            listOf(
                TemplateVersionRef(TemplateId("a"), 1),
                TemplateVersionRef(TemplateId("b"), 1),
                TemplateVersionRef(TemplateId("c"), 1)
            ),
            withAttachment(attached, stored("c"), on = true)
        )
        assertEquals(
            listOf(TemplateVersionRef(TemplateId("b"), 1)),
            withAttachment(attached, stored("a"), on = false)
        )
    }

    // --- the refresh that carries a Todo forward --------------------------------------------------

    @Test
    fun `an upgrade moves every held lineage to the offered version and keeps the order`() {
        // The write half of the dialog's *Held at version 1. This run will attach version 2.* A
        // refresh that re-attached what the Todo already held would make that sentence a lie and the
        // edit permanently unreachable — the same defect `withAttachment` fixed on the tick.
        val held = listOf(
            TemplateVersionRef(TemplateId("b"), 1),
            TemplateVersionRef(TemplateId("a"), 3)
        )
        val offered = listOf(stored("a", version = 4), stored("b", version = 2))

        assertEquals(
            listOf(TemplateVersionRef(TemplateId("b"), 2), TemplateVersionRef(TemplateId("a"), 4)),
            upgradeAttachments(held, offered)
        )
    }

    @Test
    fun `an upgrade never adds a lineage, and one no longer offered keeps what it holds`() {
        // The picker is a snapshot of what exists; a refresh is not a re-choosing. A lineage missing
        // from it is one this build cannot read, and the reference already on the row is the better
        // answer than dropping the attachment silently.
        val held = listOf(
            TemplateVersionRef(TemplateId("a"), 1),
            TemplateVersionRef(TemplateId("gone"), 2)
        )
        val offered = listOf(stored("a", version = 2), stored("new", version = 1))

        assertEquals(
            listOf(
                TemplateVersionRef(TemplateId("a"), 2),
                TemplateVersionRef(TemplateId("gone"), 2)
            ),
            upgradeAttachments(held, offered)
        )
    }

    @Test
    fun `an empty attachment list stays empty, and a current version does not move`() {
        assertEquals(
            emptyList<TemplateVersionRef>(),
            upgradeAttachments(emptyList(), listOf(stored("a", version = 2)))
        )
        val current = listOf(TemplateVersionRef(TemplateId("a"), 2))
        assertEquals(current, upgradeAttachments(current, listOf(stored("a", version = 2))))
    }

    @Test
    fun `the version notice is drawn only when a held version actually moves`() {
        val held = listOf(TemplateVersionRef(TemplateId("a"), 1))
        val notice = refreshVersionNotice(held, listOf(stored("a", version = 2)))

        assertNotNull(notice)
        assertTrue(notice!!.contains("a"))
        assertTrue(notice.contains("Tasks already marked will be read under the new version"))
        assertTrue(notice.contains("no longer shown or sent"))
        assertTrue(notice.contains("starts unmarked"))

        // Nothing moved: same version, a lineage no longer offered, no attachments, no offered list.
        assertNull(refreshVersionNotice(held, listOf(stored("a", version = 1))))
        assertNull(refreshVersionNotice(held, emptyList()))
        assertNull(refreshVersionNotice(emptyList(), listOf(stored("a", version = 2))))
    }

    @Test
    fun `one template is one attachment, so the highest version a column holds is the one it means`() {
        // The defect this rule was written for, and the shape it was found on: a Todo re-imported
        // while it held version 1 wrote version 2 *beside* it, because the picker matched a whole
        // reference — and first-match resolution then handed every file version 1. The button the
        // user had just added to the template was unreachable, and nothing said so.
        //
        // Highest rather than first, because a stored version is a record of how the template stood
        // when a run happened and not a choice a live Todo is holding two of. The lineage keeps its
        // place in the list, which is what decides a conflict.
        assertEquals(
            listOf(TemplateVersionRef(TemplateId("a"), 2)),
            decodeTemplateAttachments("a\t1\na\t2")
        )
        assertEquals(
            listOf(TemplateVersionRef(TemplateId("a"), 2), TemplateVersionRef(TemplateId("b"), 1)),
            decodeTemplateAttachments("a\t1\nb\t1\na\t2")
        )
        // Out of order as well, because the rule is the number and not the row's position.
        assertEquals(
            listOf(TemplateVersionRef(TemplateId("a"), 3)),
            decodeTemplateAttachments("a\t3\na\t1")
        )
    }

    // --- what a reference resolves to --------------------------------------------------------------

    @Test
    fun `nothing attached resolves to no control at all`() {
        // Decision 9. The old answer was this app's own tags; the new one is an empty bar, and the
        // difference is the whole reason a fallback had to go: with several templates to choose
        // from, "nobody picked one" and "pick the default" are different facts.
        val resolver = resolver(
            lineage("a", "A") to listOf(version("a", buttons = TAGS_A))
        )

        assertNull(resolver.controlFor("", AssetKind.IMAGE))
        assertEquals(ResolvedAssetControl.Empty, resolver.controlForTask(""))
    }

    @Test
    fun `the first template that admits the file answers for it`() {
        val resolver = resolver(
            lineage("a", "A") to listOf(version("a", buttons = TAGS_A)),
            lineage("b", "B") to listOf(version("b", buttons = TAGS_B))
        )

        assertEquals(listOf(ItemAction("a1")), resolver.controlFor("a\t1\nb\t1", AssetKind.IMAGE)?.actions)
        assertEquals(listOf(ItemAction("b1")), resolver.controlFor("b\t1\na\t1", AssetKind.IMAGE)?.actions)
    }

    @Test
    fun `the template that governs a file is the one whose chips it got`() {
        // The same first-match walk `controlFor` makes, answered as a version rather than a button
        // list. Two callers need the version itself — a new PDF mark is authored under it, and a
        // stored mark must still find its tool in it — and they must agree with the chips on screen
        // about which template that is, or a mark would be written under one template and shown
        // against another.
        val resolver = resolver(
            lineage("photos", "Photos") to listOf(
                version("photos", scope = TemplateScope.ofKinds(setOf(AssetKind.IMAGE)), buttons = TAGS_A)
            ),
            lineage("papers", "Papers") to listOf(
                version("papers", scope = TemplateScope.ofKinds(setOf(AssetKind.PDF)), buttons = TAGS_B)
            )
        )
        val attached = "photos\t1\npapers\t1"

        assertEquals(TemplateVersionRef(TemplateId("papers"), 1), resolver.governingFor(attached, AssetKind.PDF)?.ref)
        assertEquals(
            resolver.controlFor(attached, AssetKind.PDF)?.buttons,
            resolver.governingFor(attached, AssetKind.PDF)?.buttons
        )
        // A PDF in a Todo whose only template governs images is a PDF no template governs.
        assertNull(resolver.governingFor("photos\t1", AssetKind.PDF))
    }

    @Test
    fun `a kind nothing admits offers nothing, and the file keeps its empty bar`() {
        // The acceptance case, as a unit test: a Todo whose only template is scoped to images shows
        // a video no chips at all. Null and not an empty control, because the two are different
        // answers — the caller draws `Empty` for both and a future caller may not.
        val resolver = resolver(
            lineage("a", "A") to listOf(
                version("a", scope = TemplateScope.ofKinds(setOf(AssetKind.IMAGE)), buttons = TAGS_A)
            )
        )

        assertEquals(listOf(ItemAction("a1")), resolver.controlFor("a\t1", AssetKind.IMAGE)?.actions)
        assertNull(resolver.controlFor("a\t1", AssetKind.VIDEO))
    }

    @Test
    fun `a reference this build cannot read resolves to nothing rather than to a guess`() {
        // A row written by a later release, or one whose template was never seeded. The old reader
        // would have fallen back to the defaults here, which is the behaviour this release removes.
        val resolver = resolver(lineage("a", "A") to listOf(version("a", buttons = TAGS_A)))

        assertNull(resolver.controlFor("nowhere\t1", AssetKind.IMAGE))
        assertNull(resolver.controlFor("a\t9", AssetKind.IMAGE))
    }

    @Test
    fun `a version whose buttons this build cannot read is skipped rather than half-read`() {
        // The failing-closed rule the whole release is built on, reaching the lookup: a build that
        // cannot decode a payload must not offer the part of it it happened to understand.
        val resolver = resolver(
            lineage("a", "A") to listOf(
                version("a", version = 1, buttons = TAGS_A),
                version("a", version = 2, buttons = TAGS_A, buttonsVersion = TEMPLATE_BUTTONS_COMMENT_VERSION + 1)
            )
        )

        assertEquals(listOf(ItemAction("a1")), resolver.controlFor("a\t1", AssetKind.IMAGE)?.actions)
        assertNull(resolver.controlFor("a\t2", AssetKind.IMAGE))
    }

    @Test
    fun `a scope that admits nothing admits nothing`() {
        val resolver = resolver(
            lineage("a", "A") to listOf(version("a", scope = TemplateScope(emptyList()), buttons = TAGS_A))
        )

        assertNull(resolver.controlFor("a\t1", AssetKind.IMAGE))
    }

    // --- and what a whole Task resolves to ----------------------------------------------------------

    @Test
    fun `a Task's row is every attached template's buttons, in attachment order, each id once`() {
        val resolver = resolver(
            lineage("a", "A") to listOf(version("a", buttons = TAGS_A + SHARED)),
            lineage("b", "B") to listOf(version("b", buttons = TAGS_B + SHARED))
        )

        assertEquals(
            (TAGS_A + SHARED + TAGS_B).map { ItemAction(it.id) },
            resolver.controlForTask("a\t1\nb\t1").actions
        )
        // Same answer every time: this list decides the order of a webhook's `actions` array, and a
        // receiver diffing bodies cannot use one that reshuffles itself.
        assertEquals(
            resolver.controlForTask("a\t1\nb\t1"),
            resolver.controlForTask("a\t1\nb\t1")
        )
    }

    @Test
    fun `a button only a video's template defines is still nameable on a photograph`() {
        // The two resolutions side by side and disagreeing on purpose. The chip row follows the file
        // on screen; the badge row and the payload speak about the Task, so a video template's
        // button has to resolve while a photograph is in front of the user — otherwise a chip they
        // can see is reported as an id.
        val resolver = resolver(
            lineage("photos", "Photos") to listOf(
                version("photos", scope = TemplateScope.ofKinds(setOf(AssetKind.IMAGE)), buttons = TAGS_A)
            ),
            lineage("clips", "Clips") to listOf(
                version("clips", scope = TemplateScope.ofKinds(setOf(AssetKind.VIDEO)), buttons = TAGS_B)
            )
        )
        val marked = AssetDecisions(selections = setOf("b1"))

        assertEquals(listOf("b1"), itemActionNames(marked, resolver.controlForTask("photos\t1\nclips\t1")))
        assertEquals(
            listOf("b1"),
            itemActionNames(marked, resolver.controlFor("clips\t1", AssetKind.VIDEO)!!)
        )
        assertEquals(emptyList<String>(), itemActionNames(marked, resolver.controlFor("photos\t1", AssetKind.IMAGE)!!))
    }

    // --- what there is to attach, and what a card calls it ------------------------------------------

    @Test
    fun `there is one thing to attach per lineage, and it is the highest version this build can read`() {
        val resolver = resolver(
            lineage("a", "Alpha") to listOf(
                version("a", version = 1, buttons = TAGS_A),
                version("a", version = 2, buttons = TAGS_B)
            ),
            // Every version unreadable, so the lineage cannot be attached at all — which is how a
            // template whose bar would draw empty stays out of the picker.
            lineage("b", "Beta") to listOf(
                version("b", version = 1, buttons = TAGS_A, buttonsVersion = TEMPLATE_BUTTONS_COMMENT_VERSION + 1)
            ),
            lineage("c", "Charlie") to listOf(version("c", version = 1, buttons = TAGS_A))
        )

        assertEquals(listOf("Alpha", "Charlie"), resolver.attachable.map { it.name })
        assertEquals(2, resolver.attachable.first().ref.version)
    }

    @Test
    fun `a version a later build wrote does not outrank one this build can read`() {
        // `current` is the highest version *this build can read* and not the last row: a version
        // skipped by the failing-closed rule above must not be offered, and must not hide the
        // version beneath it either.
        val resolver = resolver(
            lineage("a", "Alpha") to listOf(
                version("a", version = 1, buttons = TAGS_A),
                version("a", version = 2, buttons = TAGS_A, buttonsVersion = TEMPLATE_BUTTONS_COMMENT_VERSION + 1)
            )
        )

        assertEquals(1, resolver.attachable.single().ref.version)
    }

    @Test
    fun `a card names the template a Task is attached to, and says when it cannot`() {
        val resolver = resolver(lineage("a", "Media Asset Control") to listOf(version("a", buttons = TAGS_A)))

        assertEquals("Media Asset Control", resolver.versionOf(TemplateVersionRef(TemplateId("a"), 1))?.name)
        // Null rather than an id at a user, which is what lets the Todo card say *a template this
        // build cannot read* instead of printing a UUID.
        assertNull(resolver.versionOf(TemplateVersionRef(TemplateId("nowhere"), 1)))
    }

    // --- the star is a button of the control, not the screen's --------------------------------------

    @Test
    fun `star is offered only when the template in force carries it`() {
        // Unreachable from the editor today, which is exactly why it is asserted here rather than
        // left to a screen: what is being pinned is that `★` is read off the stored buttons rather
        // than drawn as a constant.
        val withStar = resolver(
            lineage("a", "A") to listOf(
                version("a", buttons = listOf(star) + TAGS_A)
            )
        )
        val without = resolver(
            lineage("a", "A") to listOf(version("a", buttons = TAGS_A))
        )

        assertEquals(
            listOf(ItemAction.Favourite, ItemAction("a1")),
            withStar.controlFor("a\t1", AssetKind.IMAGE)?.actions
        )
        assertEquals(listOf(ItemAction("a1")), without.controlFor("a\t1", AssetKind.IMAGE)?.actions)
        // And the built-in does carry it, so nothing about this release's screen changed.
        assertTrue(Templates.AssetControlSeed.buttons.any { it.id == Templates.FAVOURITE_BUTTON_ID })
    }

    // --- which scopes can both be attached ----------------------------------------------------------

    @Test
    fun `two scopes that can both govern one file overlap`() {
        val images = TemplateScope.ofKinds(setOf(AssetKind.IMAGE))
        val videos = TemplateScope.ofKinds(setOf(AssetKind.VIDEO))
        val both = TemplateScope.ofKinds(setOf(AssetKind.IMAGE, AssetKind.VIDEO))

        assertFalse(scopesOverlap(images, videos))
        assertTrue(scopesOverlap(images, both))
        assertTrue(scopesOverlap(both, both))
    }

    @Test
    fun `a scope that admits nothing overlaps nothing`() {
        // Falls out of the definition rather than being special-cased: a template that governs no
        // file cannot be the second answer to anything, so the dialog is free to offer it beside
        // anything at all.
        assertFalse(scopesOverlap(TemplateScope(emptyList()), TemplateScope.ofKinds(AssetKind.entries.toSet())))
        assertFalse(scopesOverlap(TemplateScope(emptyList()), TemplateScope(emptyList())))
    }

    // --- the fixture ------------------------------------------------------------------------------

    private val star = TemplateButton(
        id = Templates.FAVOURITE_BUTTON_ID,
        name = "★",
        type = ButtonType.BOOLEAN,
        recordValue = Templates.FAVOURITE_BUTTON_ID
    )
    private val TAGS_A get() = listOf(TemplateButton("a1", "A1", ButtonType.TAG, "a1"))
    private val TAGS_B get() = listOf(TemplateButton("b1", "B1", ButtonType.TAG, "b1"))
    private val SHARED get() = listOf(TemplateButton("shared", "Shared", ButtonType.TAG, "shared"))

    private fun lineage(id: String, name: String) = TemplateEntity(id = id, name = name, createdAt = 0)

    /** One version as the picker offers it, which is a row rather than a pair of tables. */
    private fun stored(
        id: String,
        version: Int = 1,
        scope: TemplateScope = TemplateScope.ofKinds(setOf(AssetKind.IMAGE, AssetKind.VIDEO)),
        buttons: List<TemplateButton> = TAGS_A
    ) = StoredTemplateVersion(
        ref = TemplateVersionRef(TemplateId(id), version),
        name = id,
        scope = scope,
        buttons = buttons
    )

    private fun version(
        id: String,
        version: Int = 1,
        scope: TemplateScope = TemplateScope.ofKinds(setOf(AssetKind.IMAGE, AssetKind.VIDEO)),
        buttons: List<TemplateButton> = emptyList(),
        buttonsVersion: Int = TEMPLATE_BUTTONS_VERSION
    ) = TemplateVersionEntity(
        templateId = id,
        version = version,
        scope = encodeTemplateScope(scope),
        buttons = encodeTemplateButtons(buttons),
        buttonsVersion = buttonsVersion,
        createdAt = 0
    )

    /** The two tables as [TemplateResolver.of] takes them, from a handful of pairs. */
    private fun resolver(
        vararg rows: Pair<TemplateEntity, List<TemplateVersionEntity>>
    ): TemplateResolver = TemplateResolver.of(
        templates = rows.map { it.first },
        versions = rows.flatMap { it.second }
    )
}
