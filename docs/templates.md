# Templates

A **template** is a named, scoped list of action buttons. It is what decides which chips a file
offers under its name in Review, and what each of those chips sends. Since v0.7.0 a template can
also govern a PDF, where its buttons become **passage tools** chosen from a text selection rather
than chips applied to the whole file.

Before v0.5.4 the chips were a single app-wide set: two configurable tags in Settings, plus a
favourite built in. That set could not say *this folder of invoices gets these three buttons and
that folder of photos gets one*, and it could not remember what a finished Task was judged with once
somebody changed it. A template answers both, and it does it by being a **row** rather than a
constant — something with a name you can change and a history you cannot.

- [What a template holds](#what-a-template-holds)
- [Name](#name)
- [Scope](#scope)
- [Buttons](#buttons)
- [PDFs and passage tools](#pdfs-and-passage-tools)
- [Editing appends a version](#editing-appends-a-version)
- [Attaching one to a Todo](#attaching-one-to-a-todo)
- [Task reviews, the pen](#task-reviews-the-pen)
- [Which template governs a file](#which-template-governs-a-file)
- [The built-in](#the-built-in)
  - [The built-in Task Reviews](#the-built-in-task-reviews)
- [Add-ons](#add-ons)
- [Three layers, three vocabularies](#three-layers-three-vocabularies)

## What a template holds

Three things: a **name**, a **scope**, and a list of **buttons**.

They live at **Settings → Templates & Add-ons**, which lists every template — and, since
v0.7.1, every add-on beside it — and, on each row, which version of it is current. Opening one shows its page. Editing is finished with **Update**.

**A template can be started from a blank page.** Since v0.6.0, **New** on Settings → Templates & Add-ons asks
for a name and creates a lineage at version 1 with no scope and no buttons. A template also still
comes into being in the other way: the upgrade that brought an existing install's own tag sets
across gives each such Todo a template named after it. Both paths are ordinary — a new template is
edited, scoped and attached exactly like a seeded one.

The Create Tasks dialog's **New template** button is still inert on purpose. Tapping it says so and
points at **Settings → Templates & Add-ons**, which is where a template is started. It is drawn rather than
left out so the shape of the choice — *add one that exists*, or *start one* — is visible while
attaching, and so that a control which does not act there says as much instead of reading as broken.

## Name

**Template name** — what the template is called where it is listed and where it is attached.

A name is **not** part of a template's identity. Renaming one changes nothing about the Tasks
already judged with it: the Tasks were judged with the buttons, and the buttons did not move. The
same is true of a button's name and its wire value, which is why neither of them is an id.

**A rename does not append a version.** It is a label, and a label that changes is the same
template.

## Scope

**Applies to** — which files the template governs. The chips are the file kinds Listea can classify:
**Images**, **Videos**, **PDFs**, **Other files**. PDFs are a kind of their own rather than part of
*Other files*, because a PDF template's tools are offered per passage: scoping one to *Other files*
would also hand those tools to every text file and archive, which this build cannot render.

Nothing chosen means **Applies to nothing**, and the page says so in those words rather than leaving
the line blank. That is not a trap but a rule: *a template that says nothing about what it is for
cannot be used for anything*, and a file that no attached template matches gets **no chips at all** —
not a shortened row, not a star. A scope is a statement about what a template is *for*, and the app
refuses to guess one on the user's behalf.

Two rules follow from the same place:

- **A scope is a conjunction.** Choosing Images and Videos means either kind, because each chip is
  its own rule; but a scope carrying two rules that cannot both be true governs nothing, and the
  page warns when it reads one.
- **A rule this build cannot evaluate admits nothing.** A scope written by a later version may
  contain a rule this one does not understand; rather than ignoring it — which would *widen* the
  template to files it was never meant to cover — the page names the rule it cannot read and the
  template governs nothing until a build that understands it opens it.

## Buttons

A template's buttons are the chips. Each has three parts:

| | |
|---|---|
| **Display name** | What the chip says — `★`, `C1`, whatever you like. |
| **Webhook value** | The string a payload sends for it when the button is on. |
| **Records** | **On / off**, **Tag**, or **Comment**. |

**On / off** is a button that means itself, like ★: what it records is that it was pressed. **Tag**
is a button that carries a value — the shape the two original chips had. **Comment** (added in
v0.7.0) carries the reviewer's own text, written when the button is applied to a passage.

A button's meaning on a PDF follows its type. **On / off** has no passage form and is inert there;
**Tag** and **Comment** become the two passage tools, and the webhook value of a Comment is unused
because the text is the reviewer's.

**Add button** appends one; the bin beside a button removes it. A button's **id** — the thing every
stored mark is actually keyed by — is minted rather than typed, and it never changes. That is what
makes renaming a button or changing what it sends safe: every Task that used it goes on saying
exactly what it said.

**The button encoding gains version 2.** Version 1 can describe only `boolean` and `tag` buttons;
version 2 adds `comment`. The version is written as **2 only when the list actually contains a
comment**, so a template edited in this build but holding no comment stays readable by a build that
has never heard of version 2. A decoder meeting a version it cannot read fails closed, as every
payload in this codebase does.

The **value** is resolved when a round is exported, not when a mark is made. Renaming a button
changes future payloads without touching a single stored item.

## The frame, and the components a template supplies

A review surface is built from two kinds of component, and only one of them belongs to a template.

**The frame is Listea's.** The navigator (back, title, queue position), the completion checkbox,
and the file's own dismissible bar with its trailing **Info** icon are present for every template —
including no template at all. They are not buttons, versions or configuration: a template cannot
add, remove or rename them. The Info icon is the frame's answer to *what is this file, and what does
Listea hold about it*, so it stays available even when the template has nothing to offer. That is
what keeps a file reviewable after its template is removed, and what lets a stored comment, a tag
or a mark the document can no longer place still be found when the reviewer asks.

**The dynamic components are the template's.** A media template supplies the chip row over the
file; a PDF template supplies the passage tools in the selection menu. Those change with scope,
version and attachment, and they are the only parts a template editor can alter. A template version
therefore records the dynamic components and never the frame.

For a **PDF** the frame's file bar holds only Info; its dynamic components are not a chip row but
the passage tools in the selection menu. See the next section.

## PDFs and passage tools

A template scoped to **PDFs** is the same template model, not a second one: it has a name, a
version history and buttons, and it is attached to a Todo exactly as a media template is.

What differs is **where its buttons appear**. A PDF is reviewed sentence by sentence, so:

- the selection menu offers **Copy** and then the governing template's **Tag** and **Comment**
  buttons, in template order;
- choosing one creates **one mark on that passage** and closes the selection. Applying a second
  tool means making a second selection;
- the **file-level chip row carries no tools** on a PDF, because a passage tool applied to the
  whole file would not describe anything. The frame's file bar stays (it is the frame's, not the
  template's) and holds only **Info**; it comes and goes with a tap on the page, as on a picture.
  An **On / off** button is therefore inert on a PDF; the editor still offers the type because a
  template can be re-scoped, and hiding it there would change what an existing button means when
  the scope moves;
- a PDF with **no PDF template** is still readable and Copy still works. It draws no marks, offers
  no review tools and exports no passages.

**Visibility is decided by the template that governs the file now**, using the same first-match
rule as chips: the first attached version whose scope admits PDF, and the tool with the mark's id
in that version. Removing that tool — or the template — hides its marks on screen and from exports
without deleting a row; restoring the same id brings them back. This is the template rule already
used for chips, applied to passages.

A PDF's **`actions` array stays empty** in an export. Its tools are passage-scoped, and putting a
passage into a file-level array would say the file was tagged rather than the sentence. The marks
travel in their own `marks` array, described in [Exports](exports.md).

Marks belong to the Asset, never to the template: the template version a mark was authored under is
recorded as history, and it is never consulted to decide whether the mark is shown. That is what
lets a template edit hide a mark and a later restore bring it back with the same identity.

## Editing appends a version

**A template is a series of answers, not one answer.**

Renaming edits in place. Changing the **scope** or the **buttons** appends a new version —
version 2, version 3 — and leaves the previous one exactly where it was, readable by everything that
still points at it.

This is what makes an edit safe to make. A Todo attaches *one version of one template*, and a Task
judged under that version goes on meaning what it meant after the template has moved on: the
buttons it offered, the values they sent, the files they governed. Nothing is rewritten
retroactively, and a completed Task's record cannot be changed by an edit made later.

A version is **never edited**. There is no path in the app that changes what version 1 said.

## Attaching one to a Todo

**Create Tasks** is where a template is attached. The dialog's last step lists the templates that
exist and lets a run say which of them govern the files it is about to create Tasks over. The same
step carries a second, separate choice — the **Task review** every Task this run creates will be
judged by; see [Task reviews, the pen](#task-reviews-the-pen).

**A run with nothing attached is refused**, and it is one of the dialog's two gates. Since v0.6.2 the
**Create** button stays enabled and pressing it opens the **Review** section with the template
picker in front of the user, rather than a disabled button whose reason may be folded away. The
alternative is a Todo whose every file draws an empty chip row and whose Tasks can carry no mark at
all, which is a thing the user would discover after importing a folder rather than before it.

**A Todo holds one version of a lineage, not several.** Attaching a newer version replaces the one
the Todo holds, **in the position that one already occupied** rather than at the end, because the
order decides which template governs a file and bringing one up to date says nothing about that.
Unticking the row removes the attachment.

**A template the Todo holds at an older version is drawn ticked, and says so** — *Held at version 1.
This run will attach version 2.* The row is already ticked, so there is nothing to press: leaving it
alone *is* the instruction, and unticking it is how the move is refused. It is said because the
alternative is a lie — the row would read as *not attached* for a template the Todo is attached to.

A refresh run re-asks the same step and re-stamps it, which is why the drawer is drawn for a refresh
too: a refresh is exactly the run that touches every Task it is about.

**Leaving a ticked row alone on a refresh adopts the version the picker offers**, which is what that
row has always promised. Before v0.6.2 the draft was seeded from the Todo's stored references and
confirming submitted it untouched, so the dialog said *this run will attach version 2* and then
re-stamped version 1 — silently, and durably, because a refresh touches every Task it still holds. A
lineage the picker no longer offers keeps its held reference; a lineage no longer ticked is removed
as before. A follow-the-upstream sync has no dialog and never upgrades anything.

**When a held version will move, the dialog says what that does to marks that already exist** — the
entry-12 rule, stated before Confirm: after the refresh, all of that Todo's Tasks report the new
version on the next export, including those marked under the old one. If the edit only renames
buttons the wire values are unchanged; if it adds or removes buttons, marks on a removed button
become invisible and are never sent, and a new button starts unmarked. Unticking the moved template
withdraws both the write and the notice.

## Task reviews, the pen

Everything above governs a **file**: which chips a file draws and what those chips record. A **Task
Review** is the other half of the stationery. It is a template in the same sense — a lineage with
immutable versions, chosen in Create Tasks and stored on the Todo — but what it governs is the Task
as a whole: whether the Task can be completed, how a completion is committed, and which answers a
Viewer may hand back.

The stationery analogy is worth keeping, because the two kinds are matched by declared capability
rather than one to one. A file template is the pen and highlighter for the printed pages; a Task
Review is the stationery that takes the answer back off the page — a single-choice sheet, a tool
that catches a point on a picture, or the blank form that only records that the candidate worked
through the file. Any Task Review can sit beside any file template: choosing a pen does not choose a
question, and answering a question does not depend on which pen drew the marks.

**A run chooses one Task Review, and the picker is a radio group rather than a set.** There is no
scope to reconcile and nothing to order, because one page follows the Task and two questionnaires
would have to share it. The chosen version is stamped on every Task the run creates or touches, and
a refresh may move a Todo held at an older version to the version the picker offers — the same
version discipline the file templates follow. A run whose picker has no readable review falls back
to the built-in, because a Task judged by nothing is not a shape this model has.

**What a review catches is declared, and every row says it in business terms.** A review that
accepts a `choice.select` reads *catches a single choice*; one that accepts an `image.point` reads
*catches a sampled point*; one that accepts nothing reads *records the Task's file decisions*. The
last is still a useful pen — it is the "I read this and have nothing to write" answer — but it is
not the matching pen for a Package, whose Prompts are answered through a Viewer. The dialog says so
when that pairing is chosen, and a Package import starts on the single-choice review unless the user
has already picked another.

**Headless means the Viewer supplies the controls.** A headless Task Review has no chip row of its
own and no question it draws: the Prompt's page is the questionnaire, and the template decides what
an answer means — whether one tap is the commit, whether the answer may be revised, whether the
Task completes and advances. "Headless" names that division of labour, not a missing part. The pen
is real; it simply does not draw the question, because the Viewer is already holding it.

## Which template governs a file

When a Todo has more than one template attached, a file gets **the first one whose scope admits its
kind** — in the order the templates were attached.

**That rule is the resolver's, and the dialog works to keep it from being needed.** Two templates
whose scopes overlap — two that can both govern one file — **cannot both be attached**: the second
one is drawn **disabled**, reading *Governs the same files as one already attached*, rather than
hidden. *First attached wins* is the right rule for a model and not one to hand a user, who would get
two chip rows for one file and no way to tell which template drew which. Disabled rather than absent,
because a template that vanishes from a list is a template the user thinks was deleted.

The question is asked of **scopes**, and answered by trying every kind rather than by comparing
rules: a scope's rules are a conjunction whose members may mean anything, so there is no general way
to intersect two of them, and the set of kinds is four. A scope that admits nothing overlaps
nothing, which falls out of the definition rather than being special-cased — a template that governs
no file cannot be the second answer to anything.

The overlap check reads its answer off the templates **ticked**, and a ticked row contributes the
scope the run would write. A Todo holding `T@1` while the picker offers `T@2` is attached to `T`, so
it is `T@2`'s scope that the next row is measured against.

**A file no attached template admits gets no chips at all.** Not an empty row, not a star: the item
offers nothing but the completion checkbox. This is the scope rule above arriving at the item, and
it is why an empty scope is worth warning about.

**The two Task-level surfaces speak about the Task, not about the file on screen.** The badge row
under a filename, and the payload's `actions` array, are the union of **every attached template's
file-level buttons** — not of the kinds the Task happens to hold, and not filtered by whether a file
of that kind is present. A **Comment** is the one exclusion: it carries a passage, so it never
becomes a chip, a badge or an entry in `actions`; the PDF selection menu reads it from the same
buttons. That is the point: a button set on a video has to stay nameable while a photograph
is on screen, or the badge would report an unknown id for a button the user can see. The order is by
attachment, then by each version's own button order, first occurrence winning — stable, because it
decides the order of a webhook's `actions` array and an array that reshuffles itself is one a
receiver cannot diff.

A template in the attachment whose scope admits none of the Task's files still contributes its
buttons to that union, which is what makes it *the Task's* row rather than a fold over the files.
A reference this build cannot read contributes nothing, as everywhere else in this page.

## The built-in

Every install starts with four templates: one file template, **Media Asset Control**, and three
Task Reviews. They are seeded at install and at upgrade, and are otherwise ordinary templates:
rename them, re-scope them, add to them or remove from them freely.

**Media Asset Control** applies to images and videos and carries three buttons:

| Button | Records | Value |
|---|---|---|
| ★ | On / off | `favorite` |
| C1 | Tag | `cust1` |
| C2 | Tag | `cust2` |

The `favorite` value is the one thing fixed about it — a mark made
before templates existed names the same button a mark made after them does, so `favorite` is a
promise rather than a value.

An install that had configured its own tags before v0.5.4 gets more than one template. The upgrade
carries each Todo's own tag set across as a template named after that Todo, so nothing a user set up
is lost — and so the templates they see were made by *their* configuration rather than by the app
inventing a default.

### The built-in Task Reviews

| Name | Catches | How a completion is committed |
|---|---|---|
| **Headless Template for File Decisions** | nothing — records the Task's own page decisions | the completion checkbox |
| **Headless Template for Sampling a Point** | `image.point` — a long press on a picture | its own **Submit** button |
| **Headless Template for Single Choice** | `choice.select` — a tap on one of the Prompt's options | the tap itself |

The first is the old rapid review and remains the starting answer for a run that creates Tasks from
files. The other two are the pens for the kinds of Viewer answer that exist so far; the sampled-point
one is the acceptance fixture for a template whose own button is its commit control, and the
single-choice one is what a Package import starts on.

**A built-in's name is data, and editing it is the owner's.** The rows are seeded under the names in
the table and are never overwritten after that. The one exception is a repair on open: a row that
still carries the name an earlier build seeded — *Rapid Review*, *Sample a point*, *Single Choice* —
is moved to the headless name above, while a row somebody has renamed keeps their wording exactly.
The definitions' own display names are different and deliberately do not move: those strings are
inside a pinned content hash, and a hash is identity, not a label.

## Add-ons

Since v0.7.1, **an add-on** is a small questionnaire about a **Task as a whole** — the third thing in
the app that can be attached to a Todo, beside its templates. It is not a template and not an Asset:

| | Template | Add-on |
|---|---|---|
| What it governs | buttons on files of some kinds | questions about the whole Task |
| Has a scope | yes, a conjunction of kind rules | **no** |
| Produces | per-file actions, per-passage marks | Task-level answers |
| Position | wherever its buttons apply | **fixed after the Task's last file** |

**A Task whose import carried an add-on has one extra page after its last Asset.** Swiping left off
the last file shows the questionnaire; swiping left again completes the Task as leaving the last
file does today; swiping right returns. The navigator counts it, so a three-file Task with an add-on
reads `1(1/4)` … `1(4/4)`. The page is **never required**: the completion checkbox still completes
without an answer, and no question has a required flag.

**Three question types**: **Rating** (1–5), **Yes / No**, and **Text**. The prompts are yours. An
answer is written as it is given — tapping a rating or Yes / No writes it, tapping it again clears
it, text saves on Done or leaving the field, and a blank field clears. The answers are kept on the
Task, not on any file, and the Task's **Info** sheet lists them, including answers a later version
of the add-on no longer asks.

**One add-on per Todo.** Create Tasks and Refresh Import gain an **Add an add-on** section drawn like
*Add a template*: at most one choice, *None* by default, never part of the dialog's gate. The Todo
records its choice; every Task the run creates or touches is stamped with the version it was built
under. A Task imported before this round has no add-on and no extra page, and a List may mix Tasks
with and without one.

**An add-on is stored as a lineage with immutable versions**, exactly like a template: editing the
questions appends a version; renaming edits in place; nothing is deleted. A refresh adopts the
add-on's offered current version, and says what that does to answers already given: a removed
question's answer is kept but hidden — not shown and not sent — and an added question starts
unanswered. The editor lists questions earlier versions asked under **Removed questions**, and
restoring one keeps its id, so the answer it already has comes back instead of being asked again.
Answers are **hidden, not deleted**, the same rule a detached PDF mark follows.

The editor lives beside the templates at **Settings → Templates & Add-ons**. Full behaviour:
[Settings → Templates & Add-ons](settings.md#templates--add-ons). On the wire:
[Webhooks → The Task's add-on](webhooks.md#the-tasks-add-on-and-why-it-is-version-4).

## Three layers, three vocabularies

A Task now has three levels of recorded judgment, and each lives in its own place:

| Layer | What is judged | Where a decision is stored |
|---|---|---|
| Task | the Task as a whole | `task_review_answers` — the add-on's answers |
| Task–Asset pair | one file in the Task | `task_asset_state` and `task_asset_marks` — selections and passages |
| Asset | the file itself | `assets` — what the Task presents, its name, path and presence |

Templates draw the middle layer: their buttons write a file's selections and their passage tools
write a mark. An add-on draws the top layer: its questions write answers about the Task. Nothing
below the top layer changes meaning because an add-on exists — `assets` still means files the Task
presents, and the export still calls them `assets[]`.

---

Next: [Review and actions](review-and-actions.md) for what the chips do in Review, or
[Exports](exports.md) for where the values they carry go.
