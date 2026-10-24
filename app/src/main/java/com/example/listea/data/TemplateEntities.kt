package com.example.listea.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.listea.domain.TemplateKind
import kotlinx.coroutines.flow.Flow

/**
 * A template *lineage*: the identity, and what a person calls it.
 *
 * Two columns and no more, which is the whole of what §Templates means by a lineage. What the
 * template says belongs to a version, because a version is immutable and a lineage is not; the name
 * belongs here rather than there, because §Identity requires an id to survive a rename and a name
 * stored per version would make every rename a new version.
 *
 * `templates` is what a list screen reads and `template_versions` is what a resolver reads, which is
 * why they are two tables rather than one row with a version column. A lineage with no version
 * cannot be attached to anything — it is a name somebody has not filled in yet — and one with three
 * is three answers to the same question, which is the situation this shape exists to make ordinary.
 */
@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long
)

/**
 * One immutable answer: what a template version governs, and what it offers.
 *
 * **There is no update path to this table, and that is the design rather than an omission.** An edit
 * inserts version *n + 1* beside the row it changes, so the version a Task was imported under stays
 * readable for as long as the Task is — which is what makes *confirm to update* mean something, and
 * what §Templates requires of a completed review's reference. Nothing in the app issues an `UPDATE`
 * against this table; `TemplateStoreTest` asserts as much.
 *
 * [scope] and [buttons] are payloads, each in the encoding its own file documents, and [buttonsVersion]
 * sits beside [buttons] exactly as `dissolved_task_state.recordVersion` sits beside its `records`: a
 * payload is never read without knowing what wrote it. [scope] carries no version, because a rule
 * type this build cannot evaluate is *defined* behaviour rather than an unreadable payload — it
 * matches nothing — so the encoding already has a forward-compatible reading.
 *
 * The foreign key cascades, so deleting a lineage takes its versions with it. It cannot be the other
 * way round: a version whose lineage is gone is a row with no name and no way to be addressed.
 */
