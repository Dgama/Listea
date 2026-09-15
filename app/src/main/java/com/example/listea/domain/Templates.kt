package com.example.listea.domain

import java.security.MessageDigest

/**
 * The two kinds of template, kept explicit rather than inferred from what a definition happens to
 * hold.
 *
 * They answer different questions and own different things, and the split is the one that keeps
 * media assumptions out of the review model. An Asset Viewer knows how to put *content* on screen
 * and nothing about whether anybody has judged it. A Task Review knows what judging means for this
 * List and knows nothing about how a frame is drawn. A future PDF viewer is an Asset Viewer; a
 * future candidate scoring form is a Task Review; neither is a change to the other.
 */
enum class TemplateKind { ASSET_VIEWER, TASK_REVIEW }

/**
 * What one version of one template actually says.
 *
 * Deliberately tiny, and deliberately short of the architecture's list of things a Task Review
 * "conceptually may describe" — required data, layout, response fields, completion rules. Everything
 * on that list that v0.2's media workflow does not exercise is left out, because a definition is
 * something a review event points at forever and a field invented ahead of a workflow is a field
 * that has to be honoured for the rest of time. Adding one is a version bump, which is the
 * mechanism working as designed.
 *
 * The layout the media workflow does use is not here either, and that is a decision rather than an
 * omission. Gesture bindings are part of how the rapid-media surface is *implemented*; §Gestures
 * forbids domain code from storing or branching on a physical gesture, and a definition is stored.
 * The surface that draws a review owns its own bindings, and this says what the review *means*.
 */
sealed interface TemplateDefinition {

    val kind: TemplateKind

    /**
     * How one compatible Asset is presented.
     *
     * [compatibleKinds] is the whole of the compatibility contract in v0.2: a viewer declares what
     * it can put on screen, and nothing else has to ask what it can do. A PDF viewer added later
     * declares its own set and starts being offered without Task Review learning a thing about
     * file types.
     */
    data class AssetViewer(val compatibleKinds: Set<AssetKind>) : TemplateDefinition {
        override val kind = TemplateKind.ASSET_VIEWER
    }

    /**
     * The judgment over a Task, and how the content being judged is composed into it.
     *
     * [actions] is the vocabulary this template offers. [viewerTemplate] is a *reference* rather
     * than a copy, so a Task Review version stays meaningful when the viewer it composed moves on —
     * it still names the viewer version it was written against, and a reader that needs to know
     * what that looked like can.
     */
    data class TaskReview(
        val actions: Set<TaskAction>,
        val viewerTemplate: TemplateVersionRef
    ) : TemplateDefinition {
        override val kind = TemplateKind.TASK_REVIEW
    }
}

/**
 * One immutable template definition, addressed by [ref].
 *
 * Immutability here is enforced rather than promised. [contentHash] is a digest of everything the
 * definition says, and the test suite asserts the exact digest for each shipped version — so
 * editing one of these in place fails the build, and the only way to change what a template means
 * is to add a version. That matters because review events point at these references forever: a
 * definition edited under a released version would retroactively change what somebody's completed
 * review is on record as having said.
 *
 * [displayName] is inside the hash, which is a judgment call worth stating. §Templates says a
 * display name may change without changing identity, and that is true of the *lineage* — the
 * `builtin.media.rapid-review` id means the same thing across every version. It is not true of a
 * version: two devices holding different names for `@1` is a difference in a definition that
 * nothing else would catch, and the cost of tying it to a version bump is renaming a built-in,
 * which nobody does.
 */
data class TemplateVersion(
    val ref: TemplateVersionRef,
    val displayName: String,
    val definition: TemplateDefinition
) {

    /** A digest of [displayName] and everything [definition] says. See the class note. */
    val contentHash: String get() = sha256(canonicalText())

    private fun canonicalText(): String = buildString {
        append(ref)
        append('|').append(definition.kind.name)
        append('|').append(displayName)
        append('|')
        when (val it = definition) {
            is TemplateDefinition.AssetViewer ->
                append(it.compatibleKinds.map { kind -> kind.name }.sorted().joinToString(","))

            is TemplateDefinition.TaskReview -> {
                append(it.actions.map { action -> action.key }.sorted().joinToString(","))
                append('|').append(it.viewerTemplate)
            }
        }
    }
}

/**
 * Every template this build defines, defined in code.
 *
 * The whole registry: two versions, no editor, no storage, no component table. That is the shape
 * §5.5 asks for and the shape the architecture expects to stay until a second real workflow
 * justifies more — a template that exists only as a row somebody could have edited is a template
 * nothing can be held to.
 *
 * Ids are the fixed strings the schema already defaults to, so a `lists` row written before this
 * existed still resolves to the template it was always going to get.
 */
object Templates {

    /** The only Asset Viewer shipped: images and video, which is everything the app can render. */
    val MediaViewer = TemplateVersion(
        ref = TemplateVersionRef(TemplateId("builtin.media.viewer"), 1),
        displayName = "Media Viewer",
        definition = TemplateDefinition.AssetViewer(
            compatibleKinds = setOf(AssetKind.IMAGE, AssetKind.VIDEO)
        )
    )

    /**
     * The only Task Review shipped: the media workflow exactly as it behaves today.
     *
     * It offers all three [TaskAction]s, including the two that submit nothing — a template that
     * could only complete would make "I looked at this and have nothing to say" unwritable.
     */
    val MediaRapidReview = TemplateVersion(
        ref = TemplateVersionRef(TemplateId("builtin.media.rapid-review"), 1),
        displayName = "Rapid Review",
        definition = TemplateDefinition.TaskReview(
            actions = setOf(TaskAction.PreviousTask, TaskAction.NextTask, TaskAction.CompleteTask),
            viewerTemplate = MediaViewer.ref
        )
    )

    /** Every defined version, viewer first because the review template composes it. */
    val all: List<TemplateVersion> = listOf(MediaViewer, MediaRapidReview)

    /**
     * The definition a persisted reference names, or null if this build has never heard of it.
     *
     * Null rather than an exception, because the reference comes off stored data: a row written by
     * a later release, or by one whose template was removed, is a thing that can exist and is not a
     * reason to fail while reading a List. The caller decides what an unknown template means for
     * what it was doing.
     */
    fun byRef(ref: TemplateVersionRef): TemplateVersion? = all.firstOrNull { it.ref == ref }
}

/**
 * Lower-case hex SHA-256.
 *
 * Spelled out rather than reached for through a library, because this value has to be reproducible
 * and a dependency's formatting is one more thing that can move. MessageDigest is in `java.security`
 * on both the JVM and Android, so this costs the domain package no platform dependency.
 */
private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
