package com.example.listea

import com.example.listea.data.CatalogRow

/**
 * What the catalog has picked, as folders and Tasks together.
 *
 * A Task row is a Task and nothing else. A folder row is a *scope*: picking it says "everything
 * under here", which is the one thing the row cannot say by itself — the Tasks it covers are not
 * all on screen, and a scope that sits inside a Task covers part of one.
 *
 * Kept as two sets rather than one flattened set of Task ids because the two answer different
 * questions. [scopes] is what the user consciously ticked and is therefore what the folder's
 * checkbox reads back to them; [excluded] is the Tasks they unticked underneath a ticked folder
 * and is what stops a re-toggle from quietly restoring a choice they took back. Flattening to a
 * set of ids would lose which was which, and the checkbox would then be unable to tell "I cleared
 * this folder" from "I cleared two Tasks of it".
 *
 * Nothing here is stored: a selection is what a List is about to be made of, and leaving without
 * pressing Create has to leave no trace.
 */
internal data class CatalogSelection(
    val scopes: Set<String> = emptySet(),
    val tasks: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet()
) {
    /**
     * Every Task the selection covers.
     *
     * [rows] is the Source's whole catalog rather than the scope being browsed: a folder's Tasks
     * are resolved by path prefix here, which is the same rule [catalogAtScope] descends by, so a
     * folder picked at the top of a tree covers what browsing into it would have shown.
     */
    fun taskIds(rows: List<CatalogRow>): Set<String> = resolve(rows)

    private fun resolve(rows: List<CatalogRow>): Set<String> =
        rows.asSequence()
            .filter { row ->
                row.taskId in tasks || scopes.any { catalogScopeContains(it, row.groupPath) }
            }
            .map(CatalogRow::taskId)
            .filter { it !in excluded }
            .toSet()

    /** True when the folder's box reads as ticked: it was picked and has not been emptied. */
    fun isScopePicked(path: String): Boolean = path in scopes

    /**
     * Ticks or clears a folder, taking its Tasks with it.
     *
     * Any exclusion recorded against a Task under this folder is dropped either way. Ticking has to
     * drop them or the Tasks the user took back would stay out; clearing has to drop them because
     * they describe a folder that is no longer ticked, and leaving them would silently apply the
     * moment it was ticked again.
     */
    fun toggleScope(path: String, rows: List<CatalogRow>): CatalogSelection {
        val under = rows.filter { catalogScopeContains(path, it.groupPath) }
            .map(CatalogRow::taskId)
            .toSet()
        return copy(
            scopes = if (path in scopes) scopes - path else scopes + path,
            excluded = excluded - under
        )
    }

    /**
     * Ticks or clears one Task.
     *
     * Clearing a Task inside a ticked folder records an exclusion rather than unticking the folder,
     * so the folder keeps saying "I chose this" while no longer claiming a Task the user took back.
     */
    fun toggleTask(taskId: String, rows: List<CatalogRow>): CatalogSelection =
        if (isTaskPicked(taskId, rows)) {
            copy(tasks = tasks - taskId, excluded = excluded + taskId)
        } else {
            copy(tasks = tasks + taskId, excluded = excluded - taskId)
        }

    /** Whether one Task ends up covered, which is what its own row's checkbox reads. */
    fun isTaskPicked(taskId: String, rows: List<CatalogRow>): Boolean = taskId in resolve(rows)
}

/**
 * Whether a Task at [groupPath] sits under the scope [path] — the Task itself, or anything below it.
 *
 * The same containment [catalogAtScope] descends by, so what a folder covers when it is ticked is
 * exactly what opening it would have shown, and a Task named `a/b` is not treated as living under
 * a scope named `a/bc`.
 *
 * The empty path is the Source's root and covers everything, matching `catalogAtScope`'s own
 * special case for it. No row offers to tick the root today - only a scope's children are drawn as
 * folders - but this is the rule a root-ticking row would be built on, and a prefix test that
 * quietly covered nothing was the shape of the bug rather than a shortcut.
 */
internal fun catalogScopeContains(path: String, groupPath: String): Boolean =
    path.isEmpty() || groupPath == path || groupPath.startsWith("$path/")
