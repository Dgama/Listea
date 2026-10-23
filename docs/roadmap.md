# Roadmap

> **Nothing on this page is implemented.** It describes where Listea is going and why. For what
> Listea does today, see [the documentation](README.md). For the nearer v0.8.x framework round, see
> [Architecture → Framework evolution](ARCHITECTURE.md#framework-evolution--v08x-planned) and the
> [v0.8 draft](iterations/v0.8.md); this page keeps the longer product direction.

- [The current system](#the-current-system)
- [Architectural direction](#architectural-direction)
  - [1. Review templates](#1-review-templates)
  - [2. Cross-platform-ready structure](#2-cross-platform-ready-structure)
  - [3. A general task model](#3-a-general-task-model)
- [A later milestone: a check-in task workflow](#a-later-milestone-a-check-in-task-workflow)
- [Possible future workloads](#possible-future-workloads)
- [Not planned](#not-planned)

## The current system

Listea today is a **file review app with a media bent**. It reviews images, videos and PDFs that
exist as files under folders the user has granted, on one Android device — as many folders as they
care to add, each on its own grant.

Concretely, and as a baseline for everything below:

- A reviewable item **is a Task**, and a Task is files: one file, or a folder's worth of them. Its
  files have a path under the Upstream they came from, a size, a modified time, and a media type
  inferred from the extension.
- Review **is one frame with a per-kind renderer**. One swipe gesture, one navigator, one checkbox
  and one file bar; inside it an image's action bar and zoom, a video transport, or — since v0.7.0
  — a PDF's page list, selection layer and marks.
- State **is checked plus actions** — the tick keyed by the Task's own id, the chips by the
  Task–Asset pair they were given on, a PDF's passage marks keyed by that same pair, and since
  v0.7.1 an Add-on's answers owned by the Task as current state.
- A queue **comes from a Todo** — the Tasks one Create Tasks run produced — or from a List built
  out of Tasks picked from one. The position label opens a two-level guide to that queue.
- The output **is an export**, describing a List and its items. Since v0.5.6 that is one of two
  methods — a webhook or a file — and a Quick Review round uses the same shape with no List behind
  it, standing the folder in as the subject. Since v0.7.0 a PDF Asset may also carry `marks`; since
  v0.7.1 a Task may carry visible Add-on answers, and the body rises to version 4 when it does.

That is a coherent product and it works. The limitation is not quality; it is that every one of
those five sentences still ends at *file* or *folder*. v0.7.0 loosened the grip of *media* — a PDF
is read sentence by sentence rather than glanced at — but its content is still bytes on disk. A
Task has an identity of its own now, and nothing yet to hold but files.

## Architectural direction

**The goal: evolve Listea from a media review application into a general task review engine.**

The insight worth generalising is not "swiping through photos is nice". It is that *a queue of
small decisions, presented one at a time, with the decision captured and handed onward* is a shape
that fits far more work than media triage. What stops Listea from serving that shape today is that
the shape is welded to files.

Three pieces of work separate the two.

### 1. Review templates

**Abstract the Review page into a template-based review system.**

Today there is exactly one Review experience, and it makes media assumptions everywhere: a
full-bleed renderer, pinch-zoom, a video transport, a filename in the top bar, an Info sheet built
out of file facts.

The direction is for **different task types to use different Review templates**, with the current
image/video experience becoming one media-oriented template among others. A template would own:

- **Presentation** — what the item looks like on screen.
- **Controls** — what gestures and chrome make sense for it. Pinch-zoom is meaningless for a
  check-in; a "how did it go?" control is meaningless for a photo.
- **Actions** — which action buttons a task type exposes, including task-specific ones, on top of
  the user-configured set.

What would stay shared is the part that is genuinely general: the queue, the round semantics,
position and resume, progress, and the path out to an export.

**Status: not implemented *for the Review page*, which is what this section asks for.**
[v0.5.4](iterations/v0.5.4.md) built a template abstraction for a different band — the **asset
control**, the chip row a Task's files offer — so the sentence "there is no template abstraction in
the code today" stopped being true of the whole app: a template is now a stored row with an id, a
name, a version, a scope and buttons, and an edit appends a version. [v0.7.0](iterations/v0.7.0.md)
then added a second content-specific viewer and drew the boundary explicitly: the **frame**
(navigator, checkbox, dismissible file bar and Info) is Listea's on every file and every template,
while the **dynamic components** — a media chip row, a PDF's passage tools — come from the
template's scope and version. That is the shape a page-level template would build on. None of it
yet reaches the three things listed above: task-level **Presentation**, **Controls** and
**Actions** are still code-defined, and `lists.taskReviewTemplateId` still points at the single
`builtin.media.rapid-review@1`. What §1 asks for is a template that decides how the *page*
behaves, and the releases so far deliberately do not touch it.

### 2. Cross-platform-ready structure

**Prepare the project structure for a future iOS implementation.**

The immediate goal is **not** to build iOS. It is to stop making that impossible by accident:
organise project structure, domain boundaries, naming, models and reusable logic so the codebase
can evolve toward a clean multi-platform architecture instead of having to be excavated later.

Some of this already exists by habit — `FileArrange.kt`, `ReviewArrange.kt`, `QuickReview.kt`,
`ItemId.kt`, `Catalog.kt`, `ContentPolicy.kt`, `Importer.kt` and the webhook payload helpers are
deliberately free of Compose and mostly free of Android, which is why they are covered by JVM tests.
The work is to make that a property of the project rather than a coincidence, and to draw the line
between *domain* and *platform* somewhere explicit.

**The `domain/` package is the first real move in this direction** — see
[Development → `domain/`](development.md#domain--the-model-with-no-framework-in-it). It is more than
a convention: several id types rather than one, so a Task id cannot be passed where an Asset id
belongs, and an opaque identity that no path, filename or row number can be read back out of. What
it is not yet is finished: the app still persists through the Room entities in `data/`, and a great
deal of behaviour still reaches Android APIs directly from the screens.

**No framework has been chosen.** Kotlin Multiplatform is an obvious candidate and so are several
alternatives, including a plain second implementation against a shared specification. Committing to
one before the domain boundaries exist would be choosing an answer before understanding the
question.

**Status: not implemented, and no iOS work has started.**

### 3. A general task model

**Prepare Listea to review tasks that are not files.**

Today the thing a queue walks is a **Task**, and its state is keyed by the Task's own id rather than
by a path. What a Task *holds* is still files. A general task model would let a Task represent
something broader — content that does not have to be bytes on disk, and an origin that does not have
to be a folder.

This is the deepest of the three. It reaches the database schema, the identity scheme, the queue
builders and the export payload, and it is the one that makes the other two worth doing.

**Status: the identity half is done; the content half is the v0.8.x framework round.**

- **v0.2.5** gave a reviewable item an id of its own, so the model no longer assumes a filesystem
  path is the thing decisions belong to. `ReviewItem` — the queue row the screens actually walk —
  carries a `taskId`, and `task_state` hangs off it.
- **v0.3.0 and v0.4.0** separated the Upstream from the Todo and gave a Task a lifetime: a Task can
  now be flagged, reset or replaced when its content changes, and the decision is a named per-Todo
  policy rather than something inferred. That is the "identity survives a change" half, which a
  general task model needs.
- **v0.8.x (planned)** is the first move on the content half: an optional Task payload, a directory
  Package protocol, Package-scoped Resources, a typed page model, and one submission path shared
  with media. See [Architecture → Framework evolution](ARCHITECTURE.md#framework-evolution--v08x-planned).
- What is still missing after that is the product layer: vocabulary and primary-questionnaire
  experiences, which are v0.9.x. `AssetKind.OTHER` remains the member for content no viewer claims;
  it is not a structured payload kind.

## A later milestone: a check-in task workflow

> **Sequencing note (2026-10-23).** The nearer milestones are the v0.8.x framework round and the
> v0.9.x vocabulary/primary-questionnaire work. The check-in below remains the clearest *second*
> non-media task type and the same architectural test; it comes after them.

**A later concrete architectural milestone is to build a check-in task workflow — a second
non-media task type.**

A check-in is a small, recurring, non-file task: something you confirm you did, in a queue, one at a
time.

**The point is not the check-in feature.** A check-in screen could be built in an afternoon as a
special case, and that would be worth nothing architecturally. The point is that building it
*properly* — as a task type the existing engine can carry — forces the system to prove it has
actually separated:

| What it forces | Why it is the test |
|---|---|
| **Generalised task data** | A check-in has no path, no size, no modified time, no MIME type. Anything in the item model that assumes those has to go somewhere else. |
| **Review templates** | A check-in cannot use the media renderer, the zoom or the video transport, but it must use the same queue, the same round semantics and the same position tracking. |
| **Configurable task presentation** | What a check-in shows on screen is not what a photo shows. Presentation has to become a property of the type. |
| **Reusable actions** | The action bar has to work for a task whose actions mean something entirely different, without special-casing. |
| **Future cross-platform compatibility** | None of the above may be solved by reaching into Compose or Android APIs, or it will have to be solved again. |

A second task type is the cheapest honest test of whether the first one was ever really decoupled.
Everything that turns out to be hard is exactly the coupling that needs removing.

**Status: not started.**

## Possible future workloads

Illustrations of what a general task engine could carry. Except where a nearer direction is named,
**none of these is planned, scheduled or committed to** — they are here to show what the
architecture above is *for*.

- **Daily check-in tasks** — the later milestone above, generalised into recurring routines.
- **Vocabulary or flashcard review** — the v0.9.x direction the v0.8.x framework prepares for: a
  queue of prompts, a lightweight decision per card, results handed onward. Structurally the same
  shape as media triage.
- **Lightweight structured decisions** — approve/reject queues over small records.
- **Externally received tasks** — items arriving from outside the device rather than being
  discovered on it, which would make the webhook a two-way idea rather than an outbox.

## Not planned

Currently out of scope, and not being worked on:

- **Automatic List updating.** A Todo can follow its upstream (v0.4.0) but a *List* cannot: a
  folder-backed List re-syncs when you ask it to, and following a folder on a schedule is a
  different feature with a different failure mode
- Search
- Moving or renaming files
- Choosing *which* checked files a deletion takes
- Tablet layouts
- Sync between devices

---

There are no dates on this page, and no promised order beyond the nearer v0.8.x/v0.9.x direction
named above. This is a direction, not a schedule.

Back to [the documentation](README.md), or the [project README](../README.md).
