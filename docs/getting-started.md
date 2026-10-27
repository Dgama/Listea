# Getting started

Getting Listea onto a device, pointed at a folder, and through its first review.

- [Install a build](#install-a-build)
- [Build it yourself](#build-it-yourself)
- [First launch: add an Upstream](#first-launch-add-an-upstream)
- [Create Tasks from an Upstream](#create-tasks-from-an-upstream)
- [Browse an Upstream](#browse-an-upstream)
- [Open a Todo](#open-a-todo)
- [Start a Quick Review](#start-a-quick-review)
- [Create a List](#create-a-list)
- [Reviewing](#reviewing)
- [Optional: wire up an export](#optional-wire-up-an-export)
- [Keeping a List in sync](#keeping-a-list-in-sync)

## Install a build

Prebuilt APKs are on the [releases page](https://github.com/Dgama/Listea/releases). Download the
APK and install it; Android will warn about installing from an unknown source, which is expected
for an app distributed outside the Play Store.

Current builds are **debug** builds, signed with the standard Android debug key. That means they
are debuggable, larger than a release build, and cannot be installed over a future release-signed
build without uninstalling first.

Requirements: **Android 7.0 (API 24) or newer**.

## Build it yourself

You need Android Studio, or a standalone Android SDK, with **API level 37** installed. The build
uses Java 11 language level and the Gradle wrapper in the repository — no separate Gradle install.

```bash
git clone https://github.com/Dgama/Listea.git
cd Listea

# Point the build at your SDK. This file is gitignored and is never committed.
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug        # build the APK
./gradlew installDebug         # build and install on a connected device
./gradlew testDebugUnitTest    # run the unit tests
```

Opening the project in Android Studio and pressing Run works too; Studio writes `local.properties`
for you.

More detail — dependencies, project layout, what the tests cover — is in
[Development](development.md).

## First launch: add an Upstream

<!-- screenshot: upstreams-tab — the Upstreams tab with two or three Upstream cards, each showing
     the folder inside it and its folder/file/size counts -->

On first launch the **Upstreams** tab has nothing to show, because Listea has not been given
anywhere to look. Folders are added in Settings.

1. Open **Settings**.
2. Under **Upstreams**, tap **Add Upstream**.
3. Pick a folder in the system picker.

That folder becomes an **Upstream**: a folder Listea may read, and the top of everything it can see
inside it. The grant survives restarts, and Listea never reaches outside it.

**Add as many as you like.** Each Upstream holds its own grant, so a second folder is a second row
rather than a replacement — pick one folder for one kind of work and another for the next. Your
photo library and your scans can both be Upstreams at once.

Grant the folder you actually want to work in rather than the whole of internal storage. Several
things are scoped to "under this folder" — counts, checked-file totals, and the delete action in
[File management](file-management.md) — and a narrower one makes all of them easier to reason
about.

**Settings → Upstreams** is also where you see what each folder holds: how many folders, how many
files, and how much space, counted per Upstream.

## Create Tasks from an Upstream

An Upstream is a folder, not a queue. Opening it browses its files and nothing more — nothing is
reviewed from it. What turns its material into work is **Create Tasks**.

Open an Upstream from the **Upstreams** tab and walk to the folder you want, then tap **Create
Tasks** — on the folder's row, or on the Upstream's own card for the whole of it. Because the run
happens on the folder you were standing in, one Upstream can produce several Todos over different
parts of its tree, and a folder you never import produces none.

> **Those folders may overlap, and a folder may have several Todos.** Running Create Tasks on a
> folder that already has one — the same folder, or one nested inside another Todo — is allowed and
> is the ordinary way to mean two things at once. The run makes a new set of Tasks belonging to the
> new Todo, and the older Todo's Tasks are untouched: same files, different Tasks, different
> decisions. The dialog names the Todos already there so you know what you are making a second of,
> and then goes ahead.

<!-- screenshot: create-tasks-dialog — the Create tasks? dialog open on a folder, showing the Name
     field, both grouping rows, the keep-folder-tree box, the three content-policy rows, the export
     chooser and the template attach step -->

The dialog asks six things, in this order:

| Field | What it decides |
|---|---|
| **Name** | What the Todo is called. Defaults to the folder's own name |
| **Importer** | Whether each **file** becomes a Task, each **folder** does, or each **Prompt in each Package** does (nested under *One Task per file*) |
| **Keep folder tree** | Whether the Todo shows the folder structure, or one flat list of Tasks |
| **When a Task's content changes** | What later happens to a Task whose files changed or disappeared outside Listea — see [What a changed Task means](core-concepts.md#what-a-changed-task-means) |
| **When a round over this Todo finishes** | Where this Todo's decisions go when a round ends: **Don't export**, **Webhook** or **Txt**. A new Todo starts at *Don't export* |
| **Templates** | Which templates govern this run's Tasks. Optional: file templates multi-select, one headless template at most, every row cancellable — see below |
| **Add an add-on** | Optional (v0.7.1): one questionnaire to ask about each Task as a whole, on a page after its last page. *None* unless chosen — see [Templates → Add-ons](templates.md#add-ons) |

**One Task per folder** forces the tree off: a folder-as-Task run builds Tasks from the folders
themselves and they are flat by construction, so the control is hidden rather than offered and then
taken away. [Core concepts](core-concepts.md) says what the two importers produce.

The content policy is the one answer here that is about *later* rather than about this run, and it
is the only one whose third option removes anything. It defaults to **Keep it**, which changes
nothing, and Settings holds the same chooser as the default a new Todo starts with — see
[Settings → Todos](settings.md#todos).

The export question is the other one about *later*: where this Todo's decisions go when a round over
it finishes. It defaults to **Don't export**, so a Todo nobody has configured sends nothing
anywhere — see [Optional: wire up an export](#optional-wire-up-an-export).

**The template step is not a gate.** **Create** is enabled by the address field alone; a run may
attach no template, in which case its Tasks draw no chips and record their own page decisions.
File templates are multi-select and decide which action chips the run's files will offer. Headless
templates are single-select and are the pen for a Task as a whole — a Package import starts on the
single-choice one — and clearing the selected row says *no pen*. See [Templates](templates.md). If
the run holds documents, attach a template scoped to **PDFs** as well: its Tag and Comment buttons
become the passage tools in the reader. A PDF no template governs is still readable and Copy still
works; it simply offers no review tools, draws no marks and exports none.

**Only a file a Viewer can open becomes a Task.** Under either file importer, pictures, videos and
PDFs are imported; anything else — a text note, a JSON Prompt body, a manifest — is skipped, and
the run reports how many. A Package has its own importer, *Package Prompts*, which makes one Task
per Prompt and leaves its `media` files as Resources rather than Tasks.

<!-- screenshot: history-recovery — the "Decisions left behind" block in the Create tasks? dialog,
     with a Start fresh row and one kept history under it -->

**One more question appears only sometimes.** If a Todo was deleted at this folder and chose to keep
its decisions, the dialog offers them under **Decisions left behind**, with a **Start fresh** row
beside them. Loading one is optional and is a copy, not a resurrection — see
[Deleting a Todo](core-concepts.md#deleting-a-todo).

<!-- screenshot: import-report — the "Import complete" report, listing what the run created,
     adopted, updated and flagged missing, with the "started over" and "replaced" lines under the
     Task counts -->

The Tasks appear on the **Todo** tab, under the name you gave them. Running Create Tasks again over
the same folder reads it afresh and records the answers given this time, a new name included. The
Todo's card also carries **Refresh Import**, which re-runs the import that produced it and asks the
same questions with the refresh wording.

## Browse an Upstream

Tapping an Upstream's card opens its folder. The breadcrumb at the top walks back up, and from the
Upstream's own folder Back returns to the Upstreams list. Tapping a file opens a plain viewer — a
gallery that changes nothing.

Each folder card says what relationship the folder has to a List — **None**, **List** or
**Sublist** — and offers Refresh, Open List when a List owns the folder, and Create Tasks. See
[Core concepts](core-concepts.md#folder-types) for what the three types mean.

Quick Review and Create List are not on this screen. They decide what material *means*, and that
decision belongs to the Tasks a Todo holds.

## Open a Todo

<!-- screenshot: todo-card — one Todo card on the Todo tab, with its folder-as-Task line, the
     "When a Task's content changes:" line, Refresh Import and the overflow menu -->

Opening a Todo is how you get at its Tasks. On the **Todo** tab, tap the Todo's card — the card is
the way in, and the only button left on it is **Refresh Import**, which re-runs the import that
produced it.

The card's **⋮** menu edits the Todo itself. **Rename…** changes the display name only — the folder
on disk and the name each Task was imported under are untouched. **Add-on · …** attaches, changes
or clears the Todo's questionnaire without another import, and re-stamps every Task it holds in one
step. Below them are the content-policy switches, the webhook address and **Delete Todo**.

A Todo opens as a tree of folders, or as one flat list of Tasks if the run was told not to keep the
folder tree. Tapping a folder descends into it; the breadcrumb walks back up, and from the Todo's
top level Back returns to the Todo tab. That top level is the folder the run was started on — for a
Todo made over a subfolder of an Upstream, the Todo opens there rather than at the Upstream's own
folder.

Each Task row says what the Task holds: its Assets, any that are missing, and — when the Task was
given one — its add-on page. A picture Task and a Package Prompt Task read the same line; the
add-on is a page of the Task, not a file, so it is named beside the count rather than counted as
one.

Tapping a Task opens it to be looked at, and nothing more: no checkbox, no chips, no Save or Share.
`<<` and `>>` step sideways through the other Tasks in the folder you were standing in, so you can
read through a folder without going back to the list each time. Swiping off the end of a Task's
files moves to the next Task the same way.

## Start a Quick Review

The fastest way in. Inside a Todo, open the folder whose Tasks you want and tap **Quick Review**.

It reviews **the Tasks directly in that folder** — not the ones nested below it — and it creates
nothing. No List is made, consulted or replaced. Your decisions are still saved, because a decision
belongs to the Task and not to the List it was reached through.

There is no Quick Review at a folder that holds only folders, which includes the top level of a
tree-shaped Todo: to review what is under a subfolder, open that subfolder and start there.

Quick Review always starts at the first unchecked item and does not remember where it got to.

## Create a List

For work that will take more than one sitting, or that covers a whole subtree.

There are two ways in, and they end in the same place.

<!-- screenshot: catalog-selecting — a Todo's catalog in selection mode, with a folder ticked, one
     Task inside it unticked, and the selection bar reading "N Tasks selected" -->

**From inside a Todo.** Tap **Select**. A checkbox appears on every row; tick the Tasks to include —
the bar along the bottom counts them — and tap **Create List**. The dialog asks for a title, already
filled in from the folder, and lists exactly the Tasks the List will hold. Confirming makes the List
and opens it.

**From the Lists tab.** Tap **New list** and pick which Todo to take Tasks from. The same screen
opens with picking already on, so from there it is the same gesture. Confirming returns you to
Lists, with the new List open.

A List may hold Tasks from any number of Todos, which is what makes it a list rather than a view.
The two ways in divide the work between them: start a List from one Todo's catalog, then open
another Todo, select its Tasks, and use **Add to list…** in the same bar to put them into the List
you already have. A Task the List already shows is skipped rather than added a second time.

A Task's row names the Task and, under it, where it came from: the file's path, or — for a Task
imported from a Package — the Package's manifest title, such as *Dating demo*. The internal catalog
key the Task is addressed by is never shown; it is an address, not a name. The queue guide and the
Info sheet follow the same rule.

**A List carries no meaning of its own.** It is an order over Tasks and nothing else — no tags of
its own, no second copy of anything. What a Task is checked and tagged as, it is checked and tagged
as in every List that shows it, because both Lists are showing the same Task.

A List appears on the **Lists** tab, with its progress, and can be opened, reviewed, re-synced and
given its own export method from its detail page. There is still no way to type an item into a List
by hand:
a List holds Tasks, and every Task comes from an import run. Lists you made from a folder in an
earlier version are still there, with their items.

A folder's checkbox takes the whole folder — the Tasks directly in it and everything under it.
Ticking one at the top of a tree and then clearing a Task inside leaves the folder ticked and that
one Task out; tick it again and it comes back, and clearing the folder and ticking it again starts
from scratch rather than re-applying the Task you took back. A Task you ticked by hand stays ticked
whether or not a folder is ticked over it.

Tapping a Task's row while *not* selecting opens a look at it: the file, and nothing else. No
checkbox, no tags, no Info — no way to change anything by accident while you are deciding what to
put in the List. Tap the screen to hide the bar, or Back to return to the tree you were in.

## Reviewing

Open a List and tap **Review**, or use **Quick Review** inside a Todo. Either way:

| Gesture | What it does |
|---|---|
| **Swipe left** | Go forward one page; off the last one, show the Task's add-on page if it has one, otherwise mark the item checked and advance; off the add-on page, mark checked and advance |
| **Swipe right** | Go back one page; off the add-on page, return to the last page; off the first page, go back one item, changing nothing |
| **Pinch** | Zoom, up to 5×; drag to pan inside the picture |
| **Tap the media** | Show or hide the file's own controls |

An item holds one file most of the time, so most swipes are exactly what they look like. A folder
imported as a Task is one item holding several files, and a swipe walks those files before it moves
on. A Package Prompt adds its Prompt body before the files.

A Package Prompt may be a dating profile or a vocabulary drill. Both are one Content page with the
same frame, count and swipe rules; only the card differs. A vocabulary card shows the word, an
optional phonetic line, a **Listen** control for its pronunciation Resource, and its options in the
Package's declared order. Answering it is one tap, exactly like Like or Dislike.

**A Prompt page this build cannot judge is plain navigation.** When the body cannot be read, a
required file is missing, the Task is blocked, or no Task Review in force can catch its choice,
there is no answer to give — so a swipe turns the page and crosses Tasks at the edges instead of
being inert. The add-on page's forward swipe becomes a queue move for the same reason. A Prompt
page that *can* be judged still refuses the swipe: its option tap is the answer.

**A PDF opens in a reader rather than the media viewer** (v0.7.0). Pages scroll vertically, pinch
zooms, and a horizontal swipe at the page edge still steps the queue. Long-press the text to start a
selection and drag to extend it; the menu offers **Copy**, and when a PDF template governs the file,
its **Tag** and **Comment** buttons join it — choosing one attaches a single mark to that passage.
A mark is Listea's own record, drawn over the page while the document is open; the PDF is never
modified. The file bar's **Info** icon lists what the file carries, including a mark whose passage
this copy of the document can no longer place. See
[Review and actions → PDFs](review-and-actions.md#pdfs).

**A Task whose import carried an add-on has one extra page after its last page** (v0.7.1) — after
its last file, or after the Prompt body when it has no file. It asks the Todo's questions — a
rating, a Yes / No, or words — and answers are kept as you give them, with no Save button and
nothing required. The position counts it as a page, so a three-file Task with an add-on reads
`3(1/4)/7` … `3(4/4)/7`, a Prompt Task with no file reads `3(2/2)/7`, and the file slot says
**Add-on**. Swiping left again leaves the Task exactly as the last page does, and the completion
checkbox still completes without an answer. Since v0.8.2f, a button that both judges the item and
advances — a Prompt's **Like** / **Dislike**, a sampled point's **Submit** — also lands on this
page rather than jumping to the next item, so the questionnaire is shown once for every judged
Task. The same pages appear when you look at the Task without reviewing: the Todo preview and the
List item viewer show the add-on page read-only, with the answers on record and no control to
change them. See [Templates → Add-ons](templates.md#add-ons).

The name along the top is the item; the filename beside it and the `3(2/3)/7` position say which of
its files you are on and where that sits in the queue. The pair of double arrows there steps the
queue, skipping files entirely, and the single arrows step the files. An item holding one file has
no file level to speak of, so the bar is simply `3 / 7` between two arrows.

The bottom band is the Task's own state and is always there. When the template commits from the
completion checkbox, that is the band. When the template draws its own control — a Prompt's
**Like** / **Dislike**, a sampled point's **Submit** — the bottom band shows the same checkbox
read-only instead, with the state in its fill: empty is not checked, filled is checked. There is no
word beside it; the template's control is what records the judgment. Above it,
laid over the video or picture, are **★ Favourite**, your own custom action chips and **Info** —
those come up and go away when you tap the media, while the task band and the name along the top
stay. Tapping a chip tags the item; it does not check it. Checking and tagging are separate
decisions and neither implies the other.

**Info belongs to the page you are on.** On a file it lists that file's facts — path, status,
actions, type, size, modified time, and any marks or recorded events. On a Prompt's Content page it
lists the Task's provenance, List and status. On the add-on page it lists only the questionnaire's
questions and answers. A file's Info never mixes in the Task's add-on answers, and the add-on's
Info never lists a file's facts. Save and Share appear only on a file's sheet.

The full behaviour of every control is in [Review and actions](review-and-actions.md).

## Optional: wire up an export

If you have something that should act on your decisions — a script, an automation tool, anything
that accepts an HTTP POST — Listea can send it the results of a round. Or, if you only want a record
of what you did, it can write the same round to a text file. Either way the act is an **export**, and
these are the two **methods** a round can leave by:

| Method | What it does | Asks first |
|---|---|---|
| **Webhook** | POSTs a JSON body describing the round to a URL you configure | Yes |
| **Txt** | Writes the same JSON body the webhook would post to one file per round under `Download/Listea/txt/` | No |

**PDF passages travel in the same body.** A round that carries marks says `version: 3` and gives
each marked file a `marks` array beside its `actions`; a round with none stays version 2, byte for
byte as before. See [Exports → PDF passages](exports.md#pdf-passages).

**Nothing exports by default, and a new Todo exports nothing.** Three steps turn it on:

1. **Set a default address.** **Settings → Exports → Webhook** holds *Preselect the default address*
   and *Default URL*; paste a URL and tap **Send a test payload** to check the receiver before relying
   on it. The switch only decides whether a new Todo starts on *Use default*; it does not enable
   delivery. The Txt method has nothing to configure.
2. **Pick a method for a Todo.** The Create Tasks dialog's **Sync** section asks *When a round over
   this Todo finishes*, offering **Don't export**, **Webhook** or **Txt**, and choosing Webhook opens
   the address configuration — *Use default* (a copy of the default at that moment) or a custom URL.
   A new Todo starts at *Don't export*. Its address can be changed later from the Todo card's ⋮ menu.
3. **Leave a List on *Same as the Todos*, or override it.** A List's detail page carries an **Export**
   card whose **Method** row offers *Same as the Todos*, *Export nothing*, *Webhook* or *Txt*, plus a
   switch for exporting when the List completes. Choosing **Webhook** also draws an optional
   **Webhook address** field: empty follows each Todo, and a non-empty value overrides them for that
   List. Under *Same as the Todos* or a txt method the field is not shown, because the List has no
   destination of its own to override.

A round then exports when you leave a review, when a Quick Review queue runs out, or when a List goes
complete. The webhook method asks before it posts; the txt method writes and then reports. Three
switches in **Settings → Exports** decide when and what anything exports: **Export from Quick
Review**, **Export when leaving a review** and **Export checked items only**. Every export of every
method is kept in one history — **Settings → History**, the last row of the index — grouped by the
round that produced it, so a round is never lost and can be sent again later. All of it is in
[Exports](exports.md).

## Keeping a List in sync

Files on disk change; a List does not follow them automatically.

A List's **Source** card says whether its folder has changed since the List was built. **Update
from folder** shows exactly what was added and what went missing, and applies it only when you
confirm.

Checked state survives the update. Files that vanished stay in the List, flagged as missing,
keeping what you decided about them, until you choose to clear them.

---

Next: [Core concepts](core-concepts.md) for the model underneath all of this, or
[Review and actions](review-and-actions.md) for everything the Review screen does.
