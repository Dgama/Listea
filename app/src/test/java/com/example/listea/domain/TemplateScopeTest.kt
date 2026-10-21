package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a template governs, and what it does when it cannot tell.
 *
 * Everything here is about the failing direction. A scope decides which files a template's chips are
 * drawn on, so a rule read *too generously* puts marks on files nobody wrote the template for, and
 * that is worse than a template that draws nothing — there is no way for the person looking at the
 * row to tell that the chips are not theirs. Every case below fails closed, and the two that look
 * like they should agree — a rule naming no known kind, and a rule naming a known one among unknown
 * ones — deliberately do not.
 */
class TemplateScopeTest {

    @Test
    fun `a scope is exactly this text`() {
        assertEquals(
            "kind:image,video",
            encodeTemplateScope(TemplateScope.ofKinds(setOf(AssetKind.VIDEO, AssetKind.IMAGE)))
        )
    }

    @Test
    fun `a kind rule admits its kinds and nothing else`() {
        val scope = decodeTemplateScope("kind:image")

        assertTrue(scope.admits(AssetKind.IMAGE))
        assertFalse(scope.admits(AssetKind.VIDEO))
        assertFalse(scope.admits(AssetKind.OTHER))
    }

    @Test
    fun `a rule this build cannot evaluate admits nothing, whatever the rule beside it said`() {
        // The scope is `kind:image` AND a mime rule this build does not know. Conjunction is what
        // makes the second one impossible to ignore: dropping it would leave the template governing
        // every image in the library when it was written to govern the JPEGs.
        val scope = decodeTemplateScope("kind:image\nmime:image/jpeg")

        assertFalse(scope.admits(AssetKind.IMAGE))
        assertFalse(scope.admits(AssetKind.VIDEO))
    }

    @Test
    fun `a rule this build cannot evaluate survives a trip through it`() {
        // Otherwise the first person to open a template written by a later release and press Update
        // would save it back with the rule they never saw quietly removed.
        val written = "kind:image\nmime:image/jpeg"

        assertEquals(written, encodeTemplateScope(decodeTemplateScope(written)))
    }

    @Test
    fun `a kind rule naming no kind this build knows admits nothing`() {
        // `kind:gif` is a rule that loaded correctly on a build that has a GIF kind. Here it is a
        // rule that failed to load — and it must not be read as `kind:` meaning "everything", nor as
        // an empty set that some later rule could re-widen.
        assertFalse(decodeTemplateScope("kind:gif").admits(AssetKind.IMAGE))
        assertFalse(decodeTemplateScope("kind:").admits(AssetKind.IMAGE))
    }

    @Test
    fun `a kind rule half understood narrows rather than fails`() {
        // The one case where dropping what cannot be read is the *safe* move: a `kind` rule is set
        // membership, so leaving `gif` out means GIF files are not governed — narrower than written,
        // which is the direction that cannot put chips on a file nobody meant to cover. The rule
        // still carries what this build does understand, so it stays a rule.
        val scope = decodeTemplateScope("kind:image,gif")

        assertTrue(scope.admits(AssetKind.IMAGE))
        assertFalse(scope.admits(AssetKind.VIDEO))
    }

    @Test
    fun `a PDF is a kind a scope may name, and one written before PDF existed does not admit it`() {
        // PDF becomes a governable kind in v0.7.0, so it reads and writes like the other two.
        assertEquals(
            "kind:image,video,pdf",
            encodeTemplateScope(
                TemplateScope.ofKinds(setOf(AssetKind.PDF, AssetKind.IMAGE, AssetKind.VIDEO))
            )
        )
        val scope = decodeTemplateScope("kind:image,pdf")
        assertTrue(scope.admits(AssetKind.PDF))
        assertFalse(scope.admits(AssetKind.VIDEO))

        // The line that matters for every template already on a device: the built-in media control
        // was written as `kind:image,video` when PDF was not a kind at all. A PDF added to the
        // library must draw no chips from it — the scope says what it was written to say, and a new
        // member of the vocabulary does not quietly join an old set.
        assertFalse(decodeTemplateScope("kind:image,video").admits(AssetKind.PDF))
    }

    @Test
    fun `an empty scope admits nothing`() {
        // The quietest authoring mistake there is: a template saved before its scope was filled in.
        // Matching everything would make that mistake govern an entire library.
        assertFalse(TemplateScope(emptyList()).admits(AssetKind.IMAGE))
        assertFalse(decodeTemplateScope("").admits(AssetKind.IMAGE))
        assertEquals("", encodeTemplateScope(TemplateScope(emptyList())))
    }

    @Test
    fun `several rules narrow rather than widen`() {
        // Only `kind` ships, so nothing composes yet — but the reading is what a later rule type
        // will be written against, and the other reading of the same encoding writes just as easily.
        // Two kind rules are the one composition expressible today, and they intersect.
        val scope = decodeTemplateScope("kind:image,video\nkind:image")

        assertTrue(scope.admits(AssetKind.IMAGE))
        assertFalse(scope.admits(AssetKind.VIDEO))
    }

