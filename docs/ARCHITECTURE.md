# Listea Architecture

> **Status:** Normative architectural direction  
> **Scope:** Long-lived product and domain boundaries  
> **Last updated:** 2026-09-15 (revised: orthogonal boundaries; navigation, submission and lifetime semantics)

Listea is a lightweight human-judgment layer between structured inputs and workflow outputs. It turns a collection of reviewable tasks into a fast, resumable feed, records what a person decided, and leaves upstream ingestion and downstream consequences to other systems.

This document is Listea's architecture constitution. It defines meanings, ownership, and boundaries that implementations must preserve. It intentionally does not prescribe Room table names, Kotlin class names, UI framework choices, or a final importer/exporter design.

Iteration documents may narrow the work for a release, but they must not contradict this document without an explicit architecture decision.

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

## Orthogonal boundaries

Three pairs of concerns are independent dimensions. A particular workflow may chain them together, and the interface may present them as one motion, but the domain must not fuse them. Collapsing any of these is the most common way a review system quietly becomes unable to represent the next workflow.

### Navigation ⟂ Review / Completion

> Navigation changes what the user is looking at. Review actions change judgment/state. They are independent dimensions even when a particular workflow chains them together.

Moving to another Task is not a statement about the Task being left behind. A person may inspect a Task, move on, and leave it unjudged; that is a valid and expected state, not an incomplete operation. Conversely, a judgment may cause the interface to advance, but the advance is a consequence of the judgment rather than part of its meaning.

### Asset structure ⟂ Task lifecycle

> The resources attached to a Task describe how its content is presented. They do not determine how long the Task should exist.

A Task with no Asset is not a lesser Task, a temporary Task, or a Task owned by whatever context happens to contain it. A Task whose content is entirely structured fields — a question, a vocabulary prompt, a record to adjudicate — is as durable as one carrying five documents. Nothing may infer ownership, scope, or deletion policy from how many Assets a Task has.

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
                                             +---- 0..1 current TaskState
                                             |
                                             +---- 0..N ReviewEvent

