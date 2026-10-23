# Review notes — Listea v0.8.x Framework Restructuring Proposal

> **Reviewed:** 2026-10-23, against `ARCHITECTURE.md` (revision 2026-10-21), the shipped app
> `0.7.1` (schema 29, export version 4), and the code under `app/src/main/java/com/example/listea`.
> **Baseline mismatch worth stating up front:** the proposal names the `ARCHITECTURE.md` revision of
> **2026-09-15** as its baseline. The document has since recorded v0.7.0 (PDF viewer, passage marks)
> and v0.7.1 (**Add-ons**), both of which touch this proposal's subject matter. Several conflicts
> below — especially the add-on model — follow directly from reviewing an architecture that predates
> those two releases. Rebase the proposal's baseline before accepting its §3 invariant list.
> **How to read:** this file annotates the proposal **by section number** and does not edit it.
> Tags used below: **B** = blocker, a decision is needed before implementation; **C** = conflict
> with a normative rule or shipped behaviour; **G** = gap, missing from the proposal but needed;
> **Q** = question for the author; **I** = implementation note for a point that is broadly
> implementable as written.

---

## 0. Verdict

The proposal's **direction is right and its decomposition is largely the one the architecture
already points at**. Separating the import envelope from the importer, Viewer from Review Template,
Response from ReviewResult, and mapping from delivery is consistent with ARCHITECTURE.md's
invariant "Core remains capability-oriented" and with the roadmap's "general task model" milestone.

It is **not yet a v0.8.x plan**. Three things stand between the document and code:

1. **Vocabulary collision.** The proposal reintroduces *Source* as a durable registered origin.
   ARCHITECTURE.md deliberately retired that word, the `sources` table in the shipped database
   already means **Todo**, and the registered origin is the **Upstream**. Implementing the proposal
   as written would give the codebase a fourth meaning of "Source".
2. **The Response/Add-on model contradicts shipped v0.7.1.** Add-on answers are *current Task
   state* (one row per question, replaced on re-answer, hidden not deleted); the proposal makes them
   fragments of an immutable Response inside a ReviewEvent. That is a history-vs-state decision, not
   a refactor, and it changes the v4 export contract.
3. **The structured Task is a bigger lift than the plan implies.** A Task currently has **no kind,
   no payload, no title and no Resource**; the review page model is `assets + optional add-on page`.
   A zero-Asset structured Task has **zero pages** today, so the navigator, the just-shipped queue
   guide, the catalog, Quick Review and `DecisionTarget` all need an explicit content-page concept
   before the v0.9 fixtures can exist.

The rest of this review is the detail. A reduced, implementable sequence is in §5.

---

## 1. Conflicts with the architecture and with shipped code

### C1 — "Source" is a retired word and a taken table name (**B**)

ARCHITECTURE.md §*File, Source, and Upstream* (`docs/ARCHITECTURE.md:566-592`) says:

- a **Source** is raw external material, "an objective thing that exists whether or not Listea has
  ever heard of it", **not a domain concept and not a name for anything stored**;
- no user-facing surface names a Source;
- the word is already doing three jobs (the `sources` table, two user-facing messages, and the
  "Source" card/Info row), and that is recorded as an **open item**.

The shipped schema agrees:

- `data/UpstreamEntities.kt:18-49` — `upstreams` is "a folder the user has said material comes
  from, and nothing else" (id, rootUri, relativePath, displayName, addedAt).
- `data/ImportEntities.kt:62-214` — `sources` is the **Todo**: folder + importer kind + content
  policy + template/add-on attachments + follow-upstream + export choice + produced Tasks.

The proposal's §4.2 **Source** — stable opaque ID, locator, discovery/refresh configuration,
availability status, checkpoints, provenance — is the current **Upstream**, extended with refresh
state. It is not the architecture's Source, and it is certainly not a `sources` row.

**Q1.** Does the proposal intend to rename Upstream → Source across the codebase, the database and
the user-facing messages? If so, that is an explicit architecture decision, it collides with the
open item above, and it is a large mechanical change. If not, §4.2 should say **Upstream** and the
proposal should reserve "Source" for external material only.

**I.** Recommended: keep **Upstream** as the registered origin; extend `UpstreamEntity` with
discovery/refresh state (`lastScanAt`, `lastPackageRevision`, availability) rather than adding a
parallel `sources`-like table. The proposal can still use "upstream producer" for the external
party — that is the word the app already teaches.

### C2 — Add-on answers are state, not a Response fragment (**B**)

The proposal §4.10–§4.11 says an Add-on contributes a **namespaced fragment to one committed
Response**, and §4.11 says a submission appends a ReviewEvent containing that Response and updates
TaskState.

Shipped v0.7.1 says the opposite, in deliberate terms:

- `docs/iterations/v0.7.1.md` §4: one row per `(taskId, questionId)`, **re-answering replaces the
  row**, no history, "the questionnaire records the current judgment rather than a conversation";
- hidden, not deleted — a reading of the rows, never a write;
- the export sends the **current visible answers** as `addOn` after `assets`;
- `domain/AddOns.kt:114-122` — the answer carries the authored add-on id/version, not a review event;
- `data/TaskEntities.kt:282-301` — `review_events` has one template ref and a response string; it has
  no add-on version and no add-on fragment.

Folding add-ons into Response means choosing **event-sourced answers**: every re-answer is a new
event, the "hidden not deleted" rule becomes "omit from the latest snapshot", and the v4 `addOn`
object stops being a projection of current state. That is a product decision (per-tap history vs
current judgment) and a migration, not an "explicitness" change.

