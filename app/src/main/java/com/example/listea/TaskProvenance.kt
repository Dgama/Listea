package com.example.listea

import com.example.listea.data.ReviewItem

/**
 * The line under a Task's name that says where it came from, or null when there is nothing to say.
 *
 * A Task imported from a folder has a path worth showing: `bilibili/a.jpg` is not `danbooru/a.jpg`,
 * and the path is what tells them apart. A Prompt Task from a Package has no such path. Its
 * `relativePath` and `rootRelativePath` hold the **synthetic catalog key**
 * `<packageKey>/<promptKey>` (`catalogKey` in `PackageImport.kt`), which is a database identity and
 * not a directory; showing it sent the user looking for a `dating-demo` folder that has never
 * existed on disk. The Package's own name is its manifest title — the same name the Catalog uses
 * for the scope — read from `package_checkpoints.title` beside the envelope.
 *
 * This is the one place that decision is made, so a List row, a queue entry, the Todo preview and
 * the Info sheet cannot drift: a Package Task shows its Package title or nothing, and never a key;
 * a file Task shows the first path that differs from its own title.
 */
internal fun taskProvenance(
    packageKey: String?,
    packageTitle: String?,
    relativePath: String?,
    rootRelativePath: String?,
    title: String
): String? = if (packageKey != null) {
    packageTitle?.takeIf { it.isNotBlank() && it != title }
} else {
    listOf(relativePath, rootRelativePath).firstOrNull { !it.isNullOrBlank() && it != title }
}

/** [taskProvenance] for an item that already carries the facts. */
internal fun ReviewItem.provenance(): String? = taskProvenance(
    packageKey = packageKey,
    packageTitle = packageTitle,
    relativePath = relativePath,
    rootRelativePath = rootRelativePath,
    title = title
)
