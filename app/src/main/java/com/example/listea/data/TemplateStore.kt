package com.example.listea.data

import androidx.room.withTransaction
import com.example.listea.domain.TEMPLATE_BUTTONS_VERSION
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.TemplateScope
import com.example.listea.domain.encodeTemplateButtons
import com.example.listea.domain.encodeTemplateScope
import com.example.listea.sanitizeActionText
import kotlinx.coroutines.flow.Flow

/**
 * The templates as rows: what reads them, and the one way one is written.
 *
 * **The whole of the write side is [update], and that is the release expressed as an API.** Editing
 * a template appends a version; nothing here can revise one, and nothing here can delete one. It is
 * the gesture the user asked for — *add and remove buttons freely, but nothing is written until you
 * confirm* — with the confirmation being a call to this one function and the *nothing is written*
 * being the absence of any other. A page that held its edits and called this on Update is not
 * following a rule; it has nowhere else to put them.
 *
 * **The store owns the deciding as well as the writing.** [update] is handed what the page was
 * edited into and works out what that is worth writing: a name change is a row update and no
 * version, a change to the scope or the buttons is one more version, and a call that says exactly
 * what is already stored writes nothing at all. Putting that here rather than in the screen is what
 * keeps *Update* from meaning three different things on three surfaces, and it is the reason the
 * comparison can be made against the rows themselves instead of against a copy the UI is holding.
 *
 * There is no create and no delete. A lineage is minted by the step that has something to name —
 * the 23 → 24 migration's per-Todo templates, and the import that attaches one — and the release
 * that lets a user start one from a blank page is not this one.
 */
class TemplateStore(private val database: ListeaDatabase) {

    private val reads = database.templateDao()
    private val writes = database.templateWriteDao()

    /** Every lineage, by name — what the list screen draws. */
    val templates: Flow<List<TemplateEntity>> = reads.observeTemplates()

    /** Every version of every lineage, oldest first within each — what a row's summary reads. */
    val versions: Flow<List<TemplateVersionEntity>> = reads.observeVersions()

    /**
     * Both tables as they stand, read once — for a caller that has to *offer* them, not draw them.
     *
     * The import dialog's attach step: it is built the moment a folder is read, so it is answered
     * before anything is collecting either flow, and a picker built from a not-yet-emitted flow would
     * offer an empty list on a device that has templates. One transaction so the two tables cannot
     * be read a version apart.
     */
    suspend fun snapshot(): Pair<List<TemplateEntity>, List<TemplateVersionEntity>> =
        database.withTransaction { reads.templatesOnce() to reads.versionsOnce() }

    /**
     * Writes what one template's page was edited into.
     *
     * The name is written when it differs, and one version is appended when the scope or the buttons
     * differ from the version that is currently last. Both, either, or neither: a rename alone is a
     * rename, and a page that was opened and closed unchanged writes nothing, which is what makes
     * *Update* safe to press when you are not sure anything moved. What it returns is nothing, and
     * deliberately: the screens observe these tables, so *did it work* is answered by the rows
     * arriving rather than by a value somebody has to remember to look at.
     *
     * **The two writes are one transaction**, because the decision the second turns on is read
     * before it. Read-then-append outside a transaction is a race whose loser gets a primary-key
     * conflict — [TemplateWriteDao.insertVersion] aborts rather than overwrites, so the failure
     * would be loud, but a version number that depends on which tap landed first is not a thing to
     * leave to scheduling when one line removes the question.
     *
     * A version this build cannot read ends it: nothing is appended past a `buttonsVersion` this
     * build has never written, because doing so would put a payload it can read in front of one it
     * cannot and make the template's current answer the older of the two. Nothing is written, which
     * fails closed in the direction the rest of this model fails in.
     */
    suspend fun update(
        templateId: String,
        name: String,
        scope: TemplateScope,
        buttons: List<TemplateButton>
    ) {
        database.withTransaction {
            val lineage = reads.template(templateId) ?: return@withTransaction
            val latest = reads.versionsOf(templateId).lastOrNull() ?: return@withTransaction
            if (latest.buttonsVersion != TEMPLATE_BUTTONS_VERSION) return@withTransaction

            // Typed text is kept to one line on the way in, exactly as it is for a configured tag
            // (`SettingsStore.updateCustomAction`), and by the same function. One sanitiser rather
            // than one per format is a rule this codebase already pays for; a second would be the
            // place the two formats quietly started disagreeing.
            val cleanName = sanitizeActionText(name)
            val cleanButtons = buttons.map {
                it.copy(
                    name = sanitizeActionText(it.name),
                    recordValue = sanitizeActionText(it.recordValue)
                )
            }
            val scopeText = encodeTemplateScope(scope)
            val buttonsText = encodeTemplateButtons(cleanButtons)

            if (lineage.name != cleanName) writes.renameTemplate(templateId, cleanName)

            // Compared as encoded text rather than as decoded values, and that is the stricter of
            // the two: the scope encoding is canonical, so it compares values, while the buttons
            // encoding is order-significant, so a bar whose chips were rearranged is a new version.
            // A rearrangement is a change to what the row offers, and a version is how that is said.
            if (latest.scope == scopeText && latest.buttons == buttonsText) return@withTransaction

            writes.insertVersion(
                TemplateVersionEntity(
                    templateId = templateId,
                    version = latest.version + 1,
                    scope = scopeText,
                    buttons = buttonsText,
                    buttonsVersion = TEMPLATE_BUTTONS_VERSION,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }
}
