package com.example.listea

import android.content.Context
import android.net.Uri
import com.example.listea.data.ScannedFile
import com.example.listea.domain.DiagnosticScope
import com.example.listea.domain.PackageDiagnostic
import com.example.listea.domain.PackageManifest
import com.example.listea.domain.PackagePlan
import com.example.listea.domain.PackageProtocol
import com.example.listea.domain.PackageSnapshot
import com.example.listea.domain.PlannedPrompt
import com.example.listea.domain.PlannedSection
import com.example.listea.domain.PromptAvailability
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.Templates
import com.example.listea.domain.Viewers
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The host half of a Package import: find the manifests in a scanned folder, read the bytes a
 * planning pass asks for, and hand the result back as an ordinary [ImportPlan].
 *
 * **Discovery and planning are separate on purpose.** [scanFolderTree] already walked the tree and
 * can say which paths exist; [PackageProtocol] is pure and decides what those paths mean. This file
 * is the only place the two meet, so every rejection rule stays testable on the JVM and a partially
 * read Package can be diagnosed before anything is written.
 *
 * A Package root is any directory whose `.listea/manifest.json` the scan saw, up to
 * [PackageProtocol.MAX_DISCOVERY_DEPTH] folders below the scope. The scope itself may be a Package.
 * Nested Packages are allowed: a Package may ship another inside a member folder, and each is read
 * as its own.
 */
fun planPackages(
    context: Context,
    importRoot: String,
    snapshot: FolderSnapshot,
    composition: CompositionChoice = CompositionChoice.Default
): ImportPlan {
    val files = snapshot.files.associateBy { it.relativePath }
    val roots = packageRoots(snapshot, files)
    if (roots.size > PackageProtocol.MAX_PACKAGES_PER_SCOPE) {
        return ImportPlan(
            tasks = emptyList(),
            notices = emptyList(),
            diagnostics = listOf(
                PackageDiagnostic(
                    DiagnosticScope.PACKAGE,
                    null,
                    "limit-exceeded",
                    "This folder holds ${roots.size} Packages; the limit is " +
                        "${PackageProtocol.MAX_PACKAGES_PER_SCOPE}."
                )
            ),
            packagesRejected = roots.size
        )
    }

    val planned = roots.map { root ->
        val prefix = if (root.isEmpty()) "" else "$root/"
        val members = snapshot.files.filter { it.relativePath.startsWith(prefix) }
        val byMember = members.associateBy { it.relativePath.removePrefix(prefix) }
        val manifest = byMember[MANIFEST_MEMBER]
        val packageSnapshot = PackageSnapshot(
            rootRelativePath = packageScopePath(importRoot, root),
            manifestBytes = manifest?.let { readBounded(context, it.uri, PackageProtocol.MAX_MANIFEST_BYTES) },
            presentMembers = byMember.keys,
            readMember = { member ->
                byMember[member]?.let {
                    readBounded(context, it.uri, PackageProtocol.MAX_DESCRIPTION_BYTES)
                }
            }
        )
        PackageProtocol.plan(packageSnapshot) to (root to byMember)
    }

    // Two directories declaring one packageKey in one run name the same Package twice, and there is
    // no fact of the matter about which copy was meant. Neither writes, exactly as the plan says.
    val accepted = planned.filter { (plan, _) -> plan.accepted }
    val duplicatedKeys = accepted
        .groupBy { (plan, _) -> plan.manifest!!.packageKey }
        .filterValues { it.size > 1 }
        .keys
    val diagnostics = mutableListOf<PackageDiagnostic>()
    val tasks = mutableListOf<PlannedTask>()
    val packages = mutableListOf<PlannedPackage>()

    for ((plan, rootAndMembers) in planned) {
        val (root, byMember) = rootAndMembers
        if (!plan.accepted) {
            diagnostics += plan.diagnostics
            continue
        }
        val manifest = plan.manifest!!
        if (manifest.packageKey in duplicatedKeys) {
            diagnostics += PackageDiagnostic(
                DiagnosticScope.PACKAGE,
                manifest.packageKey,
                "duplicate-package-key",
                "More than one Package in this folder declares \"${manifest.packageKey}\"; " +
                    "none of them was imported."
            )
            continue
        }

        diagnostics += plan.diagnostics
        val packageRoot = packageScopePath(importRoot, root)
        val plannedPrompts = plan.prompts.map { prompt ->
            val assets = prompt.assets.filter { it.present }.mapIndexedNotNull { index, asset ->
                val file = byMember[asset.member] ?: return@mapIndexedNotNull null
                PlannedAsset(
                    rootRelativePath = childRelativePath(packageRoot, asset.member),
                    name = asset.name,
                    uri = file.uri,
                    sizeBytes = file.sizeBytes,
                    lastModified = file.lastModified,
                    sortOrder = index
                )
            }
            plannedPromptPart(manifest, prompt, assets) to prompt
        }
        val plannedTaskList = plannedTasks(
            manifest = manifest,
            prompts = plannedPrompts,
            sections = plan.sections,
            composition = composition
        )
        tasks += plannedTaskList.tasks
        diagnostics += plannedTaskList.diagnostics
        packages += PlannedPackage(
            rootRelativePath = packageRoot,
            packageKey = manifest.packageKey,
            protocol = manifest.protocol,
            title = manifest.title,
            revision = manifest.revision,
            fingerprint = plan.fingerprint!!,
            // The scanned member's own URI, attached here because this is the layer that holds the
            // snapshot. The protocol names the member; the host is the only one that can say where
            // it is.
            resources = plan.resources.map { resource ->
                resource.copy(uri = byMember[resource.member]?.uri)
            }
        )
    }

    return ImportPlan(
        tasks = tasks.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, PlannedTask::groupPath)
                .thenBy(PlannedTask::groupPath)
        ),
        notices = emptyList(),
        packages = packages.sortedBy { it.packageKey },
        diagnostics = diagnostics,
        packagesRejected = planned.count { (plan, _) -> !plan.accepted } +
            accepted.count { (plan, _) -> plan.manifest!!.packageKey in duplicatedKeys }
    )
}

