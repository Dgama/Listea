package com.example.listea.domain

/**
 * One rule in a [TemplateScope]: what kind of test, and what it is testing for.
 *
 * One shape for every rule, because the rules the product wants next are all the same shape. Today a
 * file's [AssetKind] decides it. The list the user gave — a specific mime rather than a broad kind, a
 * filename pattern, a size, dimensions — is one more member each, and none of them changes how a
 * scope is stored or read.
 *
 * [admits] is the whole of the contract: a rule answers for one file, and nothing else has to ask
 * what a rule can do.
 */
sealed interface ScopeRule {

    /** This rule's name in the encoding. See [encodeTemplateScope]. */
    val type: String

    /** Whether a file of this [kind] is inside this rule. */
    fun admits(kind: AssetKind): Boolean

    /**
     * The file's kind, which is the only rule the product has.
     *
     * It is resolved from the file's name without opening it, which is what lets it be a filter at
     * all. It is also the same test [TemplateKind.ASSET_CONTROL]'s `compatibleKinds` made, arriving
     * as one rule among a list rather than as a field of its own — which is the difference between a
     * template that *is* the rule and a template that *carries* one.
     */
    data class Kind(val kinds: Set<AssetKind>) : ScopeRule {

        override val type = TYPE

        override fun admits(kind: AssetKind): Boolean = kind in kinds

        companion object {
            const val TYPE = "kind"
        }
    }

    /**
     * A rule this build cannot evaluate, kept rather than dropped.
     *
     * **It admits nothing**, and keeping it is the point. A rule skipped on the way in would make the
     * template match *more* than it was written to — a scope is the conjunction of its rules, so
     * removing one widens it — and a template that quietly governed files it was never written for
     * is a row of chips nobody chose. This is the direction v0.5.3 §3 settled on for an unrecognised
     * action id, for the same reason: an unreadable thing fails closed.
     *
     * It is a member of the model rather than a decode failure so that a template's page can say what
     * it does not understand, and so that a scope written by a later build survives a trip through
     * this one instead of being quietly rewritten into the part this build happened to know.
     */
    data class Unknown(override val type: String, val argument: String) : ScopeRule {
        override fun admits(kind: AssetKind): Boolean = false
    }
}

/**
 * What a template version applies to: the rules that decide which files it governs.
 *
 * **A scope is the conjunction of its rules.** Several rules narrow rather than widen — `kind:image`
 * with a filename rule beside it means the images whose names match — which is what makes a list of
 * rules worth having instead of a second way to write one. Only [ScopeRule.Kind] ships, so nothing
 * composes yet; the reading is fixed here because the other reading of the same encoding is just as
 * easy to write by accident and much harder to notice.
 *
 * **An empty scope admits nothing.** A template that says nothing about what it applies to cannot be
 * applied. The other choice — an empty scope meaning *every file* — would turn the quietest authoring
 * mistake there is, a template saved before its scope was filled in, into a template that governs an
 * entire library.
 */
data class TemplateScope(val rules: List<ScopeRule>) {

    /** Whether a file of this [kind] is governed by a template carrying this scope. */
    fun admits(kind: AssetKind): Boolean = rules.isNotEmpty() && rules.all { it.admits(kind) }

    /**
     * The kinds this scope governs, as the editor shows them.
     *
     * The **intersection** of every `kind` rule rather than the first one's set, because that is what
     * the scope actually admits: several rules are a conjunction, so `kind:image` beside
     * `kind:image,video` governs the images. An intersection is also the reading that cannot widen a
     * scope on the way out — [withKinds] writes back exactly these, so a scope this build did not
     * author is reported and re-saved as the set it governs rather than as the set one of its rules
     * happens to name.
     */
    val kinds: Set<AssetKind>
        get() = rules.filterIsInstance<ScopeRule.Kind>()
            .map { it.kinds }
            .reduceOrNull { left, right -> left intersect right }
            ?: emptySet()

