# Listea v0.8.x Framework Restructuring Proposal

> **Status:** Revised proposal for implementation-agent review; not an implementation instruction  
> **Revised:** 2026-10-23  
> **Baseline:** [ARCHITECTURE.md](../../ARCHITECTURE.md), revision 2026-10-21, including its v0.7.1 Add-ons section  
> **Implementation reference:** [Implementation review](LISTEA_V0.8_FRAMEWORK_RESTRUCTURING_PROPOSAL-review.md), describing v0.7.1, Room schema 29, and export contracts through v4  
> **Target:** v0.8.x framework preparation for v0.9.x vocabulary and questionnaire support  
> **Scope decision:** Add-on structure, placement, ownership, update path, and export relationships remain as shipped. Add-on restructuring is excluded from v0.8.0 and is not scheduled elsewhere in this proposal.

## 1. Purpose and baseline

Listea remains a human-judgment layer between prepared inputs and downstream consumers. v0.8.x should let structured content reach the same review engine as existing media, while preserving the architecture and behavior already established through v0.7.1.

The starting point is no longer the September architecture or a one-file/one-item prototype. The current baseline already separates Upstream registration, Todo import intent, Tasks, Assets, Lists, and review history. It already has per-Asset controls, PDF passage marks, stored immutable template versions, a round export snapshot, separate export mechanisms, and Task-level Add-ons. These are existing responsibilities to preserve.

The remaining gaps identified by the implementation review are concrete: Task storage has no structured kind/payload; import plans assume folder snapshots; catalog selection and decision targets assume files; and review pages consist of Assets followed by an optional Add-on. A zero-Asset structured Task therefore needs an explicit content page, catalog representation, and submission compatibility check.

This revision retains the original proposal's protocol, rendering, judgment, and export boundaries, but rebases them onto those existing seams. Code locations and schema observations below are taken from the supplied implementation review; implementation planning must verify them against the checkout used for each release.

## 2. Architectural thesis and complete flow

For a protocol that declares member roles, Listea interprets those declarations through a supported Importer. Existing file/folder importers retain their current filename-based kind recognition and grouping behavior.

Prompts, option labels, legal values, and resource references are upstream content. They become a human response only when the reviewer submits a choice through the review contract.

The import intent is a **Todo**. The registered material origin is an **Upstream**. A **Package** is the new protocol envelope interpreted during that Todo's import run.

```text
External producer / source material
                 |
                 v
             Upstream
       registered location + access
                 |
       Todo selects a folder/scope
       importer + configuration + content policy
       follow-upstream + templates + existing Add-on + export choice
                 |
                 v
         discover Package(s) / files
                 |
                 v
         pure Importer plan -> runImport reconciliation
                 |
                 v
     Todo catalog: Tasks + payloads + Assets + Resource references
                 |
       select complete, compatible Tasks
                 |
                 v
          Quick Review / List
                 |
                 v
          Task review pages
     [content page?] -> [Asset pages] -> [existing Add-on page?]
            |                  |                    |
     Structured Viewer    Asset Viewer +      existing answer
     + Headless Template  Asset Controls      update path
            |                  |                    |
            +--- primary submission ---+     task_review_answers
                                       |        current state
                                       v
                              ReviewSubmission service
                                       |
                              TaskState + ReviewEvent

Round export snapshot combines current Task/Asset results
and the existing projection of visible Add-on answers
                 |
          pure ExportMapping
                 |
       serialization -> delivery -> stored original bytes
```

The two answer paths are deliberately explicit. The primary submission path is generalized for structured Tasks. The existing Add-on current-state path retains its meaning and remains outside the new Response/event contract.

## 3. Invariants carried forward

1. **Task is the durable judgment target.** It may have zero, one, or many Assets. A structured payload does not create a new lifetime category.
2. **Domain identity, upstream identity, membership, and wire identity stay distinct.** Preserve Task/Asset domain IDs and their stored `publicId` arrival IDs. Paths and array positions remain locators or ordering metadata.
3. **Todo scopes import intent.** Two Todos over one file produce independent Tasks and task-specific Assets. Two Lists over one Task share its state.
4. **Import and review selection remain separate.** Only the importer defines Task boundaries. Catalog browsing, Quick Review, and Create List select whole normalized Tasks.
5. **Navigation is pure.** Task navigation, page navigation, reveal, playback, and inspection do not submit or complete a Task.
6. **State and history retain different meanings.** Task completion is Task-level; selections and PDF marks concern the Task–Asset pair; Add-on answers are Task-owned current state. None of these state edits automatically becomes a ReviewEvent.
7. **A review submission is an explicit commit.** Validate and atomically append its event and update the permitted state projection. An administrative checkbox edit remains state-only.
8. **Lists own membership and order.** Deleting a List removes its context and entries, not underlying Tasks or their judgments. Round queues remain stable snapshots; resume uses stable identities.
9. **Interpretive versions are immutable.** Historical events record what was used. Current visibility/export of Asset controls, PDF marks, and Add-on answers continues to use the versions governing them now, including existing first-match and hidden-not-deleted rules.
10. **Content policy remains explicit and per Todo.** Preserve `keep/reset/drop` and their documented exceptions. A change without an applicable, user-named policy erases nothing. This proposal does not replace that rule with an absolute claim that history can never be deleted.
11. **Viewers render and emit intent.** Neither Asset nor Structured Viewers own Task identity, import grouping, Task navigation, completion policy, or delivery. Existing template-controlled marking operations keep their current state semantics.
12. **External content remains read-only.** PDF marks and answers stay in Listea. Package import and review do not write back into the input directory.
13. **Export history preserves sent bytes.** A resend uses the stored original body, even after a new mapper or contract version ships.
14. **Core expresses capabilities.** Framework-free domain contracts must not branch on vocabulary, questionnaire, recall, or other workflow-specific meanings.