**Q2.** Is the intent that add-on answers become history, or that they stay current state and a
submission merely *snapshots* them? If the latter, Response needs a well-defined snapshot rule and
the live answers stay authoritative for UI and export.

**I.** Recommended for v0.8: keep `task_review_answers` exactly as shipped. Define Response for the
**primary** interaction only. If historical exports are required, add an optional `addOnSnapshot`
section to the ReviewEvent, written at commit, without changing the live state's authority. Write
the decision into ARCHITECTURE.md §Add-ons — this is exactly the kind of deviation the project's
docs rule says must be recorded.

### C3 — A Task has no kind or payload, and the page model is Asset-count based (**B**)

- `domain/Domain.kt:94` — `data class Task(val id: TaskId, val createdAt: Long)`. No title, no kind,
  no structured fields.
- `data/TaskEntities.kt:47-64` — `tasks(id, createdAt, publicId)`. Nothing else.
- `data/TaskEntities.kt:100-137` — an Asset is the only content a Task has.
- `ReviewScreen.kt:1077-1078` — `taskPageCount(assetCount, hasAddOn) = assetCount + (addOn ? 1 : 0)`.

A structured Task with zero Assets therefore has **zero pages**. `reviewBlockFor` already lets a
zero-Asset Task through (`Catalog.kt:61-71`), but the review surface would draw nothing, the page
navigator would have nothing to step, and the two-level queue guide just shipped would show **no
second level at all** — its page list is `reviewPageEntries(assets, addOn)`, and `hasPageLevel` is
`pageCount > 1`, so the arrow never appears.

**Q3.** Is the structured payload a **page** of the Task, or is it the Task surface itself with no
Asset navigator? The proposal does not say.

**I.** Recommended: make the payload **page 0** of the Task. Then:

```text
pages = [payload page, if content kind is structured]
      + assets, in order
      + add-on page, last
```

`taskPageCount` gains a `hasContentPage` input; `reviewPageEntries` names the payload page (e.g. the
Task's import display name or the payload's declared title); the queue guide gets a row for it; and
forward from the last page still leaves the Task exactly as today. The alternative — a Task surface
outside the page model — breaks "the Asset Navigator is always present as a boundary" and every
gesture the review surface already has.

Also note: `ReviewItem.target` is `DecisionTarget.File` or `ManualItem`
(`data/ListEntities.kt:598-601`). A structured Task in a List needs a third target variant (or a
nullable file target) and every `when` over it changes. That belongs in the same design.

One subtle write-boundary consequence: `contentPresentable` is
`assetsForTask(taskId).all { … }` (`data/ListsDao.kt:3126-3130`), so for a zero-Asset Task it is
**vacuously true**. Without a composition check at the same boundary, a structured Task could be
submitted with no viewer able to read its payload. The proposal's §5 resolution must be enforced
there, not only in the UI — the same rule `submitReview` already follows for media.

### C4 — ReviewEvent provenance is thinner than the proposal assumes (**C**)

`ReviewEventEntity` stores one `templateId`/`templateVersion`, the response text and the context.
It does **not** record the Asset Viewer version, the per-Asset control version(s), any add-on
version, or a task-content revision. The proposal's §4.11 and §6.3 require "enough schema/template/
add-on version provenance" and "enough task-content provenance (such as Package/task revision or a
content snapshot/hash)" for old answers to remain interpretable.

**I.** Before Phase A's "canonical contracts", define the event's provenance envelope:

```text
review_event_provenance
  taskRevision        (opaque, declared by the package/importer)
  taskReviewTemplate  (id @ version)          // exists today
  viewer              (id @ version)          // new
  assetControls       (per Asset: id @ version) // new; the media path currently does not store this
  addOns              (id @ version)          // if the C2 decision keeps a snapshot
  responseVersion     (how to decode the payload)
```

Old rows cannot be backfilled: do not invent a viewer or an add-on version they never recorded. A
decoder must therefore handle "provenance absent" as a first-class case. Note that the current
response text format changed in v0.5.4 **without a migration** (`domain/ReviewResponse.kt:56-61`);
any new decoder has to recognise at least `completed;selections` and `completed;favorite;actions`,
or declare old events readable-but-not-decodable rather than guessing.

### C5 — "Zero or more Add-ons" vs one per Todo (**C**)

Proposal §5 composes "zero or more Add-on Versions"; the shipped schema allows **at most one**
(`sources.addOn`, `task_imports.addOn`, primary key `(taskId, questionId)`). v0.7.1 explicitly
deferred multiple add-ons because "there is no position or ordering model that would let two
coexist" (`data/ImportEntities.kt:154-169`).

If v0.8 lifts that, it needs, at minimum: an ordering rule, a namespace per add-on, primary key
`(taskId, addOnId, questionId)`, an export shape for several `addOn` objects (array? namespaced
object?), and a page order for several questionnaires. None of that is in the proposal.

**I.** Recommended: keep **one add-on per Task** through v0.8. Shape the composition contract as if
there could be several (namespaced fragments), but do not change the storage or UI until a concrete
workflow asks. Say so in §5; otherwise the plan promises more than the schema can hold.

### C6 — §10.1 is right about not inventing history, but silent about existing history (**C**)

> "Existing checked/action state seeds TaskState only. Do not create historical Responses or
> ReviewEvents for actions whose submission history was never stored."

