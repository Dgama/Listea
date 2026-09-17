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
- [Optional: wire up a webhook](#optional-wire-up-a-webhook)
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

The dialog asks three things:

| Field | What it decides |
|---|---|
| **Importer** | Whether each **file** becomes a Task, or each **folder** does |
| **Name** | What the Todo is called. Defaults to the folder's own name |
| **Keep folder tree** | Whether the Todo shows the folder structure, or one flat list of Tasks |

**One Task per folder** forces the tree off: a folder-as-Task run builds Tasks from the folders
themselves and they are flat by construction, so the control is hidden rather than offered and then
taken away. [Core concepts](core-concepts.md) says what the two importers produce.

The Tasks appear on the **Todo** tab, under the name you gave them. Running Create Tasks again over
the same folder reads it afresh and records the answers given this time, a new name included.

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

Opening a Todo is how you get at its Tasks. On the **Todo** tab, tap the Todo's card — the card is
the way in, and the only button left on it is **Refresh Import**, which re-runs the import that
produced it.

A Todo opens as a tree of folders, or as one flat list of Tasks if the run was told not to keep the
folder tree. Tapping a folder descends into it; the breadcrumb walks back up, and from the Todo's
top level Back returns to the Todo tab. That top level is the folder the run was started on — for a
Todo made over a subfolder of an Upstream, the Todo opens there rather than at the Upstream's root.

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
wired to a webhook from its detail page. There is still no way to type an item into a List by hand:
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

## Optional: wire up a webhook

If you have something that should act on your decisions — a script, an automation tool, anything
that accepts an HTTP POST — Listea can send it the results of a round.

1. Open the List's detail page and expand **Webhook**.
2. Switch it on and paste a URL.
3. Tap **Test webhook** to check the receiver before relying on it.

The real delivery fires when the List transitions from incomplete to complete, and asks you first.
There are three other events you can switch on, an app-wide default endpoint used by Quick Review,
and a history of every payload Listea has ever built. All of it is in [Webhooks](webhooks.md).

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