The latest [architecture](../../ARCHITECTURE.md) remains authoritative for unchanged behavior, including PDF anchoring and rendering, Todo/List export precedence, and the bounded destructive effects of an explicitly selected content policy.

## 4. Definitions and responsibilities

### 4.1 External producer and Source

An **upstream producer** prepares the material: for example, a vocabulary scheduler, a survey authoring system, or a person maintaining a folder. Its business logic remains upstream.

**Source** retains the architecture's meaning of external material, not a new persisted entity or user-facing registered origin. The existing database table `sources` holds **Todos**. This proposal adds no second Source model and does not require renaming that table or resolving every legacy use of the word.

A producer may declare prompts, choices, references, and stable keys. It cannot declare that the reviewer has submitted an answer, choose an export destination, or inject executable behavior into Core.

### 4.2 Upstream

An **Upstream** is registered material access: stable identity, granted root locator/access, display name, and provenance. It owns access and availability, not work or completion.

If discovery requires additional metadata, Upstream may record access availability and last scan information. Todo-specific follow-upstream choices, import checkpoints, policies, and export settings stay with the Todo/import provenance. One Todo's scan must not advance another Todo's reconciliation checkpoint.

v0.8.x supports the existing local granted folder path. Remote feed registration and network Package fetching are outside this proposal's implementation scope.

### 4.3 Todo and Package

A **Todo** is the existing import intent and container for the resulting catalog. It retains its importer kind/configuration, selected Upstream/folder, content policy, follow-upstream choice, attached template versions, existing optional Add-on reference, and export choice/address. Tasks remain linked to the Todo through import provenance; Lists are separate review contexts.

One Todo may discover **one or more Packages** under its selected import scope. This does not create another user-facing lifecycle.

A **Package** is a protocol envelope: a manifest plus declarations of Task records, Assets, Resources, keys, and revisions. It tells the Importer which members define work and which support that work. It is not a Task, Todo, List, or review session.

For v0.8.x:

- use a directory containing the minimal `.listea` manifest and local members;
- do not introduce a `packages` domain entity, package ownership UI, or garbage-collection lifecycle;
- retain Package key, protocol version, revision/fingerprint, and locator as Todo-scoped import provenance;
- retain Package/task provenance on each imported Task;
- represent checkpoints per Package within a Todo, rather than one `lastPackageRevision` scalar that would lose multi-Package information.

A checkpoint collection or small import bookkeeping table is an implementation choice; it must not become a second Task owner. The chosen persistence is documented before the v0.8.1 migration.

The minimal protocol must be written and fixture-tested during v0.8.1, before structured fixtures depend on it. It defines protocol version, manifest filename, Package key, Task keys, kind/schema, title, payload, ordered Asset references, Resource definitions/references, and diagnostics. This proposal specifies its boundaries, not its final byte-level grammar.

### 4.4 Importer

The **Importer** is a pure translator from a supported input representation to an import plan. Platform adapters read the folder/manifest and resolve content; `runImport` applies reconciliation and remains the writer of imported content records.

The current folder planner cannot be expanded merely by adding a workflow case to a switch expecting `FolderSnapshot`. Introduce equivalent typed inputs and planned outputs:

```text
ImportInput = FolderSnapshot | validated PackageManifest
ImportPlan  = planned Tasks/content/dependencies + diagnostics
Planned Task = keys/provenance + label + optional payload + ordered Assets + Resource refs
```

Keep existing file-as-Task and folder-as-Task grouping unchanged. Add a Package Importer that interprets declared roles and can produce zero-Asset structured Tasks.

It validates supported protocol versions, member references, key uniqueness, and payload envelopes, then plans reconciliation. It does not render, select a List, commit answers, or execute exports.

Package parsing and reference resolution are stages inside this import boundary. They do not require a second independent import lifecycle. New media in a Package becomes a Task only if the manifest declares a Task using it.

### 4.5 Task and structured payload

A **Task** keeps its existing identity, creation time, completion/state, history, and List memberships. Structured content is an additive representation, not a replacement of existing media Tasks.

The proposed storage seam is an optional one-to-one payload record, such as:

```text
task_payloads(taskId, kind, schemaVersion, normalizedPayload, revision, updatedAt)
```

Exact column naming belongs to the iteration plan. There are no vocabulary-specific or questionnaire-specific columns. Media Tasks without a payload continue to work.

Keep two concepts separate:

- **content kind/schema:** how a payload is interpreted;
- **viewer capability:** the renderer and intents used to present it.

A protocol-namespaced workflow kind is permitted at the boundary, but Core only passes or compares registered identifiers. The registry resolves it to capabilities; Core never branches on its business meaning. `AssetKind.OTHER` is not a structured payload kind.

The Package's optional short title feeds `task_imports.displayName`. If absent, the Importer supplies a neutral label from the record key. That import label is the structured Task's queue/page label; the long question or prompt remains payload content. Existing List/title behavior for media is preserved.

### 4.6 Asset and locator

An **Asset** remains content presented for one Task. Existing files already are Assets; v0.8.x does not need a file-to-Asset migration.