Read narrowly, this is correct: a chip toggle was never a submission, and a migration must not mint
an event for one. But the app **does have stored ReviewEvents** (`review_events`, written by
`submitItemReview` since the review engine landed), and the plan does not say what happens to them.
The migration section needs a third sentence: preserve existing events, map their `MediaReview`
response into the new Response shape as far as it goes, and record that viewer/control/add-on
provenance is *absent* rather than inventing it. Completion ticks that came from a List page are
state-only and must stay that way; that part of the sentence is right.

### C7 — Export history immutability (**C**, but easy to honour)

ARCHITECTURE.md:504-518 says a history row "resigns its original bytes rather than being re-rendered
under the new contract". The proposal's mapping seam is compatible, but state it: a new mapper must
never re-render an old row, and the legacy adapter must keep producing byte-identical v2/v3/v4
bodies for the same round. Add a golden-bytes test for each version.

---

## 2. Section-by-section annotations

### §1 Purpose — **Agree**

The diagnosis ("a file appears to be the source, imported content, Task and review item at once")
is accurate for the media path. One correction: the media surface no longer *only* displays
content; v0.7.0/v0.7.1 deliberately split the static frame from template controls
(`docs/ARCHITECTURE.md:389-448`). The proposal should acknowledge that seam, because it is the
precedent the Structured Task Viewer builds on.

### §2 Architectural thesis — **Agree, with one qualifier**

> "Listea does not infer what an upstream file 'really is'."

This is already half-true in code: `assetKindOf` is a **name-based** classification used for
eligibility and controls, and the proposal itself allows the file/folder importers to keep their
explicit grouping rules. The qualifier to add: **existing media importers may keep inferring kind
from the filename**; the rule applies to *protocols that declare roles*. Otherwise the proposal
reads as a demand to rewrite the media path, which it explicitly says it is not doing.

### §3 Invariants — **Agree**, with two notes

- Invariant 3 ("Review surfaces never invent Task boundaries") is already enforced: `plan()` is a
  pure function and `runImport` is the only writer (`Importer.kt:9-24`, `data/ListsDao.kt:2723`).
- Invariant 10 ("Viewers do not own Task lifecycle") should be extended to **structured viewers**:
  a recall-card viewer must not decide that revealing the answer is a submission, and it must not
  own the Task navigator. The proposal says this in §4.8 but not in §3.

### §4.1 Upstream producer — **Agree**

Not persisted, external, may supply prompts/choices/resources/stable keys. Good. Add one invariant:
the producer must not be writable by Listea — the package is read-only input, matching the
read-only Asset rule. This also rules out a future "write the answer back to the source" feature
without a new decision.

### §4.2 Source — **Conflict, see C1**

Beyond the naming: the proposal gives a Source "discovery and refresh configuration" and
"checkpoints or revision information". The current Upstream has none of that; the Todo has the
follow-upstream flag and the last-import summary. Decide which entity owns refresh state. My
recommendation: Upstream owns *availability and last scan*; Todo owns *follow-upstream and content
policy*; the Package owns *revision*. Three different questions, three different places.

Remote feed Sources are a non-goal for v0.8 in my view: SAF folder + package file is enough to
prove the seam. Say so, or the security and permission surface grows without a workflow.

### §4.3 Package — **Agree on the concept; needs a minimal spec in v0.8**

The idea of a declared envelope is sound and it fixes a real ambiguity in "folder-as-Task": a
manifest can say which members are Assets and which are Resources. But §9 Phase B defers the
protocol, while Phase E needs fixtures. That is a circular dependency.

**I.** Write the minimal `.listea` envelope **before** Phase B code, as a short normative document
(protocol version, manifest, task records, member roles, stable references, diagnostics, size
limits). Suggested starting point:

```json
{
  "protocol": "listea.package/1",
  "package": { "key": "…", "revision": "…" },
  "tasks": [
    {
      "key": "vocab-1",
      "kind": "structured.single-choice",
      "schema": 1,
      "title": "…",
      "payload": { … },
      "assets": [ { "key": "a1", "member": "media/a1.jpg" } ],
      "resources": [ { "key": "r1", "role": "audio" } ]
    }
  ],
  "resources": [
    { "key": "r1", "kind": "audio", "member": "media/r1.mp3", "revision": "…", "required": false }
  ]
}
```

Rules worth fixing now, because the codebase already has a convention for them:

- unknown **protocol version** → the whole package fails, nothing imported (fail closed, like
  `decodeExportChoice` and `decodeAddOnQuestions`);
- unknown **content kind** in one record → that Task is unavailable with a diagnostic, the rest of
  the package imports;
- absolute paths, `..`, symlinks, duplicate member names, and oversized members → rejected;
- bounded JSON depth/size, bounded archive expansion;
- no code execution, no remote fetch, no writes outside the app cache.

### §4.4 Importer — **Agree**, but the current type is too narrow

`plan(kind, importRoot, snapshot: FolderSnapshot)` is pure and returns
`PlannedTask(groupPath, displayName, assets)`. A package importer cannot use that signature: its
input is a manifest, its output has kind, payload, resources and an upstream key, and it may have
zero Assets.

**I.** Do not extend the `when` over `ImporterKind` with an overload that needs a different input
type. Introduce:

```kotlin
sealed interface ImportInput { data class Folder(val snapshot: FolderSnapshot) … ; data class Package(val manifest: …) }
sealed interface PlannedContent { data class FileTask(…) ; data class StructuredTask(… kind, payload, resources) }
interface Importer { fun plan(input: ImportInput): ImportPlan }
```