/**
 * Whether a scanned folder holds a Package (v2 or later) that declares at least one Section.
 *
 * The import dialog asks this **before** the run's plan exists, so it reads manifests only. A full
 * [planPackages] would read every description body twice — once to ask, once to import — and the
 * answer needs only the declaration. A folder with no Package, a v1 Package, or a Package whose
 * `sections` is empty answers false, which is what keeps the composition choice hidden where
 * choosing it would change nothing.
 */
fun folderOffersSectionComposition(
    context: Context,
    importRoot: String,
    snapshot: FolderSnapshot
): Boolean = packageDeclarations(context, importRoot, snapshot).any { plan ->
    val protocol = plan.manifest?.protocol
    (protocol == PackageProtocol.PROTOCOL_V2 || protocol == PackageProtocol.PROTOCOL_V3) &&
        plan.sections.isNotEmpty()
}

/**
 * Whether any Package in this folder declares a v3 condition.
 *
 * Read from the manifests alone, for the same reason [folderOffersSectionComposition] is: the dialog
 * needs the answer before a run exists. It selects the composition the dialog opens on — a
 * condition needs its source in the same Task, which only a Section can promise — and it is what
 * makes the Section row carry the sentence saying so.
 *
 * The question is asked of the manifest rather than of a plan because the protocol drops a Prompt
 * whose body is missing, and the dialog must still warn about a Package whose bodies are there. A
 * `when` is a declaration in the manifest, so no body is needed to read it.
 */
fun folderNeedsSectionComposition(
    context: Context,
    snapshot: FolderSnapshot
): Boolean {
    val files = snapshot.files.associateBy { it.relativePath }
    return packageRoots(snapshot, files).any { root ->
        val member = if (root.isEmpty()) MANIFEST_MEMBER else "$root/$MANIFEST_MEMBER"
        PackageProtocol.declaresConditions(
            files[member]?.let { readBounded(context, it.uri, PackageProtocol.MAX_MANIFEST_BYTES) }
        )
    }
}

/**
 * Each Package's plan from its manifest, with no description body read.
 *
 * The Section registry lives in the manifest, so it survives a Prompt whose body is missing — which
 * is the only fact [folderOffersSectionComposition] asks.
 */
