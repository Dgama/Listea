package com.example.listea

import com.example.listea.data.ScannedFile
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import com.example.listea.domain.DiagnosticScope
import com.example.listea.domain.PackageDiagnostic
import com.example.listea.domain.PlannedPackageResource
import com.example.listea.domain.PlannedPromptResource
import com.example.listea.domain.TemplateVersionRef

/**
 * The importer layer: a pure grouping rule that turns a folder snapshot into planned Tasks.
 *
 * This is the one place Listea decides which raw inputs form a Task, and it is deliberately free of
 * `Context`, SAF and the database. The reason is the same one [FileArrange] and [FolderScan] have
 * hundreds of tests between them: a grouping rule that can be given a [FolderSnapshot] and asked
 * what it would produce is a rule that can be pinned down, and "which files are one Task" is the
 * one decision the whole release exists to make explicit. A `plan` never writes; [ListsDao.runImport]
 * is the only thing that turns a plan into rows.
 *
 * Two rules answer two different questions. Both apply the same supported-content policy — a file
 * a viewer can open — because a Task nothing can open is a Task nobody can judge; since v0.8.2f the
 * file-as-Task rule no longer keeps v0.1's "every file" behaviour, which turned a Package's
 * manifest and Prompt bodies into unreviewable Tasks. Whether a judgment may be recorded against a
 * Task is still a later question, answered by review eligibility, not by the importer.
 */

/** The grouping rules v0.2.5 ships, keyed by the value persisted on `sources.importerKind`. */
enum class ImporterKind(val key: String) {
    FILE_AS_TASK("file-as-task"),
    FOLDER_AS_TASK("folder-as-task"),

    /**
     * One Todo over the Packages beneath a folder: each `listea.package/1` manifest is read and
     * each planned Prompt becomes a Task. This is a third printing convention, not a detection
     * rule under the other two — see `docs/package-protocol.md`.
     */
    PACKAGE("package");

