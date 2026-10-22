package com.example.listea.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * An add-on *lineage*: the identity, and what a person calls it.
 *
 * The same two-column shape [TemplateEntity] has, for the same reason: a name may change without
 * making a different thing, and what the add-on *asks* belongs to a version, because a version is
 * immutable and a lineage is not. An add-on with no version is a name nobody has filled in yet; one
 * with three versions is three questionnaires somebody kept, which is the situation this shape
 * exists to make ordinary.
 *
 * **Not a `templates` row.** The two entities share an editing model and a Settings screen; they do
 * not share a vocabulary. A template version has a scope and buttons, an add-on version has
 * questions, and folding them into one table would mean three nullable payload columns and a reader
 * that has to decide which half of a row is the real one.
 */
@Entity(tableName = "addons")
data class AddOnEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long
)

/**
 * One immutable questionnaire: its questions, in the order they are asked.
 *
 * **There is no update path to this table, and that is the design rather than an omission.** An edit
 * inserts version *n + 1* beside the row it changes, so the version a Task was imported under stays
 * readable for as long as the Task is. [questions] and [questionsVersion] sit together exactly as a
 * template's payload and version do: a payload is never read without knowing what wrote it. An
 * unknown version fails closed — the whole version is unreadable, rather than partially read — see
 * `decodeAddOnQuestions`.
 *
 * The foreign key cascades, so deleting a lineage would take its versions with it. Nothing in this
 * release deletes one; the cascade is the schema's statement about what a version without a
 * lineage would be, not a promise that deletion exists.
 */
@Entity(
    tableName = "addon_versions",
    primaryKeys = ["addOnId", "version"],
    foreignKeys = [
        ForeignKey(
            entity = AddOnEntity::class,
            parentColumns = ["id"],
            childColumns = ["addOnId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AddOnVersionEntity(
    val addOnId: String,
    val version: Int,
    val questions: String,
    val questionsVersion: Int,
    val createdAt: Long
)

/**
 * Reading add-ons, and only reading them.
 *
 * The split from [AddOnWriteDao] is the same rule [TemplateDao] states: an interface with no writes
 * is a rule the compiler keeps. What is left is the two questions a page asks — every lineage, and
 * every version of one lineage — plus the once-only reads the import dialog needs.
 */
@Dao
interface AddOnDao {

    /** Every lineage, by name — what the shared Templates & Add-ons list draws. */
    @Query("SELECT * FROM addons ORDER BY name, id")
    fun observeAddOns(): Flow<List<AddOnEntity>>

    /** Every version of every add-on, oldest first within each. */
    @Query("SELECT * FROM addon_versions ORDER BY addOnId, version")
    fun observeVersions(): Flow<List<AddOnVersionEntity>>

    /** One lineage, which the writer has to read before it may rename one. */
    @Query("SELECT * FROM addons WHERE id = :addOnId")
    suspend fun addOn(addOnId: String): AddOnEntity?

    /** One lineage's versions, oldest first. */
    @Query("SELECT * FROM addon_versions WHERE addOnId = :addOnId ORDER BY version")
    suspend fun versionsOf(addOnId: String): List<AddOnVersionEntity>

    /**
     * Both tables as they stand, read once — for the import dialog, which has to *offer* add-ons
     * rather than draw them and is built before anything is collecting.
     *
     * One snapshot so the two tables cannot be read a version apart; the same reason
     * `TemplateStore.snapshot` exists.
     */
    @Query("SELECT * FROM addons ORDER BY name, id")
    suspend fun addOnsOnce(): List<AddOnEntity>

    /** See [addOnsOnce]. Oldest first within each lineage. */
    @Query("SELECT * FROM addon_versions ORDER BY addOnId, version")
    suspend fun versionsOnce(): List<AddOnVersionEntity>
}

/**
 * The write side: two inserts and one rename, and nothing else.
 *
 * **No update of a version, and no delete of one.** [insertVersion] aborts on a key that is already
 * there rather than replacing it, so *an edit appends a version* is a constraint SQLite enforces
 * rather than a rule the code is asked to remember — the property `AddOnStoreTest` asserts is the
 * one the schema holds. The only column a lineage may change in place is its name, because a name is
 * not part of identity.
 */
@Dao
interface AddOnWriteDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAddOn(addOn: AddOnEntity)

    /** One more version, beside the ones already there. Never over one. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(version: AddOnVersionEntity)

    /** Renames a lineage. A Task imported under version 1 still means version 1. */
    @Query("UPDATE addons SET name = :name WHERE id = :addOnId")
    suspend fun renameAddOn(addOnId: String, name: String)
}