private fun packageDeclarations(
    context: Context,
    importRoot: String,
    snapshot: FolderSnapshot
): List<PackagePlan> {
    val files = snapshot.files.associateBy { it.relativePath }
    return packageRoots(snapshot, files).mapNotNull { root ->
        val prefix = if (root.isEmpty()) "" else "$root/"
        val byMember = snapshot.files
            .filter { it.relativePath.startsWith(prefix) }
            .associateBy { it.relativePath.removePrefix(prefix) }
        val manifest = byMember[MANIFEST_MEMBER] ?: return@mapNotNull null
        PackageProtocol.plan(
            PackageSnapshot(
                rootRelativePath = packageScopePath(importRoot, root),
                manifestBytes = readBounded(
                    context,
                    manifest.uri,
                    PackageProtocol.MAX_MANIFEST_BYTES
                ),
                presentMembers = byMember.keys,
                // Prompt bodies are irrelevant to the declaration: the sections and the conditions
                // are read out of the manifest, and a missing description only makes a prompt
                // unavailable.
                readMember = { null }
            )
        )
    }
}

/**
 * A Package's catalog key: `<packageKey>/<promptKey>`.
 *
 * Deliberately **not** a filesystem path. A prompt-only Task has no locator of its own, and giving
 * it one that looked like a path would invite a resolver to try. The key exists so the catalog can
 * show one Package as one scope and so `task_imports.groupPath` can carry a stable identity.
 */
fun catalogKey(packageKey: String, promptKey: String): String = "$packageKey/$promptKey"

/**
 * A Section composition's catalog key: `<packageKey>/~section/<sectionKey>`.
 *
 * The `~` first segment cannot be a Prompt key (the protocol's key grammar starts with a
 * lowercase letter or digit), so a Section Task and a Prompt Task can never collide in the catalog.
 */
fun sectionCatalogKey(packageKey: String, sectionKey: String): String =
    "$packageKey/~section/$sectionKey"

private fun plannedPromptPart(
    manifest: PackageManifest,
    prompt: PlannedPrompt,
    assets: List<PlannedAsset>
): PlannedPart = PlannedPart(
    partKey = prompt.promptKey,
    position = 0,
    origin = PartOrigin.PACKAGE,
    prompt = PlannedTaskPrompt(
        packageKey = manifest.packageKey,
        promptKey = prompt.promptKey,
        kind = prompt.kind,
        schema = prompt.schema,
        payload = prompt.payload,
        revision = manifest.revision,
        resources = prompt.resources
    ),
    assets = assets,
    resources = prompt.resources,
    condition = prompt.condition,
    // The protocol's own verdict travels with the part: a Prompt it found unreadable is imported
    // unavailable rather than as a page that will fail when somebody opens it. A condition the
    // composition cannot honour is added below, and never overwrites this first cause.
    availability = (prompt.availability as? PromptAvailability.Unavailable)?.code
)

/** The Tasks a Package's composition makes, and what planning them found worth reporting. */
private class PlannedTaskSet(
    val tasks: List<PlannedTask>,
    val diagnostics: List<PackageDiagnostic>
)

/**
 * The Tasks one Package would produce under [composition], with every condition resolved as far as
 * the chosen composition allows.
 *
 * One Task per Prompt is the default and the only v1 behavior. [CompositionChoice.SECTION] groups
 * tagged Prompts under the Section's title, in manifest order, and leaves an untagged Prompt its
 * own Task. A Section with no tagged Prompt yields no Task: it is a declaration, not work.
 *
 * **A condition is a claim about parts sharing a Task**, and the composition decides whether that
 * claim can be honoured. A source in another Task makes the target unavailable rather than
 * silently unconditional: the Task still imports, the report names it, and a required one blocks
 * the Task it sits on. The condition itself is written down either way, so reopening the Package
 * under one Task per Section repairs what this run could not.
 */
