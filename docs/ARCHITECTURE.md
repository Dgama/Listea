# Listea Architecture

> **Status:** Normative architectural direction  
> **Scope:** Long-lived product and domain boundaries  
> **Last updated:** 2026-10-23 (the v0.8.x framework direction is recorded in
> [Framework evolution — v0.8.x](#framework-evolution--v08x-planned): Package protocol, Task payload,
> Resources, Structured Viewer and Headless Review Template, the typed page model, the
> ReviewSubmission service, and the ExportMapping seam. The shipped v0.7.1 Add-on model is retained
> unchanged. The pre-v0.8 revision is archived at
> [ARCHITECTURE-v0.7.1.md](ARCHITECTURE-v0.7.1.md).)
> **Earlier revisions:** v0.7.0's PDF viewer, passage mark and renderer-neutral seam are recorded in
> [Asset](#asset), [Asset Viewer Template](#asset-viewer-template) and
> [Gestures and semantic actions](#gestures-and-semantic-actions); v0.6.0's versioned export body in
> [Exporter / Action](#exporter--action) and [Identity](#identity); v0.5.4's stored Asset Control
> templates in [Templates](#templates); v0.5.6's export/mechanism split in
> [Exporter / Action](#exporter--action); and v0.5.1's Task–Asset pair state in
> [TaskState](#taskstate).

Listea is a lightweight human-judgment layer between structured inputs and workflow outputs. It turns a collection of reviewable tasks into a fast, resumable feed, records what a person decided, and leaves upstream ingestion and downstream consequences to other systems.

This document is Listea's architecture constitution. It defines meanings, ownership, and boundaries that implementations must preserve. It intentionally does not prescribe Room table names, Kotlin class names, UI framework choices, or a final importer/exporter design.

Iteration documents may narrow the work for a release, but they must not contradict this document without an explicit architecture decision.

## Status at a glance

The code and the architecture are not the same artefact. This table says where the implementation
stands so a later reader does not mistake a planned boundary for a shipped one.

| Release | State (2026-10-23) | Established |
|---|---|---|
| v0.7.1 | shipped, not published | Add-ons as Task-owned current state; schema 29; export v4 |
| current `main`, after v0.7.1 | implemented, unreleased | the position label opens a two-level queue guide |
| v0.8.0 | planned | primary response contract, ReviewSubmission service, per-Asset provenance; no schema change |
| v0.8.1 | planned | directory Package protocol, payload persistence, Package-scoped Resources |
| v0.8.2 | planned | typed page model, structured content page, single-choice Headless Template, ExportMapping seam |
| v0.8.3 | planned | shared-Resource and reveal-card fixtures; contract freeze |

The v0.8.x rows are commitments of direction, not descriptions of code. Each release's iteration
document is the authoritative statement of what that release actually does.

## Product principles

1. **Human decisions should be cheap.** The interface minimizes waiting, context loss, repeated opening, and unnecessary navigation.
2. **Inputs and outputs stay clean.** Listea receives normalized Tasks and produces review results. How raw material becomes a Task and what happens after a judgment are separate concerns.
3. **Workflow-specific behavior belongs outside Core.** Core must not know what a candidate, shortlist, vocabulary word, insurance claim, or job-market paper means.
4. **Listea captures judgment; it does not dictate judgment.** Templates can describe available responses, but Core does not impose their business meaning.
5. **Architecture serves real workflows.** New abstractions must solve an observed need or protect a known extension point. Speculative generality is not a goal.
6. **Historical meaning must survive change.** Stable identity and immutable template versions keep old reviews intelligible.

## System boundary

The long-term flow is:

```text
Raw source
    |
    v
Importer / Task Builder
    |
    v
Normalized Task ------+
                      |
Template Version -----+--> Listea Core --> Review Result / Event
                      |                         |
List / Review Round --+                         v
                                           Exporter / Action
```

Listea Core begins after a Task has been assembled. It does not decide that several filenames, a folder, or a ZIP belong to the same real-world subject. Core ends after it has recorded a person's result. It does not send rejection email, move a hiring pipeline forward, rename a file, or decide what an external automation should do.

Importers, task builders, exporters, and actions are architectural boundaries. They do not all need framework abstractions or user-facing configuration yet.

**v0.8.x makes the left-hand side of that flow explicit without moving Core's start.** The producer,
the Upstream and the Todo already sit outside Core. A **Package** is a protocol envelope discovered
and validated inside the same import boundary, and a **structured payload** is content carried by a
Task rather than a new kind of review object. Core still begins after the importer has assembled a
Task and ends after a submission has been recorded.

## Orthogonal boundaries

Three pairs of concerns are independent dimensions. A particular workflow may chain them together, and the interface may present them as one motion, but the domain must not fuse them. Collapsing any of these is the most common way a review system quietly becomes unable to represent the next workflow.

### Navigation ⟂ Review / Completion

> Navigation changes what the user is looking at. Review actions change judgment/state. They are independent dimensions even when a particular workflow chains them together.

Moving to another Task is not a statement about the Task being left behind. A person may inspect a Task, move on, and leave it unjudged; that is a valid and expected state, not an incomplete operation. Conversely, a judgment may cause the interface to advance, but the advance is a consequence of the judgment rather than part of its meaning.

### Asset structure ⟂ Task lifecycle

> The resources attached to a Task describe how its content is presented. They do not determine how long the Task should exist.

A Task with no Asset is not a lesser Task, a temporary Task, or a Task owned by whatever context happens to contain it. A Task whose content is entirely structured fields — a question, a vocabulary prompt, a record to adjudicate — is as durable as one carrying five documents. Nothing may infer ownership, scope, or deletion policy from how many Assets a Task has.

An import may therefore carry an explicit per-Todo **content policy**, naming what a change to a
Task's Asset set means:

| Policy | What a change to the Task's Assets means |
|---|---|
| **`keep`** | The Task and its judgment stand; missing material is flagged. |
| **`reset`** | The Task's identity stands, and its *current state* is cleared — an operation [ReviewEvent and ReviewResult](#reviewevent-and-reviewresult) already contemplates for an import. |
| **`drop`** | The Task's **identity** does not survive the change, and a new Task takes its place. |

`drop` is the only tier that reaches lifetime, and it is admitted **only** as an explicit,
user-named, per-import policy — which [Re-import and reconciliation](#re-import-and-reconciliation)
already requires for a change of Task boundary — and never as an inference drawn from a Task's
structure. All three tiers read a *change* in the set of Assets, never its size, and a Task left with
zero resolvable Assets is flagged missing and lives.

**No tier chooses a policy from a count**, and the one place a count is read at all is worth stating
exactly, because it is the boundary of the rule above rather than an exception to it. Asking to
`drop` a Task that holds a single Asset **starts it over instead**: such a Task *is* its Asset, so
replacing it would mint a Task holding that same one Asset under a new id, with its judgment and its
List memberships gone — the identity lost and nothing bought. The user's statement still decides
*that* something happens to the Task; the Task's shape only decides which of two ways of honouring it
is available. A policy is never *inferred* from a count, and a Task is never replaced because of one.

Who names the policy is the whole of the distinction. A user saying *"when this Task's content moves,
start it over"* is stating an intention about a Task they own, which is a thing this model must be
able to represent. A program noticing that a Task now has one Asset where it had two, and concluding
anything from that, is deriving a lifetime from structure — which is what the paragraph above
forbids, and still does.

### Task creation ⟂ Task review

> Assembling Tasks and judging Tasks are separate responsibilities, even when they ship in the same application.

Listea Core reviews Tasks. It is not also a generic authoring or CRUD surface for inventing them. Material becomes a Task through a deliberate creation/import boundary — a folder scan, a generator, a CSV, a database, an upstream scheduling system — and arrives already normalized. A Task Creator may eventually live inside the same app; architecturally it remains upstream of the review engine.

This matters because Listea does not necessarily own the long-term business object a Task stands for. An upstream vocabulary system may keep its word database, definitions, learning curves and scheduling logic, and generate today's review Tasks from them. Listea reviews what it is given and reports what a person decided.

## Domain model

The names below are deliberately singular and distinct. `Item` may remain as temporary UI or legacy vocabulary, but it is not a sufficient core entity because it has historically meant both a file and a queue entry.

```text
Template 1 ---- N TemplateVersion
                         |
                         | used by
                         v
List 1 -------- N ListEntry N -------- 1 Task
                                             |
                                             +---- 0..N Asset
                                             |
                                             +---- 0..1 structured payload  (v0.8.x planned)
                                             |
                                             +---- 0..N Resource use        (v0.8.x planned)
                                             |
                                             +---- 0..1 current TaskState
                                             |
                                             +---- 0..N ReviewEvent
                                             |             |
                                             |             +---- 0..N caught event (review_event_answers, derived)
                                             |
                                             +---- 0..N add-on answer (task_review_answers)

Task Review Template Version ---- defines the judgment over a Task
Asset Viewer Template Version --- renders one compatible Asset
Headless Review Template ------- is a Task Review Template whose controls the Viewer supplies
```

This is a semantic model, not a required physical schema.

The v0.8.x additions do not put a Package in that model. A Package is import provenance inside a
Todo's run — it declares Tasks and Resources but owns neither. See
[Framework evolution — v0.8.x](#framework-evolution--v08x-planned).

### Task

A **Task** is the smallest durable object about which a person is asked to make a judgment.

A Task:

- has a stable, opaque, globally unique identity;
- may contain structured fields;
- may reference **zero, one, or many Assets**;
- owns its current review state;
- has zero or more historical review events;
- is independent of any List that happens to contain it.

Current media review is the simplest valid shape:

```text
Task
└── Image Asset
```

A manual, vocabulary, or questionnaire Task may have no Asset. A document-review Task may have several Assets. No implementation may assume `task_id == file_id`, that every Task has a file, or that a Task has exactly one Asset.

Nor may an implementation read anything about a Task's *lifetime* from its Asset count. See [Asset structure ⟂ Task lifecycle](#asset-structure--task-lifecycle): "this Task has no Asset" must never become "this Task belongs to the List that contains it" or "this Task may be deleted with its membership." Where a legacy workflow genuinely needs list-bound Task cleanup, it is a closed compatibility case that names its own rows, not a rule derived from structure.

Structured fields are content, not permission to put profession-specific columns into Core. A field may be named `candidate_name` by imported data or a user-facing template, but `Candidate` is not a Core subtype.

**v0.8.x gives structured fields a storage seam without changing what a Task is.** A Task may carry
at most one optional **payload**: a normalized content record with a `kind`, a schema version, a
revision, and the payload itself. A payload is content, not identity, not a lifetime, and not a
second Task. A Task may have a payload and no Assets; that is a complete Task, not a partial one.
The payload's long prompt or question remains content, while the short label that names the Task in
the queue is the existing import `displayName`, not a new title column. See
[Framework evolution — v0.8.x](#framework-evolution--v08x-planned).

### Asset

An **Asset** is content referenced by a Task and presented through a compatible viewer. An Asset is not the judgment target and is not a queue membership.

An Asset:

- has its own stable, opaque identity, distinct from its Task's identity;
- has a media/content kind such as image, video, PDF, audio, or another future type;
- may carry a label, role, ordering information, source reference, and type-specific metadata;
- is treated as read-only by default;
- does not own Task completion, review responses, Task navigation, webhook configuration, or downstream workflow rules.

An Asset's location, filename, URI, or List position is mutable metadata and must not be its durable domain identity. Source-specific locators may still be required to resolve local content and to preserve legacy behavior.

**A locator's meaning is separate from its spelling.** Existing file Assets use a granted root plus a
relative path. A Package member that resolves to a real file under the granted root uses those same
fields with its **actual** location; a Package key, archive pseudo-path, or embedded value is never
written into `rootUri` or `relativePath`. Archive members and embedded binary blobs are not Asset
content in v0.8.x; supporting them requires an explicit locator, storage and export extension. A
supporting file that is not presented as content is a **Resource**, not an Asset.

**v0.7.0 makes *read-only* a boundary with a stored counterpart, and this is where the decision is
recorded.** A PDF Asset can now be read and marked — a selected passage carries a comment or a tag —
but the mark is a **Listea record filed under the Asset**, not a change to the file. The PDF's bytes
are never written: the viewer reads the document and draws its marks over it, and the checksum of
the original is the acceptance test for that sentence. A mark therefore belongs to the same family
as the pair state described in [TaskState](#taskstate): it is the Task's current state *about* one of
its Assets, keyed by the Asset, and deleting the Asset takes it along. What a mark is attached to
inside the file is a **quote anchor** — the authoritative quotation plus a non-authoritative
character hint — so that *the sentence that was marked* survives a reopen, a renderer upgrade, or a
document whose bytes have changed. A mark that can no longer be located is kept and shown as
detached rather than erased; the invariant *current state describes what is true now* still holds,
and a detached mark is what *true now* looks like when the document moved.

The viewer itself is **not a template**, exactly as the video player is not: it renders content and
produces nothing of its own. A PDF template is the existing Asset Control, scoped to PDF, whose
Tag and Comment buttons become passage tools. Whether a mark is **shown and sent** is decided by
the template that governs the file *now* — the same first-match rule as the chip row — never by the
version recorded on the mark, which is authored-under history. A template or tool that leaves
therefore hides its marks and stops their export without deleting a row, and restoring the same id
brings them back. See [Templates](templates.md#pdfs-and-passage-tools) and [v0.7.0](iterations/v0.7.0.md).

### Resource (v0.8.x, planned)

A **Resource** is content a Task's page depends on but does not present as a page: pronunciation
audio, a prompt illustration, a reference image. It is not an Asset. It does not appear in
`assets[]`, file counts, Asset chip rows, the file Info sheet, or page counts; it does not create a
Task or own judgment state.

A Resource:

- has an opaque internal identity and a Package-scoped reconciliation key
  `(todoId, packageKey, resourceKey)`;
- is declared with a content kind, a local member reference, a revision/availability, and its
  dependency links;
- is shared by the Tasks of one Package; cross-Todo and global deduplication are deferred;
- carries requiredness **per Task use**, so one file may be optional for one Task and required for
  another;
- is resolved by the Viewer through an adapter, not by Core;
- records missing/unavailable status separately from Task completion.

A missing optional Resource disables the affected affordance and allows an otherwise valid review.
A missing required Resource produces a named missing-content reason and blocks submission. The same
physical file may be a Resource in one import and an Asset in another; the role belongs to the
import intent, not to the file.

### List and ListEntry

A **List** is an ordered review context over Tasks. A **ListEntry** is membership of a Task in a List.

Therefore:

- List identity is not Task identity.
- Deleting a List removes its membership and review context, not the underlying Tasks or their judgment state.
- The same Task may be reachable from more than one review context without becoming multiple Tasks.
- List progress is derived from the current state of its member Tasks.
- Ordering, filtering, a saved cursor, and review-round position belong to the List/session side of the model, not to the Task.

How a List's Tasks were prepared is not part of what a List is. A List may be built from a scanned folder, an import, a generator, or a future Task Creator; the review context is the same object either way. If a release removes a particular way of creating Lists — as v0.2 removes hand-authored ones, by [Task creation ⟂ Task review](#task-creation--task-review) — that is a product-boundary decision about *creation*, and it must not be read back as a rule that Lists are permanently bound to any one source.

For the first general architecture, a List uses one Task Review Template Version. The app may contain Lists for different kinds of work, but one List remains homogeneous. Mixed-template Lists may be considered later only when a real workflow justifies their navigation and completion complexity.

v0.8.x does not relax that rule. A Package may contain several content kinds, but Create List and Quick Review select **one compatible review configuration**, show which Tasks qualify, and explain exclusions. They do not silently create several Todos or enable arbitrary mixed-template Lists. Different Viewer kinds may share a context only when the same review contract supports all included Tasks.

The architecture does not currently introduce per-membership completion. If a future workflow genuinely requires “this Task is complete in List A but incomplete in List B,” that need may add explicit ListEntry or session state. It must not silently redefine TaskState.

### TaskState

**TaskState** is the current projection of what is true about a Task for review purposes. `checked` is one current-state field meaning “I have dealt with this in Listea.” It does not describe what the decision meant.

Current state belongs to the Task, not to an Asset and not to a List membership. In the media workflow, this preserves the existing rule that Quick Review and List Review see the same decision when they reach the same reviewable object.

> **Since v0.5.1 the current state is filed under two keys, and this paragraph is what a reader
> would check that against.** Completion is still Task-level (`task_state.isCompleted`). `★` and the
> configured actions are not: they live on the **Task–Asset pair** (`task_asset_state`, one row per
> Asset, holding a single `selections` column since v0.5.4), so two files of one folder-as-Task can
> carry different tags, and the badge row under an item's name is now the *file's* rather than the
> item's.
>
> The decision was taken deliberately, and the argument for it is in
> [v0.5.1](iterations/v0.5.1.md#the-record-still-belongs-to-the-task): the record is the **Task's**
> current state — it is *about* one of the Task's Assets and *owned* by the Task, and the Asset is
> the key it is filed under rather than the object that holds it. The evidence offered for that
> reading is structural: the table is `task_asset_state` and not `asset_state`; it is written through
> the Task's own state row's transaction; and deleting the Asset takes the record with it, as a
> Task's state cascades from its Task. The alternative — decision columns on `assets` — was rejected
> because `runImport` is the sole writer of the asset rows, and judgment in those columns would make
> the import run the co-owner of a column it must never write.
>
> It is recorded **here** and not only there because until now the argument existed solely inside an
> iteration document, and the sentence above read on its own says the opposite of what the code does.
> Nothing in the paragraph is repealed: what is now pair state is still the Task's current state, and
> still belongs to no List membership. What a later reader should treat as open is not the decision
> but its **shape** — whether *"current state belongs to the Task"* is the right sentence for a model
> whose current state is one value at the Task and several at the pair, or whether the rule should
> narrow to *completion* with marks named as pair state in a sentence of their own. The column that
> merged `isFavorite` and `customActions` into the pair's one set is v0.5.4's schema step — see
> [the plan](iterations/v0.5.4-plan.md) §1.

TaskState may include current responses or action selections as the product grows, but those values must remain distinct from historical review records and from exporter-specific wire values.

### ReviewEvent and ReviewResult

A **ReviewEvent** records a judgment that a person submitted at a point in time, through a Task Review interaction. It is append-oriented history, not the mutable current row.

A ReviewEvent is not a change log for TaskState. Current state can move for many reasons that are not somebody rendering a verdict: an administrative correction from a list or index screen, a bulk edit, a source file going missing, an import. Those update TaskState and record nothing. What produces a ReviewEvent is a **review submission** — the person was presented with a Task through a Task Review Template and committed a response to it.

This is why the two cannot be derived from one another. "Score 4, category B, comment attached", "Again / Hard / Good / Easy", and "reviewed and marked complete" are submissions carrying a response. Ticking a checkbox on an index screen to tidy up is a state edit. A design that mints an event from every state mutation cannot tell those apart, and will report work that nobody did.

A review event should be able to retain:

- its own stable identity;
- the Task identity;
- the exact Task Review Template Version that gave the response meaning;
- a timestamp;
- semantic response/action data;
- enough provenance to interpret the event later.

**A caught event is stored where it belongs.** v0.8.1 adds `review_event_answers`, a child of a
ReviewEvent keyed `(reviewEventId, ordinal)` and cascading from it: one row per event the viewer
handed the template, in the order reported. It is **derived, not canonical** — the ReviewEvent's
response is still the record, and the rows spell the value with the same grammar the response line
uses (one encoder in `domain/`), so no reader has to learn a second format. `assetId` is provenance:
the Asset a point was taken on, nullable and with **no foreign key**, because a file may be gone while
the fact that a point was taken on it stands. This table records what happened; **whether a judgment
can be revised is template policy, not a schema constraint**, and a future revisable-answer family
would keep its current value in a separate row rather than rewrite history here.

A **ReviewResult** is a current or completed structured outcome suitable for local use or export. It may be projected from TaskState and ReviewEvents; it must not be modeled as a webhook body or spreadsheet row.

The invariant is:

> Current state describes what is true now. Review history describes what happened.

For example, a Task may currently be unchecked and still have two prior review events. Unchecking must not erase the fact that previous judgments occurred. Likewise, `review_count` is derived from review history — it counts submissions. It is not another name for `checked`, not a tally of how often `checked` became true, and not a manually synchronized counter when the events themselves can answer the question.

Migration from a system that stored only current state must preserve that state without inventing historical facts that were never recorded.

**v0.8.x makes the committed response richer, and keeps the state/history split.** A new primary
submission records a response-encoding version, the Task Review Template version, the Viewer
capability version that produced the interaction, per-Asset control versions where applicable, and
the structured content revision. These are provenance, not new mutable state. Old events are not
rewritten or reinterpreted: known legacy encodings are decoded as far as they actually go, an
unknown encoding is preserved as unreadable-with-diagnostic, and absent provenance stays absent. A
historic aggregate selection is not reconstructed into per-Asset choices.

One **ReviewSubmission** service owns the explicit commit: it validates composition, response, and
observed revision, then atomically appends the event and updates the permitted TaskState. Media and
structured hosts both use it. State-only edits — including Add-on answer updates — stay outside it.
The existing content-presentability check and completed-List reporting remain, and the check must
not pass a zero-Asset structured Task merely because `assets.all(...)` is vacuously true;
composition is checked at the write boundary, not only when the UI opens.

## Identity

Every durable core entity uses a stable, opaque, globally unique ID: at minimum Task, Asset, Template, TemplateVersion, and ReviewEvent. IDs must not be derived from a local path, filename, row order, List position, display name, or mutable template name.

Different identity concepts must remain different:

- **Domain ID:** the stable identity of a Task or Asset inside the general model.
- **Source identity:** how an importer or local content resolver recognizes material, such as a Storage Access Framework root plus relative path.
- **Membership ID:** the identity of one Task's entry in one List.
- **Legacy public item ID:** the existing webhook-facing identity for one arrival/registration of an item.

The current public item ID contract is not a substitute for Task identity. In the legacy media system, the same file can arrive again and receive another public ID. That contract may remain useful to downstream webhook consumers, but it must be translated at the compatibility boundary rather than imposed on the new domain.

**v0.6.0 makes that translation explicit rather than derivational, and this is where the decision is
recorded.** The export body gained a Task level, so the wire now carries two arrival ids: one per
Task and one per Asset. Both are stored as `publicId` columns *beside* each row's domain id, minted
at row creation, and neither is ever used to refer to a Task or Asset inside the domain. The
distinction above survives: the domain id answers *which Task is this*, the public id answers *which
arrival did the receiver see*, and a re-arrival of the same material still gets a new public id
under the same kind of domain identity. Storing the wire id on the row rather than minting it at
delivery is what makes it stable across resends and across two delivery paths that no longer share
a registration side table. An upgrade backfills existing rows from the old wire names where they
exist, so old records still join; see [Core concepts → Item identity](core-concepts.md#item-identity).

Identity must survive ordinary edits, reorderings, template renames, and movement between review contexts. Destructive identity merges or splits require an explicit migration policy.

## Templates

A **Template** is a stable, user-recognizable lineage. A **TemplateVersion** is an immutable definition used to interpret a particular review or viewer configuration.

Editing a template creates a new version. Completed and historical reviews continue to reference the exact version under which they were made. A display name can change without changing identity.

### Template kinds

Template kinds are explicit. At minimum, the architecture distinguishes:

#### Asset Viewer Template

Defines how one compatible Asset is presented and which interactions belong to that content surface. Examples include the existing image/video presentation and the PDF viewer added in v0.7.0.

It may define:

- compatible Asset kinds;
- rendering behavior;
- viewer chrome and content-specific controls;
- viewer-owned gestures such as zoom, pan, scroll, playback, or scrub.

It does not define Task-level judgment, List progress, Task completion, or Task navigation.

**The PDF viewer is built behind a renderer-neutral seam.** PDFium is reached only through a small
session interface — open a document, read a page's characters and text, and paint a page — and the
anchor's stored character hint names the parser that produced it
(`nucleus-pdfium/<version>`). The renderer is therefore a **capability choice, not an architectural
one**: replacing PDFium changes the adapter and invalidates the character hints (the quotation is
re-found by text), but it changes no Room column, no template, and no exported field. The
[AndroidX PDF gate](iterations/v0.7.0.md#renderer-decision-and-the-androidx-gate) is written down
because a swap is expected to be possible, not because one is planned.

#### Task Review Template

Defines the interaction and result of reviewing a Task.

Conceptually it may describe:

- required Task data/capabilities;
- the review layout or interaction profile;
- semantic actions and response fields;
- completion rules;
- how Assets and their viewers are composed into the review surface.

It does not aggregate raw inputs into Tasks and does not execute downstream workflow.

### Capability, not profession

Core templates describe reusable capability: asset viewing, metadata presentation, rating, single choice, multiple choice, text note, or another demonstrated primitive. User-facing templates may be named “Faculty Candidate Review,” but Core must not branch on `candidate`, `CV`, `shortlist`, `faculty`, `top_5_percent`, or equivalent business terms.

Template abstraction does not imply a component table, drag-and-drop builder, marketplace, plugin runtime, or universal schema language. Those are separate product decisions. A small number of code-defined, versioned built-ins is a valid first implementation.

**v0.5.4 moves the Asset Control band past that first implementation**, and the move is recorded here because this paragraph is what a reader would check it against. A template in that band is now a **stored row** rather than a code-defined constant: it has a `templates` lineage with a name a person may change, immutable `template_versions` rows an edit appends to, a **scope** deciding which files it governs, and **buttons** rather than a fixed tag set. The built-in is still seeded from code and still carries its original id, so nothing above changes for the two kinds this section names. What the release does *not* take is any of the separations the paragraph above lists: there is no component table, no builder, no plugin runtime, and the button vocabulary is a small closed set of two types. One thing about the attachment is a rule rather than an implementation detail and is stated here for that reason: **a Todo holds one version of a lineage, not several.** The version records how the template stood when a run happened, which is what lets a completed Task go on meaning what it meant after an edit; it is not a choice being held, so *use the media control* is a thing a Todo has or does not have, and updating it is a move rather than an addition. See [v0.5.4](iterations/v0.5.4.md) and [ARCHITECTURE → Identity](#identity) for the id rule the minting had to obey.

## Add-ons

An **Add-on** is a stable lineage of **questionnaires about a Task as a whole**. It is the Task layer
of judgment, beside the Template layer that judges a Task's files. `addons` holds the lineage and a
name; `addon_versions` holds immutable versions whose payload is an ordered list of questions —
**Rating** (1–5), **Yes / No**, or **Text** — exactly as `template_versions` holds buttons.

An add-on is **not an Asset and not a row in `templates`**. `assets` means files a Task presents:
they are paged, counted, checked for missing sources, given a chip row and a file Info sheet, and
exported as `assets[]`. A questionnaire is not a file. A `templates` row would have to carry a
nullable scope, nullable buttons and nullable questions at once, and every reader would decide which
half of the row is real. The two share the editing model and the Settings screen; they share no
storage.

**An add-on's answers belong to the Task.** `task_review_answers` is keyed `(taskId, questionId)` and
cascades from `tasks`; it has **no foreign key to `addons`**, because the add-on a row was authored
under is a plain id-and-version pair. That is what makes **hidden, not deleted** possible: an answer
is visible while the Task is still attached to the same lineage and the version governing it now
still offers the same question id as the same type. Removing a question, changing its type or
detaching the add-on hides the answer; restoring them brings it back. The rule is the one PDF marks
already follow on the pair layer.

**The page is the Task's, not a file's.** A Task whose import carried an add-on has one extra page
after its last Asset, counted in the navigator. The position is fixed; there are no start/end
attributes, no required flag and no completion precondition in this round. The completion checkbox
remains a direct path.

**One add-on per Todo.** The Todo holds one reference (`sources.addOn`, `addOnId \t version`);
every Task it imports is stamped with the version that run used (`task_imports.addOn`). A refresh
adopts the offered version, as with templates, and unticking removes the reference without deleting
answers. The export writes the Task's answers as an `addOn` object **after `assets`**, and raises the
body to version 4 only when at least one visible answer exists; rounds with nothing to say are
unchanged at 3 or 2. See [v0.7.1](iterations/v0.7.1.md) and
[Templates → Add-ons](templates.md#add-ons).

**v0.8.x does not restructure this model.** The scope decision for the framework round is explicit:
at most one Add-on per Todo, stamped on import, last page, answers as Task-owned current state,
replace-on-re-answer, hidden-not-deleted, no required flag, no completion gate, and the existing
`addOn` export object. No Add-on answer becomes part of `ReviewResponse` or `ReviewEvent`, and no
Add-on snapshot is added to events. A questionnaire that is **primary Task content** in v0.9.x is a
different thing: it arrives as a structured payload and is submitted through a Headless Review
Template. The two must not be merged by convenience.

## Review surface and navigation

The review hierarchy is:

```text
Review context
└── Task Navigator
    └── Task Review Surface
        ├── Task content / metadata
        ├── Asset Navigator
        │   └── Asset Viewer
        └── Task Review Controls
```

**v0.8.x adds a typed page model to this hierarchy without changing the navigator boundaries.** A
Task's review pages are, in order: an optional structured **content page** (the payload), the Asset
pages in their existing order, and the existing optional Add-on page. The payload is page 0, not a
synthetic Asset. A payload-only Task has one labelled content page. Resources are rendered inside
their referring page and never increase the count. This one list is the source for rendering, the
position count, resume identity, and the queue guide's second level; `taskPageCount`, the
queue-entry builders, and selection must not grow independent rules.

A structured content page is an explicit **decision target** of its own. Opening it, selecting in it,
showing its Info, navigating to it, and exporting it must not disguise it as
`DecisionTarget.File` or as the legacy manual-item case. Every exhaustive target dispatch is
reviewed when the target is introduced.

### Task Navigator

The Task Navigator changes the current Task or returns to the containing List. It owns concepts such as previous Task, current Task position, next Task, and back to List. It wraps the review surface and remains outside every Asset Viewer.

Task controls must not be hidden inside a filename or another Asset property. A filename belongs to an Asset and cannot become the conceptual owner of Task navigation.

Navigation is pure. `previous Task` and `next Task` change which Task is in front of the person and nothing else. Specifically, neither may mutate TaskState, mark a Task complete, append a ReviewEvent, trigger List completion, trigger a delivery, or otherwise imply that the Task being left has been reviewed. Reaching the last Task by navigating is not completion; the forward control is simply unavailable there.

A workflow may still chain a judgment to an advance — "record this decision, then show me the next one" — but that is one semantic review action whose *consequence* is navigation, not a navigation action that quietly carries a judgment. The two must remain separately invocable, so that a person can move through a queue without being taken to have judged anything.

### Asset Navigator

The Asset Navigator selects among the current Task's Assets. It never changes the current Task. Its state and controls are separate from the Task Navigator even when a Task has exactly one Asset.

Architectural existence does not require visual existence. The Asset Navigator is always present as a boundary in the composition and state model; whether it draws anything is a presentation decision. For a Task with a single Asset it should draw nothing, because a `1 / 1` indicator is chrome that tells the person nothing and costs room on the content surface. When a Task carries several Assets, the same boundary renders a real selector without the hierarchy changing.

What may never happen is the two navigation concepts collapsing in the domain or state model to match what is currently on screen.

The **queue guide** is the position label itself: tapping `x/y` or `x(m/n)/y` opens a list of the
current queue; it is not a separate navigation mode. When the queue holds whole Tasks, level 1 lists
them and an arrow opens a Task's pages; tapping the Task name lands on its first page. A single-page
Task is a leaf, not a one-row submenu. The guide is navigation only: opening it, moving in it, and
closing it write no completion, event, or resume position. v0.8.2 extends the second level from
"Assets + Add-on" to the typed page model above.

### Gestures and semantic actions

Raw input maps to named semantic intent before it changes domain state:

```text
gesture / button / shortcut
            |
            v
semantic review action
            |
            v
state transition + ReviewEvent
```

The same semantic action may be invoked by a swipe, button, keyboard shortcut, or accessibility action. Domain logic must not store or branch on a physical gesture such as `swipeLeft`.

Asset Viewer gestures belong to the viewer unless a Task Review Template explicitly binds a non-conflicting semantic action to one. A template may bind either a review action or a navigation action this way — what makes the binding legitimate is that a named template owns it deliberately, not which dimension the action belongs to.

What may *not* happen is generic Task navigation helping itself to a gesture. Scroll, pan, zoom, playback, selection and anything else needed to inspect an Asset stay with the viewer, and the Task Navigator's own entry point is always an explicit control. A rapid-media template binding a horizontal swipe is that template's choice; it does not become the way Listea navigates Tasks, and the PDF viewer must still be free to claim the gestures its content needs.

**The PDF viewer is where that freedom was exercised**, and the result is a useful boundary case.
Vertical scrolling and pinch zoom belong to the page — reading is not navigating — and text
selection belongs to the reader whether or not a template exists. Only a **horizontal swipe at the
page edge** remains the Asset Navigator's, so the queue still advances exactly as it does over a
picture. The rule survives in both directions: the viewer took the gestures its content needed, and
navigation kept one gesture that no reading gesture needed.

The current rapid-media interaction may keep a left swipe bound to the semantic `checked/complete` action and advance after that action. The state transition is the meaning; advancing is review-flow behavior, not proof that swipe is the universal Task Navigator.

## Review rounds, queues, and progress

A review round is a pass through a snapshot of an ordered queue. Changing current state during a round must not make the active card vanish or unpredictably renumber the queue. Filtering narrows what the round walks; it does not redefine total List progress or completion.

Queue membership and order are review-context concerns. Task identity and TaskState remain valid after a round ends. Resume state points to stable membership or Task identity, never an array index whose meaning changes after synchronization.

## Import and export boundaries

### Importer / Task Builder

An importer or task builder translates raw sources into normalized Tasks and Assets; v0.8.x adds an optional Task payload and Resource references to what a plan may produce. It owns source-specific grouping rules, such as whether a folder, manifest, CSV, or set of related filenames forms one Task.

Core receives the result and must not recreate those rules. Adding a new source should be an adapter concern rather than a reason to add business-specific fields to Task.

This boundary is also where Task *authoring* belongs. By [Task creation ⟂ Task review](#task-creation--task-review), the review surface does not double as a place to invent Tasks: a free-text field that mints a Task inside a review context is an upstream responsibility leaking downstream, and it is the reason a review engine slowly acquires a CRUD interface it was never meant to have. Shopping lists, vocabulary sets, AI-extracted candidates, CSV rows and database queries should all arrive through one deliberate creation boundary, whether or not that boundary ships in the same binary.

### Exporter / Action

An exporter translates canonical review results into an external representation or side effect. JSON, CSV, XLSX, webhook delivery, and future integrations are possible adapters. None is the canonical internal model.

Exporter configuration must not appear on Task, Asset, or Template merely because one current workflow uses it. Delivery attempts and delivery history may have their own persistence at the boundary.

These boundaries do not require a generalized importer/exporter framework in the first iteration. Preserving a clean seam is sufficient until multiple implementations justify an abstraction.

**v0.5.6 takes both sentences above at their word, and this paragraph is what a reader would check
the result against.** Until then the *mechanism* was in the model — a POST URL column on every List,
a `webhookEnabled` flag beside it, a `webhook_history` table — which is the thing *"None is the
canonical internal model"* forbids. An **export** is now the act and an **export kind** is how it
happens: `webhook` and `txt` are the two this build ships, named by a closed enum, so the set of
kinds is the set of exporters and cannot be a table. A kind this build cannot read decodes to *export
nothing* rather than to a guess, which is v0.5.3's failing-closed rule one level up.

Where the choice is stored is where the prohibition above comes nearest, so it is stated rather than
implied. **A Todo names one kind; a List names one choice and overrides the Todos it holds; and a
List that has said nothing is not a List that exports nothing** — its round is split by the Todo each
item came from, and each group leaves by its own Todo's kind. Nothing in `tasks`, `assets`,
`templates` or `template_versions` holds any of it, so the sentence above stands. Two consequences
are worth naming because neither is obvious from it:

- **Export configuration now reaches a Todo**, which sits on the Task-creation side of the boundary.
  This document had not said whether that is allowed. It is recorded here as permitted for the reason
  the opening paragraph gives: an exporter is a boundary in its own right, and what a run of Tasks
  will do with its results is a property of the run, not of any Task inside it.
- **A List may hold Tasks from several Todos with different kinds, and export more than once per
  round.** That does not touch [the one-Task-Review-Template rule](#list-and-listentry), which is
  about the review surface and not about where results go; the architecture's "one List remains
  homogeneous" is a statement about the review interaction and completion complexity that sentence
  names, and this release adds neither.

The List half is not new — [Legacy compatibility is not architecture](#legacy-compatibility-is-not-architecture)
already allows a List to display and configure a delivery. Delivery history stays at the boundary, as
the paragraph above permits: the table keeps its `webhook_history` name and gains a `kind`, and it now
records every kind's attempts rather than one kind's. See [v0.5.6](iterations/v0.5.6.md).

**v0.6.0 turns the seam into a published contract, and stops treating the body as a rendering
detail.** One `ExportPayload` holds the round as `ExportTaskView`s, each with its Assets and its
attached template identities, assembled once from the database before either renderer runs; the two
kinds spell the same values. The body is versioned (`2`), the previous flat `items` shape is a hard
cut, and a stored history record resends its original bytes rather than being re-rendered under the
new contract. That last rule follows from the boundary rather than despite it: a history row is a
record of *what was sent*, not a projection that may be recomputed when the format changes.

Two placements inside the payload are architectural rather than cosmetic. **Completion sits on the
Task**, because checking off is one judgment however many files a Task presents. **`relativePath` and
`actions` sit on the Asset**, because both describe a particular file: the first is its location
under the Upstream, independent of how the Task was packaged, and the second is resolved through the
control that governs that file's own kind, never a Task-wide union. The values are a snapshot of the
round as it ended, not a review history; a timestamped history would be an additive level, not a
reason to change these. See [v0.6.0](iterations/v0.6.0.md) and [Webhooks](webhooks.md).

**v0.6.2 moves the destination onto the row that decides the export, and links a round's records.**
A Todo's address is a column on `sources`, written as a copy at Create/Refresh and never read live
from Settings; `lists.webhookUrl` is read again as a List's deliberate override — applied only when
that List's method is Webhook, since v0.7.0 — and an empty value means *follow the Todos*. The
Settings default survives as the value a new Todo copies and as the fallback for Tasks with no
owning Todo — a source of values, not a global gate. This keeps the
boundary paragraph above intact: the address is part of the export configuration the Todo already
owns, not a new fact about a Task. Delivery history gains the attempted address and a **round id**
on `webhook_history`; the id groups rows produced by one `deliverPayload` without changing the
one-row-per-actual-export rule. The default and the old gate live in DataStore, which a Room
migration cannot read, so schema 27 leaves a marker and a one-time bootstrap copies the value (only
when the old gate was on) inside the same transaction as the first address write. See
[v0.6.2](iterations/v0.6.2.md).

**v0.8.x adds the missing pure mapping stage.** The pipeline is: database → one round snapshot →
the existing export-choice/grouping plan → `ExportMapping(contract version)` → serializer → delivery
→ original bytes in history. The mapper consumes the snapshot and never queries the database; it
may select, rename, group, or restructure fields, but it may not redefine review meaning or mutate
stored responses. Existing v2/v3/v4 bodies remain byte-compatible for equivalent snapshots. Export
**v5** is introduced only for rounds containing new structured content/results, with its schema
documented before the first structured fixture; a mixed round uses the new envelope while preserving
the media and Add-on field semantics. Saved history is resent from its original bytes, never
remapped, and a resend never gains a later contract version.

## Source material, import, and review selection

The path into Core contains several distinct concepts and operations:

```text
External material
    |
    v
Upstream registration
    |
    v
Importer / Task Builder -------- Import provenance
    |
    v
Normalized Tasks + Assets
    |
    +---------------------> Upstream-backed catalog / browse projection
    |                                      |
    |                                      +--> Quick Review context
    |                                      |
    |                                      +--> Task selection
    |                                                |
    +------------------------------------------------+--> persistent List + ListEntries
                                                                |
                                                                v
                                                            Task review
```

The interface may present several of these stages as one convenient motion. The domain must still keep them separate. Opening a folder, interpreting its contents, selecting Tasks, and creating a List are not one operation whose meaning changes according to the current screen.

### File, Source, and Upstream

A **file** is raw external material. It may be discovered through a local folder, document provider, cloud service, archive, or another source. A file is not a Task merely because Listea can open it, and it is not an Asset until an importer deliberately attaches a representation of it to a Task. It has no List membership, TaskState, completion, or review history of its own.

A **Source** is a file *outside* the system: an objective thing that exists whether or not Listea has ever heard of it. Source is therefore not a domain concept Core owns and not a name for anything stored. It is the word for what an Upstream was before someone chose it — the reason the app says "add an Upstream" rather than "add a Source", and the reason **no user-facing surface names a Source as a registered origin**.

> **The v0.8.x decision is to keep the word only in its external-material sense, and to keep the
> table name.** The three surviving uses are resolved as follows:
>
> 1. **Storage and query layer.** `sources` remains an internal table name that holds **Todos**
>    (`SourceEntity`). It is a known compatibility misnomer; renaming it would touch every query,
>    migration and wire value for no behavioural gain. No second Source model is introduced.
> 2. **Todo dialog messages.** `"Could not read this Source's folder. Nothing was changed."` and
>    `"This Source is no longer registered."` name the Todo and are a wording defect; they are to be
>    corrected to **Todo** in a later wording pass, not by inventing or renaming a model.
> 3. **The List detail card and the file Info row.** There, **"Source"** is the plain-English sense —
>    where the material lives — not a registered origin. It may stay.
>
> No screen offers a Source as a thing to register, and the v0.8 Package protocol adds no Source
> entity. The open item is therefore closed for this round: the word survives only as external
> material, and *producer* remains the name for the upstream system that prepares it.

An **Upstream** is a Source that has been chosen: the point where external material enters the system. A folder is one Upstream kind, not a universal container in Core, and a device may hold several at once — each with its own access grant, because granting a second folder must not cost the first. An Upstream retains a stable internal identity, external root locator and access information, display name, and enough provenance to reconcile later imports.

**An Upstream is material, not work.** It holds no Tasks, has no completion, and owns no review context: it is a folder the app may read, and nothing more happens on it than browsing it and running an importer over part of it. What an importer produces is a **Todo**, and the two are separate records because they have separate lifetimes — an Upstream outlives every Todo made from it, and a Todo says nothing about which folder it came from beyond the provenance that lets a later run find it again.

Upstream identity remains distinct from Task and Asset identity. A path or URI may help recognize the same external material again, but it is never a globally stable domain ID. Upstream availability also remains distinct from Task lifetime: removing access to an Upstream, or finding a file missing, must not silently delete TaskState or ReviewEvents.

### Import decides Task boundaries

The importer owns the grouping decision:

> Which source records or files together form one reviewable Task?

Examples include one file becoming one Task with one Asset, one immediate child folder becoming a Task containing several descendant files as Assets, one CSV row becoming a structured Task, or an upstream vocabulary system emitting the prompt due today.

The same folder tree may therefore be interpreted differently by different explicit importers. The choice is made during import, recorded as provenance, and remains stable during review. The current folder or screen must never decide Task identity after import.

Once created, a Task is indivisible from the point of view of review selection:

- selection includes the Task, not an arbitrary subset of its Assets;
- a ListEntry points to the Task, not to the files currently visible;
- Quick Review may include the Task only when it is complete in the selected scope;
- browsing a narrower folder must not split the Task or mint a replacement identity.

Changing Task boundaries is a new import/reconciliation operation. It is never an effect of creating a List or entering Quick Review.

### Upstream structure is not Task structure

Folders and other Upstream hierarchies help an importer discover material and help a Todo present it. They do not define universal Task ownership.

For example:

```text
upstream/
├── candidate-a/
│   ├── cv.pdf
│   └── research.pdf
└── candidate-b/
    └── cv.pdf
```

A file-as-Task importer may produce three Tasks. A folder-as-Task importer may produce two. If `candidate-a` has already been imported as one Task, entering that folder and choosing Quick Review or Create List cannot reinterpret its two PDFs as separate Tasks.

### Catalog, scope, and Task groups

A **Task Catalog** is a browse-and-selection projection over imported Tasks. It is what opening a Todo shows. It may preserve the Upstream's hierarchy and present top-level labels such as “Picture Classification” or “Candidate Selection.”

The interface may call such a top-level view a **Task Group**, but Task Group is not initially a new Core ownership entity. It is an Upstream-backed catalog scope, or a saved browse projection, unless a demonstrated requirement gives it independent identity, membership, lifecycle, permissions, or behavior.

Therefore:

- a catalog/group does not own TaskState or ReviewEvents;
- deleting a List does not remove a Task from its catalog;
- a List may select Tasks from more than one catalog scope;
- catalog hierarchy does not become List hierarchy;
- moving through folders does not change Task identity;
- future non-file Tasks can appear in a catalog without pretending to be files.

**v0.8.x adds a Package scope inside a Todo.** It is identified by Todo and Package provenance, lists
the Package's normalized Task records, and does not fabricate a file path for a payload-only Task.
Legacy `groupPath` containment remains for file imports. Selection uses Task IDs and reports
Tasks/pages rather than requiring a file count. Quick Review over a Package scope selects its direct
Task records; legacy folder Quick Review keeps its direct-child behaviour. The whole declared Task
belongs to that Package scope (or an ancestor Todo scope), including all of its Asset references; a
shared Resource does not split it. Runtime readiness additionally requires a readable supported payload, compatible
composition, and all required dependencies. Optional missing Resources do not make the Task partial.

### Complete containment in a scope

An Upstream-backed Task is **complete in a scope** when every Asset required to present it is resolvable through and contained by that scope. For file-backed Tasks:

```text
required Upstream-backed Assets(Task) ⊆ Assets resolvable in Scope
```

Merely intersecting a Task is insufficient. If a scope exposes only some Assets of a Task, the Task is partial there. A partial Task may be shown to explain the situation, but it cannot be reviewed or added to a List as though it were a smaller Task.

Completeness is selection eligibility, not Task health or TaskState. A Task can be incomplete in one browse scope and complete in a wider one without changing identity, content, or judgment state. Tasks without file-backed Assets use an appropriate catalog scope rather than a fabricated folder location.

### Import is independent from selection

The action that used to do all of this at once — pick a folder, import it, and open something to review — is gone, and what replaced it is the split this section asks for. Adding an Upstream registers material and stops there; importing is a **Create Tasks** run of its own, started inside the Upstream on the folder it is to be run over; what to review is chosen after that, from the Tasks the run produced. The operations and their failure states remain independent:

1. **Add an Upstream** identifies external material and registers it. It creates no Task and no review context, and it is a Settings action because it is about what the app may read rather than about what there is to do.
2. **Import** creates or reconciles Tasks and Assets using an explicit importer, and writes a Todo to hold them.
3. **Select Tasks** chooses already normalized, complete Tasks. It happens inside a Todo, as **Create List**, and not on the Todo tab — a row there stands for a Todo, and selecting from it would be the row pretending to be the screen behind it.
4. **Start Quick Review or create a List** establishes a review context.

Failure to import must not create a partly meaningful List. Failure to create a List must not roll back otherwise successful imported Tasks. Reopening a catalog scope must not re-import merely because the user wants to review it.

User-facing language should name the intended operation: **Create Tasks** to import, opening a **Todo** to see what an import produced, **Quick Review** for an ephemeral round, **Create List** for a persistent one. None of these is a synonym for another, and none should be presented as one. Opening a Todo is not itself an operation with a name, which is why it is the Todo's card rather than a button on it.

### Quick Review and List are independent review contexts

Quick Review and List Review consume the same normalized Tasks and use the same TaskState and ReviewEvent semantics. Neither defines Task boundaries.

**Quick Review** creates an ephemeral ordered review context over complete Tasks discoverable in the current catalog scope. It does not implicitly run an importer for already imported material, create or reshape Tasks, or create a persistent List merely to hold its queue. “Quick” describes persistence and entry cost, not a partial form of review: the judgment target remains a complete Task.

A **List** is a named, persistent, ordered review context. Creating one selects existing Tasks and creates ListEntries. It must not scan raw files to infer Tasks or alter Task-to-Asset membership. A Task may appear in Quick Review and several Lists while retaining one identity, current state, and review history. Deleting the List removes membership and context only.

An interface may offer **Import and create List** as a composed workflow. Internally it remains `Import`, followed by `Select Tasks`, followed by `Create List`, with separately meaningful failure and retry behavior.

### Independence matrix

| Concept | Defines Task boundary | Owns TaskState/history | Owns ordering | Owns external location | Persistent review context |
|---|---:|---:|---:|---:|---:|
| File | No | No | No | Source system | No |
| Upstream | No | No | Upstream browse order only | Yes | No |
| Importer | Yes, during import | No | May assign Asset order | Interprets it | No |
| Asset | No | No | Asset order within Task | Retains locator/provenance | No |
| Task | Is the resulting boundary | Yes | No | No | No |
| Catalog scope / Task Group | No | No | Browse/display order | May project Upstream hierarchy | No |
| Quick Review | No | No; writes through Task | Ephemeral queue order | No | No |
| List / ListEntry | No | No; reads/writes through Task | Yes | No | Yes |

### Re-import and reconciliation

Re-import is the routine operation allowed to reconsider imported material. It must be explicit and use stored Upstream and importer provenance. It is started from inside the Upstream, on the folder it is to be run over, so that reading part of an Upstream is an ordinary thing to do rather than one that registers anything.

It must distinguish unchanged material, newly discovered material, changed content or metadata at an existing source locator, material no longer resolvable, and a requested change of importer/grouping rule. Unchanged material should reconcile to the same Task and Asset identities. Missing material must not erase review state or history by default.

> **"By default" is load-bearing, and it came in with v0.4.0.** As written, this sentence was
> absolute, and the shipped content policies make it false in two of their three tiers: `reset`
> clears a Task's *current state* when its Assets change or go missing, and `drop` deletes the Task
> and its decisions — including when the change is purely a disappearance, on a Task holding more
> than one Asset. Neither is an inference: both are a user having named a policy for that Todo, in
> advance, in writing, which is the distinction
> [Asset structure ⟂ Task lifecycle](#asset-structure--task-lifecycle) rests on and the one this
> sentence did not anticipate.
>
> The invariant that survives, and the one that was actually load-bearing, is this: **a change
> nobody has named a policy for erases nothing.** `keep` — the default for every Todo that has not
> chosen otherwise, and the behaviour of every version before v0.4.0 — flags what has gone and
> changes nothing else, so an upgraded install behaves exactly as it did. The two sections should
> be read together; this one is the rule, and that one is the single, bounded, user-named exception
> to it.

Changing from file-as-Task to folder-as-Task can merge or split Task identity and is therefore not an ordinary refresh. It requires an explicit migration policy; until one exists, the system must create a separate import or reject the change rather than silently rewriting reviewed Tasks. Since v0.4.0 the *first* of those two is always available and no longer needs a reason: a folder may carry any number of Todos, so "make a separate import" is an ordinary thing to do rather than a fallback, and nothing is rejected on the ground that a folder already has one.

**One file may back several Tasks, and does.** This was the open question above, and v0.4.0 closed it in favour of sharing. The reasoning is the one the escape hatch in the paragraph before already hinted at: an Asset is not the file, it is the file *presented for* a Task — so a second Task over the same file is a second Asset, and nothing is referenced twice. What uniqueness remains is `(taskId, rootUri, relativePath)`: one Task still holds one Asset per file, which is the invariant that was actually load-bearing, because two Assets of one Task at one path would be two answers to the same question.

The consequence for identity is that the sharing boundary sits at the **Task**, not the file:

| Reached through | Is | State |
|---|---|---|
| Two Todos, one file | Two Tasks | Independent |
| Two Lists, one Task | One Task | Shared |

And for reconciliation: a locator held by another Todo is not a collision, so a re-import mints its own Tasks beside them rather than reporting a conflict. Only a locator held by the *same* Todo is still a conflict, because that is the grouping rule disagreeing with itself. See [v0.4.0](iterations/v0.4.0.md).

### Package reconciliation and checkpoints (v0.8.x, planned)

Protocol-declared Tasks reconcile on `(todoId, packageKey, taskKey)`; Resources on
`(todoId, packageKey, resourceKey)`. Domain IDs are still opaque and minted separately. Two Todos
importing the same Package are independent. Duplicate keys within one Package/Todo are conflicts.
Existing unkeyed Tasks keep path-based reconciliation under their existing importer; keys are never
backfilled from `groupPath`, and a keyed Task is never auto-merged with an unkeyed one merely
because paths match.

A Package run records its protocol version, key, revision/fingerprint, and locator as Todo-scoped
provenance, and keeps a **checkpoint per Package** rather than one `lastPackageRevision` scalar that
would lose multi-Package information. A missing revision does not erase a supplied key: compare
canonical normalized fingerprints instead. An unchanged revision/fingerprint with unchanged
dependency availability and Todo configuration is a no-op for content, judgments, and events — but
Resource availability is still checked, and a template/Add-on attachment refresh still honours the
existing refresh contract. A declared revision that contradicts observed normalized content is an
import conflict. Changing importer or grouping remains a separate import or an explicitly designed
migration, never a silent merge or split. Automatic learning rescheduling stays upstream: if a new
review occurrence needs independent completion, the producer emits a **new Task key** rather than
relying on a payload update to create a fresh review round.

Payload and Resource changes are new categories not named by the media Asset-set policies. v0.8.x
uses the conservative rules below; it does not silently extend `reset`/`drop` to structured history.

| Change | v0.8.x behaviour |
|---|---|
| Existing media Asset-set change | Existing per-Todo `keep/reset/drop` implementation and exceptions |
| New declared Task key | Create a new Task within that Todo |
| Resource-only change or missing Resource | Update revision/availability; no new Task or automatic judgment reset |
| Compatible structured payload revision | Retain Task ID/current judgment, record revision, preserve old events' interpretation |
| Declared structured Task withdrawn | Retain and flag it unavailable; no inferred deletion |
| Incompatible kind/schema or identity conflict | Report and block affected replacement; retain prior data |
| Importer/grouping change | Separate import or explicit migration; no silent merge/split |

For hybrid Tasks with real Assets, genuine Asset-set changes still follow the existing policy.
Resource-role changes are never manufactured into Asset-set changes to trigger it.

### Upstream/import invariants

1. A file path, URI, filename, folder name, or List position is never a Task or Asset domain ID.
2. Only an importer or Task Builder defines which raw inputs form a Task.
3. Selecting a narrower scope never splits an existing Task.
4. Quick Review and List creation operate only on normalized Tasks.
5. A review context reviews the whole Task even when the surface displays one Asset at a time.
6. Quick Review and every List observe the same TaskState for the same Task.
7. List deletion removes ListEntries, not Tasks, Assets, TaskState, or ReviewEvents.
8. Upstream removal or temporary unavailability does not silently rewrite review history.
9. An Asset Viewer never decides Task navigation, completion, or import grouping.
10. Adding PDF support changes Asset kind and viewer capability; it does not redefine Task, List, Quick Review, or import semantics.

## Framework evolution — v0.8.x (planned)

> **Status:** Direction, not shipped behavior. The released baseline is v0.7.1 (schema 29, export
> v4); the current `main` also carries the two-level queue guide. The release sequence at the end of
> this section is a set of bounded proposals; each iteration document remains the authority for what
> a release actually does.

v0.8.x prepares the framework for v0.9.x vocabulary and primary-questionnaire work. It does not ship
those products. Its job is to let structured content reach the same review engine as media without
changing the meanings already established for Upstream, Todo, Task, Asset, List, Add-on,
ReviewEvent, or export history.

The scope decision is explicit: **the shipped Add-on model is retained unchanged**. Its structures,
placement, ownership, update path, requiredness, completion behavior, and export authority are not
part of this evolution. A primary questionnaire is new structured content reviewed through a
Headless Review Template; it is not the Add-on.

### Vocabulary

| Term | Meaning | Not |
|---|---|---|
| **External producer** | The upstream system or person preparing material. Its scheduling and business logic stay upstream. | A Listea entity, a reviewer, an exporter |
| **Source** | External material in the plain-English sense; not stored and not a registered origin. `sources` is an internal table name holding Todos. | A new entity, a Task, a lifecycle |
| **Upstream** | Registered material access: root locator, grant, name, provenance. | Work, completion, a Task owner |
| **Todo** | Import intent and configuration: importer, scope, content policy, follow-up, templates, one Add-on, export choice. It owns the resulting catalog. | A review context, a List |
| **Package** | A protocol envelope (manifest plus declarations) discovered under a Todo's selected scope. It declares Tasks, Assets, Resources, keys, and revisions. | A Task, a Todo, a List, a persisted owner, a review session |
| **Importer** | A pure translator from a supported input representation to an import plan. | A renderer, a selector, a committer, an exporter |
| **Task payload** | Optional normalized structured content carried by a Task: kind, schema version, payload, revision. | A Task kind, a second Task, an Asset |
| **Resource** | A declared supporting dependency of a Task page (audio, illustration, reference). | An Asset, a page, a judgment target |
| **Viewer** | A renderer plus a versioned capability declaration that emits named intents. | A template that owns completion or navigation |
| **Headless Review Template** | A Task Review Template Version whose primary controls are supplied by the Structured Viewer; it owns validation, commit, and completion policy. | A second response type, an Add-on |
| **ReviewSubmission** | The explicit commit service that validates and atomically appends an event and permitted state. | A state-edit path, a navigation action |
| **ExportMapping** | The pure stage that turns one round snapshot into a contract version's fields. | A database reader, a stored-response mutator |

`Source` and `Upstream` are not interchangeable: Source is the material before it is chosen; Upstream
is the access that has been registered. `sources` holding Todos is a compatibility name, not a third
meaning.

### Package is a protocol, not an owner

A Package is a directory containing a minimal `.listea` manifest and local members in v0.8.x. It
declares protocol version, manifest filename, Package key, Task keys, kind/schema, short title,
payload, ordered Asset references, Resource definitions and references, and diagnostics. It is not a
domain entity: there is no `packages` table, no package ownership screen, no garbage-collection
lifecycle, and no second Task owner. Package key, protocol version, revision/fingerprint, locator,
and per-Package checkpoints are **import provenance** on the Todo/Task side. Exact byte grammar and
limits are fixed by the protocol document in v0.8.1, before fixtures depend on them.

A Todo may discover one or more Packages under its selected scope. Multi-Package runs report
per-Package failures and commit only validated Package plans; a failed Package is never interpreted
as a list of deleted Tasks.

### Importer input and plan

The current folder planner is typed around `FolderSnapshot`. v0.8.x introduces equivalent typed
inputs and planned outputs rather than adding a workflow case to that switch:

```text
ImportInput  = FolderSnapshot | ValidatedPackageManifest
ImportPlan   = planned Tasks/content/dependencies + diagnostics
Planned Task = keys/provenance + label + optional payload + ordered Assets + Resource refs
```

The platform adapter reads the folder and manifest and resolves members; the Importer is pure and
validates protocol versions, references, key uniqueness, and payload envelopes. It does not render,
select a List, commit answers, or export. Existing file-as-Task and folder-as-Task grouping is
unchanged. A Package Importer may produce zero-Asset structured Tasks. New media in a Package becomes
a Task only when the manifest declares a Task using it.

### Task payload

The storage seam is optional and one-to-one, for example:

```text
task_payloads(taskId, kind, schemaVersion, normalizedPayload, revision, updatedAt)
```

Exact column naming belongs to the iteration plan. There are no vocabulary- or
questionnaire-specific columns. Media Tasks without a payload continue unchanged.

**Content kind/schema and viewer capability are different facts.** The former says how a payload is
interpreted; the latter names the renderer and intents used to present it. A protocol-namespaced
workflow kind may cross the boundary, but Core only passes or compares registered identifiers. The
registry resolves it to capabilities; Core never branches on its business meaning. `AssetKind.OTHER`
is not a structured payload kind. The import label is the Task's queue/page label; the long question
or prompt stays payload content.

### Structured Viewer and Headless Review Template

A Structured Viewer renders the payload and resolves Resources through an adapter, and emits named
intents to its host. It does not call a DAO or a delivery adapter, and it does not own Task identity,
import grouping, Task navigation, completion policy, or delivery. Its capability declaration is
versioned and hashable, identifies supported kinds/schema ranges and emitted intents, and is
distinct from `MediaPreview`/`PdfPreview` and from stored Asset Control templates.

A Headless Review Template is a Task Review Template Version that owns the response contract
without drawing a duplicate action bar. It declares:

```text
commitOn: named intent(s)
completesTask: explicit policy
advanceAfterCommit: explicit policy
```

Completion is never inferred solely from a response value type, and the UI cannot always force
`completed = true`. The media adapter preserves current semantics through an equivalent policy.
Automatic advance happens only after a successful commit and remains a consequence of the review
action, not navigation.

For upstream-defined choices, the ownership is:

| Concern | Owner |
|---|---|
| Prompt, option ID, label, legal value, order | Package payload, normalized by the Importer |
| Display and physical input handling | Viewer renderer |
| Accepted intent, validation, commit, completion | Task Review Template Version |
| Submitted answer and its interpretation | ReviewResponse and event provenance |
| External field names/layout and delivery | ExportMapping and exporter |

The initial proof implements single choice. Multiple choice, boolean, bounded number, and bounded
text are later primitives when v0.9.x needs them. A recall grade is a single choice with declared
options; Core does not acquire a `Recall` or `Vocabulary` response type.

### Validation and input limits

The protocol document fixes concrete byte, count, depth and string-size limits, canonical encoding,
duplicate-key handling, allowed local reference syntax, and per-record diagnostics before a parser
is accepted. Required behaviour:

- unknown protocol version or malformed envelope: reject the whole Package before import writes;
- unknown Task kind/schema in a valid envelope: diagnose that record and keep it unavailable; import
  supported records without claiming the unknown one is reviewable;
- an unknown update to an existing keyed Task must not overwrite its previously valid payload;
- duplicate Task/Resource/member keys, unresolved required declarations, absolute member paths,
  `..` traversal, symlink escapes, and out-of-grant references are rejected before committing the
  affected Package;
- manifest size, nesting, record counts, and member access are bounded; oversized input is rejected
  rather than partially parsed;
- only granted local directory members are allowed; no code execution, remote fetch, or upstream
  writes;
- archive/zip and embedded-binary inputs are explicitly unsupported and rejected without extraction.

A runtime member disappearance after a valid import is availability state, not a malformed Package
retroactively erasing work. Unsupported content/viewer/schema produces a named
**no-compatible-viewer** diagnostic, and a missing required Resource produces a missing-content
reason; import diagnostics and runtime availability use the same reason vocabulary, so the user sees
one explanation rather than two.

### Migration and compatibility

There is no wholesale Task/Asset migration. Preserve domain IDs, public IDs, Lists, ordering, import
ownership, current pair state, PDF marks, Add-on answers, ReviewEvents, and export history. Existing
Tasks remain unkeyed unless their actual protocol supplies a key; `groupPath` is never backfilled as
an upstream identity, and `legacy_item_ids`/`legacy_task_ids` are reused rather than replaced by a
second registration system.

v0.8.0 needs no Room schema change: the new response envelope can live in the existing response text
column. v0.8.1 allocates the next schema versions — 30 for the Task Review definition and its
attachment, and 31 for the derived caught-event child table — each with its migration, exported
schema JSON, migration test, and fixture subtraction helper. `MIGRATION_30_31` backfills by reading
each stored response through the app's own domain reader (`ReviewResponse.parse`), so no second
grammar exists; a response that build cannot read is skipped, never guessed at. Follow
the minSdk 24 conventions: additive tables/nullable fields where possible, no dependence on
`ALTER TABLE … DROP COLUMN`, no table rebuild solely for terminology cleanup. A schema-only
migration must preserve populated state byte-for-byte where no conversion is specified. Add-on
tables and attachments are untouched.

Old response encodings are decoded as far as they actually go; an unknown encoding is preserved as
unreadable-with-diagnostic. Missing Viewer/control/content provenance stays explicitly absent and is
never inferred from today's templates. Export history resends stored bytes.

### Cross-platform boundary

```text
domain/   Pure contracts, typed intents, validation, encoding, capability resolution
data/     Room rows/DAOs, transaction adapter, migrations, snapshot assembly
import/   Pure planners plus separate platform discovery/manifest-reading adapters
export/   Pure snapshot mappings plus serialization/delivery adapters
UI/       Compose renderers, page/navigation hosts, user-facing diagnostics
```

Domain contracts and planners are JVM-testable without Android, Compose, Room, or `org.json`.
Platform JSON parsing, SAF access, audio/PDF rendering, and storage resolution stay in adapters.
Existing module/package conventions are kept where they already satisfy this boundary.

### Release sequence

| Release | Scope | Schema / export |
|---|---|---|
| **v0.8.0** | Primary response contract, content-kind/composition/submission-policy contracts, shared ReviewSubmission over the existing atomic transaction, per-Asset provenance for new events; existing media host routed through it | No schema change; existing response column |
| **v0.8.1** | Directory Package protocol and limits, typed Package planning, payload persistence, multi-Package checkpoints, keyed reconciliation, scoped Resources; structured records may import as unavailable until a renderer exists | Next schema version if needed; export unchanged |
| **v0.8.2** | Typed page list, Package catalog scope, structured DecisionTarget, selection/Quick Review, queue-label and guide integration; one Structured Viewer, Headless single-choice template, submission-time composition check; ExportMapping seam with v2/v3/v4 goldens and documented v5 | v5 only for structured rounds |
| **v0.8.3** | Second fixture: prompt/reveal, declared grades, shared pronunciation Resource; optional/required diagnostics, compatible payload revisions, independent Todo identities; freeze protocol/capability contracts | v5 fixture; migration/regression docs |

These are framework proofs, not the v0.9 product. Add-on restructuring is assigned to none of them.

The v0.8.x preparation is complete when a payload-only Task can be imported, discovered, selected,
rendered, submitted, reopened, and exported through the same contracts as media, while existing
media/PDF behavior, Add-on behavior, content policies, and v2/v3/v4 export bodies pass regression
checks.

## Legacy compatibility is not architecture

The existing app is a coherent media-review product. Its folder scanning, Quick Review, List-backed queues, per-file state, public item IDs, custom action settings, webhook configuration, webhook history, and file-management behavior remain real compatibility obligations.

They do not define the future Core.

In particular:

- a folder path may resolve or deduplicate a current Upstream-backed Task, but it is not the Task's permanent domain ID;
- `list_items` may currently mix membership and manual-item data, but `ListEntry == Task` is not an architectural rule;
- current file review state may seed TaskState, but `Task == file review row` is not an architectural rule;
- a webhook body may require a legacy public item ID, but `Task.id == webhook item.id` is not an architectural rule;
- a List may continue to display and configure a webhook, but webhook fields do not belong in Task, Asset, or Template;
- Quick Review may assemble an ephemeral queue from a folder, but it writes through the same Task-state semantics as persistent List Review.

Compatibility should be implemented with migration and adapters: same Listea outside, different Listea inside.

## Negative scope

Listea Core is not:

- a file manager or document editor;
- a generic authoring or CRUD surface for creating Tasks by hand;
- an applicant tracking or hiring workflow system;
- an email, notification, approval-pipeline, or business-rules engine;
- a place for profession-specific domain semantics;
- the component model of a universal low-code UI builder;
- an exporter-shaped database;
- a system that lets AI silently replace a person's high-impact judgment.

Evidence notes, annotations, search, synchronization, collaborative review, AI-assisted extraction, and new Task types may be valuable later. They must be introduced from real requirements and respect the same boundaries. For sensitive uses such as employment, AI may assist with locating or summarizing material, but the recorded evaluative judgment must remain attributable to a human unless the product explicitly adopts and governs a different model.

## Evolution rules

A change is architecturally additive when it can be expressed as one of the following without redefining existing identity or ownership:

- a new Asset kind and compatible Asset Viewer;
- a new Task Review Template Version;
- a new importer that produces the existing normalized model;
- a new exporter that consumes canonical results;
- a new optional Task field or response primitive justified by a real workflow;
- a new structured content kind with a compatible Structured Viewer and Headless Review Template;
- a new Package importer producing existing normalized Tasks;
- a new Resource kind resolved by a viewer;
- a new ExportMapping or export contract version.

A change requires explicit architecture review when it:

- makes Task and Asset identity interchangeable;
- makes current state and history interchangeable;
- derives review history mechanically from state mutation, or lets navigation imply review;
- makes Task lifetime depend on how many Assets a Task has;
- adds Task authoring to a review context;
- makes review state depend implicitly on List membership;
- lets a viewer own Task navigation;
- stores a raw gesture as domain meaning;
- mutates a TemplateVersion already referenced by reviews;
- introduces workflow-specific semantics into Core;
- makes a legacy transport contract the canonical internal model;
- gives a Package ownership of Tasks, Lists, or review state, or a durable Package lifecycle;
- treats a Task payload as Task identity, or derives completion solely from a response value type;
- routes Add-on current state through the primary ReviewResponse/event path;
- extends `reset`/`drop` to structured payload replacement or record withdrawal by inference;
- enables mixed-template Lists without one review contract that supports every included Task.

The practical architecture test is simple: adding a PDF Asset Viewer later should be an extension of the Asset side, not a rewrite of Task, List, Review, identity, or result semantics.

**v0.7.0 is that test taken, and it passed.** The PDF viewer, the PDF-scoped template and the
passage mark arrived as Asset-side extensions: Task, List, ListEntry, TaskState's completion half,
ReviewEvent, and both id systems were untouched. The costs were the ones this document already
permits — a new Asset kind with a compatible viewer, a new button type inside an existing template
[a version bump](templates.md#buttons) pays for, a new side table keyed to the Asset, and an
additive `marks` array behind export version 3. The one boundary the round had to state rather than
inherit is the read-only one in [Asset](#asset): Listea records judgment **about** external
material and still does not edit it, so *document annotation* did not turn Core into a document
editor.

**The v0.8.x test is the same shape on the content side.** A payload-only Task must become
importable, selectable, reviewable and exportable without making Task, List, Asset, or completion
semantics depend on a file count, without changing the Add-on, and without reinterpreting a stored
event. If a proposed change fails one of those, it is an architecture change, not a framework
detail.
