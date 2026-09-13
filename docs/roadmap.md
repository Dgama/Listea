# Roadmap

> **Nothing on this page is implemented.** It describes where Listea is going and why. For what
> Listea does today, see [the documentation](README.md).

- [The current system](#the-current-system)
- [Architectural direction](#architectural-direction)
  - [1. Review templates](#1-review-templates)
  - [2. Cross-platform-ready structure](#2-cross-platform-ready-structure)
  - [3. A general task model](#3-a-general-task-model)
- [Next milestone: a check-in task workflow](#next-milestone-a-check-in-task-workflow)
- [Possible future workloads](#possible-future-workloads)
- [Not planned](#not-planned)

## The current system

Listea today is a **media triage app**. It reviews images and videos that exist as files under one
granted folder on one Android device.

Concretely, and as a baseline for everything below:

- A reviewable item **is a file**. It has a path under the root, a size, a modified time, and a
  media type inferred from its extension.
- Review **is a media screen**. One renderer, one swipe gesture, one action bar, one zoom
  implementation, one video transport.
- State **is checked plus actions**, keyed by root and relative path.
- A queue **comes from a folder** — directly, as a Quick Review, or via a List over a subtree.
- The output **is a webhook**, describing a List and its items.

That is a coherent product and it works. The limitation is not quality; it is that every one of
those five sentences says *file*, *media* or *folder*.

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
position and resume, progress, and the path out to a webhook.

**Status: not implemented. There is no template abstraction in the code today.**

### 2. Cross-platform-ready structure

**Prepare the project structure for a future iOS implementation.**

The immediate goal is **not** to build iOS. It is to stop making that impossible by accident:
organise project structure, domain boundaries, naming, models and reusable logic so the codebase
can evolve toward a clean multi-platform architecture instead of having to be excavated later.

Some of this already exists by habit — `FileArrange.kt`, `ReviewArrange.kt`, `QuickReview.kt`,
`ItemId.kt` and the webhook payload helpers are deliberately free of Compose and mostly free of
Android, which is why they are covered by JVM tests. The work is to make that a property of the
project rather than a coincidence, and to draw the line between *domain* and *platform* somewhere
explicit.

**No framework has been chosen.** Kotlin Multiplatform is an obvious candidate and so are several
alternatives, including a plain second implementation against a shared specification. Committing to
one before the domain boundaries exist would be choosing an answer before understanding the
question.

**Status: not implemented, and no iOS work has started.**

### 3. A general task model

**Prepare Listea to review tasks that are not files.**

Today a `ReviewItem` is a file, and its state is keyed by a path under a storage root. A general
task model would let a review item represent something broader — with an identity that does not
have to be a filesystem path, content that does not have to be bytes on disk, and a source that
does not have to be a folder.

This is the deepest of the three. It reaches the database schema, the identity scheme, the queue
builders and the webhook payload, and it is the one that makes the other two worth doing.

**Status: not implemented.**

## Next milestone: a check-in task workflow

**The next concrete architectural milestone is to build a check-in task workflow — the first
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

Illustrations of what a general task engine could carry. **None of these is planned, scheduled or
committed to** — they are here to show what the architecture above is *for*.

- **Daily check-in tasks** — the milestone above, generalised into recurring routines.
- **Vocabulary or flashcard review** — a queue of prompts, a lightweight decision per card, results
  handed onward. Structurally the same shape as media triage.
- **Lightweight structured decisions** — approve/reject queues over small records.
- **Externally received tasks** — items arriving from outside the device rather than being
  discovered on it, which would make the webhook a two-way idea rather than an outbox.

## Not planned

Currently out of scope, and not being worked on:

- Automatic List updating
- Search
- Moving or renaming files
- Choosing *which* checked files a deletion takes
- Tablet layouts
- Sync between devices

---

There are no dates on this page, and no promised order beyond the milestone named above. This is a
direction, not a schedule.

Back to [the documentation](README.md), or the [project README](../README.md).