Retain per-Task Asset identity, ordering, media kind, existing file locators, source availability, Asset Controls, and PDF passage-mark behavior. Two Tasks using the same external file still have separate Assets and independent pair state.

Separate a locator's meaning from its storage spelling:

- existing file locator: granted root plus relative path/source URI;
- Package-member locator: Todo/Package import provenance plus a validated member reference.

For the directory-only Package implementation, a member may resolve to a normal file under the granted root. In that case existing file locator fields contain its **actual** location under that root. Do not put a Package key, archive pseudo-path, or embedded value into `rootUri` or `relativePath`.

The resolver contract may accommodate a Package-member reference, but zip members and embedded binary blobs are not implemented in v0.8.x. Their future support needs an explicit locator/storage/export extension.

### 4.7 Resource

A **Resource** is a declared supporting dependency, such as pronunciation audio or a prompt illustration used inside the content page. It has an opaque internal ID, local member reference, kind, revision/availability, and dependency links.

Initial reconciliation identity is scoped to `(todoId, packageKey, resourceKey)`. Tasks in that Package may share the Resource. Cross-Todo/global deduplication is deferred.

Resource references carry role and whether that dependency is required; default is optional. Requiredness is a property of the Task's use of the Resource, so one shared file can be optional for one Task and required for another.

Resources:

- do not appear in `assets[]`, file counts, Asset chip rows, file Info sheets, or page counts;
- do not create independent Tasks or own judgment state;
- are resolved by the Viewer through an adapter;
- record missing/unavailable status separately from Task completion.

The same physical audio file can be a Resource in one Package and an Asset in another import. Those are explicit roles in different import intents.

### 4.8 Viewer and existing Asset Controls

A **Viewer** renders content and translates interaction into named intents. Its contract is represented in pure domain declarations; its renderer lives in the UI/platform layer.

Preserve the distinction established by PDF: the renderer itself is not an Asset Control template. Existing code may describe viewer capability through `TemplateDefinition.AssetViewer`; that declaration and its version are distinct from `MediaPreview`/`PdfPreview` and from the stored controls governing actions or marks.

A Structured Task Viewer follows the same separation: a versioned, hashable capability declaration identifies supported kinds/schema ranges and emitted intents; a registered renderer displays the payload and resolves Resources.

Existing zoom, playback, text selection, quote anchors, detached marks, and control-governed annotation behavior remain intact. New structured view interactions such as reveal or play are presentation state. Answer selection may update a draft; only an explicitly bound commit intent submits.

For upstream-defined choices:

| Concern | Owner |
|---|---|
| Prompt, option ID, label, legal value and order | Package payload, normalized by Importer |
| Display and physical input handling | Viewer renderer |
| Accepted semantic intent, validation, commit and completion | Task Review Template Version |
| The submitted answer and its interpretation | ReviewResponse and event provenance |
| External field names/layout and delivery | ExportMapping and exporter |

A Viewer emits an intent to its host; it does not call a DAO or delivery adapter.

### 4.9 Headless Review Template

A **Headless Review Template** is a Task Review Template Version whose primary question/answer controls are supplied by the Structured Viewer. It owns the response contract without requiring a duplicate visible action bar.

It declares accepted intent/value shapes, validation, and a versioned submission policy:

```text
commitOn: named intent(s)
completesTask: explicit policy
advanceAfterCommit: explicit policy
```

Completion cannot be inferred solely from a response value type, nor can the UI always force `completed = true`. The media adapter preserves its current semantics through an equivalent policy. Automatic advance occurs only after successful commit and remains separate from navigation.

The initial fixture implements single choice. Further generic primitives may include multiple choice, boolean, bounded number, and bounded text when required by v0.9.x. A recall grade is a single choice with declared options; it does not require a `Recall` or `Vocabulary` response type in Core.

### 4.10 Add-on — existing contract retained

This section records a compatibility boundary, not a v0.8 implementation workstream.

An **Add-on** remains a lineage of questionnaires about a whole Task, stored in `addons` and immutable `addon_versions`. Its Rating, Yes/No, and Text questions keep their existing editor, attachment, rendering, and answer-update behavior.

The complete existing relationship remains:

```text
Todo choice (sources.addOn)
    -> import stamps task_imports.addOn
    -> Task's final Add-on page
    -> task_review_answers(taskId, questionId), replace on re-answer
    -> current visibility projection
    -> round snapshot's existing addOn object
```

Specifically:

- at most one Add-on per Todo, stamped onto imported Tasks;
- page stays last, after the Task's primary content/Asset pages;
- answers remain Task-owned current state, with authored-under Add-on ID/version;
- detaching, removing a question, or changing its type hides answers under existing rules; restoring compatibility reveals them;
- no required flag, completion precondition, or new validation through the Headless Template;
- completion retains its existing direct path for the shipped media/Add-on workflow;
- the existing v4 export includes current visible answers after `assets`, and only when the existing version-selection rule calls for it.

No Add-on fragment or `addOnSnapshot` is added to ReviewResponse or ReviewEvent. No namespace change, new answer key, multiple-Add-on model, or Add-on migration is proposed. New structured-page composition only preserves the existing final-page position; it does not redefine the Add-on.

A questionnaire imported as **primary Task content** in v0.9.x uses a Structured Viewer and Headless Template. The existing **Add-on questionnaire** remains an independent supplementary current-state facility.

### 4.11 Response, submission, and event provenance