Keep `plan()` pure and JVM-tested; keep file reading and archive extraction in an adapter. This is
also the cross-platform boundary the roadmap asks for.

`file-as-task` and `folder-as-task` stay byte-identical; the release must not change how a current
folder import groups Tasks.

### §4.5 Task — **Agree in principle, but this is the largest new surface**

"versioned structured payload fields" needs a storage decision. Do **not** add typed columns per
workflow. Recommended:

```text
task_payloads(taskId PK, kind TEXT, schemaVersion INTEGER, payload TEXT, revision TEXT, updatedAt)
```

or two nullable columns on `tasks` if you prefer no join. Core treats `kind`/`schemaVersion` as
opaque strings for equality/range checks only; the payload parser lives with the viewer adapter.
This keeps invariant 9 ("capability-oriented") honest and keeps `domain/` free of Android.

Also: the Task still needs a **queue label**. Today the title comes from `task_imports.displayName`
or `list_items.title`. Decide whether a structured Task's `title` is import provenance, payload
content, or both. Recommendation: import `displayName` remains the navigator label; the payload's
prompt is content rendered by the viewer; if a package supplies no label, the importer must supply
one (not Core synthesising from payload semantics).

**One naming caution.** The proposal's examples mix two different axes: `structured.single-choice`
is a payload *schema* (how to read the fields), while `structured.recall-card` is a workflow
*viewer* (what the card looks like). Keeping both in one `kind` string invites the `recall-card`
branch into Core — the thing invariant 9 forbids. Recommendation: `contentKind` is either a
capability schema (`structured.choice@1`) or a **protocol-namespaced** workflow id
(`com.example.vocab/recall-card@1`); the registry maps the latter to a viewer and a template, and
Core only compares strings. `AssetKind` stays what it is (the media kind of an *Asset*) and is not
reused for structured kinds; `AssetKind.OTHER` is not a payload kind.

### §4.6 Asset — **Agree**, with a locator caveat (see G3)

The Asset definition is unchanged and that is right. The sentence "never becomes a queue entry by
itself" matches the current queue model. What is missing is a locator model for package members
that are not SAF files — see G3.

### §4.7 Resource — **Agree; the distinction is well argued**

The role-based Asset/Resource split is the strongest part of the document. Two implementation
notes:

- Resources must not appear in `assets` or in `taskPageCount`, or every count, missing-file badge,
  chip row and export `assets[]` acquires an exception.
- Resource identity should be **package-scoped** initially (`(packageKey/sourceId, resourceKey)`),
  not global. Global sharing needs a lifecycle and a conflict story the proposal does not give.

### §4.8 Viewer — **Agree on the ownership split; add the code-side split**

The proposal correctly separates "render + emit intent" from "validate/commit". In this codebase a
"viewer" is already two things: a hashable `TemplateDefinition.AssetViewer` in `domain/` and a
Compose renderer (`MediaPreview`, `PdfPreview`) keyed by it. A Structured Task Viewer should follow
the same pattern:

- **domain side:** a `TemplateDefinition.StructuredTaskViewer(compatibleKinds, schemaRange,
  intents)` — immutable, hashed, referenced by the composition;
- **UI side:** a renderer registered by ref, in the app layer, free to use Compose.

Otherwise the viewer declaration will drift into the UI and stop being versionable.

### §4.9 Headless Review Template — **Agree**, but completion must become version-driven

Today `ReviewResponse.completesTask` is an exhaustive `when` over response types
(`domain/ReviewResponse.kt:95-97`) and `submitItemReview` always commits `completed = true`
(`ListsViewModel.kt:1622-1633`). A headless template that "may follow a valid submission with
automatic advance" cannot be expressed that way: whether a choice completes and whether it advances
are properties of the **template version**, not of the value type.

**I.** Put two booleans on the Task Review definition (or a small submission policy):

```text
commitOn: named intent(s)
completesTask: Boolean
advanceAfterCommit: Boolean
```

Keep `TaskAction` as the media adapter's intent vocabulary; the headless path uses named intents
with typed values. Do not let the UI decide advance.

### §4.10 Add-on — **Conflict with shipped semantics; see C2 and C5**

Also note §4.10 says "If required, the enclosing Review Template validates its fragment before
commit." v0.7.1 deliberately has **no required flag** and the completion checkbox remains a direct
path (`docs/iterations/v0.7.1.md`). Adding "required" is an additive product capability, but it
must be stated as one, not slipped in as composition detail.

### §4.11 Response — **Agree as a target; gaps in the mapping**

The example `primary` + `addOns` has no place for **per-Asset decisions**. Today
`ReviewResponse.MediaReview` is `(completed, selections)` where `selections` is the **fold** of the
Task's pair records (`ListsViewModel.kt:1612-1627`). If a Task has several Assets and each carries
its own tags, a canonical Response that only has a flat `primary` loses which file carried what.

**I.** Define the media adapter explicitly, e.g.:

```json
{
  "primary": { "action": "complete", "value": true },
  "assets": [
    { "assetId": "…", "selections": ["custom1", "favorite"] }
  ],
  "addOns": { … }
}
```

Namespaces become `assets.<assetId>.…`, which also gives Add-ons a natural collision rule. Keep the
encoding canonical (sorted ids, no blanks) as `ReviewResponse.encode()` already does.

