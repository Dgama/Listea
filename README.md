# Listea

> **Turn tasks into a feed.**
>
> A swipe-first review interface for repetitive human decisions.

Listea takes work that would otherwise be a long list of small judgement calls and presents it one
item at a time, full screen, in a queue you move through with your thumb. Decide, swipe, next.

The first workload it does well is **media triage**: working through a folder of images, video and
documents and saying what should happen to each file. Since v0.7.0 a PDF is not only looked at but
**read**: a passage can be selected and a comment or a tag attached to it.

<p align="center">
  <img src="docs/assets/screenshots/review-media.jpg" width="300" alt="Listea's Review screen: one video filling the screen, with a position counter, a completion checkbox and custom action chips along the bottom">
</p>

> **Status: under active development.** It builds, it runs, and everything described below works.
> Behaviour, the database schema and the interface all still change between versions. See
> [Project status](#project-status).

---

## What is Listea?

Reviewing a few hundred files is not hard, it is just *long*. The cost is not the decision — it is
the file manager: opening something, closing it, finding your place again, remembering which ones
you already looked at.

Listea removes that. It turns a body of review work into a sequential feed:

- one item fills the screen,
- you make a lightweight decision about it,
- you swipe, and the next one is already there.

Your place, what you have finished, and what you decided are all remembered, so a queue can be put
down and picked up. When a queue is done, Listea can hand the decisions to whatever acts on them
next — over a webhook, or by writing them to a file.

The idea generalises past files — see the [Roadmap](#roadmap) — but **today Listea reviews images,
videos and PDFs on your device, and that is all it reviews.**

## How it works

```
Upstream  →  Create Tasks  →  Todo  →  Quick Review  or  List  →  Review items  →  (optional) export
```

1. Add a folder on your device, through Android's own folder picker, in Settings. That folder is
   an **Upstream**, and you can add as many as you like.
2. Open it, walk to the folder you want, and run **Create Tasks**: choose whether each file or each
   folder becomes a Task, give it a name, and say whether it keeps its folder tree. What it produces
   is a **Todo**.
3. Work through a Todo one item at a time — as a Quick Review, or as a List that persists.
4. Mark each item done, and optionally tag it with an action that says what should happen to it.
5. Export the result somewhere, if you have somewhere for it to go — a POST, or a file.

Nothing is moved or renamed. [Three explicit actions](docs/file-management.md) write to storage,
and you confirm every one of them.

## Two ways to build a queue

### Quick Review

Open a Todo, open the folder you want, and start. No setup, nothing created, nothing to clean up
afterwards — it reviews the Tasks directly in that folder and stops. Available whether or not a List
covers them.

### Lists

For work that outlives one sitting. A List keeps its own progress and can be reopened and wired to
an export method. There are two kinds:

- **Built from a folder**, the way v0.2.x made them: the List covers that folder and everything
  beneath it, and can be re-synced against it when the folder changes.
- **Built from Tasks**, by opening a Todo, tapping **Select**, picking the Tasks you want and
  pressing **Create List**. This kind has no folder behind it, so there is nothing for it to drift
  from and no **Source** card on its detail page.

<p align="center">
  <img src="docs/assets/screenshots/list-detail.jpg" width="300" alt="A List's detail page showing its source folder, progress and item checklist">
</p>

Either way the decisions are the same decisions: checking a Task in Quick Review and checking it
from a List are one act, because state belongs to the Task rather than to the queue you reached it
from. See [Core concepts](docs/core-concepts.md).

## Actions and templates

Review is not a yes/no machine. Checking something off says *"I have dealt with this"* — it does
not say what dealing with it meant.

So the Review bar carries action chips alongside the completion checkbox, and **a template** decides
which ones a file gets. A template has a name, a **scope** saying which files it governs, and a list
of **buttons**; each button has a name you read and a value that goes on the wire. Listea does not
decide what they mean: it captures the decision quickly, and [exports](docs/exports.md) carry it to
whatever acts on it.

Templates live at **Settings → Templates**, and a Todo attaches exactly one of them — chosen in the
**Create Tasks** dialog, which will not create a run with none attached. Editing a template — its
scope or its buttons — appends a version rather than rewriting it, so a Task you finished under the
old buttons goes on meaning what it meant. Renaming it does not: a name is a label, and a label that
changes is the same template. See [the Templates page](docs/templates.md).

<!-- screenshot: settings-templates — the Templates list on the Settings screen, and one template's
page showing its scope line and its button list -->

Two chips and a favourite (★) come configured on the built-in template; add, rename, retag or remove
them freely. ★ is a button rather than a fixture — a template that does not carry it gives you no
star on the files it governs.

**On a PDF the same buttons become passage tools.** A PDF is read sentence by sentence, so it gets
no file-level chip row: select a passage and the governing template's **Tag** and **Comment**
buttons join **Copy** in the selection menu, making one mark on that passage. Marks are Listea's
records, drawn over the document while it is open; the file itself is never changed. The Review
frame — the navigator, the checkbox and the file bar with its **Info** icon — is Listea's on every
file and every template, including no template at all. See
[Review and actions → PDFs](docs/review-and-actions.md#pdfs).

## Example workflow

You have a folder that syncs from somewhere — a few hundred images and videos you have not looked
at yet.

1. Add the folder as an Upstream in Settings, open it, run **Create Tasks**, then tap **Quick
   Review** in the Todo it produced to start there and then — or **Select → Create List** if this is
   going to take more than one sitting.
2. Swipe through it. Each file fills the screen; swipe left to mark it done and advance, right to
   go back. Pinch to look closer; video plays in place.
3. As you go, tap ★ or one of your own chips on anything that needs more than "done" — the ones
   worth keeping, the ones to deal with later, whatever your chips mean to you.
4. When the queue runs out, Listea offers to export the round: a POST to your webhook, or a text
   file under `Download/Listea/txt/`. Either body says which files you processed and which chips you
   gave each one.
5. Something on the other end does the actual work — a script, an automation tool, anything that
   accepts an HTTP POST or reads a file. Listea never touches your files to make it happen.

If you have nowhere for it to go, skip steps 4 and 5. Listea is a perfectly good "get through this
folder" tool on its own.

## Current capabilities

Everything in this table works today.

| | |
|---|---|
| **Upstreams** | Any folder you grant, through Android's Storage Access Framework, added in Settings and listed one card each. Opening one browses its files with the ones already checked marked. An Upstream is material, not work: it outlives every Todo made from it. |
| **Create Tasks** | Turns a folder's material into Tasks — one Task per file or one per folder, under a name you choose, keeping the folder tree or flattening it. It asks, in the same dialog, what should happen when a Task's content later changes. What it produces is a Todo. |
| **Todo** | The Tasks one Create Tasks run produced, one card each. Opening one browses them and offers Quick Review and Select there. A Todo says on its card which content policy it is on, and its overflow menu carries its own *Follow the upstream* and *Keep decisions when deleted* switches. |
| **Task lifetime** | A Task's content can change or disappear outside Listea. The Todo's content policy decides what that means: **Keep it** flags what has gone and changes nothing else, **Start it over** keeps the Task and clears its ticks so moved content is judged again, **Replace the Task** deletes the Task and mints a new one, taking it out of every List that held it. |
| **Dissolution** | Deleting a Todo takes its Tasks and their decisions with it, leaves the files' own identity alone, and tells every List what it lost. A per-Todo switch can write the decisions down first, so a Todo created at the same folder later can load one history from them. |
| **Catalog** | Every Task a Todo holds, browsable at its own scope, with the ones you have judged marked. Where **Select** and **Create List** are reached from. |
| **Review** | Full-screen images, animated GIFs, video and PDFs. Swipe to check and advance, swipe back to reconsider, pinch to 5×, per-item Info sheet. A PDF scrolls pages, selects text with word snapping, and draws stored marks over their passages. |
| **Video** | Its own transport strip: play/pause, scrubber, 0.5×–2× speed, mute. Autoplay and start-muted are settings. |
| **Quick Review** | Any folder holding Tasks, instantly, without creating anything. |
| **Lists** | Persistent queues, either over a folder and its subfolders — with progress, source-change detection and manual re-sync — or over Tasks you picked out of a Todo, which have no folder behind them. |
| **Checked state** | Belongs to the Task, not to the queue. Shared by every List and Quick Review that reaches the same Task. |
| **Templates** | Named, scoped, versioned definitions of the controls a Todo offers: a scope saying which files it governs, and buttons each with a name and a wire value. On images and video those are action chips; on a PDF they become passage tools (Tag, Comment). Editing one appends a version, so completed work goes on meaning what it meant. |
| **Custom actions** | The buttons a template carries, with their own display names and wire values, plus a favourite (★) on the built-in one. |
| **Filter and sort** | By media type — pictures, videos, text and now PDFs as a kind of its own — checked status and freshness; sort by name, time, size, type or unchecked-first. Applies to browsing *and* to the review queue. Shared globally or kept per folder. |
| **Review rounds** | Optionally queue only unchecked items, so each pass through a folder is its own round. |
| **Resume** | Full Review remembers where it got to. |
| **Exports** | Two methods — **Webhook** and **Txt** — one chosen per Todo, overridable per List. A List that has not chosen follows its Todos, so a round can split and export more than once. PDF passages travel as `marks` in the same body; a round with none is byte-for-byte the version 2 it always was. |
| **Webhooks** | One app-wide URL, four events, a fixed JSON payload (version 2, or 3 when it carries PDF marks), stable public item ids, a test button, and a confirmation before anything leaves the device. |
| **Export history** | Every export Listea performs is kept with its method and its outcome, and can be inspected, resent (a webhook) or deleted. |
| **File management** | Opt-in deletion of checked files, always behind a full named listing and an explicit confirmation. Save a viewed file to a gallery album, or share it out. |

Details for each of these live in the [documentation](#documentation).

## Roadmap

The direction is to grow Listea from a media review app into a **general task review engine** —
same feed, same gestures, many kinds of work. Three pieces of architecture stand between here and
there:

1. **Review templates** — abstract the Review screen so different task types can present
   themselves differently. Today's image/video experience becomes one template among several.
   v0.7.0 moved the Asset side: a PDF-scoped template supplies passage tools, while the frame —
   navigator, checkbox, file bar and Info — is Listea's on every template. Page-level presentation
   and controls are still code-defined.
2. **Cross-platform-ready structure** — organise domain logic, models and boundaries so an iOS
   implementation is possible later. No framework has been chosen, and iOS is not being built yet.
3. **A general task model** — let a reviewable item be something other than a local file.

**Next milestone: a check-in task workflow**, as the first non-media task type. Not because
check-ins are the point, but because building one forces task data, state, actions and presentation
to stop being welded to image and video files.

**Almost none of this is implemented.** v0.2.5 gave a reviewable item an identity of its own — a
Task, whose state hangs off that id rather than off a file path — and v0.3.0 and v0.4.0 separated
the Upstream from the Todo and gave a Task a lifetime. v0.7.0 added the first content kind that is
*read* rather than glanced at, a PDF with passage marks, and showed the Asset side can grow a
viewer and a template without touching Task, List or the core of the payload. That is progress on
the first and third pieces; the rest of the third still waits, because every Task still holds
files. The rest describes where the project is going, not what it does. The full version is in
[docs/roadmap.md](docs/roadmap.md).

## Documentation

**→ [Full documentation](docs/README.md)**

| | |
|---|---|
| [Getting started](docs/getting-started.md) | Build it, add an Upstream in Settings, run your first review |
| [Core concepts](docs/core-concepts.md) | Upstreams, Todos, Lists, items, rounds, and where state lives |
| [Review and actions](docs/review-and-actions.md) | Every gesture, control and behaviour in Review |
| [Templates](docs/templates.md) | Names, scopes, buttons, versions, and attaching one |
| [Exports](docs/exports.md) | The two methods, where the choice lives, and how a round resolves |
| [Webhooks](docs/webhooks.md) | Events, payload format, the txt body, history, resending |
| [File management](docs/file-management.md) | Everything that touches your files |
| [Settings](docs/settings.md) | Every switch, and what it changes |
| [Development](docs/development.md) | Environment, build, tests, project layout |
| [Roadmap](docs/roadmap.md) | Where this is going, and why |

## Project status

Listea is under active development and is not stable.

- The database schema changes between versions. Migrations are written and shipped, so upgrading in
  place is expected to keep your data — but the schema is not settled, and no version of it is
  promised to survive.
- Settings, defaults and interface details move around.
- The wire vocabulary and the webhook payload have changed before and may again — v0.5.6 renamed
  the test event from `list.webhook.test` to `export.test`. See [Webhooks](docs/webhooks.md) for
  what is currently on the wire.

Listea reads the folder you grant it and changes nothing there on its own. Even so, it is not yet a
tool to point at data you cannot afford to have a bug touch.

Builds are published on the [releases page](https://github.com/Dgama/Listea/releases). The current
one is a debug build, signed with the standard Android debug key.

## Licence

[Apache License 2.0](LICENSE).

You may use, modify and redistribute this code, including commercially, as long as you keep the
copyright and licence notices and state what you changed. The licence also grants an explicit
patent licence from contributors, and the code comes with no warranty of any kind.

The same licence covers Listea's main dependencies — AndroidX, Compose, Media3, Kotlin and Coil are
all Apache-2.0. The PDF viewer adds a native renderer under other terms: the **Nucleus** wrapper
and **pdfium-binaries** are MIT, and **PDFium** itself is BSD-3-Clause. The app carries the full
texts of all of them in **Settings → About**, so a build redistributes one set of notices rather
than several.