Use the existing **`ReviewResponse`** type as the canonical response family. “Response” in this proposal is shorthand for it, not a second competing type.

Extend it with a versioned encoding that distinguishes primary structured answers from media submissions and preserves per-Asset attribution for newly recorded media events:

```json
{
  "responseVersion": 1,
  "primary": { "action": "complete", "value": true },
  "assets": [
    { "assetId": "opaque-id", "selections": ["favorite", "tag-id"] }
  ]
}
```

This example is internal data, not export v1 or v5. Structured answers carry typed values and stable option IDs where applicable. Add-on answers are absent from this contract.

Preserve these separate representations:

| Representation | Meaning / authority |
|---|---|
| Current TaskState | Current completion and applicable primary-response projection |
| Task–Asset selections/marks | Current judgment about individual Task Assets |
| task_review_answers | Existing current Add-on answers |
| ReviewResponse | Data explicitly committed through a primary review submission |
| ReviewEvent | Historical submission with its response and known provenance |
| ReviewResult / round views | Projection selected for consumption/export |
| Export history bytes | What was actually serialized/sent |

Introduce one submission service over the existing atomic DAO transaction:

```text
ReviewSubmission(taskId, taskReviewTemplateRef, response, context, observedRevision)
submit -> validate composition + response + revision
       -> atomically append event and update permitted TaskState
       -> return outcome and post-commit navigation intent
```

The media host and structured host both use it. Existing state-edit paths, including Add-on answer updates, are not rerouted through it. Preserve `submitReview`'s content-presentability check and completed-List reporting.

Composition checks run at the write boundary, not just when opening the UI. A zero-Asset Task does not pass merely because `assets.all(...)` is vacuously true. Reject an unsupported renderer/schema, missing required dependency, or stale content revision without writing a partial event/state change.

For new primary submissions, record response encoding version, Task Review Template ref, known Viewer capability ref, per-Asset governing control refs, and structured content revision when applicable. Structured answers retain the selected option's ID/value/label definition; the minimal protocol defines bounded interpretive snapshots, without media bytes. If required interpretation exceeds the configured bound, report it rather than silently truncate it.

v0.8.0 may store this envelope in the existing response text column; no new Room schema is required merely to introduce the contract. Any later query/index requirement can justify an additive provenance table. This is not a versioning project for Add-ons.

Old events remain intact. Decode known `completed;selections` and `completed;favorite;actions` formats as far as they actually support; preserve unrecognized raw content as unreadable-with-diagnostic. A historic aggregate selection cannot be reconstructed into per-Asset choices. Missing Viewer/control/content provenance remains explicitly absent. Never infer it from today's templates.

### 4.12 Exporter and ExportMapping

The app already builds `ExportPayload` / `ExportTaskView` round snapshots and has Webhook/Txt delivery paths. Generalize the missing pure mapping step:

```text
database -> one round snapshot
         -> existing export-choice/grouping plan
         -> ExportMapping(contract version)
         -> serializer -> delivery -> original bytes in history
```

The mapper consumes the snapshot and never queries the database. It may select, rename, group, or restructure fields. It does not redefine review meaning or mutate stored responses.

Preserve Todo export choices and addresses, List overrides and follow-Todo behavior, grouping by Todo, round IDs, and one history row per actual export. Unknown export kinds keep the existing fail-closed behavior.

Preserve media export v2/v3/v4 bodies for equivalent snapshots:

- Task completion stays at Task level;
- Asset `relativePath`, actions, marks, and governing template resolution keep their current meaning;
- v4 `addOn` comes from current visible Add-on answers, not ReviewEvents;
- saved history is resent from original bytes, never remapped.

Propose export **v5 only for rounds containing new structured content/results**, with its exact schema documented before the v0.8.2 fixture. Media-only rounds keep existing version selection. A mixed structured round uses the new envelope while preserving existing media/Add-on field semantics. Do not silently omit unsupported structured results or squeeze them into v4 Asset fields.

## 5. Review composition, pages, and selection

### 5.1 Capability resolution

A closed, code-defined capability registry resolves content kind/schema to versioned Viewer and review capabilities. Reuse the existing resolver pattern: success or a named diagnostic, never a guessed fallback. Stored editable Asset Controls remain stored templates with their existing resolution rules.

Composition verifies payload support, required Resources, compatible Asset viewers, and the Headless Template's intent/response contract. Existing Add-on attachment/readability follows its own shipped rules and does not become a primary-submission dependency.

Unavailable required Resources produce a `ReviewBlock`-style missing-content reason and block submission. Missing optional Resources disable the affected affordance, show a notice, and allow otherwise valid review. Unsupported content/viewer/schema produces a no-compatible-viewer diagnostic. Import diagnostics and runtime availability should use the same reason vocabulary.

### 5.2 Content-page model

Use a typed page list as the common source for rendering, count, navigation, and queue-guide entries:

```text
pages =
  [structured payload page, if present]
  + [Asset pages in existing order]
  + [existing Add-on page, if attached]
```

The payload is page 0; it is not a synthetic Asset. Resources are displayed inside their referring page and never increase page count. A payload-only Task has one labelled page.

The Task Navigator still changes whole Tasks. Page selection changes what is shown within the same Task; the Asset Navigator remains the boundary for Asset selection. Preserve current media page gestures and forward behavior without letting page movement submit.