private fun plannedTasks(
    manifest: PackageManifest,
    prompts: List<Pair<PlannedPart, PlannedPrompt>>,
    sections: List<PlannedSection>,
    composition: CompositionChoice
): PlannedTaskSet {
    fun promptTask(part: PlannedPart, prompt: PlannedPrompt) = PlannedTask(
        groupPath = catalogKey(manifest.packageKey, prompt.promptKey),
        displayName = prompt.title,
        parts = listOf(part),
        compositionKey = "prompt:${prompt.promptKey}"
    )

    val grouped = if (composition == CompositionChoice.SECTION &&
        (manifest.protocol == PackageProtocol.PROTOCOL_V2 ||
            manifest.protocol == PackageProtocol.PROTOCOL_V3)
    ) {
        val titles = sections.associate { it.sectionKey to it.title }
        val bySection = linkedMapOf<String, MutableList<Pair<PlannedPart, PlannedPrompt>>>()
        val untagged = mutableListOf<Pair<PlannedPart, PlannedPrompt>>()
        for (entry in prompts) {
            val section = entry.second.section
            if (section == null) {
                untagged += entry
            } else {
                bySection.getOrPut(section) { mutableListOf() } += entry
            }
        }
        bySection.map { (sectionKey, members) ->
            PlannedTask(
                groupPath = sectionCatalogKey(manifest.packageKey, sectionKey),
                displayName = titles[sectionKey] ?: sectionKey,
                parts = members.mapIndexed { index, (part, _) -> part.copy(position = index) },
                compositionKey = "section:$sectionKey"
            )
        } + untagged.map { (part, prompt) -> promptTask(part, prompt) }
    } else {
        prompts.map { (part, prompt) -> promptTask(part, prompt) }
    }

    val diagnostics = mutableListOf<PackageDiagnostic>()
    return PlannedTaskSet(
        tasks = grouped.map { resolveJudgments(resolveConditions(it, diagnostics), diagnostics) },
        diagnostics = diagnostics
    )
}

/**
 * Mark every conditioned part whose source the chosen composition did not place in this Task.
 *
 * The protocol has already refused a source that is missing, later in the manifest, or does not
 * expose the field. What is left is the composition's own question: the source must be an earlier
 * part **of this Task**, and every Prompt is required. A part that already carries a code keeps it,
 * so the report names one cause rather than a cascade.
 */
private fun resolveConditions(
    task: PlannedTask,
    diagnostics: MutableList<PackageDiagnostic>
): PlannedTask {
    val byKey = task.parts.associateBy { it.partKey }
    var changed = false
    val parts = task.parts.map { part ->
        val condition = part.condition ?: return@map part
        if (part.availability != null) return@map part
        val source = byKey[condition.sourcePartKey]
        if (source != null && source.position < part.position && source.required) return@map part

        changed = true
        diagnostics += PackageDiagnostic(
            DiagnosticScope.PROMPT,
            part.prompt?.promptKey,
            CONDITION_SOURCE_OUTSIDE_TASK,
            "Prompt \"${part.partKey}\" depends on \"${condition.sourcePartKey}\", which this " +
                "composition puts in another Task. It was imported unavailable; choose one Task " +
                "per Section so the two share a Task."
        )
        part.copy(availability = CONDITION_SOURCE_OUTSIDE_TASK)
    }
    return if (changed) task.copy(parts = parts) else task
}

/**
 * Decide whether a Task is **mixed**, and if so give every part its own judgment.
 *
 * Mixed means one Task holds unlike answer sheets: several forms, several media parts, more than
 * one kind, or any condition at all. A mixed Task's Task-level review becomes the `mixed` container,
 * which owns completion only; each part names the built-in that draws and commits it. A homogeneous
 * Task keeps the run's Task Review and writes no part judgments, so its v0.8.4 behavior and export
 * shape do not move.
 *
 * A part no built-in judgment can answer makes the Task mixed too, and the part unavailable
 * (`unsupported-judgment`): a page with no way to commit it must not look like a page that can.
 */
private fun resolveJudgments(
    task: PlannedTask,
    diagnostics: MutableList<PackageDiagnostic>
): PlannedTask {
    if (isHomogeneous(task.parts)) return task

    val parts = task.parts.map { part ->
        val judgment = judgmentFor(part)
        if (judgment != null) {
            part.copy(judgment = judgment)
        } else {
            diagnostics += PackageDiagnostic(
                DiagnosticScope.PROMPT,
                part.prompt?.promptKey,
                UNSUPPORTED_JUDGMENT,
                "Prompt \"${part.partKey}\" is part of a mixed Task, and no built-in judgment in " +
                    "this build answers kind \"${part.prompt?.kind ?: "files"}\". It was imported " +
                    "unavailable."
            )
            part.copy(availability = part.availability ?: UNSUPPORTED_JUDGMENT)
        }
    }
    return task.copy(parts = parts, taskReview = Templates.MixedStructured.ref)
}

