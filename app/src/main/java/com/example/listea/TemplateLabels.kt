package com.example.listea

import com.example.listea.domain.TaskReviewDefinition
import com.example.listea.domain.Templates
import com.example.listea.domain.ViewerEventType
import com.example.listea.domain.Viewers

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
 * The empty set is not "nothing": it is a headless definition, which records the Task's own file
 * decisions instead of an answer the Viewer reported. The Form Viewer also emits no events — its
 * answer is a structured record — so the line names that answer rather than the file decisions a
 * media Task would make. Reading the Viewer is what keeps the two empty-event cases apart.
 */
internal fun reviewCatchLine(definition: TaskReviewDefinition): String =
    when {
        definition.accepts.isNotEmpty() ->
            "Headless template · catches " + viewerEventNouns(definition.accepts)
        definition.viewer == Viewers.Form.ref ->
            "Headless template · records the form's answer"
        else ->
            "Headless template · records the Task's file decisions"
    }

/**
 * The same line for a reference that may name one of the internal judgments.
 *
 * `builtin.media.part` and `builtin.structured.mixed` are code-only and never reach the picker, so
 * they are absent from the seeded rows and from every list a person chooses from. If one is ever
 * shown — a Package's report naming what a part is judged by, or a screen reading a stored part —
 * this says what it is rather than describing it as the rapid review, which has the same empty
 * accept set but a different job.
 */
internal fun reviewCatchLine(templateId: String, definition: TaskReviewDefinition): String =
    when (templateId) {
        Templates.MixedStructured.ref.templateId.value ->
            "Headless template · records each part's answer"
        Templates.MediaPart.ref.templateId.value ->
            "Headless template · records the files"
        else -> reviewCatchLine(definition)
    }