Update `taskPageCount`, `reviewPageEntries`, resume identity, and the two-level queue guide from this one page model. A multi-page Task's second level lists the content page, Assets, and trailing Add-on in order. A single-page Task retains the existing no-redundant-submenu behavior, while its page entry is still labelled in the model.

Introduce an explicit structured decision target instead of disguising it as `DecisionTarget.File` or the legacy manual-item case. Review every exhaustive target dispatch involved in opening, selection, Info, navigation, and export.

### 5.3 Catalog scope and mixed content

Add an explicit Package catalog scope within a Todo, identified by Todo and Package provenance. Do not fabricate a file path for payload-only Tasks. Keep legacy `groupPath` semantics for file imports.

A Package scope lists its normalized Task records; Todo root browsing can include these scopes. Selection uses Task IDs and reports Tasks/pages rather than requiring a file count. Quick Review over a Package scope selects its direct Task records; legacy folder Quick Review keeps its existing direct-child behavior.

For Package Tasks, scope containment means the whole declared Task belongs to that Package scope (or an ancestor Todo scope), including all of its Asset references. Shared declared Resources can resolve through the Package grant; they do not split the Task into separate folder scopes. Runtime readiness additionally requires a readable supported payload, compatible composition, and all required dependencies. Optional missing Resources do not make the Task partial. Missing Package access is flagged conservatively; records and judgments remain subject to the existing explicit policy rules.

One Package can contain several kinds. Keep one Todo for its import intent. For v0.8.x, Create List and Quick Review select **one compatible review configuration**, show which Tasks qualify, and explain exclusions. Do not silently create several Todos or treat arbitrary mixed-template Lists as supported. Different Viewer kinds may share a context only when the same review contract supports all included Tasks.

## 6. Reconciliation, identity, and Package validation

### 6.1 Keys and checkpoints

Use `(todoId, packageKey, taskKey)` to reconcile protocol-declared Tasks. Use Package-scoped Resource keys as defined in §4.7. Mint opaque Task/Asset/Resource domain IDs separately.

Two Todos importing the same Package remain independent. Duplicate keys within one Package/Todo are conflicts. Existing unkeyed Tasks retain path-based reconciliation under their existing importer. Do not backfill keys from `groupPath`, and do not auto-merge an unkeyed Task with a keyed one merely because paths match.

A missing Package revision does not erase a supplied Task key. Compare canonical normalized payload/declaration fingerprints when revisions are absent; path fallback is for legacy unkeyed file imports. Unknown media replacement must be reported when available metadata cannot establish a safe match. Changing importer/grouping remains a separate import or an explicitly designed migration.

An unchanged Package revision/fingerprint with unchanged dependency availability and Todo configuration is a no-op for normalized content, judgments, and events. Resource availability still has to be checked, and a template/Add-on attachment refresh must still honor the existing Todo refresh contract. Operational scan status may change. A revision-only shortcut must not suppress those independent changes.

A declared revision/hash that contradicts observed normalized content is an import conflict. Ordinary prompt/option edits under a compatible kind/schema may be a new content revision; changing the contract incompatibly or reusing a key for a different declared identity is not silently accepted as the same work.

### 6.2 Preserve content policy and bound its extension

| Change | v0.8.x behavior |
|---|---|
| Existing media Asset-set change | Existing per-Todo `keep/reset/drop` implementation and exceptions |
| New declared Task key | Create a new Task within that Todo |
| Resource-only change or missing Resource | Update revision/availability; no new Task or automatic judgment reset |
| Compatible structured payload revision | Retain Task ID/current judgment, record revision, preserve old events' interpretation |
| Declared structured Task withdrawn | Retain and flag it unavailable; no inferred deletion |
| Incompatible kind/schema or identity conflict | Report and block affected replacement; retain prior data |
| Importer/grouping change | Separate import or explicit migration; no silent merge/split |

The existing media policy is not being rewritten. In particular, explicit `reset` clears the applicable current state without inventing submissions; explicit `drop` can replace/delete Tasks and their judgments as documented, including the single-Asset restart exception and zero-resolvable-Asset rule.

Payload revisions and Resource changes are new change categories not previously named by those Asset-set policies. v0.8.x uses the conservative rules above for them. A Package import must explain that the chosen media content policy applies to Asset-set changes; it does not silently authorize destruction of structured history. Extending `reset/drop` to payload replacement or record withdrawal requires a separately specified policy decision.

For hybrid Tasks with real Assets, genuine Asset-set changes still follow the existing policy. Resource-role changes must never be manufactured into Asset-set changes to trigger it.

Automatic learning rescheduling remains upstream. If a new review occurrence needs independent completion, the producer emits a new Task key rather than relying on a payload update to create a fresh review round.

### 6.3 Minimal protocol validation

v0.8.1's protocol document fixes concrete byte, count, depth, and string-size limits and fixtures before parser implementation is accepted. It also fixes canonical encoding, duplicate-key handling, allowed local reference syntax, and per-record diagnostics.

Required behavior:

- unknown protocol version or malformed envelope: reject that whole Package before import writes;
- unknown Task kind/schema in a valid envelope: report that record and keep it unavailable; import supported records without claiming the unknown record is reviewable;
- an unknown update to an existing keyed Task must not overwrite its previously valid payload;
- duplicate Task/Resource/member keys, unresolved required declarations, absolute member paths, `..` traversal, symlink escapes, and out-of-grant references are rejected before committing the affected Package;
- bound manifest size, nesting, record counts, and member access; reject oversized input rather than partially parsing it;
- allow only granted local directory members; no code execution, remote fetch, or upstream writes;
- archive/zip and embedded-binary inputs are explicitly unsupported and rejected without extraction.