**Good news:** the atomic commit the section asks for already exists at the write boundary.
`ListsDao.submitReview` is `@Transaction`, refuses when the content is not presentable, writes state
and event together, and returns the Lists that completed (`data/ListsDao.kt:416-445`). The work is
to generalise it into a submission service with a versioned Response, not to invent the transaction.

### §6 Synchronization and reconciliation — **Agree on the graph rule; map it onto the existing tiers**

§6.1 is the right rule ("follow the declared graph, not every file") and it is consistent with the
shipped `keep`/`reset`/`drop` policy being about Asset-set changes only. Two things to add:

- **Map package reconciliation onto the existing `ContentPolicy` tiers** rather than inventing a
  second policy. A resource-only change is `keep` by definition (no Asset-set change, no Task
  created); a declared Task removed is `keep` (flag) unless the user named `drop`; a content
  revision is a *new* kind of change the current policy does not name — decide whether it is `keep`
  with a recorded revision or `reset`, and say so.
- **Missing Resource** should reuse `AssetEntity.sourceMissing`'s idea (a fact about content, not a
  judgment) on the resource row, so the UI and export have one vocabulary for "gone".

§6.2's identity list should include the **Todo/Source-row** scope explicitly (G12); as written it
lists "Source ID" but not the import intent that scopes the produced Tasks.

### §7 End-to-end workflows — **Coherent, but §7.1 mislabels the current actions**

§7.1 says "current media Task Review Template and optional action Add-ons". The shipped media
actions are **Asset Control buttons** (per-file templates), not Add-ons; Add-ons are the new
Task-level questionnaires. Using "Add-Ons" for the old action controls is exactly the double meaning
this review warns against (C2/C5). Rename them in the diagram: "per-Asset action controls".

§7.2–§7.4 are the right shapes, but each depends on primitives the proposal has not fixed:
- the recall card needs a page (C3) and a queue label (G7);
- the questionnaire needs option identity and a viewer/template contract (§4.5/§4.9);
- the final-assessment Add-on is the one place the current add-on model already fits, provided
  "required" is added as an explicit capability (§4.10).

### §4.12 Exporter — **Agree; the two stages are implementable now**

`Exporters.kt` already has an `ExportPayload`/`ExportTaskView` snapshot, `planExports`, and
`Exporter` implementations. The missing piece is exactly the pure stage:

```text
ExportTaskView (round snapshot) -> ExportMapping (pure) -> bytes -> delivery
```

**I.** Introduce `ExportMapping` as a pure function per contract, keep `WebhookExporter`/`TxtExporter`
as the delivery + legacy serialization adapters, and keep the existing "history stores bytes"
rule. Do not make the mapper read the database.

### §5 Compatibility resolution and review composition — **Agree; two notes**

- The resolution rules are right, and "fail closed / explicit diagnostic" matches the codebase's
  `decodeExportChoice` and `decodeAddOnQuestions` conventions. Reuse `TemplateResolver`'s shape:
  a resolution returns a value or a named failure, never a default.
- **Heterogeneous packages will hit the one-configuration-per-List rule immediately.** A package
  that declares both recall cards and questionnaires produces mixed Tasks in one Todo; a List today
  holds one `taskReviewTemplateId`. "Routed into separate Lists/contexts" is not a mechanism. For
  v0.8, either (a) the package importer splits a heterogeneous package into homogeneous Todos, or
  (b) Create List filters Tasks by compatible configuration and says so. Mixed-template Lists stay
  deferred, but the routing decision cannot be.

### §8 What changes — **Mostly agree; §8.1's premise is inaccurate**

> "The current architecture names raw Source and Importer but does not explicitly model a protocol
> envelope between them."

ARCHITECTURE.md does model the registered origin and the import intent — as **Upstream** and
**Todo** (`docs/ARCHITECTURE.md:564-672`), even though it calls neither one "Source". What is
genuinely missing is the **Package envelope**. Correct this sentence, and place the Package between
the Upstream and the Todo's importer run rather than between "Source" and Importer.

§8.2–§8.7 are sound as principles. §8.5 ("Add-on becomes composition") is the one that changes a
shipped ownership rule — see C2.

### §9 Plan — **Right order in outline; phases are too large and one dependency is inverted**

- Phase A's contracts are useful, but designing `Response` before deciding the add-on history
  question (C2) will produce a contract the shipped model cannot honour. Make that an explicit
  architecture decision at the start of Phase A.
- Phase B defers the `.listea` spec, but Phase E needs it. Write the minimal spec *in* Phase B.
- Phase C's "Headless Review Template" needs the commit seam (G13) and the content-page model
  (C3); without them it is a contract with no surface.
- Phase D can run earlier (the export mapping is independent of the package work) if you want an
  early, low-risk win.
- Each phase needs the project's usual artefacts: an iteration doc, a plan doc, schema JSON +
  migration test + fixture subtraction, JVM tests, and a device walk. The proposal does not mention
  them; the repo's process will fail without them.

### §10 Migration — see C6; three additions

- Reuse `legacy_item_ids` and `legacy_task_ids` for the compatibility mapping rather than inventing a
  new table; they already exist for exactly this.
- The media path needs **no** file-to-Asset migration: files are already Assets. Say so, so the
  section does not imply a rewrite.
- Existing Tasks have no upstream key. Do not backfill one from `groupPath`; leave them unkeyed and
  reconcile them by path as today (G12).

### §11 Acceptance tests — **Good list; add these**

- A structured Task with zero Assets appears in the catalog, opens, and has a labelled content page;
  the queue guide's level 2 lists that page.
