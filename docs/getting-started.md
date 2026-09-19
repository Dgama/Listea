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
| **Importer** | Whether each **file** becomes a Task, or each **folder** does |
| **Keep folder tree** | Whether the Todo shows the folder structure, or one flat list of Tasks |
| **When a Task's content changes** | What later happens to a Task whose files changed or disappeared outside Listea — see [What a changed Task means](core-concepts.md#what-a-changed-task-means) |
| **When a round over this Todo finishes** | Where this Todo's decisions go when a round ends: **Don't export**, **Webhook** or **Txt**. A new Todo starts at *Don't export* |
| **Templates** | Which templates govern the files this run creates. **At least one is required** — see below |

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

**The template step is the one gate in the dialog.** **Create** stays disabled until at least one
template is attached, because a run with none produces a Todo whose every file draws an empty chip
row and whose Tasks can carry no mark at all. Attaching a template decides which action chips the
run's files will offer — see [Templates](templates.md).

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

A Todo opens as a tree of folders, or as one flat list of Tasks if the run was told not to keep the
folder tree. Tapping a folder descends into it; the breadcrumb walks back up, and from the Todo's
top level Back returns to the Todo tab. That top level is the folder the run was started on — for a
Todo made over a subfolder of an Upstream, the Todo opens there rather than at the Upstream's own
folder.

Tapping a Task opens it to be looked at, and nothing more: no checkbox, no chips, no Save or Share.
`‹` and `›` step sideways through the other Tasks in the folder you were standing in, so you can
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
| **Swipe left** | Go forward one file; off the last one, mark the item checked and advance |
| **Swipe right** | Go back one file; off the first, go back one item, changing nothing |
| **Pinch** | Zoom, up to 5×; drag to pan inside the picture |
| **Tap the media** | Show or hide the file's own controls |

An item holds one file most of the time, so most swipes are exactly what they look like. A folder
imported as a Task is one item holding several files, and a swipe walks those files before it moves
on.

The name along the top is the item; the filename beside it and the `3(2/3)/7` position say which of
its files you are on and where that sits in the queue. The pair of double arrows there steps the
queue, skipping files entirely, and the single arrows step the files. An item holding one file has
no file level to speak of, so the bar is simply `3 / 7` between two arrows.

The checkbox sits along the bottom, on its own. Above it, laid over the video or picture, are
**★ Favourite**, your own custom action chips and **Info** — those come up and go away when you tap
the media, while the checkbox and the name along the top stay. Tapping a chip tags the item; it
does not check it. Checking and tagging are separate decisions and neither implies the other.

The full behaviour of every control is in [Review and actions](review-and-actions.md).

## Optional: wire up an export

If you have something that should act on your decisions — a script, an automation tool, anything
that accepts an HTTP POST — Listea can send it the results of a round. Or, if you only want a record
of what you did, it can write the same round to a text file. Either way the act is an **export**, and
these are the two **methods** a round can leave by:

| Method | What it does | Asks first |
|---|---|---|
| **Webhook** | POSTs a JSON body describing the round to a URL you configure | Yes |
| **Txt** | Writes one tab-separated text file per round under `Download/Listea/txt/` | No |

**Nothing exports by default, and a new Todo exports nothing.** Three steps turn it on:

1. **Point the webhook somewhere.** **Settings → Exports → Webhook** holds *Default enabled* and
   *Default URL*; paste a URL and tap **Send a test payload** to check the receiver before relying on
   it. The Txt method has nothing to configure.
2. **Pick a method for a Todo.** The Create Tasks dialog asks *When a round over this Todo finishes*,
   offering **Don't export**, **Webhook** or **Txt**. A new Todo starts at *Don't export*.
3. **Leave a List on *Same as the Todos*, or override it.** A List's detail page carries an **Export**
   card whose **Method** row offers *Same as the Todos*, *Export nothing*, *Webhook* or *Txt*, plus a
   switch for exporting when the List completes.

A round then exports when you leave a review, when a Quick Review queue runs out, or when a List goes
complete. The webhook method asks before it posts; the txt method writes and then reports. Three
switches in **Settings → Exports** decide when and what anything exports: **Export from Quick
Review**, **Export when leaving a review** and **Export checked items only**. Every export of every
method is kept in one history — **Settings → Exports → History** — so a round is never lost and can
be sent again later. All of it is in [Exports](exports.md).

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