Task Review Template Version ---- defines the judgment over a Task
Asset Viewer Template Version --- renders one compatible Asset
```

This is a semantic model, not a required physical schema.

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

### Asset

An **Asset** is content referenced by a Task and presented through a compatible viewer. An Asset is not the judgment target and is not a queue membership.

An Asset:

- has its own stable, opaque identity, distinct from its Task's identity;
- has a media/content kind such as image, video, PDF, audio, or another future type;
- may carry a label, role, ordering information, source reference, and type-specific metadata;
- is treated as read-only by default;
- does not own Task completion, review responses, Task navigation, webhook configuration, or downstream workflow rules.

An Asset's location, filename, URI, or List position is mutable metadata and must not be its durable domain identity. Source-specific locators may still be required to resolve local content and to preserve legacy behavior.

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

The architecture does not currently introduce per-membership completion. If a future workflow genuinely requires “this Task is complete in List A but incomplete in List B,” that need may add explicit ListEntry or session state. It must not silently redefine TaskState.

### TaskState

**TaskState** is the current projection of what is true about a Task for review purposes. `checked` is one current-state field meaning “I have dealt with this in Listea.” It does not describe what the decision meant.

Current state belongs to the Task, not to an Asset and not to a List membership. In the media workflow, this preserves the existing rule that Quick Review and List Review see the same decision when they reach the same reviewable object.

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

A **ReviewResult** is a current or completed structured outcome suitable for local use or export. It may be projected from TaskState and ReviewEvents; it must not be modeled as a webhook body or spreadsheet row.

The invariant is:

> Current state describes what is true now. Review history describes what happened.

For example, a Task may currently be unchecked and still have two prior review events. Unchecking must not erase the fact that previous judgments occurred. Likewise, `review_count` is derived from review history — it counts submissions. It is not another name for `checked`, not a tally of how often `checked` became true, and not a manually synchronized counter when the events themselves can answer the question.

Migration from a system that stored only current state must preserve that state without inventing historical facts that were never recorded.

## Identity

Every durable core entity uses a stable, opaque, globally unique ID: at minimum Task, Asset, Template, TemplateVersion, and ReviewEvent. IDs must not be derived from a local path, filename, row order, List position, display name, or mutable template name.

Different identity concepts must remain different:

- **Domain ID:** the stable identity of a Task or Asset inside the general model.
- **Source identity:** how an importer or local content resolver recognizes material, such as a Storage Access Framework root plus relative path.
- **Membership ID:** the identity of one Task's entry in one List.
- **Legacy public item ID:** the existing webhook-facing identity for one arrival/registration of an item.

The current public item ID contract is not a substitute for Task identity. In the legacy media system, the same file can arrive again and receive another public ID. That contract may remain useful to downstream webhook consumers, but it must be translated at the compatibility boundary rather than imposed on the new domain.

Identity must survive ordinary edits, reorderings, template renames, and movement between review contexts. Destructive identity merges or splits require an explicit migration policy.

## Templates

A **Template** is a stable, user-recognizable lineage. A **TemplateVersion** is an immutable definition used to interpret a particular review or viewer configuration.

Editing a template creates a new version. Completed and historical reviews continue to reference the exact version under which they were made. A display name can change without changing identity.

### Template kinds

Template kinds are explicit. At minimum, the architecture distinguishes:

#### Asset Viewer Template

Defines how one compatible Asset is presented and which interactions belong to that content surface. Examples include the existing image/video presentation and a future PDF viewer.

It may define:

- compatible Asset kinds;
- rendering behavior;
- viewer chrome and content-specific controls;
- viewer-owned gestures such as zoom, pan, scroll, playback, or scrub.

It does not define Task-level judgment, List progress, Task completion, or Task navigation.

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

### Task Navigator

The Task Navigator changes the current Task or returns to the containing List. It owns concepts such as previous Task, current Task position, next Task, and back to List. It wraps the review surface and remains outside every Asset Viewer.

Task controls must not be hidden inside a filename or another Asset property. A filename belongs to an Asset and cannot become the conceptual owner of Task navigation.

Navigation is pure. `previous Task` and `next Task` change which Task is in front of the person and nothing else. Specifically, neither may mutate TaskState, mark a Task complete, append a ReviewEvent, trigger List completion, trigger a delivery, or otherwise imply that the Task being left has been reviewed. Reaching the last Task by navigating is not completion; the forward control is simply unavailable there.

A workflow may still chain a judgment to an advance — "record this decision, then show me the next one" — but that is one semantic review action whose *consequence* is navigation, not a navigation action that quietly carries a judgment. The two must remain separately invocable, so that a person can move through a queue without being taken to have judged anything.

### Asset Navigator

The Asset Navigator selects among the current Task's Assets. It never changes the current Task. Its state and controls are separate from the Task Navigator even when a Task has exactly one Asset.

Architectural existence does not require visual existence. The Asset Navigator is always present as a boundary in the composition and state model; whether it draws anything is a presentation decision. For a Task with a single Asset it should draw nothing, because a `1 / 1` indicator is chrome that tells the person nothing and costs room on the content surface. When a Task carries several Assets, the same boundary renders a real selector without the hierarchy changing.

What may never happen is the two navigation concepts collapsing in the domain or state model to match what is currently on screen.

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

What may *not* happen is generic Task navigation helping itself to a gesture. Scroll, pan, zoom, playback, selection and anything else needed to inspect an Asset stay with the viewer, and the Task Navigator's own entry point is always an explicit control. A rapid-media template binding a horizontal swipe is that template's choice; it does not become the way Listea navigates Tasks, and a future PDF viewer must still be free to claim the gestures its content needs.

The current rapid-media interaction may keep a left swipe bound to the semantic `checked/complete` action and advance after that action. The state transition is the meaning; advancing is review-flow behavior, not proof that swipe is the universal Task Navigator.

## Review rounds, queues, and progress

A review round is a pass through a snapshot of an ordered queue. Changing current state during a round must not make the active card vanish or unpredictably renumber the queue. Filtering narrows what the round walks; it does not redefine total List progress or completion.

Queue membership and order are review-context concerns. Task identity and TaskState remain valid after a round ends. Resume state points to stable membership or Task identity, never an array index whose meaning changes after synchronization.

## Import and export boundaries

### Importer / Task Builder

An importer or task builder translates raw sources into normalized Tasks and Assets. It owns source-specific grouping rules, such as whether a folder, ZIP, manifest, or set of related filenames forms one Task.

Core receives the result and must not recreate those rules. Adding a new source should be an adapter concern rather than a reason to add business-specific fields to Task.

This boundary is also where Task *authoring* belongs. By [Task creation ⟂ Task review](#task-creation--task-review), the review surface does not double as a place to invent Tasks: a free-text field that mints a Task inside a review context is an upstream responsibility leaking downstream, and it is the reason a review engine slowly acquires a CRUD interface it was never meant to have. Shopping lists, vocabulary sets, AI-extracted candidates, CSV rows and database queries should all arrive through one deliberate creation boundary, whether or not that boundary ships in the same binary.

### Exporter / Action

An exporter translates canonical review results into an external representation or side effect. JSON, CSV, XLSX, webhook delivery, and future integrations are possible adapters. None is the canonical internal model.

Exporter configuration must not appear on Task, Asset, or Template merely because one current workflow uses it. Delivery attempts and delivery history may have their own persistence at the boundary.

These boundaries do not require a generalized importer/exporter framework in the first iteration. Preserving a clean seam is sufficient until multiple implementations justify an abstraction.

## Legacy compatibility is not architecture

The existing app is a coherent media-review product. Its folder scanning, Quick Review, List-backed queues, per-file state, public item IDs, custom action settings, webhook configuration, webhook history, and file-management behavior remain real compatibility obligations.

They do not define the future Core.

In particular:

- a folder path may resolve or deduplicate a current source-backed Task, but it is not the Task's permanent domain ID;
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
- a new optional Task field or response primitive justified by a real workflow.

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
- makes a legacy transport contract the canonical internal model.

The practical architecture test is simple: adding a PDF Asset Viewer later should be an extension of the Asset side, not a rewrite of Task, List, Review, identity, or result semantics.