/**
 * The one family of answer sheet a part belongs to, for deciding whether a Task is homogeneous.
 *
 * Families, not kinds: profile and vocabulary are the same option-tap sheet and may share a Task
 * with any count. A form or a media part has no "several in one sheet" shape, so more than one of
 * either is already two sheets. A degenerate part carries no payload and is answered by its files.
 */
private enum class PartFamily { SINGLE_CHOICE, FORM, MEDIA, ASSET_ONLY, OTHER }

private fun familyOf(part: PlannedPart): PartFamily {
    val prompt = part.prompt ?: return PartFamily.ASSET_ONLY
    return when (Viewers.forPayload(prompt.kind, prompt.schema)) {
        Viewers.Profile, Viewers.Vocabulary -> PartFamily.SINGLE_CHOICE
        Viewers.Form -> PartFamily.FORM
        Viewers.Media -> PartFamily.MEDIA
        else -> PartFamily.OTHER
    }
}

/**
 * Whether every part of this Task reads under the one Task-level answer sheet v0.8.4 stamped.
 *
 * A condition forces a mixed Task even with a single part: the Task must then aggregate what the
 * parts say, and its page list includes a part the Task-level viewer never drew.
 */
private fun isHomogeneous(parts: List<PlannedPart>): Boolean {
    if (parts.any { it.condition != null }) return false
    val family = familyOf(parts.first())
    if (parts.any { familyOf(it) != family }) return false
    return when (family) {
        PartFamily.FORM, PartFamily.MEDIA -> parts.size == 1
        else -> true
    }
}

/** The built-in answer sheet one mixed Task's part is committed with, or null when none fits. */
private fun judgmentFor(part: PlannedPart): TemplateVersionRef? = when (familyOf(part)) {
    PartFamily.FORM -> Templates.StructuredForm.ref
    PartFamily.SINGLE_CHOICE -> Templates.StructuredSingleChoice.ref
    PartFamily.MEDIA, PartFamily.ASSET_ONLY -> Templates.MediaPart.ref
    PartFamily.OTHER -> null
}

private const val CONDITION_SOURCE_OUTSIDE_TASK = "condition-source-outside-task"

private const val UNSUPPORTED_JUDGMENT = "unsupported-judgment"

private const val MANIFEST_MEMBER = ".listea/manifest.json"

private fun packageRoots(snapshot: FolderSnapshot, files: Map<String, ScannedFile>): List<String> =
    buildList {
        if (MANIFEST_MEMBER in files) add("")
        for (directory in snapshot.directories) {
            if (directoryDepth(directory) > PackageProtocol.MAX_DISCOVERY_DEPTH) continue
            if ("$directory/$MANIFEST_MEMBER" in files) add(directory)
        }
    }.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, { path: String -> path }).thenBy { it }
    )

/**
 * The Package root's path inside the folder the run scans.
 *
 * [root] is a discovered Package root *relative to the scope*, so an empty root is the scope
 * itself rather than a child with an empty name. Plain concatenation turns that case into
 * `"dating/"`, and every member path beneath it then starts with `"//"` — found on device as
 * `dating//media/alice.png`. The scope path is returned unchanged instead.
 */
private fun packageScopePath(importRoot: String, root: String): String = when {
    root.isEmpty() -> importRoot
    importRoot.isEmpty() -> root
    else -> "$importRoot/$root"
}

/** The number of segments in a root-relative directory path; the scope root itself is depth 0. */
private fun directoryDepth(path: String): Int =
    if (path.isEmpty()) 0 else path.count { it == '/' } + 1

/**
 * Read at most [limit] bytes plus one.
 *
 * The extra byte is what lets [PackageProtocol] tell "exactly at the limit" from "over it" without
 * this reader allocating a file that may be arbitrarily large. A provider error is `null`, which
 * the protocol already diagnoses as an unreadable member rather than a crash.
 */
private fun readBounded(context: Context, uri: String, limit: Int): ByteArray? = runCatching {
    context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
        input.readAtMost(limit + 1)
    }
}.getOrNull()

private fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