    // --- what the editor reads a scope as -----------------------------------------------------------

    @Test
    fun `the kinds a scope governs are the intersection of its kind rules`() {
        // Not the first rule's set, and not their union. Several rules are a conjunction, so
        // `kind:image` beside `kind:image,video` governs the images and nothing else — and a page
        // that drew `video` as chosen would offer an edit that widened the scope on the way out.
        assertEquals(setOf(AssetKind.IMAGE), decodeTemplateScope("kind:image,video\nkind:image").kinds)
        assertEquals(setOf(AssetKind.IMAGE, AssetKind.VIDEO), decodeTemplateScope("kind:image,video").kinds)
    }

    @Test
    fun `a scope with no kind rule governs no kind`() {
        assertEquals(emptySet<AssetKind>(), decodeTemplateScope("").kinds)
        assertEquals(emptySet<AssetKind>(), TemplateScope(emptyList()).kinds)
        // `kind:gif` decodes as a rule that failed to load, and a rule that failed to load is not a
        // rule that chose these kinds. The page shows it as the warning it is, and shows no kind.
        assertEquals(emptySet<AssetKind>(), decodeTemplateScope("kind:gif").kinds)
    }

    @Test
    fun `a kind rule this build reads is reported even when an unreadable rule sits beside it`() {
        // Deliberate, and the one place `kinds` and `admits` disagree: this scope admits *nothing*,
        // because the mime rule matches nothing here. What the page needs to draw is the half it
        // understands — the images are the chips — and what it needs to say out loud is the half it
        // does not. Answering `emptySet` here would draw both chips off beside a warning, which
        // reads as "this governs nothing and something is wrong with it", and only one is true.
        val scope = decodeTemplateScope("kind:image\nmime:image/jpeg")

        assertEquals(setOf(AssetKind.IMAGE), scope.kinds)
        assertFalse(scope.admits(AssetKind.IMAGE))
    }

    // --- and what the editor writes back ------------------------------------------------------------

    @Test
    fun `re-choosing the kinds it already governs changes not one character`() {
        // The property the Update button rests on. A page that rewrote a scope it was not asked to
        // edit would make every template look edited the moment it was opened, and the bottom bar
        // would offer a version that says what the one before it said.
        for (written in listOf("kind:image", "kind:image,video", "kind:other", "")) {
            val scope = decodeTemplateScope(written)
            assertEquals(written, encodeTemplateScope(scope.withKinds(scope.kinds)))
        }
    }

    @Test
    fun `a rule this build cannot evaluate survives the editor`() {
        // The failure this prevents is the worst one available here: opening a template a later
        // build wrote, turning one chip on, and saving it back with half its scope gone — which
        // would *widen* it, and put chips on files nobody wrote it for.
        val scope = decodeTemplateScope("mime:image/jpeg\nkind:image")

        assertEquals(
            "mime:image/jpeg\nkind:video",
            encodeTemplateScope(scope.withKinds(setOf(AssetKind.VIDEO)))
        )
    }

    @Test
    fun `turning every kind off leaves no rule, and does not delete one it could not read`() {
        // Two halves of the same gesture. `kind:image` with both chips off is a scope of no rules,
        // which is what an empty scope already means; writing `kind:` instead would decode as an
        // unreadable rule and be reported as one, when what happened is that the user chose nothing.
        assertEquals("", encodeTemplateScope(decodeTemplateScope("kind:image").withKinds(emptySet())))

        // A rule this build never showed is not one the user turned off, so it stays exactly where
        // it was — the scope is empty and the editor did not touch it.
        assertEquals(
            "mime:image/jpeg",
            encodeTemplateScope(decodeTemplateScope("mime:image/jpeg").withKinds(emptySet()))
        )
    }

    @Test
    fun `a kind rule goes in front when the scope has never had one`() {
        // The position the encoder's own reading would give it, and the one that makes the next save
        // compare equal: the rule is written where a scope built by [TemplateScope.ofKinds] puts it.
        assertEquals(
            "kind:image\nmime:image/jpeg",
            encodeTemplateScope(decodeTemplateScope("mime:image/jpeg").withKinds(setOf(AssetKind.IMAGE)))
        )
    }

    @Test
    fun `a second kind rule is not left behind`() {
        // The page draws one chip row, so a scope carrying two kind rules has a half the editor
        // cannot show. Rewriting the first and leaving the second would save a scope that says
        // something nobody saw, and the intersection is what the chips were drawn from — so the
        // second one goes.
        val scope = decodeTemplateScope("kind:image,video\nkind:image")

        assertEquals(setOf(AssetKind.IMAGE), scope.kinds)
        assertEquals("kind:video", encodeTemplateScope(scope.withKinds(setOf(AssetKind.VIDEO))))
    }
}
