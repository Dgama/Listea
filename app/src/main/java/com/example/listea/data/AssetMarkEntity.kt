package com.example.listea.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.listea.domain.AssetMark
import com.example.listea.domain.MarkKind
import com.example.listea.domain.QuoteAnchor

/**
 * One passage mark, as the `task_asset_marks` row v0.7.0 adds.
 *
 * **The anchor's fields are columns of this row rather than a serialized blob.** The quote, its two
 * neighbours and the character hint are what a reader matches against a freshly opened document, and
 * a migration or a query that had to decode them would be a second format to keep in step. The
 * format's own number lives beside them ([anchorVersion]) so a later shape is read, or refused,
 * rather than guessed at — the same rule template buttons and webhook payloads follow.
 *
 * **The foreign key is the Asset and only the Asset.** An Asset deleted by an import run cascades
 * here, because there is no longer a file for a passage to be on. There is deliberately no foreign
 * key to a template or a button: removing a tool hides its marks and never deletes them, which is the
 * whole promise this table exists to keep. [authoredTemplateId] and [authoredTemplateVersion] are
 * plain history columns for that reason.
 *
 * [kind] holds a [MarkKind] key. [charStart] and [charEnd] are the renderer's inclusive indices and
 * mean nothing without [charParser]; [exactText], [prefix] and [suffix] are the authority.
 */
@Entity(
    tableName = "task_asset_marks",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("assetId")]
)
data class AssetMarkEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    val toolId: String,
    val authoredTemplateId: String,
    val authoredTemplateVersion: Int,
    val kind: String,
    val value: String,
    val anchorVersion: Int,
    val documentFingerprint: String,
    val pageIndex: Int,
    val charStart: Int,
    val charEnd: Int,
    val charParser: String,
    val exactText: String,
    val prefix: String,
    val suffix: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** The domain row, or null when this build cannot read the mark's kind. */
fun AssetMarkEntity.toAssetMark(): AssetMark? {
    val kind = MarkKind.fromKey(kind) ?: return null
    return AssetMark(
        id = id,
        assetId = assetId,
        toolId = toolId,
        authoredTemplateId = authoredTemplateId,
        authoredTemplateVersion = authoredTemplateVersion,
        kind = kind,
        value = value,
        anchor = QuoteAnchor(
            version = anchorVersion,
            documentFingerprint = documentFingerprint,
            pageIndex = pageIndex,
            exactText = exactText,
            prefix = prefix,
            suffix = suffix,
            charStart = charStart,
            charEnd = charEnd,
            charParser = charParser
        ),
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

/** The row a new mark is written as. */
fun AssetMark.toEntity(): AssetMarkEntity = AssetMarkEntity(
    id = id,
    assetId = assetId,
    toolId = toolId,
    authoredTemplateId = authoredTemplateId,
    authoredTemplateVersion = authoredTemplateVersion,
    kind = kind.key,
    value = value,
    anchorVersion = anchor.version,
    documentFingerprint = anchor.documentFingerprint,
    pageIndex = anchor.pageIndex,
    charStart = anchor.charStart,
    charEnd = anchor.charEnd,
    charParser = anchor.charParser,
    exactText = anchor.exactText,
    prefix = anchor.prefix,
    suffix = anchor.suffix,
    createdAt = createdAt,
    updatedAt = updatedAt
)