    /**
     * This scope with the kinds it governs replaced: every `kind` rule becomes one, or, for an empty
     * [kinds], none.
     *
     * The editor's operation, and the only way this build rewrites a scope it read. **A rule it does
     * not understand is carried through untouched**, so opening a template an unknown rule sits on
     * and saving it cannot quietly drop the half of the scope a later build wrote — which is the
     * whole reason [ScopeRule.Unknown] is a member of the model rather than a decode failure.
     *
     * An empty [kinds] leaves no rule at all rather than an empty one. `kind:` decodes as an unknown
     * rule and would be reported on the page as something unreadable, where what actually happened is
     * that the user turned both kinds off; an empty scope already says that, and says it in the
     * direction this model fails in.
     *
     * The new rule takes the place of the first `kind` rule, so a scope that had one keeps its rule
     * order — and with it the exact string [encodeTemplateScope] writes, which is what lets an
     * untouched save be recognised as no change at all. With no `kind` rule to replace it goes in
     * front, the position the encoder's own reading of a scope would put it in.
     */
    fun withKinds(kinds: Set<AssetKind>): TemplateScope {
        val replacement = kinds.takeIf { it.isNotEmpty() }?.let { ScopeRule.Kind(it) }
        var placed = false

        val rebuilt = rules.mapNotNull { rule ->
            if (rule !is ScopeRule.Kind) return@mapNotNull rule
            // Only the first one is rewritten. A second is a rule the editor never showed, and
            // leaving it behind would make the saved scope say something the page did not.
            if (placed) return@mapNotNull null
            placed = true
            replacement
        }

        return TemplateScope(
            if (placed || replacement == null) rebuilt else listOf(replacement) + rebuilt
        )
    }

    companion object {
        /** The scope of a template that governs [kinds] and nothing else — the only rule there is. */
        fun ofKinds(kinds: Set<AssetKind>): TemplateScope =
            TemplateScope(listOf(ScopeRule.Kind(kinds)))
    }
}

/**
 * A scope as one string: one rule per line, `type:argument`.
 *
 * ```text
 * kind:image,video
 * ```
 *
 * The kinds inside a `kind` rule are written in declaration order rather than the set's, so the same
 * scope always writes the same string — storage that varies with a set's iteration order looks
 * changed when nothing has.
 */
fun encodeTemplateScope(scope: TemplateScope): String =
    scope.rules.joinToString("\n") { rule ->
        when (rule) {
            is ScopeRule.Kind ->
                "${ScopeRule.Kind.TYPE}:" +
                    rule.kinds.sortedBy { it.ordinal }.joinToString(",") { it.key }

            // Verbatim, so a scope this build only half understands is not rewritten into the half
            // it does the first time somebody opens and saves the template.
            is ScopeRule.Unknown -> "${rule.type}:${rule.argument}"
        }
    }

/**
 * The scope a payload holds. Total, and never a guess: anything this build cannot evaluate becomes
 * [ScopeRule.Unknown], which admits nothing.
 *
 * That is where this decoder deliberately parts company with the tolerant ones around it.
 * [decodeTemplateButtons] drops a line it cannot read, because a dropped button costs one chip.
 * Dropping a *rule* would widen the scope the rule is part of, so an unreadable one is kept as
 * something that matches nothing — the failure shows up on the template's page rather than silently
 * in what the template governs.
 *
 * `''` decodes to an empty scope, which admits nothing. See [TemplateScope].
 */
fun decodeTemplateScope(text: String): TemplateScope = TemplateScope(
    text.split('\n').mapNotNull { raw ->
        // Tabs and carriage returns are stripped in place rather than by trimming, so a rule whose
        // argument legitimately contains a space keeps it.
        val line = raw.replace('\t', ' ').replace('\r', ' ')
        if (line.isBlank()) return@mapNotNull null
        val type = line.substringBefore(':').trim()
        if (type.isEmpty()) return@mapNotNull null
        val argument = line.substringAfter(':', missingDelimiterValue = "").trim()

        when (type) {
            ScopeRule.Kind.TYPE -> {
                val kinds = argument.split(',').mapNotNull { AssetKind.fromKey(it.trim()) }.toSet()

                // `kind:` and `kind:gif` both mean "this build cannot say which files this covers",
                // which is not the same as covering none of them by choice. The first is a rule that
                // failed to load and the second is one that loaded correctly and matched; both admit
                // nothing, but only the second should ever be written by this build.
                if (kinds.isEmpty()) ScopeRule.Unknown(type, argument)
                else ScopeRule.Kind(kinds)
            }

            else -> ScopeRule.Unknown(type, argument)
        }
    }
)