    companion object {
        fun fromKey(key: String): ImporterKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What a Create Tasks run is configured with: the grouping rule, what the Source is called, and
 * whether the folder tree survives into how the Todo is presented.
 *
 * The three travel together because they are one answer to one question — "make Tasks out of this
 * folder, like this" — and are answered in a single dialog. [displayName] is what the user typed, and
 * it names the Source rather than any Task: the Tasks inside keep whatever the importer calls them.
 *
 * [keepTree] is only meaningful for [ImporterKind.FILE_AS_TASK]; [normalizeKeepTree] is what makes
 * that true everywhere it is read, so the stored column and this option cannot disagree.
 *
 * [contentPolicy] is the odd one of the four, and it is here for a reason worth stating: it is not
 * a decision about *this* folder's shape at all, it is the Todo's standing answer to what a change
 * to its Tasks' content means. It travels with the others because it is asked in the same dialog,
 * and it is written back to the Todo afterwards so the next dialog opens on it — see
 * `ListsDao.configureSource`. Its absence from a refresh is not an absence of the question: a
 * refresh's dialog opens on the stored answer and offers it unchanged.
 */
data class ImportOptions(
    val importer: ImporterKind = ImporterKind.FILE_AS_TASK,
    val displayName: String = "",
    val keepTree: Boolean = true,
    val contentPolicy: ContentPolicy = ContentPolicy.Default,

    /**
     * How a Package's Prompts become Tasks in this run.
     *
     * Asked in the dialog only when the Package declares Sections; stored on the Todo so a refresh
     * repeats the taker's rule rather than reverting it. A v1 Package has no Sections, and the
     * choice behaves as [CompositionChoice.PROMPT].
     */
    val composition: CompositionChoice = CompositionChoice.Default,

    /**
     * The templates the dialog attached, in the order the user attached them.
     *
     * An instruction *for this run* rather than a record of one, exactly as [contentPolicy] is —
     * see `applyImport`, which passes it into the run rather than reading it back from the row it
     * is about to write. The two travel together for the same reason.
     *
     * **Empty means nothing was attached, and that is a refusal rather than a default.** It is what
     * the dialog will not let a user confirm on, and what a caller with no dialog to show gets: a
     * Task whose files no template governs draws no chips at all, which is legible, rather than a
     * built-in standing in for a choice nobody made, which is not. This field used to be a per-kind
     * tag map with exactly that fallback, and dropping it is the change v0.5.4 makes.
     *
     * A *reference* per entry rather than a copy of the buttons: what a Task was judged under is
     * [TemplateVersionRef], and the buttons it offers are resolved from the template tables at the
     * moment somebody looks — see [TemplateResolver]. That is what lets a template be edited
     * without a re-import, and what makes `…asset-control@1` go on meaning version 1.
     */
    val templates: List<TemplateVersionRef> = emptyList(),

    /**
     * Whether this run adopts the offered version of every template the Todo already holds.
     *
     * **True for Refresh Import, false everywhere else.** Create always submits references the
     * picker drew from the current versions, so there is nothing to move; a follow-the-upstream
     * sync has no dialog and must not move a template on its own, exactly as it must not re-decide
     * the export choice. A refresh is the one run where the user is told *this will attach version
     * N* and then confirms it — see [upgradeAttachments].
     *
     * The upgrade happens in `applyImport`, over the list the dialog submitted, so unticking a row
     * before confirming is a refusal that is honoured rather than a notice that outvoted it.
     */
    val upgradeTemplates: Boolean = false,

    /**
     * The add-on this run attaches, or null for none.
     *
     * **At most one, and it is a choice rather than a default.** Unlike [templates], a new Todo
     * does not start with one: an add-on is a questionnaire somebody deliberately decided to ask,
     * and a Task with no page after its last asset is the ordinary Task. The dialog's *Add an
     * add-on* section starts at *None*.
     *
     * A [AddOnVersionRef] rather than a copy of the questions, for the same reason [templates]
     * holds references: the questions are whatever the add-on's current version asks, resolved from
     * the add-on tables when somebody looks.
     */
    val addOn: AddOnVersionRef? = null,

    /**
     * Whether this run adopts the offered current version of the add-on the Todo holds.
     *
     * **True for Refresh Import, false everywhere else**, exactly as [upgradeTemplates] is: a
     * refresh carries the Todo forward to the version the picker offered, while Create submits what
     * it was given and a sync re-stamps what the Todo already names.
     */
    val upgradeAddOn: Boolean = false,

    /**
     * The Task Review this run judges its Tasks with, or null for the built-in rapid review.
     *
     * A reference rather than a copy of the definition, exactly as [templates] and [addOn] are: the
     * policy behind it is whatever version the reference names, read at the moment somebody looks.
     * Null is *no answer from the dialog*, and because every Task has always been judged by
     * something, it reads as the built-in `builtin.media.rapid-review@1` rather than as none.
     *
     * The dialog's choice for this run and, once the run succeeds, the Todo's standing answer — the
     * Tasks are stamped with it and `sources.taskReview` records it for the next run.
     */
    val taskReview: TemplateVersionRef? = null,

    /**
     * Whether this run adopts the offered current version of the Task Review the Todo holds.
     *
     * **True for Refresh Import, false everywhere else**, for the same reason [upgradeTemplates] is:
     * a refresh carries the held reference forward to what the picker offered, while Create submits
     * what it was given and a sync re-stamps what the Todo already names.
     */
    val upgradeTaskReview: Boolean = false,

    /**
     * Whether this run leaves the Todo following its upstream: scanning it on entry and importing
     * what appeared, without asking.
     *
     * Asked in the Create Tasks dialog as of v0.6.2, because it is a standing answer about the Todo
     * that outlives this run — and a new Todo should not have to be created and then found on
     * another screen to be given the answer the dialog was already showing. Create seeds it from
     * Settings; Refresh seeds it from the Todo; a sync passes what the Todo already holds.
     */
    val followUpstream: Boolean = false,

    /**
     * Whether deleting the Todo keeps its Tasks and their decisions instead of letting them go.
     *
     * The same standing-answer shape as [followUpstream]. There is no Settings default for it: a
     * new Todo starts with the answer every Todo had before the switch existed, and the dialog is
     * where a user who wants otherwise says so once.
     */
    val keepStateOnDelete: Boolean = false,

    /**
     * The webhook address this run writes to the Todo, already resolved from the dialog's choice.
     *
     * *Use default* resolves to the live default at the moment Confirm is pressed, so the row gets
     * a copy rather than a promise to follow Settings later; *Custom* resolves to what was typed.
     * A refresh may also keep the address the Todo already has. The value is final here, which is
     * what lets [ListsViewModel.applyImport] validate it before registering anything.
     */
    val webhookUrl: String = "",

    /**
     * Whether [webhookUrl] is a copy of the default rather than typed.
     *
     * Recorded because the two mean different things to a later reader: *this Todo posts to the
     * address it was born with* versus *this Todo was given that address on purpose*. It does not
     * make the row follow later edits to the default — nothing does; the copy is a snapshot.
     */
    val webhookFromDefault: Boolean = false,

    /**
     * Whether the address is a decision this run is making, and may therefore refuse on.
     *
     * True for Create and Refresh, whose dialog asked and whose answer is about to be written.
     * **False for an upstream sync**: it asked nobody, changed no address, and must not refuse to
     * bring in material because the Todo's standing configuration would fail an export later. A
     * sync that did so would turn an export problem into an import outage, which is a different
     * subject from the run and a worse answer than letting the delivery path report it.
     */
    val validateWebhookAddress: Boolean = true,

    /**
     * How this Todo's decisions leave the app, chosen here and written back to the row.
     *
     * Defaults to [ExportChoice.Nothing] — *nothing was chosen, so nothing is sent* — and that is
     * deliberately not the same answer as an existing Todo's, which migrates to
     * [ExportKind.WEBHOOK] (v0.5.6 decision 12): preserving what a Todo already did is one
     * question, and what a brand-new Todo should do is another.
     *
     * A single value and never a set. The plural case is real — a List holding Tasks from several
     * Todos exports more than once — but it is a **sum over the Todos a round touched**, computed
     * when the round ends, and not something stored on any row.
     */
    val exportChoice: ExportChoice = ExportChoice.Nothing
)

/**
 * [keepTree] as an importer actually honours it.
 *
 * A folder-as-Task Source produces one Task per child folder and nothing else, so it has no tree to
 * keep: its catalog is flat the moment it is written, whatever the option says. The control that
 * offers it is hidden for that importer, but hiding a control is a presentation fact and cannot be
 * what makes the stored column honest — this is. It is silent by design: the user never had the
 * choice, so there is nothing to report back to them about losing it.
 */
fun normalizeKeepTree(importer: ImporterKind, keepTree: Boolean): Boolean =
    when (importer) {
        ImporterKind.FILE_AS_TASK -> keepTree
        // Both group their Tasks by something other than the browsed tree: a folder-as-Task run
        // produces one Task per child folder, and a Package run produces one per Prompt. Neither
        // has a filesystem tree to keep, and a stored `true` would promise a level nothing creates.
        ImporterKind.FOLDER_AS_TASK -> false
        ImporterKind.PACKAGE -> false
    }

/** One file, as an importer wants to attach it to a Task. Root-relative, ordered. */
data class PlannedAsset(
    val rootRelativePath: String,
    val name: String,
    val uri: String,
    val sizeBytes: Long?,
    val lastModified: Long?,
    val sortOrder: Int
)

/**
 * Where one part of a planned Task came from.
 *
 * [PACKAGE] is a declared Prompt; [FILE] and [FOLDER] are the two local grouping rules, which each
 * synthesize exactly one part because a folder tells Listea nothing about the judgments inside it.
 * [LEGACY] is a Task migrated from schema 32 with no Package link. [SYNTHESIZED] is reserved for a
 * host that assembles a part from material without a Prompt — no importer writes it yet.
 */
enum class PartOrigin(val key: String) {
    PACKAGE("package"),
    FILE("file"),
    FOLDER("folder"),
    LEGACY("legacy"),
    SYNTHESIZED("synthesized");

    companion object {
        fun fromKey(key: String?): PartOrigin? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One Task an importer would produce: what it grouped, what it called it, and the parts it holds.
 *
 * [compositionKey] is the taker's grouping rule that produced this Task — `prompt:<key>` or
 * `section:<key>` — or null for a local Task, which has no composition concept. It is provenance,
 * not a Task id: the same rule over two Todos still produces two independent Tasks.
 */
data class PlannedTask(
    val groupPath: String,
    val displayName: String,
    val parts: List<PlannedPart>,
    val compositionKey: String? = null
) {
    init {
        require(parts.isNotEmpty()) { "A planned Task must hold at least one part." }
    }
}

/**
 * Every file the Task would present, across its parts, in part then attachment order.
 *
 * The whole-Task view is what the write path and reconciliation already speak: a Task's Asset set
 * is the union of its parts'. Reading it through this function keeps the part boundary visible at
 * the call site rather than hiding it in a field.
 */
fun PlannedTask.allAssets(): List<PlannedAsset> = parts.flatMap { it.assets }

/**
 * The decision unit one Task would be written with.
 *
 * A Package Prompt becomes one part with its payload, Assets and Resources. The local planners
 * synthesize one part with no payload (`partKey = local:0`); the Prompt stays null and the files
 * are Assets of that single part. A part is never empty: it needs a payload or at least one Asset,
 * because a part with nothing to present is a decision nobody can make.
 */
data class PlannedPart(
    val partKey: String,
    val position: Int,
    val origin: PartOrigin,

    /**
     * The Prompt this part instantiates, when it came from a Package; null for a degenerate part.
     *
     * The payload travels with the part rather than in a second list because the two are one
     * output: a Package Prompt that produced no part is a Prompt nothing takes on, and a part
     * written without its payload would be a decision that can never be reviewed.
     */
    val prompt: PlannedTaskPrompt? = null,
    val assets: List<PlannedAsset> = emptyList(),
    val resources: List<PlannedPromptResource> = emptyList(),
    val required: Boolean = true
) {
    init {
        require(prompt != null || assets.isNotEmpty()) {
            "A part needs a Prompt payload or at least one Asset."
        }
    }
}

/**
 * One composition the plan would record: the taker's grouping rule over a Package.
 *
 * [sourceId] is filled by the host, not by the pure planner; the Package protocol never names a
 * Todo. The row is provenance for reconciliation, not an owner of the Task.
 */
data class PlannedComposition(
    val sourceId: String,
    val packageKey: String,
    val compositionKey: String,
    val present: Boolean = true
)

/**
 * How a Package's Prompts are grouped into Tasks.
 *
 * The default is one Task per Prompt, which is what every v1 Package meant and what a v2 taker gets
 * when they ignore Sections. [SECTION] groups tagged Prompts by their Section, in manifest order;
 * an untagged Prompt stays its own Task under `prompt:<key>`.
 */
enum class CompositionChoice(val key: String) {
    PROMPT("prompt"),
    SECTION("section");

    companion object {
        val Default = PROMPT

        fun fromKey(key: String?): CompositionChoice =
            entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * The stored half of one Package Prompt: what a Viewer needs to draw it and what a submission
 * needs to check itself against.
 *
 * [payload] is the canonical description body. Core never parses it; [kind] and [schema] are the
 * envelope that says which Viewer may.
 */
data class PlannedTaskPrompt(
    val packageKey: String,
    val promptKey: String,
    val kind: String,
    val schema: Int,
    val payload: String,
    val revision: Int,
    val resources: List<PlannedPromptResource>
)

/** One accepted Package in a run: its checkpoint and the Resources its Prompts may use. */
data class PlannedPackage(
    val rootRelativePath: String,
    val packageKey: String,
    val protocol: String,
    val title: String,
    val revision: Int,
    val fingerprint: String,
    val resources: List<PlannedPackageResource>
)

/**
 * A thing the importer saw and did not make a Task out of.
 *
 * Not a failure — an import run is [com.example.listea.ImportPlan] even when it carries notices.
 * A notice is the importer doing its job, which is what lets a stray `rubric.txt` stop nothing.
 * [relativePath] is root-relative, the same space [PlannedAsset.rootRelativePath] speaks in.
 */
sealed interface ImportNotice {
    val relativePath: String

    /** A file outside the supported-content policy. Reported, never an Asset. */
    data class UnsupportedFile(override val relativePath: String) : ImportNotice

    /** An immediate child folder holding no supported file. No Task, exactly one notice. */
    data class EmptyFolder(override val relativePath: String) : ImportNotice
}

/**
 * What an importer made of a snapshot: the Tasks it would write, the things it skipped, and — for
 * a Package run — the checkpoints and per-record diagnostics.
 *
 * [packages] is empty for the two folder rules. It is not derivable from [tasks]: a Package whose
 * Prompts all failed still writes a checkpoint, and one whose fingerprint is unchanged writes no
 * Task at all while still being seen.
 */
data class ImportPlan(
    val tasks: List<PlannedTask>,
    val notices: List<ImportNotice>,
    val packages: List<PlannedPackage> = emptyList(),
    val diagnostics: List<PackageDiagnostic> = emptyList(),
    /** How many Packages the planning pass refused outright. A run result adds its own refusals. */
    val packagesRejected: Int = 0
)

/**
 * Plans Tasks for [snapshot] using [kind].
 *
 * [importRoot] is the SAF-grant-relative path of the folder the snapshot was scanned from (`''` is
 * the grant root). The snapshot's paths are relative to that folder, so every root-relative value
 * this returns is rebased through it; a file at `a.jpg` under an import root of `holiday` is
 * `holiday/a.jpg`, which is the identity every other part of Listea names it by.
 */
fun plan(
    kind: ImporterKind,
    importRoot: String,
    snapshot: FolderSnapshot,
    packagePlanner: ((String, FolderSnapshot) -> ImportPlan)? = null
): ImportPlan =
    when (kind) {
        ImporterKind.FILE_AS_TASK -> planFileAsTask(importRoot, snapshot)
        ImporterKind.FOLDER_AS_TASK -> planFolderAsTask(importRoot, snapshot)
        // The Package planner reads bytes, so it arrives from the host as a function. A caller
        // without one (a data-layer helper that only knows the two folder rules) gets a plan that
        // says so rather than a silent empty run that would look like an empty folder.
        ImporterKind.PACKAGE -> packagePlanner?.invoke(importRoot, snapshot)
            ?: ImportPlan(
                tasks = emptyList(),
                notices = emptyList(),
                diagnostics = listOf(
                    PackageDiagnostic(
                        scope = DiagnosticScope.PACKAGE,
                        key = null,
                        code = "package-unsupported",
                        message = "This path cannot read Packages."
                    )
                )
            )
    }

/**
 * One Task per discovered file a viewer can open, one Asset each.
 *
 * Since v0.8.2f this importer admits the same set [planFolderAsTask] does — picture, video and PDF
 * — because a Task nothing can open is a Task nobody can judge. Every other file, and a Package's
 * manifest and Prompt bodies are the reason the rule exists, is skipped and reported rather than
 * turned into an empty chip row. `directories` is unused — there is nothing this importer can say
 * about a folder.
 */
fun planFileAsTask(importRoot: String, snapshot: FolderSnapshot): ImportPlan {
    val notices = mutableListOf<ImportNotice>()
    val tasks = mutableListOf<PlannedTask>()
    for (file in snapshot.files) {
        if (assetViewerAvailable(file.name)) {
            tasks += fileTask(importRoot, file)
        } else {
            notices += ImportNotice.UnsupportedFile(childRelativePath(importRoot, file.relativePath))
        }
    }
    return ImportPlan(tasks = tasks, notices = notices)
}

/**
 * The file templates a run stores, given its importer.
 *
 * A Package run creates Tasks from Prompts rather than from files: its Tasks are answered in a
 * Viewer and hold no file Assets of their own, so a file template has nothing to govern and is
 * dropped rather than stamped onto every Prompt. The picker hides those rows for a Package; this
 * function is the rule both the dialog and the write path share, so a caller that bypassed the
 * picker cannot store a template the model says does not apply.
 */
fun <T> fileTemplatesFor(importer: ImporterKind, templates: List<T>): List<T> =
    if (importer == ImporterKind.PACKAGE) emptyList() else templates

/**
 * Each immediate child folder of the import root is one Task; its supported descendants are its
 * Assets, ordered by relative path; supported files directly under the root are one-Asset Tasks.
 *
 * Nested folders organise Assets and never create Tasks, so entering `alice/papers` later cannot
 * redefine `sample.pdf` as its own Task. A child folder with no supported file produces no Task and
 * one notice, and an unsupported file anywhere is skipped and reported. Supported means picture,
 * video or PDF — the kinds a Viewer in this build can open, so every Task made here is reviewable.
 */
fun planFolderAsTask(importRoot: String, snapshot: FolderSnapshot): ImportPlan {
    val notices = mutableListOf<ImportNotice>()
    val tasks = mutableListOf<PlannedTask>()

    val rootFiles = snapshot.files.filter { '/' !in it.relativePath }
    val filesInFolders = snapshot.files.filter { '/' in it.relativePath }

    for (file in rootFiles) {
        if (folderAsTaskSupported(file.name)) {
            tasks += fileTask(importRoot, file)
        } else {
            notices += ImportNotice.UnsupportedFile(childRelativePath(importRoot, file.relativePath))
        }
    }

    val childFolders = snapshot.directories.filter { '/' !in it }
    for (folder in childFolders) {
        val inFolder = filesInFolders.filter { it.relativePath.startsWith("$folder/") }
        // Ordered by relative path, the same case-insensitive comparator the scan uses, so the
        // initial Asset order is a property of the importer rather than of whatever order the
        // caller happened to hand it.
        val supported = inFolder.filter { folderAsTaskSupported(it.name) }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, ScannedFile::relativePath)
                    .thenBy(ScannedFile::relativePath)
            )
        inFolder.filterNot { folderAsTaskSupported(it.name) }.forEach {
            notices += ImportNotice.UnsupportedFile(childRelativePath(importRoot, it.relativePath))
        }

        if (supported.isEmpty()) {
            notices += ImportNotice.EmptyFolder(childRelativePath(importRoot, folder))
        } else {
            tasks += PlannedTask(
                groupPath = childRelativePath(importRoot, folder),
                displayName = folder,
                parts = listOf(
                    degeneratePart(
                        origin = PartOrigin.FOLDER,
                        assets = supported.mapIndexed { index, file ->
                            plannedAsset(importRoot, file, index)
                        }
                    )
                )
            )
        }
    }

    return ImportPlan(
        tasks = tasks.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, PlannedTask::groupPath)
                .thenBy(PlannedTask::groupPath)
        ),
        notices = notices
    )
}

/**
 * The supported-content policy both folder importers share: a file a viewer can open.
 *
 * Written as [assetViewerAvailable] rather than as its own extension list, so the importer, the
 * asset-control lookup and the catalog badge answer with one opinion. Everything else —
 * `rubric.txt`, a Package's manifest — is skipped and reported.
 */
fun folderAsTaskSupported(name: String): Boolean = assetViewerAvailable(name)

private fun fileTask(importRoot: String, file: ScannedFile): PlannedTask = PlannedTask(
    groupPath = childRelativePath(importRoot, file.relativePath),
    displayName = file.name,
    parts = listOf(
        degeneratePart(
            origin = PartOrigin.FILE,
            assets = listOf(plannedAsset(importRoot, file, 0))
        )
    )
)

/**
 * The one part a local importer synthesizes for a Task.
 *
 * A folder carries files, not judgments, so the local rules write exactly one decision over
 * whatever they grouped. The key is stable within the Task and deliberately not a Prompt key: the
 * importer does not fake a Prompt it never read.
 */
internal fun degeneratePart(origin: PartOrigin, assets: List<PlannedAsset>): PlannedPart =
    PlannedPart(
        partKey = "local:0",
        position = 0,
        origin = origin,
        assets = assets
    )

private fun plannedAsset(importRoot: String, file: ScannedFile, sortOrder: Int): PlannedAsset =
    PlannedAsset(
        rootRelativePath = childRelativePath(importRoot, file.relativePath),
        name = file.name,
        uri = file.uri,
        sizeBytes = file.sizeBytes,
        lastModified = file.lastModified,
        sortOrder = sortOrder
    )
