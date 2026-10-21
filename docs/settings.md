# Settings

Every switch and field on the Settings screen, what it changes, and where the full explanation
lives.

Settings are app-wide. A List's own choice of export method is on that List's detail page, not here —
see [Exports → Where the choice lives](exports.md#where-the-choice-lives).

> **Every switch added after a feature shipped defaults to off**, so a fresh install and an
> untouched Settings page behave the same as they did before that feature existed.

- [Upstreams](#upstreams)
- [Review](#review)
- [Media and review behaviour](#media-and-review-behaviour)
- [Todos](#todos)
- [Templates](#templates)
- [Exports](#exports)
- [File management](#file-management)
- [History](#history)
- [About](#about)
- [Todo deletion](#todo-deletion)
- [A Todo's own two policies](#a-todos-own-two-policies)

**Since v0.6.2 the Settings screen is an index, and every section below is one row on it.** Each
row carries the category's name and a one-line state — how many Upstreams, whether Quick Review is
on, which export methods exist and where the default points, how the history stands — and opens a
page holding that category's controls. No switch, add button or cleanup tool sits on the main
screen: a page is where a category is edited, and the index is where it is found.

The sections below are in the order the index draws them, ending with **History** and, below the
same divider, **About**. The final two sections are **not on that screen** — both describe per-Todo
controls, recorded here because this is where defaults are written down.

## Upstreams

| Control | Default | What it does |
|---|---|---|
| **Each Upstream row** | — | Names one granted folder and what a walk of it finds: how many folders, how many files, and how much they take up. Counted the same way a List's scan counts, so the two can never disagree about what the folder holds. |
| **⋮ Remove Upstream…** | — | Forgets the registration after a confirmation. Todos, Tasks, Lists and files stay; the Android grant is kept. Offered on the row and on the Upstreams tab, and available even when the grant is lost. |
| **Add Upstream** | — | Opens Android's folder picker. The folder you choose becomes an Upstream. |

**This is where folders are added.** The Upstreams tab lists them and opens them, and offers no
picker of its own: adding a folder is not one of the things you do to a folder, and two places to
add one would be two places for the wording to drift apart.

**Adding a folder does not disturb the ones already there.** Each Upstream holds its own grant, and
a new one is registered beside them. Nothing is rebound, and nothing already registered is affected.

**Each row reports its own folder.** The counts under a row are that folder's alone, which is what
makes several Upstreams readable side by side.

See [Core concepts → Folders you grant](core-concepts.md#folders-you-grant).

## Review

| Control | Default | What it does |
|---|---|---|
| **Enable Quick Review in Todo** | On | Off takes Quick Review out of a Todo, and stops the Upstreams browser showing which files are already checked. Presentation only — Lists keep their items, completion and actions. |

**That is the whole section, and it is the whole section deliberately.** The custom actions that used
to be configured here are not a property of the app. Since v0.5.4 they are **buttons on a template**,
and a template is a thing with a name, a scope and a version — so they are edited on the template's
own page, at **Settings → Templates → <template>**, rather than in a card every run shared.

Full behaviour: [Review and actions → Buttons](review-and-actions.md#buttons). What
the values do on the wire: [Webhooks](webhooks.md).

## Templates

**One row, and a screen behind it.** A template is what a Todo records with: which files it applies
to, and the buttons it offers. None of that fits on a card in a scrolling page, so what this screen
says about them is how many there are and where they are edited.

| Control | Default | What it does |
|---|---|---|
| **All templates** | — | How many templates the app holds. Opens the **Templates** screen, and from there a template's own page. |

A template's page holds its **name**, its **scope** — which files it applies to — and its **buttons**,
each with a **display name** (the chip) and a **webhook value** (the string an export sends).
**Nothing is written until Update, and Update appends a version** rather than overwriting one, so the
version a Task was imported under is kept: renaming a button or changing what it sends never reaches
back into what a Task already says it was judged with.

Every install starts with one built-in template, and the upgrade that brought an existing Todo's own
buttons across gave each of those one of its own. A template is also what a new **Todo** starts with,
which is what leaves one place to edit a button rather than two.

Where a button's value goes: [Webhooks](webhooks.md). What a button *records*, and what removing one
leaves behind: [Review and actions → Buttons](review-and-actions.md#buttons).

## Media and review behaviour

<p align="center">
  <img src="assets/screenshots/settings-media-behavior.jpg" width="300" alt="The Media and review behaviour section of Settings, with switches for playing videos automatically, starting videos muted, remembering review position, auto-checking list freshness and reviewing unchecked items only">
</p>

| Control | Default | What it does |
|---|---|---|
| **Play videos automatically** | On | Whether a video starts playing when you arrive at it. |
| **Start videos muted** | Off | Whether a video arrives muted. A rule about *arriving*, not a mode — unmuting one video does not unmute the next. The transport's mute button overrides it for that file alone. |
| **Remember review position** | On | Whether full Review resumes where it left off. Quick Review always starts at the first unchecked item regardless. |
| **Auto-check list freshness on entry** | On | Whether opening a folder-backed List scans its source folder for changes. Off, a List reports *Not checked* until you use **Update from folder**, which still works either way. |
| **Review unchecked items only** | Off | Whether entering a review queues only the items that were unchecked at that moment. The queue is fixed for the round, so an item you check stays in front of you until you leave. |

*Remember review position* decides what is **read** on entry, never what is written — the position
keeps being recorded either way.

See [Review and actions](review-and-actions.md#video) for the video behaviour, and
[Rounds](review-and-actions.md#rounds) for what *unchecked items only* does to a queue.

## Todos

**Headed "Todos" on the screen, with "What new Todos start with" above the rows.** The two app-wide
defaults a new Todo is created with. Both are also held per Todo, and a new Todo takes these only at
the moment it is created.

| Control | Default | What it does |
|---|---|---|
| **When a Task's content changes** | Keep it | The answer a new Todo starts with when a file it imported changes or disappears outside Listea. **Keep it** flags what has gone and changes nothing else; **Start it over** keeps the Task and clears its ticks, so moved content is judged again; **Replace the Task** deletes a Task that changed and mints a new one, taking it out of every List that held it. See [What a changed Task means](core-concepts.md#what-a-changed-task-means). |
| **Follow the upstream on new Todos** | Off | On, a new Todo is created following its upstream: opening it scans the folder and brings in material that appeared since, without asking. Off, opening a Todo never writes. |

**This is the same three-row chooser the Create Tasks dialog asks with**, drawn from the same
`ContentPolicyChoice` composable and the same `ContentPolicy` labels, so the default and the
per-run answer cannot call a tier two things. A Todo set here remembers whatever the dialog is told
instead.

**The three answers are written on the choice itself, not just its name**, and the third is the only
one that removes anything — which is why it is the third. A user setting it is stating an intention
about a Task they own, and that is the whole of its licence: nothing in Listea infers this tier from
a Task's shape. See [Task lifetime](core-concepts.md#task-lifetime).

Both defaults follow the rule at the top of this page, and both are the behaviour-preserving answer.
Before this release a re-import changed no Task's state, and no run ever started by itself; a Todo
that arrived already starting Tasks over would have begun clearing judgments the user never asked it
to touch.

## Exports

**One row, and a screen behind it** — the shape the Templates card above already has. It replaced a
card headed **Webhook** that held a default URL, three switches that were never about HTTP, and a
history button; every one of those lives under here now, one level down.

| Control | Default | What it does |
|---|---|---|
| **Exports** | — | How many methods there are, and where the default address points — *"2 methods · webhook at n8n.example"*, or *"webhook not set"*. Opens the **Exports** screen. History is no longer reported here; it has its own index row. |

The screen behind it lists one row per method — **Webhook**, then **Txt** — each with its destination
on the row, and each opening a page of its own. Under the methods sit the three switches that every
export obeys and none of them owns: **Export from Quick Review**, **Export when leaving a review**
and **Export checked items only** — see [Exports → Settings → Exports](exports.md#settings--exports)
for what each decides. They sat in the old Webhook card, which made them look like
statements about HTTP; the two send switches are about *when* a round exports and the third is about
*what* a payload carries, so they belong one level above the methods rather than inside one of them.

**The method a round uses is not chosen here.** A Todo picks one in the Create Tasks dialog, and a
List's own **Export** card can override its Todos or leave each to answer for its own Tasks.

What each method does, when it asks first, and how a choice resolves: [Exports](exports.md). What the
webhook method sends, and how a delivery fails: [Webhooks](webhooks.md).

## File management

| Control | Default | What it does |
|---|---|---|
| **Same filter and sort everywhere** | On | One filter and one sort order shared by every folder and every List. Off, each keeps its own. Switching discards nothing — both are kept either way. |
| **Ask to delete after webhook** | Off | After an export that **actually went out**, offers to delete the files that delivery carried as checked. |
| **Upstream** chips | First Upstream | Which folder the two rows below count and delete in. Shown only when more than one Upstream is registered; with one, there is nothing to choose. |
| **Checked files** | — | Counts every checked file under that folder that is still on the device. Not a List's progress and not a round's tally. |
| **Delete checked files** | — | Deletes all of them, permanently, after showing you the full named listing. |

Filtering and sorting only change what is shown, and which items a review then walks. Progress,
completion and exports always count the whole folder or the whole List.

> **Two different scopes, deliberately.** *Ask to delete after webhook* offers exactly the checked
> items in the export that just went out. *Delete checked files* covers every checked file under
> the chosen Upstream, however and whenever it was checked. The dialogs say which one you are
> looking at.

**The label names the webhook and the offer does not.** *Ask to delete after webhook* is the
switch's stored name and its wording on this screen, and it was not renamed when the mechanism
became a choice. What the offer actually follows is a successful **export** of any method: a txt
export that wrote its file offers exactly as a POST that was accepted does. The label is a recorded
open item, described accurately here rather than left to be discovered.

Full behaviour, including write access and what happens to deleted files' decisions:
[File management](file-management.md). The filter and sort options themselves:
[Review and actions → Filtering and sorting](review-and-actions.md#filtering-and-sorting).

The **Upstream** chips and both file tools derive their roots from the Upstream list. Removing an
Upstream therefore removes that folder from these tools even though its files, Todos and Lists
remain — the confirmation says so rather than leaving it to be noticed.

## History

**The last row on the index**, because history is a record of what the app did rather than part of
configuring how it does it. It used to be reachable from three doors — the Exports landing page, the
Webhook page and a note on the Txt page — and those copies are gone. The row reads *"Nothing yet"*
when no export has been attempted, otherwise *"N exports · R rounds"* (or *"N exports"* when the two
numbers are equal), and carries a warning tone while a record is still pending a receiver.

**The screen groups records by the round that produced them.** A round card shows the time, the
List, the event, how many exports it produced and one aggregate verdict — *Delivered*, *Partly
delivered*, *Failed*, *Not sent*, or *Outcome not recorded*. Tapping it expands into the actual
exports, each of which keeps its own method, address, item count and outcome; *Open payload*,
*Resend* and *Delete* act on one export. A single-export round uses the same shape rather than a
different one. Records written before schema 27 have no round id and are each a round of one — a
resend moves its round to the top, because the round's time is its newest row.

*Resend* is described under [Webhooks → The destination](webhooks.md#the-destination): it prefills
the address that attempt used, and an edit there is temporary.

## About

**The last row on the index, under a divider**: *Version 0.7.0 · third-party notices*. It was added
in v0.7.0, when the build first embedded someone else's native renderer; before that the app had no
About or licences surface anywhere, so this page is new rather than an old screen being reused.

It names the running build — the same `BuildConfig.VERSION_NAME` the APK is stamped with — and
prints the full text of every licence that applies to what ships inside it:

| Component | Licence |
|---|---|
| **Nucleus PDFium** `dev.nucleusframework:pdfium:158.0.8111.0` | MIT |
| **PDFium** | BSD-3-Clause |
| **pdfium-binaries** (the native build packaged by the wrapper) | MIT |
| **Listea itself** | Apache-2.0 |

The text is carried in the APK as a raw resource and read on the device, so the page works with no
network and cannot point at a licence document that has since moved. It is inside a selection
container, so any part of it can be selected and copied.

## Todo deletion

**Not on the Settings screen**, and recorded here because it is a default and this is where defaults
are written down. It sits in the delete confirmation instead, where its consequence is legible at
the moment it is being decided, and it is stored per Todo rather than app-wide.

| Control | Default | What it does |
|---|---|---|
| **Keep the decisions** | Off | Whether dissolving a Todo writes down what its Tasks were judged to be — including, for Tasks nobody judged, the fact that nobody had. On, a Todo **created** at the same folder is later offered that record to load from; off, the decisions go with the Tasks. See [Deleting a Todo](core-concepts.md#deleting-a-todo). |

Off follows the rule at the top of this page, and it is the sharper case for it: before v0.4.0
nothing could delete a Todo at all, so a Todo that arrived already keeping a record would have begun
doing something the user never asked for. The switch is the only way this behaviour is reached —
nothing writes a record as a side effect of anything else.

## A Todo's own two policies

**Also not app-wide**, and for the same reason: both are statements about one Todo, so both live on
that Todo rather than here. They are the two switches in the Todo card's overflow menu, above
**Delete Todo…**, and the app-wide settings above are only what a *new* Todo is seeded with.

| Control | Default | What it does |
|---|---|---|
| **Follow the upstream** | Off | This Todo alone: opening it syncs material that appeared since, without asking. |
| **Keep decisions when deleted** | Off | This Todo alone: dissolving it writes down what its Tasks were judged to be, for a later Todo at the same folder to load back. |

**Both write the same columns the two app-wide defaults seed**, which is what makes a Todo's card
the place its own behaviour is decided: a Todo made before either control existed has the settings
its row was backfilled with, and changing the app-wide default afterwards leaves it where it is.

The Todo holds a **third** standing answer — *When a Task's content changes* — and the card
**reports it without offering it**. It is printed under the Todo's name, in the words and under the
heading the Create Tasks dialog asked the question with, so a Todo under **Replace the Task** is
visibly not one under **Keep it** before the run that would tell you. Changing it stays in the
dialog, because that is where its three consequences are spelled out and a one-line switch cannot
carry them. Earlier drafts left the card silent on the grounds that the tier is a word about a run;
the cost of that is a Todo whose next import will delete a Task looking exactly like one that will
not.

Each row prints its consequence under its name, because neither switch shows one when it is thrown:
following the upstream writes on a later day without being asked, and keeping decisions on delete
only matters at a delete that has not happened yet. The row is the target rather than the thumb, and
tapping one leaves the menu open — a policy is set without the menu closing, so both can be read and
changed in one visit.

---

Next: [File management](file-management.md) for the destructive settings in full, or
[Exports](exports.md) for how a round's decisions leave the app.
