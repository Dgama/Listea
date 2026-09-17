package com.example.listea.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The v16 table: a folder the user has said material comes from, and nothing else.
 *
 * Until v16 one row did two jobs and did neither honestly. A `sources` row was written the moment
 * a folder was picked *and* was the thing an import configured, so a folder that had never been
 * read was indistinguishable from a Todo with no Tasks in it, and the two tabs read the same list
 * because they were the same list. The words the app used were already two — Upstream and Todo —
 * and the schema had one.
 *
 * The split is the one the words always meant:
 *
 * - An **Upstream** is this table. A folder, the SAF grant it sits under, and a name. It carries no
 *   importer, no tree option, no run history and no count, because none of those are facts about a
 *   folder — they are facts about a decision somebody made about one. Registering a folder says
 *   only *here is where material is*.
 * - A **Todo** is still a [SourceEntity]. It is written by an import run, it is what an importer is
 *   configured on, and it owns the Tasks that run produced.
 *
 * One folder can therefore be an Upstream and carry several Todos at once, because a Todo is rooted
 * at the folder the import was started *in* — the Upstream itself, or any folder beneath it. That
 * is what makes "import this subfolder, and only its files" a thing the app can say, and it is why
 * the overlap rule between import roots has nothing to do with registering a folder: two Upstreams
 * may nest freely, and so may two *Todos* — a Task belongs to exactly one of them, and a second
 * Todo over the same ground makes Tasks of its own rather than contending for the first one's.
 *
 * [displayName] is the user's or the provider's name for the folder. It is deliberately not derived
 * from [relativePath] at read time: the path is how the app finds the folder, and a list titled
 * `2026-09-11/bilibili` is the app talking to itself.
 *
 * The pair `(rootUri, relativePath)` is a uniqueness constraint, not an identity: picking the same
 * folder twice adds no second row. [id] is opaque, and nothing outside this table is keyed on it.
 */
@Entity(
    tableName = "upstreams",
    indices = [Index(value = ["rootUri", "relativePath"], unique = true)]
)
data class UpstreamEntity(
    @PrimaryKey val id: String,
    val rootUri: String,
    val relativePath: String,
    val displayName: String,
    val addedAt: Long
)