A runtime member disappearance after a valid import is availability state, not a malformed Package retroactively erasing work. Multi-Package runs report per-Package failures and commit only validated Package plans; they do not interpret a failed Package as a list of deleted Tasks.

## 7. End-to-end collaboration

### 7.1 Existing media and PDF

```text
Upstream + Todo(file/folder importer, current policy/config)
-> existing import plan/run
-> Tasks with task-specific Assets
-> image/video/PDF Viewer + current per-Asset Controls
-> existing state edits; explicit primary completion submits through shared service
-> TaskState + ReviewEvent
-> existing round snapshot -> v2/v3/v4 mapping -> Webhook/Txt
```

Favourite/tags are Asset Control judgments, not Add-ons. PDF passage marks stay local, preserve quote anchors and detached state, and use current control visibility/export rules.

### 7.2 Vocabulary fixture and v0.9 product path

A directory Package declares a structured prompt/reveal payload, choice options, and a shared pronunciation Resource. The Todo's Package Importer creates keyed Tasks and Resource references. The catalog supplies a short label; page 0 renders the prompt and audio control.

Revealing or playing creates no event. Choosing a grade emits a typed single-choice intent to the Headless Template. The shared service validates the current composition and commits a ReviewResponse with interpretive provenance. Its policy may complete and advance. The structured round mapper produces v5 output for an external scheduler; Listea does not implement the scheduler.

### 7.3 Primary questionnaire

A Package declares a prompt, supported answer schema, stable options, and optional media references. Import creates a structured Task, the content page renders it, and the Headless Template validates the explicit answer submission.

This uses the primary ReviewResponse/event path. It does not use or migrate `task_review_answers`. Single choice is the v0.8 proof; broader questionnaire behavior is v0.9 work.

### 7.4 Existing supplementary Add-on

The Todo's existing Add-on attachment is stamped during import. Its page remains last. Editing an answer updates the existing current-state row; re-answering replaces that row. The ordinary visibility projection decides what is shown and what enters the round's `addOn` export object.

No primary submission or Headless Template is required to save an Add-on answer. No Add-on answer becomes a completion gate. This existing path is a regression boundary, not a new deliverable.

## 8. Changes relative to the latest architecture

| Area | Existing baseline | Proposed v0.8.x change |
|---|---|---|
| Source / Upstream / Todo | Retired Source noun; registered Upstream; Todo holds import intent | Keep vocabulary/ownership; add Package provenance within Todo runs |
| Importer | Pure folder planning and explicit reconciliation | Typed Package input and normalized payload/dependency plans |
| Task | Durable domain identity; implementation currently media-oriented | Optional generic payload and stable protocol keys |
| Asset / PDF | Task-specific files, controls, marks, real locators | Preserve; route declared local members through resolution |
| Resource | No explicit support-dependency model described in review | Package-scoped dependency records, outside Asset/page counts |
| Viewer | Renderer/capability/control separation already established | Add a Structured Viewer declaration and content renderer |
| Review Template | Existing semantic submission behavior | Headless answer contract and version-driven completion/advance |
| Add-on | Current Task answers, one attachment, trailing page | No restructuring; retain its existing position and relationships |
| ReviewResponse / Event | Existing response text, aggregate media data, limited provenance | Versioned primary responses, new per-Asset attribution, known provenance |
| Catalog / pages | File scopes and Asset pages plus Add-on | Package scopes, structured targets, optional page 0 |
| Export | Round snapshot, choice planning, Webhook/Txt, immutable history bytes | Pure mapping stage and a separate structured-result contract |

Package and Resource solve the information/member-role separation exposed by vocabulary tasks. The content-page and catalog extensions make a zero-Asset Task reachable and reviewable. The submission service gives both old and new UI hosts one validated commit path. None requires changing the shipped Add-on model.

## 9. Proposed v0.8.x sequence

These are bounded release proposals for implementation planning. Each receives the project's iteration document, plan, relevant JVM tests, and device walk. Schema changes additionally require the checked-in Room schema JSON, migration test, and fixture subtraction helper.

### v0.8.0 — Primary contracts and submission behind the current UI

- Extend existing `ReviewResponse` with explicit encoding/legacy decoding and known provenance; no second Response type.
- Introduce pure ContentKind/PayloadSchema, ReviewComposition, capability resolution, and submission-policy contracts.
- Route the existing media primary submission through a shared service over the existing atomic DAO transaction; preserve current media actions and completed-List behavior.
- Record newly available per-Asset attribution at commit without attempting to reconstruct old events.
- Keep the Room schema unchanged; use the existing event response column for the new envelope.
- Verify that navigation/state edits create no events and an explicit valid submission creates exactly one.

**Add-on is outside v0.8.0's work scope.** Its structures, save path, page, attachments, requiredness, completion behavior, and export projection are not changed. Existing Add-on regression checks may run, but they are not Add-on refactoring tasks. No UI or Package importer is required for this release.

### v0.8.1 — Directory Package protocol, provenance, and Resources

- Write the minimal protocol/limits specification first, including failure cases and fixture examples.
- Keep Upstream/Todo ownership; add typed Package planning, multi-Package checkpoints, keyed reconciliation, payload persistence, and scoped Resource resolution.
- Use directory members only and preserve existing file importer behavior.
- If schema 29 is still the implementation baseline, allocate schema 30 with `MIGRATION_29_30`, exported Room schema JSON, and the corresponding fixture subtraction helper. Verify the actual next version before implementing.
- Validate identity stability, independent Todos, unchanged import, new Task, resource-only change, missing dependency, and conflicts.