- A Task with a payload plus Assets and an add-on pages in the declared order.
- The media export bytes for v2/v3/v4 are unchanged (golden).
- A package with an unknown protocol version imports nothing; a package with one unknown Task kind
  imports the rest and reports the one.
- A package with a `..` member or an oversized archive is rejected without writes.
- A re-import with an unchanged package revision writes nothing.
- Existing ReviewEvents survive migration with provenance recorded as "absent", not guessed.

### §12 Non-goals — **Agree**, add two

- Remote feed Sources / network package fetching.
- `.listea` archive (zip) support if the directory form is sufficient for the v0.9 fixtures; archive
  extraction is a security surface that deserves its own round.

### §14 Definition of done — **Make it measurable**

Add three checks that can be automated or walked:

- `grep` for workflow words (`vocabulary`, `questionnaire`, `recall`, `flashcard`, `candidate`) in
  `domain/` returns nothing but comments; no Core `when` branches on them.
- Every new contract has a JVM test; the domain package still has no Android/Compose dependency.
- The media device walk (the one in `docs/development.md` / the iteration docs) passes unchanged,
  including the two-level queue guide.

---

## 3. Gaps — things the proposal does not consider

### G1 — The Todo has no place in the model (**B**)

The proposal's path is `Source → Package → Importer`. The shipped path is
`Upstream → Todo (importer config, content policy, templates, add-on, export choice) → runImport → Tasks`.
The Todo is the unit that owns everything the proposal distributes across Source and Package. The
document never says which row holds the import configuration, which row owns the produced Tasks, or
whether one Todo can import several Packages.

**I.** State it explicitly in §4.3–§4.4. My recommendation: a **Todo remains the import intent**:
one Todo is one importer + policy + configuration over one Upstream/folder; a run discovers one or
more Packages and imports them into that Todo. A Package has no user-facing row. This preserves
content policy, follow-upstream, export choice and template/add-on stamping without a second
lifecycle.

### G2 — Catalog, selection and Quick Review for non-file Tasks (**B**)

- The catalog is `task_imports.groupPath` + folder tree (`Catalog.kt:8-42`).
- "Complete in a scope" is `Assets(Task) ⊆ Assets resolvable in Scope`
  (`ARCHITECTURE.md:649-659`).
- Quick Review is direct-child-of-scope over files (`Catalog.kt:29-42`).

A structured Task has no Assets and no folder. `reviewBlockFor` returns null for zero Assets, so it
is *eligible*, but it cannot be **found** or **selected**: there is no scope algebra, no catalog
row, and `DecisionTarget` has no case for it. Phase E cannot be reached without designing:

- a virtual `groupPath` (or a separate package scope) for the Todo's catalog;
- "complete in scope" for a Task with no Assets (probably: complete when its Package is available);
- a selection/picker row that does not count files;
- Quick Review over a scope that contains structured Tasks.

### G3 — Asset locators beyond SAF files (**B** for packages with embedded media)

`AssetEntity` locates content by `(rootUri, relativePath)` plus `sourceUri`. A package member may be
inside a zip or directory that is not a SAF file, and an embedded member has no `relativePath` in
the Upstream. Do not overload `rootUri` with a package path: `sourceMissing`, the Info sheet, export
`relativePath`, and the uniqueness index all read that pair.

**I.** Add a locator kind to the Asset model (file | package-member | embedded) and resolve through
an adapter. Keep `rootUri`/`relativePath` populated only for file-backed Assets so current
behaviour is untouched. Package-relative assets need their own missing/unavailable state.

### G4 — Security of Package parsing and extraction (**B**)

The non-goals mention "no arbitrary code execution", but not: zip-slip/path traversal, symlinks,
decompression bombs, duplicate member names, oversized JSON, deeply nested payloads, encoding,
or how `.listea` as an archive is extracted. Listea already reads untrusted local files; a Package
protocol makes "this file declares Tasks/Resources" a higher-value input. Add explicit limits and
fail-closed rules to §4.3 and §12.

### G5 — Cross-platform / JVM-domain constraint (**C** with the roadmap)

`docs/roadmap.md` §2 and `docs/development.md` make `domain/` framework-free and JVM-tested. The
proposal's new contracts (Response, Package manifest, composition, capability) belong in that
package; package discovery, JSON, archive extraction and resource resolution belong in adapters.
The document does not say where the code boundary is, which is how the roadmap's goal gets lost.

**I.** State the package map in the plan:

```text
domain/      pure contracts and encodings; no Android, no org.json
data/        Room entities, DAOs, migrations
import/      pure planners + platform discovery adapters
export/      pure mappings + delivery adapters
```

Every new contract gets JVM tests, exactly as `MediaQueueGuideTest` and the existing domain tests do.

### G6 — Schema, migration fixtures and minSdk conventions (**I**)

Every schema change in this project comes with a Room schema JSON, a `MIGRATION_n_n` test, and a
fixture helper (`subtractV29Additions` etc.). `minSdk 24` means no `ALTER TABLE … DROP COLUMN`;
the convention is to keep dead columns rather than rebuild (see `TaskStateEntity.isFavorite`). The
plan should name schema 30, the migration test, and the fixture subtraction, or the release will
break the migration suite late.

### G7 — Queue label for structured Tasks (**Q**)

See §4.5. The navigator needs a one-line label; the payload prompt may be long. Decide whether the
queue shows the import `displayName`, a payload `title`, or the List entry's title. Keep Core out of
it.

### G8 — The two-level queue guide just shipped (**I**)

