package com.example.listea

/**
 * The decision table behind Create List's two confirmation gates, held apart from the ViewModel's
 * control flow so it can be pinned by a test that owes nothing to Android, SAF or Room.
 *
 * v0.2.5 added a second gate - import consent - beside the pre-existing replace confirmation, and
 * the two answer different questions: whether this material needs importing before it can be
 * selected at all, and whether creating the List would take an existing one's place. They must be
 * asked one at a time, because accepting the first is not accepting the second: a List must never
 * be deleted by a dialog that never named it.
 */
enum class FolderListStep {
    /** Ask whether to import this material first. Accepting re-enters this table, now imported. */
    NEEDS_IMPORT_CONSENT,

    /** Ask which existing Lists this would replace. */
    NEEDS_REPLACE_CONFIRM,

    /** Nothing left to ask. */
    COMMIT
}

/**
 * [isImported] is whether a Source already covers the folder; [hasConflicts] is whether creating
 * the List would replace one or more existing Lists. Import consent is checked first: it is the
 * gate for whether the Tasks being selected may be created at all, which is a precondition for the
 * replace question to even make sense.
 */
fun folderListStep(isImported: Boolean, hasConflicts: Boolean): FolderListStep = when {
    !isImported -> FolderListStep.NEEDS_IMPORT_CONSENT
    hasConflicts -> FolderListStep.NEEDS_REPLACE_CONFIRM
    else -> FolderListStep.COMMIT
}