Structured records may be imported as explicitly unavailable until their renderer arrives. Do not expose an empty review surface or pretend zero Assets proves presentability.

### v0.8.2 — Structured content page and single-choice proof

- Implement the typed page list, Package catalog scope, structured DecisionTarget, selection/Quick Review, queue label, and two-level guide integration.
- Add one Structured Viewer, a Headless single-choice template, and the submission-time composition/revision check.
- Add the pure ExportMapping seam **here, before the first structured exporter**, with v2/v3/v4 golden tests and a documented v5 structured contract.
- Store/read the current primary structured result needed for display and round snapshots using an additive response projection or an explicit query of valid latest primary events; document its reset and revision semantics in the plan.
- Walk one fixture from directory import through answer, event, reopen, and export, alongside unchanged media behavior.

The existing Add-on page simply remains the last page. This release does not introduce Add-on dependencies into primary submission.

### v0.8.3 — Shared Resource and reveal-card proof

- Add a second fixture with prompt/reveal presentation, declared single-choice grades, and a shared pronunciation Resource.
- Exercise optional/required dependency diagnostics, compatible payload revisions, and independent Todo identities.
- Prove both fixtures share the same generic submission and mapping contracts.
- Finish migration/regression documentation and freeze the minimal protocol/capability contracts used by the fixtures.

These are framework proofs, not the full vocabulary/questionnaire product release. No Add-on restructuring is assigned to v0.8.1–v0.8.3 either.

### Cross-platform implementation boundary

```text
domain/   Pure contracts, typed intents, validation, encoding, capability resolution
data/     Room rows/DAOs, transaction adapter, migrations and snapshot assembly
import/   Pure planners plus separate platform discovery/manifest-reading adapters
export/   Pure snapshot mappings plus serialization/delivery adapters
UI/       Compose renderers, page/navigation hosts, user-facing diagnostics
```

Domain contracts and planners are JVM-testable without Android, Compose, Room, or `org.json`. Platform JSON parsing, SAF access, audio/PDF rendering, and storage resolution stay in adapters. Keep existing module/package conventions where they already satisfy this boundary.

## 10. Migration and compatibility

### 10.1 Preserve existing rows and history

There is no wholesale Task/Asset migration. Preserve domain IDs, public IDs, Lists, ordering, import ownership, current pair state, marks, and existing events.

Where a legacy mapping is actually needed, reuse `legacy_item_ids` and `legacy_task_ids`. Do not introduce another identity registration system. Existing Tasks remain unkeyed unless their actual protocol supplies a key; `groupPath` is not a backfilled upstream identity.

Keep stored response text/events intact and decode them through the compatibility reader. Old aggregate choices stay aggregate; missing provenance stays absent. State-only actions never gain invented historical submissions.

### 10.2 Preserve controls, Add-ons, and visibility

Stored Asset Control lineages/versions, first-match rules, per-Asset selections, and PDF marks keep their current ownership. New event snapshots do not replace the live visibility rules.

No changes to `addons`, `addon_versions`, `task_review_answers`, `sources.addOn`, or `task_imports.addOn` are part of this restructuring. Reuse current answer encoding and attachment-refresh behavior. Do not move custom actions into Add-ons.

### 10.3 Preserve export behavior

Current round assembly remains authoritative for media/Add-on output. Golden comparisons cover each existing contract version and both delivery representations where applicable. Resending existing history sends the same stored bytes and keeps original version information.

New response encoding must not change old export bytes merely because media submissions now record richer provenance. v5 is explicitly for the new structured round contract.

### 10.4 Additive database and failure handling

Follow the schema 29 baseline and minSdk 24 migration conventions identified by the review: additive tables/nullable fields where needed, no dependence on `ALTER TABLE ... DROP COLUMN`, and no table rebuild solely for terminology cleanup.

Each migration includes schema JSON, migration fixtures/helper changes, and tests with existing TaskState, pair selections, PDF marks, Add-on answers, ReviewEvents, List memberships, and export history populated. In a schema-only migration, their data must survive byte-for-byte where no conversion is specified.

Import is transactional per validated Package plan. A failed/unknown Package cannot erase the previous import. Idempotent re-import must not duplicate content or responses. Template/configuration refresh and Resource availability changes are tested separately from the unchanged-Package fast path.

## 11. Acceptance scenarios