`MediaQueueMenu.kt` builds page rows from `reviewPageEntries(assets, addOn)`. A structured Task needs
a page row for its payload page, or there is no level 2 at all and the arrow disappears
(`hasPageLevel` is `pageCount > 1`). This is a small, concrete integration point; include it in the
fixture work and in the page-model tests.

### G9 — v4 export compatibility (**C**)

If Response gains an `assets` section and add-ons remain state (C2), the legacy `addOn` object must
still be produced from current state, and the legacy `actions`/`marks` arrays must be unchanged.
Define which exporter version carries which shape; do not let the new canonical Response leak into
v4.

### G10 — What "degraded" looks like (**Q**)

The proposal says a missing required Resource makes the Task "unavailable or degraded with an
explicit diagnostic". The app already has `ReviewBlock` (`MISSING`, `NO_VIEWER`) and import notices.
Map the new diagnostics onto those; decide whether a missing optional audio resource disables only
the play control, and whether a required one blocks submission or only the affected page. Without
this, the v0.9 fixture cannot pass its acceptance test.

### G11 — Package lifecycle and refresh (**Q**)

If there is no durable Package entity (§13 Q1, my answer: none in v0.8), how does a refresh know it
is looking at the same package? Store `packageKey`, `packageRevision`, `protocolVersion` on the Todo
and per-Task provenance; compare revisions before importing. Define what happens when the revision
is unchanged (no writes), changed (reconcile), or absent (fall back to folder reconciliation).

### G12 — Scope and conflict rules for upstream keys (**B**)

The same file can back two Tasks in two Todos today (`ARCHITECTURE.md:721-730`). If an upstream Task
key is global, two Todos importing the same package would collide. Define the key's scope: Todo
(import intent) or Package. Recommend `(Todo, packageKey, taskKey)` unique, stored on
`task_imports`; only when the protocol supplies it. Do not synthesise a key from `groupPath` — that
would make a path an identity by another name. Also define how keyed and unkeyed Tasks coexist in
one Todo during migration.

"Reusing a key for semantically different work is a conflict" needs a comparison. Current Assets
have size/lastModified but no hash. Use the package's declared `revision`/`contentHash`; if absent,
compare the normalized payload bytes for structured Tasks and report a conflict for media rather
than silently replacing.

### G13 — The commit seam is not named (**I**)

Today the commit path is `ListsViewModel.submitItemReview` → `dao.submitReview`. A headless template
must not call the DAO directly. Introduce one service:

```kotlin
data class ReviewSubmission(taskId, template: TemplateVersionRef, response: ReviewResponse, context)
fun submit(submission): Result   // validates, appends event, projects TaskState, in one transaction
```

The media screen keeps its current actions but calls this service; the structured viewer calls the
same service. This is the smallest change that makes §4.9 real and is testable on the JVM.

### G14 — Name collision: `ReviewResponse` vs Response (**I**)

The code already has `ReviewResponse` (a sealed interface), and the proposal introduces a second
`Response`. Either extend the existing type (new members + a versioned encoding) or rename one, and
write the mapping table. Two types called Response will cost more than the abstraction saves.

---

## 4. Answers to the proposal's §13 open decisions

1. **Durable Package entity?** No in v0.8. Persist `packageKey`/`revision`/`protocolVersion` as
   provenance on the Todo and `task_imports`; no `packages` table, no lifecycle, no GC. Revisit only
   when package-level retention or a package diagnostics screen exists.
2. **Resource identity scope?** Package/Source-scoped: `(scopeKey, resourceKey)` unique, referenced
   by `task_resources(taskId, resourceId, role)`. Global sharing is a later feature with a conflict
   story you do not need yet.
3. **How much Task payload in event provenance?** For structured Tasks: the normalized payload
   revision plus the **selected value and its label/value definition**; optionally a bounded prompt
   + options snapshot (e.g. ≤ 8 KB) so the event is interpretable without the source. Never snapshot
   media bytes. For media Tasks: keep the existing per-Asset selection snapshot and add the
   viewer/control versions (C4). Older events without provenance stay readable as "provenance
   absent", not guessed.
4. **Where do current custom actions map?** The **primary media Task Review Template**, not an
   Add-on. They are per-Asset marks, recorded in the Response's `assets` section, and the legacy
   wire values live only in the compatibility adapter. Add-ons are Task-level questionnaires; do not
   give the word a second meaning.
5. **Which primitives?** `singleChoice(options)`, `multipleChoice(options)`, `boolean`,
   `number(min, max, step)`, `text(maxLength)`. A recall grade is `singleChoice` with four declared
   options; a rating is `number(1..5)`. No `recall`/`vocabulary` member in Core.
6. **What should exporter mapping consume?** The **round snapshot** (`ExportPayload` /
   `ExportTaskView`) for v0.8, because delivery and history already use it. Add a pure
   `ExportMapping` stage per contract. Do not switch to individual events; if a future exporter
   needs events, make that an explicit per-exporter choice.
7. **Degraded behaviour?** Optional Resource missing: render the page, disable the affordance, show
   a notice; submission remains possible if the template does not require it. Required Resource
   missing: block submission with a `ReviewBlock`-style diagnostic, keep state/history, do not drop
   the Task. The package declares `required`; default optional.
8. **Capability registry?** A code-defined registry like `Templates` (ids, versions, compatible
   kinds/schemas, hashes), closed and JVM-tested. Unknown capability → diagnostic, not a guess. No
   plugin runtime.

