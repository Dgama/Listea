package com.example.listea

import com.example.listea.domain.ViewerEventType

/**
 * The vocabulary a headless template is described in.
 *
 * A Task Review is the pen for a Task: the default one records the Task's own file decisions, and
 * a **headless** one catches the answer a Viewer reports and files it on the answer sheet. The
 * event keys (`choice.select`, `image.point`) are the wire vocabulary; these are the words a
 * person choosing a pen should read.
 */
internal fun viewerEventNouns(types: Set<ViewerEventType>): String =
    types.sortedBy { it.key }.joinToString(" and ") { event ->
        when (event) {
            ViewerEventType.IMAGE_POINT -> "a sampled point"
            ViewerEventType.CHOICE_SELECT -> "a single choice"
        }
    }

/**
 * One line for a Task Review in a picker: what the template is, and what it catches.
 *
 * The empty set is not "nothing": it is the headless default, which records the Task's own file
 * decisions instead of an answer the Viewer reported.
 */
internal fun reviewCatchLine(accepts: Set<ViewerEventType>): String =
    if (accepts.isEmpty()) {
        "Headless template · records the Task's file decisions"
    } else {
        "Headless template · catches " + viewerEventNouns(accepts)
    }
