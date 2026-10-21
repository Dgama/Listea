# Templates

A **template** is a named, scoped list of action buttons. It is what decides which chips a file
offers under its name in Review, and what each of those chips sends.

Before v0.5.4 the chips were a single app-wide set: two configurable tags in Settings, plus a
favourite built in. That set could not say *this folder of invoices gets these three buttons and
that folder of photos gets one*, and it could not remember what a finished Task was judged with once
somebody changed it. A template answers both, and it does it by being a **row** rather than a
constant — something with a name you can change and a history you cannot.

- [What a template holds](#what-a-template-holds)
- [Name](#name)
- [Scope](#scope)
- [Buttons](#buttons)
- [Editing appends a version](#editing-appends-a-version)
- [Attaching one to a Todo](#attaching-one-to-a-todo)
- [Which template governs a file](#which-template-governs-a-file)
- [The built-in](#the-built-in)

## What a template holds

Three things: a **name**, a **scope**, and a list of **buttons**.

They live at **Settings → Templates**, which lists every template and, on each row, which version of
it is current. Opening one shows its page. Editing is finished with **Update**.

**Nothing in this release starts a template from a blank page.** A template comes into being when
something has something to name: the upgrade that brought an existing install's own tag sets across
gives each such Todo a template named after it. Settings lists what exists and edits it, and offers
no way to add one.

The Create Tasks dialog does draw a **New template** button, and it is inert on purpose. Tapping it
says so, and points at **Settings → Templates**, which is where a template is started today. It is
drawn rather than left out so the shape of the choice — *add one that exists*, or *start one* — is
visible from the start, and so that a control which cannot do anything yet says as much instead of
reading as broken.

## Name

**Template name** — what the template is called where it is listed and where it is attached.

A name is **not** part of a template's identity. Renaming one changes nothing about the Tasks
already judged with it: the Tasks were judged with the buttons, and the buttons did not move. The
same is true of a button's name and its wire value, which is why neither of them is an id.

**A rename does not append a version.** It is a label, and a label that changes is the same
template.

## Scope

**Applies to** — which files the template governs. The chips are the file kinds Listea can classify:
**Images**, **Videos**, **Other files**.

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
| **Records** | **On / off** or **Tag**. |

**On / off** is a button that means itself, like ★: what it records is that it was pressed. **Tag**
is a button that carries a value — the shape the two original chips had.

**Add button** appends one; the bin beside a button removes it. A button's **id** — the thing every
stored mark is actually keyed by — is minted rather than typed, and it never changes. That is what
makes renaming a button or changing what it sends safe: every Task that used it goes on saying
exactly what it said.

The **value** is resolved when a round is exported, not when a mark is made. Renaming a button
changes future payloads without touching a single stored item.

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
exist and lets a run say which of them govern the files it is about to create Tasks over.

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
to intersect two of them, and the set of kinds is three. A scope that admits nothing overlaps
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
buttons** — not of the kinds the Task happens to hold, and not filtered by whether a file of that
kind is present. That is the point: a button set on a video has to stay nameable while a photograph
is on screen, or the badge would report an unknown id for a button the user can see. The order is by
attachment, then by each version's own button order, first occurrence winning — stable, because it
decides the order of a webhook's `actions` array and an array that reshuffles itself is one a
receiver cannot diff.

A template in the attachment whose scope admits none of the Task's files still contributes its
buttons to that union, which is what makes it *the Task's* row rather than a fold over the files.
A reference this build cannot read contributes nothing, as everywhere else in this page.

## The built-in

Every install starts with one template: **Media Asset Control**, applying to images and videos,
carrying three buttons:

| Button | Records | Value |
|---|---|---|
| ★ | On / off | `favorite` |
| C1 | Tag | `cust1` |
| C2 | Tag | `cust2` |

It is seeded from code at upgrade and is otherwise an ordinary template: rename it, re-scope it, add
to it or remove from it freely. The `favorite` value is the one thing fixed about it — a mark made
before templates existed names the same button a mark made after them does, so `favorite` is a
promise rather than a value.

An install that had configured its own tags before v0.5.4 gets more than one template. The upgrade
carries each Todo's own tag set across as a template named after that Todo, so nothing a user set up
is lost — and so the templates they see were made by *their* configuration rather than by the app
inventing a default.

---

Next: [Review and actions](review-and-actions.md) for what the chips do in Review, or
[Exports](exports.md) for where the values they carry go.
