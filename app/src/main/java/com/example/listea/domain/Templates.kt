package com.example.listea.domain

import java.security.MessageDigest

/**
 * The three kinds of template, kept explicit rather than inferred from what a definition happens to
 * hold.
 *
 * They answer different questions and own different things, and the split is the one that keeps
 * media assumptions out of the review model. An Asset Viewer knows how to put *content* on screen
 * and nothing about whether anybody has judged it. A Task Review knows what judging means for this
 * List and knows nothing about how a frame is drawn. An Asset Control knows what to *record* about
 * one file and nothing about how it is presented. A future PDF viewer is an Asset Viewer; a future
 * candidate scoring form is a Task Review; neither is a change to the other.
 *
 * Asset Control is the middle band of the review screen — between the navigator, which is true of
 * any file whatever it is for, and the media transport, which is true of any video whatever it is
 * being judged as. It is the only band whose contents are a function of two things at once, the
 * judgment intended and the kind of file in front of it, and that is why it is the one band that
 * needed a template before either of the others did.
 */
enum class TemplateKind { ASSET_VIEWER, TASK_REVIEW, ASSET_CONTROL }

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

    /**
     * What may be recorded about one Asset, and which kinds of Asset it may be used for.
     *
     * [compatibleKinds] is the same contract an Asset Viewer declares, asking a different question:
     * not "what can I put on screen" but "which files is this configuration valid for". The two are
     * deliberately independent — a control may govern a kind no viewer can display, and a viewer may
     * display a kind no control governs — so neither is derived from the other, and coupling them
     * because they happen to agree today is the tidy-looking error.
     *
     * [favourite] is here rather than fixed in the review surface because `★` is a thing this
     * template records about a file, exactly as a configured tag is. A future control for a kind
     * where starring means nothing omits it.
     *
     * **What is not here is the tags.** Which tags, in what order, under what names and wire values,
     * is the *configuration* and belongs to the Todo — the same split the architecture draws
     * between a template version and the List that names it. A definition is immutable, hashed and
     * pointed at by review events forever; a tag row is edited by a user between two runs, and
     * folding it in here would either make `contentHash` a lie or make every edit a version bump.
     * The definition says what a control of this kind *can* hold; the configuration says what this
     * Todo wants it to hold.
     */
    data class AssetControl(
        val compatibleKinds: Set<AssetKind>,
        val favourite: Boolean
    ) : TemplateDefinition {
        override val kind = TemplateKind.ASSET_CONTROL
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

            // Both fields, or two controls differing only in a compatible kind — or only in whether
            // they carry `★` — would hash alike and be freely interchangeable under a review event
            // that named one of them forever.
            is TemplateDefinition.AssetControl -> {
                append(it.compatibleKinds.map { kind -> kind.name }.sorted().joinToString(","))
                append('|').append(if (it.favourite) "favourite" else "plain")
            }
        }
    }
}

/**
 * Every template this build defines, defined in code.
 *
 * The whole registry: three versions, no editor, no storage, no component table. That is the shape
 * §5.5 asks for and the shape the architecture expects to stay until a second real workflow
 * justifies more — a template that exists only as a row somebody could have edited is a template
 * nothing can be held to.
 *
 * v0.5.0 made one part of a template editable without changing that, and the seam is worth naming
 * because it is the thing a reader will come here looking for. What Settings edits is a
 * **configuration** — which tags, with which names and wire values, a Todo wants — and a
 * configuration is stored on the Todo that chose it, never here. A **definition** stays what this
 * object is: code, versioned, hashed, and asserted by a test. The two are not two halves of one
 * thing that drifted apart; they answer different questions, and only one of them is a promise to
 * a review event that already happened.
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

    /**
     * The only Asset Control shipped: the tag row exactly as v0.4.0 drew it.
     *
     * Images and video share it, which is not a compromise but the honest description of the app
     * today — one row, one meaning, whatever is on screen. The release that gives images and video
     * different controls adds a second version of this definition and changes nothing else; the
     * lookup is already keyed by kind, and the key is already stored.
     *
     * It carries `★`, because every media Asset has always been starrable and a control that
     * silently dropped it would lose the one action no user configured.
     */
    val MediaAssetControl = TemplateVersion(
        ref = TemplateVersionRef(TemplateId("builtin.media.asset-control"), 1),
        displayName = "Media Asset Control",
        definition = TemplateDefinition.AssetControl(
            compatibleKinds = setOf(AssetKind.IMAGE, AssetKind.VIDEO),
            favourite = true
        )
    )

    /** Every defined version, viewer first because the review template composes it. */
    val all: List<TemplateVersion> =
        listOf(MediaViewer, MediaRapidReview, MediaAssetControl)

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