---

## 5. Suggested reduced, implementable v0.8.x sequence

The proposal's Phase A–E is a target, not a release plan. A smaller sequence that keeps the seams
and ships something verifiable:

### v0.8.0 — Contracts behind the current UI

- Pure `domain/` contracts: `ContentKind`, `PayloadSchema`, `ReviewResponse` (versioned encoding),
  `ReviewComposition`, `CapabilityRegistry`.
- One `ReviewSubmission` service; the media path calls it, behaviour unchanged.
- No schema change (or one nullable `task_payloads` table if you want the storage seam early).
- Tests: the existing media suites + contract tests (navigation creates no event; an answer creates
  exactly one; a state edit creates none). No UI change.

### v0.8.1 — Upstream, Package, Resources

- Decide the vocabulary (C1). Extend Upstream with refresh state; keep the Todo as the import
  intent (G1).
- Minimal `.listea` envelope spec; folder-based package discovery only; Resources table and
  resolution; provenance columns; reconciliation by upstream key with `groupPath` fallback (G12).
- Schema 30 + migration test + fixture subtraction.
- Tests: unchanged re-import, new Task, resource-only change, missing Resource, key conflict.

### v0.8.2 — One structured page and one fixture

- Payload page 0 (C3), catalog/selection/Quick Review for zero-Asset Tasks (G2), `DecisionTarget`
  variant, queue-guide page row (G8).
- One Structured Task Viewer (single choice) and the `headless-answer` template.
- Export v5 mapping for the fixture; legacy v4 golden test unchanged.
- Device walk: one package fixture, answer recorded, event written, export produced, media rounds
  unchanged.

### v0.8.3 — Export mapping seam and the recall-card fixture

- Pure `ExportMapping` per contract; recall card with a shared pronunciation Resource; diagnostics.
- Cross-platform package map documented; every new contract JVM-tested.

**Defer from v0.8:** multiple add-ons, required add-ons, add-on answers inside ReviewEvents (until
the C2 decision is recorded), mixed-template Lists, remote feed Sources, `.listea` archive/zip
support if the directory form is enough for the fixtures.

---

## 6. Questions for the author

1. Is "Source" a deliberate rename of Upstream, or a new name for the registered origin? If the
   former, what happens to the `sources` table (Todos) and the three live senses of the word?
2. Do add-on answers become event history, or stay current state with an optional snapshot? Which
   one does v0.9 need?
3. Is the structured payload a page of the Task or the Task surface itself? What does the queue
   guide show for a Task with zero Assets?
4. Where does the Todo live in the new diagram, and can one Todo import several Packages?
5. Is one add-on per Task still the v0.8 rule?
6. What is the minimal `.listea` manifest, and which version is frozen for the v0.9 fixtures?
7. What is the scope of an upstream Task key, and what counts as "incompatible content"?
8. Which parts of this proposal are you willing to defer to v0.9 to make v0.8 shippable?

---

## 7. 评审摘要（中文）

- **方向正确**：Package / Importer / Viewer / Review Template / Response / Exporter 的拆分，与
  ARCHITECTURE 的“能力导向、专业语义不进 Core”一致，值得作为 v0.9 的目标架构保留。
- **最大冲突一：Source 这个词已经被退役**。ARCHITECTURE 明确说 Source 不是存储概念、不面向用户；
  数据库里的 `sources` 表存的是 Todo，已注册的来源叫 Upstream。方案 §4.2 描述的 Source 实际就是
  Upstream + 刷新状态。需要先改名或改用 Upstream，否则代码里会出现第四种 Source。
- **最大冲突二：add-on 的模型**。v0.7.1 把 add-on 答案做成 Task 的**当前状态**（同题覆盖、隐藏不删、
  导出当前可见答案），方案却把它并入不可变的 Response / ReviewEvent。这是“历史 vs 现状”的产品决定，
  不是重构细节，还会改变 export v4。
- **最大缺口：结构化 Task 在代码里还不存在**。Task 只有 id/时间/publicId；页面模型是
  `assets + 可选 add-on 页`，零 Asset 的 Task 是零页；catalog、Quick Review、DecisionTarget 都按文件
  设计。需要先定义“内容页”，再做 v0.9 fixture。
- **其他缺口**：Todo 在新流程图里缺席；Package 内嵌媒体的定位方式未定义；Package 解析的安全边界
  （zip-slip、大小、深层 JSON）未写；跨平台/JVM 的包边界未写；上游 key 的作用域与冲突判定未写；
  多个 add-on 与现有一 Todo 一个的规则冲突。
- **建议缩表**：v0.8.0 只做契约 + 提交通道（不改 UI）；v0.8.1 做 Upstream/Package/Resource 与最小
  协议；v0.8.2 做“一个内容页 + 一个 single-choice fixture”；v0.8.3 做导出映射与 recall-card
  fixture。多 add-on、必需 add-on、add-on 入事件、混合模板 List 全部推迟。
- **可直接实施的点**：导出映射两段式（现有 ExportPayload 已具备条件）；ReviewSubmission 单一提交通道；
  资源表与 package 作用域身份；能力注册表沿用 Templates 的代码定义 + hash；最小 `.listea` 清单；
  迁移沿用现有 schema JSON + fixture 惯例。

这份批注按“冲突 / 缺口 / 可实施 / 问题”分层，意图是保留方案的方向，同时把 v0.8 的范围压到
当前代码能够承接的大小：一次重构不同时改动身份、历史、页面和导出四条主线。
