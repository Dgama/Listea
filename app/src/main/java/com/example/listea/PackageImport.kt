package com.example.listea

import android.content.Context
import android.net.Uri
import com.example.listea.data.ScannedFile
import com.example.listea.domain.DiagnosticScope
import com.example.listea.domain.PackageDiagnostic
import com.example.listea.domain.PackageProtocol
import com.example.listea.domain.PackageSnapshot
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
fun planPackages(context: Context, importRoot: String, snapshot: FolderSnapshot): ImportPlan {
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
        for (prompt in plan.prompts) {
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
            tasks += PlannedTask(
                groupPath = catalogKey(manifest.packageKey, prompt.promptKey),
                displayName = prompt.title,
                assets = assets,
                prompt = PlannedTaskPrompt(
                    packageKey = manifest.packageKey,
                    promptKey = prompt.promptKey,
                    kind = prompt.kind,
                    schema = prompt.schema,
                    payload = prompt.payload,
                    revision = manifest.revision,
                    resources = prompt.resources
                )
            )
        }
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
 * A Package's catalog key: `<packageKey>/<promptKey>`.
 *
 * Deliberately **not** a filesystem path. A prompt-only Task has no locator of its own, and giving
 * it one that looked like a path would invite a resolver to try. The key exists so the catalog can
 * show one Package as one scope and so `task_imports.groupPath` can carry a stable identity.
 */
fun catalogKey(packageKey: String, promptKey: String): String = "$packageKey/$promptKey"

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