@Entity(
    tableName = "template_versions",
    primaryKeys = ["templateId", "version"],
    foreignKeys = [
        ForeignKey(
            entity = TemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TemplateVersionEntity(
    val templateId: String,
    val version: Int,
    val scope: String,
    val buttons: String,
    val buttonsVersion: Int,
    val createdAt: Long,

    /**
     * Which kind of payload this version carries: an asset control's scope/buttons, or a Task
     * Review's definition.
     *
     * A column rather than a fourth table, because both kinds share the lifecycle this table
     * exists for — lineage, immutable versions, a reference forever. The default is what every row
     * written before v0.8.1 was, so the upgrade reads them as what they already were.
     */
    @ColumnInfo(defaultValue = "'asset-control'")
    val kind: String = StoredTemplateKind.ASSET_CONTROL,

    /** The [com.example.listea.domain.TaskReviewDefinition] payload, empty for other kinds. */
    @ColumnInfo(defaultValue = "''")
    val definition: String = "",

    /** The encoding version of [definition]; 0 when there is none. */
    @ColumnInfo(defaultValue = "0")
    val definitionVersion: Int = 0
)

/**
 * The stored vocabulary for [TemplateVersionEntity.kind].
 *
 * Strings rather than [TemplateKind]'s names, for the same reason every other stored key is: a
 * later release may reorder or rename the enum, and a column written by this build must keep being
 * readable by one that has never heard of it. An unknown key is skipped by the resolver rather
 * than guessed into the nearest kind.
 */
object StoredTemplateKind {

    const val ASSET_CONTROL = "asset-control"
    const val TASK_REVIEW = "task-review"

    /** The word this build writes for [kind]. */
    fun of(kind: TemplateKind): String = when (kind) {
        TemplateKind.ASSET_CONTROL -> ASSET_CONTROL
        TemplateKind.TASK_REVIEW -> TASK_REVIEW
        TemplateKind.ASSET_VIEWER -> "asset-viewer"
    }

    /**
     * The kind a stored [key] names, or null for one this build has never written.
     *
     * Only the two kinds this build stores are decodable. An asset viewer stays code-defined in
     * v0.8.1, so a stored row claiming to be one is not something to draw from.
     */
    fun toKind(key: String?): TemplateKind? = when (key) {
        ASSET_CONTROL -> TemplateKind.ASSET_CONTROL
        TASK_REVIEW -> TemplateKind.TASK_REVIEW
        else -> null
    }
}

/**
 * Reading templates, and only reading them.
 *
 * **The write side is [TemplateWriteDao], and the split is the whole of the design.** A version is
 * appended by whoever is creating one and never revised, so the interface the screens read through
 * must not be able to revise one either — an interface with no writes is a rule the compiler keeps,
 * which is worth more than the paragraph that would otherwise have to. What is left here is the two
 * questions a page asks: every lineage, and every version of one lineage.
 */
@Dao
interface TemplateDao {

    /** Every lineage, by name — what the list screen shows, in the order it shows it. */
    @Query("SELECT * FROM templates ORDER BY name, id")
    fun observeTemplates(): Flow<List<TemplateEntity>>

    /**
     * Every version of every template, oldest first within each.
     *
     * One query rather than one per lineage, because the list screen names each template's current
     * version and how many buttons it offers. Asking per row would be a query per card, for a table
     * that holds a handful of rows in total.
     */
    @Query("SELECT * FROM template_versions ORDER BY templateId, version")
    fun observeVersions(): Flow<List<TemplateVersionEntity>>

    /** One lineage, which the writer has to read before it may rename one. */
    @Query("SELECT * FROM templates WHERE id = :templateId")
    suspend fun template(templateId: String): TemplateEntity?

    /**
     * One lineage's versions, oldest first.
     *
     * A list rather than a "current version" lookup, because which version is current is not a fact
     * about the template: a Todo attached at version 1 goes on meaning version 1 however many edits
     * have happened since, so the caller picks by the reference it already holds. The writer appends
     * after the last of these for the same reason it reads them all — the next version number is the
     * one after whatever is there, which a maximum would also give and a list gives without a second
     * query.
     */
    @Query("SELECT * FROM template_versions WHERE templateId = :templateId ORDER BY version")
    suspend fun versionsOf(templateId: String): List<TemplateVersionEntity>

    /**
     * Both tables as they stand, read once.
     *
     * The same two queries as the flows above, suspended rather than observed, and for a caller that
     * has to *offer* the templates rather than draw them: the import dialog's attach step is built
     * into an [com.example.listea.ImportFolderRequest.Confirm] the moment the folder is read, so it
     * is answered before anything is collecting. Handing that dialog a flow would make it a screen
     * that can be empty for a frame, and a picker that says there are no templates on a device that
     * has one is the failure the template list's nullable flow exists to avoid.
     */
    @Query("SELECT * FROM templates ORDER BY name, id")
    suspend fun templatesOnce(): List<TemplateEntity>

    /** See [templatesOnce]. Oldest first within each lineage, as the flow above reads them. */
    @Query("SELECT * FROM template_versions ORDER BY templateId, version")
    suspend fun versionsOnce(): List<TemplateVersionEntity>
}

/**
 * The write side: two inserts and one rename, and nothing else.
 *
 * **No update of a version, and no delete of one.** [insertVersion] aborts on a key that is already
 * there rather than replacing it, so *an edit appends a version* is a constraint SQLite enforces
 * rather than a rule the code is asked to remember — the property `TemplateStoreTest` asserts is
 * therefore the one the schema holds, not one a reviewer has to take on trust.
 *
 * The single column a template's page may change in place is the lineage's name, and §Identity is
 * why it is not a version of its own: a display name is not part of identity, so renaming one must
 * not mint an answer a Todo could be holding.
 */
@Dao
interface TemplateWriteDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTemplate(template: TemplateEntity)

    /** One more version, beside the ones already there. Never over one. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(version: TemplateVersionEntity)

    /**
     * Renames a lineage.
     *
     * An update rather than a new row, because the name is not what a version says: a Task imported
     * under version 1 goes on meaning version 1 under whatever the template is called today.
     */
    @Query("UPDATE templates SET name = :name WHERE id = :templateId")
    suspend fun renameTemplate(templateId: String, name: String)
}