| Area | Required evidence |
|---|---|
| Primary submission | One valid submission yields one event and the intended state projection atomically; failed validation yields neither |
| Navigation/state edits | Task/page movement, reveal, playback, tags, administrative edits, and existing Add-on saves do not become primary submissions |
| Add-on preservation | Same one-per-Todo attachment, trailing page, replace-on-answer behavior, hidden/restored answers, direct media completion and v4 export |
| Per-Asset meaning | Two Assets can retain different selections; new response attribution preserves that distinction; old aggregates are not guessed |
| Zero-Asset Task | Appears in Package catalog and picker, starts Quick Review/List review, and displays one labelled content page |
| Pages/guide | Payload + Assets + optional existing Add-on appear in that order; multi-page guide entries and resume resolve stable page identities |
| Write boundary | Unsupported structured schema/viewer, missing required Resource, or stale revision blocks submission even without Assets |
| Resources | Shared audio creates no Task/page/Asset row; optional loss disables play; required loss blocks submission; neither invents history |
| Key scope | Same Package in two Todos has independent Tasks; repeated import within one Todo preserves IDs; no path-derived key backfill |
| Refresh | Unchanged content/config/dependencies is a no-op; changed availability and attachment versions are not skipped by revision caching |
| Content policy | Existing media keep/reset/drop behavior and exceptions pass unchanged; structured/resource-only changes follow §6.2 |
| Protocol | Unknown protocol/malformed envelope has no Package import writes; unsupported record is diagnosed while supported records proceed |
| Input limits | Traversal, duplicate keys, symlink escapes, oversize/deep JSON, and unsupported archives are rejected before import writes |
| Export | v2/v3/v4 golden bodies remain unchanged; v5 fixture exports typed structured results; history resend uses original bytes |
| Migration | Existing events/answers/marks/state/memberships/public IDs survive; old provenance is absent rather than fabricated |
| Selection | Incompatible Tasks are clearly excluded from a selected review configuration; mixed-template Lists are not silently enabled |
| Domain boundary | New contracts have JVM tests; no new workflow-specific Core branches or Android/Compose/Room/JSON-platform dependencies |
| Media/device | Existing image/video/PDF and two-level queue-guide walks pass, including marks, gestures, List progress and export grouping |

The workflow-name check targets newly introduced domain branches/types. Existing Add-on questionnaires are already a domain feature; a blanket requirement to erase every occurrence of “questionnaire” would contradict the scope decision.

## 12. Non-goals

- Add-on restructuring: multiple Add-ons, new positions, required answers, new keys/namespaces, snapshots in events, or changed export authority.
- Renaming Upstream to Source, renaming the `sources` table, or introducing a second registered-origin model.
- Rebuilding current media/PDF storage, viewers, controls, or gesture behavior.
- Remote feed access, network Package downloads, zip/archive extraction, embedded binary payloads, or arbitrary Package code.
- A durable Package ownership/lifecycle model or global Resource deduplication.
- Mixed-template Lists, Task merge/split automation, or cross-Todo identity merging.
- A universal form builder, importer language, exporter transformation language, or plugin runtime.
- Vocabulary scheduling, survey analytics, or complete v0.9.x user-facing features.
- Extending media `reset/drop` to previously unnamed structured-content changes without a separate policy decision.
- Re-rendering historical export bodies or reconstructing missing historical provenance.

## 13. Review disposition and remaining implementation choices

The owner's instruction settles the Add-on question: **retain the current model and its relationships**. The optional event snapshot suggested in review C2 is not adopted, nor is C5's suggestion to pre-shape multiple-Add-on fragments. Those would add work without a current requirement.

| Review item | Resolution in this revision |
|---|---|
| C1 / G1 | Keep Source/Upstream/Todo meanings; place Package inside Todo import runs (§4.1–4.4) |
| C2 / C5 | Keep Add-on current state, one attachment, final page, and export authority; no event snapshot (§4.10) |
| C3 / G2 / G7 / G8 | Explicit payload page, structured target, Package scope, short label, picker and queue-guide integration (§5) |
| C4 / C6 / G14 | Extend ReviewResponse, preserve old events/encodings and absent provenance (§4.11, §10) |
| C7 / G9 | Immutable history bytes, v2/v3/v4 goldens, explicit v5 boundary (§4.12) |
| G3 | Real directory locators; explicit member resolution; archives/embedded media deferred (§4.6) |
| G4 | Minimal bounded directory protocol with rejection rules (§6.3) |
| G5 / G6 | Framework-free contracts, additive schema/migration fixtures, minSdk conventions (§9–10) |
| G10 | Optional-resource degraded control versus required-resource submission block (§5.1) |
| G11 / G12 | Multi-Package provenance, scoped keys, cautious revision fast path, unkeyed compatibility (§6.1) |
| G13 | Shared validated primary submission service over existing transaction (§4.11) |

Implementation plans still need to fix exact manifest syntax and limits, checkpoint persistence, response envelope encoding/snapshot bounds, primary-result projection storage, and the v5 wire schema. The responsible releases are specified in §9; these decisions must be concrete before their fixtures ship.

They are not invitations to reopen Add-on ownership, event-source current answers, or change media identity. If implementation uncovers a genuine conflict with the baseline, record a separate architecture decision rather than hiding a product change inside this refactor.

## 14. Definition of done

v0.8.0 is complete when current media primary submissions use the tested contract/service with preserved behavior, no schema requirement, and no Add-on restructuring.

The v0.8.x preparation is complete when:

- Upstream and Todo ownership remain recognizable, with Package protocol/provenance added at import;
- payload-only Tasks are discoverable, selectable, renderable, and protected by submission-time compatibility checks;
- Resources resolve as supporting dependencies outside Asset/page counts;
- one single-choice fixture and one reveal/audio fixture use generic review capabilities and the shared submission path;
- new primary events preserve known meaning while old events retain honest absent provenance;
- Add-on structure, final-page placement, answer state, attachments, and export projection retain shipped behavior;
- existing media/PDF behavior, content policies, export choices, and v2/v3/v4 bodies pass regression checks;
- the structured export contract, protocol, migration fixtures, JVM tests, and device walks are documented and pass.

v0.9.x can then develop the vocabulary and primary questionnaire experiences on these contracts. The existing Add-on remains available in its current form.

