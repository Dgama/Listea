# Core concepts

The vocabulary Listea uses, and the one rule that explains most of its behaviour.

- [Folders you grant](#folders-you-grant)
- [Upstreams](#upstreams)
- [Tasks and Todos](#tasks-and-todos)
- [Deleting a Todo](#deleting-a-todo)
- [Folders](#folders)
- [Files and items](#files-and-items)
- [Questions and parts](#questions-and-parts)
- [Lists](#lists)
- [Folder types](#folder-types)
- [Quick Review](#quick-review)
- [Rounds and queues](#rounds-and-queues)
- [Checked state](#checked-state)
- [Actions](#actions)
- [Templates](#templates)
- [Where state lives](#where-state-lives)
- [Item identity](#item-identity)

## Folders you grant

Listea reaches nothing it has not been given. Access comes from Android's system folder picker, one
grant per folder, and **each grant is held on its own**: adding a second folder does not cost you
the first. A grant is a Storage Access Framework tree URI, and it persists across restarts.

A grant covers the folder you picked and nothing else. Listea cannot reach outside it — not into a
sibling folder, not into a parent — and taking the grant away in Android's settings makes that
folder unavailable rather than deleting anything Listea knows.

Earlier versions had a single **root**, the one folder everything was scoped to. Lists built then
still record it and still work; nothing you add now is tied to it.

## Upstreams

An **Upstream** is a folder that has come into the system. Outside Listea it is only a folder — a
*Source*, in the sense of a file that exists whether or not anyone has chosen it — and what makes it
an Upstream is someone picking it.

Adding one is a **Settings** action, because it is about what Listea may read rather than about what
there is to do. The **Upstreams** tab lists one card per Upstream, and opening a card browses that
folder live.

An Upstream is not a queue and holds nothing to review. It has no Tasks of its own, no Quick Review
and no selection; the two things to do with one are looking at it and running **Create Tasks**
somewhere inside it. It is not tied to any one Todo either — you may run several imports over
different folders within it, or none at all.

An Upstream is **available** while Listea still holds the grant its folder sits under. One whose
grant is gone stays on the list rather than disappearing: the Tasks it produced still exist, a List
built from them still works, and what is lost is refreshing and browsing it.

## Tasks and Todos

A **Task** is one unit of work. Running **Create Tasks** over a folder inside an Upstream makes them:

| Importer | What a Task is |
|---|---|
| **One Task per file** | One Task per file in the folder that a Viewer can open — picture, video, PDF. Its folder tree can be kept or flattened |
| **One Task per folder** | One Task per immediate child folder, holding that folder's openable files. Flat by construction |
| **Package Prompts** | One Task per Prompt declared by each Package's manifest, nested under *One Task per file*. The Package's `media` members are Resources, not Tasks |

**Since `0.8.4-preview`:** a Package may declare **Sections**, and the import then groups questions
as **one Task per question** (the default) or **one Task per Section**. The rest of the importer's
meaning is unchanged. The model is [Questions and parts](#questions-and-parts).

Since v0.8.2f a file no Viewer can open is **skipped, not imported**: a text note, a JSON Prompt
body, a manifest. A Task nothing can render is a Task nobody could judge, and refusing the judgment
after creating the Task left the user with a row that could never be answered. The run reports how
many files it skipped.

A **Todo** is what one run produced: those Tasks, plus the provenance saying which folder they came
out of and under which importer. Opening a Todo shows them — as a browsable tree of folders when the
run kept the folder tree, and as one flat list when it did not.

**A Todo is not "everything the Upstream holds".** A run happens on the folder you were in when you
asked for it, so one Upstream can carry several Todos over different parts of its tree, and a folder
that has never been imported has none. What a Todo holds is exactly what its one run made, which is
why its card is a thing in its own right rather than a view of the Upstream.

**They may overlap, and a folder may have as many Todos as you have intentions for it.** A run over
a folder nested inside a Todo you already made is allowed, and so is running the same folder twice.
What a run makes is a *new* set of Tasks: they belong to the Todo that ran, and the fact that the
same files are behind another Todo's Tasks is not a relationship between them.

That is the rule the whole model turns on. A **Task** is the user's intention, so the same file
reached through two Todos is two Tasks with two decisions, and the same Task reached through two
Lists is one Task with one. The file itself is never owned: it is content *presented for* a Task,
and presenting it for a second one takes nothing away from the first — see [Lists](#lists).

Creating a Todo on a folder that already has one is therefore ordinary, and the dialog says so
rather than asking permission: it names the Todos already there, then proceeds.

### What a changed Task means

A file in a Todo's folder can change without Listea doing anything: it is deleted in the Files app, or
it is moved, or new files are added beside the ones a run imported. The next run over that folder
finds a Task whose content is not what the run left there, and every Todo has to answer what that
means. The answer is a **content policy**, chosen per Todo, and there are three:

| Policy | The Task | Its decisions | Lists holding it |
|---|---|---|---|
| **Keep it** | survives | survive | keep it |
| **Start it over** | survives, same Task | cleared | keep it |
| **Replace the Task** | deleted, a new one minted | go with the Task | lose it, and are told |

**Keep it** is the default and the answer that changes nothing: material that has gone is flagged
missing, and the Task, its ticks and every List holding it are untouched.

**Start it over** keeps the Task's identity — so its history, its List memberships and every id an
export would carry are unchanged — and clears its *decisions*. A Task whose content moved is not
the same thing to judge, so carrying the old tick forward is the mistake this tier exists to avoid.

**Replace the Task** is the only tier that reaches lifetime, and it is the only one that removes
anything. The Task is deleted and a fresh one is minted by the same run, at the same path, so a
replacement exists exactly when the run has material to put in it: a group whose files have all gone
is replaced by nothing.

**Two things stop a policy from becoming a machine that acts on everything.** First, a Task the run
finds *unchanged* is under no policy at all — all three describe what a change means, so a run that
finds the material as it left it has nothing to answer, and a Todo under *Start it over* does not
clear its Tasks' decisions every time it is refreshed. Material already flagged missing counts as
unchanged for the same reason. Second, a **refresh only looks at what it scanned**: a partial
re-import of one folder says nothing about the Assets a Task holds outside it, and their absence
from the plan is not a disappearance.

**A file an earlier build imported and this one will not plan is reported missing once.** Before
v0.8.2f the file importer admitted every file, so an older Todo may hold a Task whose only Asset is
a text file or a JSON Prompt body. A refresh now skips that file, so the first run over the folder
sees a held path the plan does not name: **Keep it** flags it missing and leaves the Task, **Start
it over** clears the Task's decisions, and **Replace the Task** deletes it and mints no successor,
because the run has no material for one. Nothing is rewritten at upgrade — the policy is applied by
the next run, which is the run that has a policy to apply.

### Task lifetime

**A Task's lifetime never depends on how many Assets it has.** A folder-as-Task Task over a folder
that empties, and is later given a file, is still the same Task; deleting a Task because its count
reached zero would make that return impossible. No policy reads a Task's *size* — all three read a
change to the set.

**Nothing in Listea replaces a Task it decided about on its own.** A program that deleted a Task
because it noticed something about the Task's structure would be deriving a lifetime from structure,
which the architecture forbids. The only way a Task is replaced is a user having said *replace the
Tasks that change* for that Todo: the difference is who said it, and that is the whole of the tier's
licence.

One consequence is not an exception to the rule but the same rule applied: **asking to replace a Task
that holds a single file starts it over instead.** Replacing it would mint a Task holding that same
one file under a new id, with its decisions and its List memberships gone — loss with nothing bought.
The policy still decides *that* something happens; the Task's own shape only decides which of two
ways of honouring it is available.

## Deleting a Todo

Deleting a Todo is **a dissolution**: deleting an intention is abandoning what it meant, so the
Tasks go with it, and so do their decisions. Lists that held those Tasks keep their titles, their
order and how they export, and lose the memberships — a List is an order over Tasks, and an order
nothing satisfies is empty rather than gone. Each List that lost rows says so, and when.

What does not go is the file's own identity. A file deleted and re-imported is the same file to the
wire, still carrying the checked state Quick Review and the browser show it under. Only the
*judgments made by the Tasks that went* are abandoned.

**A deletion can leave a record behind, and you choose whether it does.** *Keep the decisions* in the
confirmation writes down what each Task was judged to be before it goes — including, for a Task
nobody had judged, the fact that nobody had. That record is called a **history**, and it belongs to
the folder the intention was made at rather than to the files.

A Todo **created** at that folder is then offered the histories left there, newest first, and may
load **one** of them. Loading is a copy rather than a resurrection: the new Todo's Tasks take on the
judgments recorded for the paths they hold, and nothing about the old Tasks comes back — so a file
the new run did not produce has nothing to receive. Paths are the whole of the match, which
means a history written by one grouping rule and loaded into another recovers nothing at all.

**The note a List leaves says which of the two took its rows.** A deletion and a *Replace the
Task* policy both shorten a List, and a List that reported the wrong cause would be describing an
operation that never happened — so the reason is stored beside the count and the time, and the
sentence is written from it: *"3 items removed when a Todo was deleted"* against *"3 items removed
when their content moved"*. Unlike the count and the time, the reason is **not** cleared when the
List moves on: nothing reads it unless a count is standing next to it.

Two things follow from that, and both are visible:

- **Loading a history does not consume it.** A later Todo at the same folder is offered it again.
  What a history says is *these were the judgments once*, and more than one dish knowing that is
  correct.
- **A refresh is never offered one.** A refresh runs over a Todo that is still here, and loading a
  history there would overwrite the decisions made since with older ones.

## Folders

Folders are browsed, not imported. The **Upstreams** tab opens an Upstream's folder and walks its
tree live, reading whatever is on disk at the moment you look.

Listea does not modify anything it browses. Nothing is moved, renamed or created. The only writes
to storage happen through three explicit actions — see [File management](file-management.md).

## Files and items

A **file** is what is on disk. An **item** is a file as it appears in a queue: a title, a path, and
whatever has been decided about it.

- **A folder is not an item by default.** Under one-Task-per-file, only files become Tasks. Under
  one-Task-per-folder a single Task stands for a whole folder, and a swipe walks the files inside
  it before moving on — see [Tasks and Todos](#tasks-and-todos).
- A List may still hold **manual items** — typed by hand, with no file behind them. They are no
  longer something you can add: a List built before v0.2 may have them, and they go on working
  exactly as they did, reviewable and editable, until their own List's lifecycle removes them. They
  behave differently in one specific way, noted under [Where state lives](#where-state-lives).

## Questions and parts

> **Since `0.8.4-preview`.** The app separates the words: the **TaskPart** is the decision unit,
> the **Asset** is a presented file. The normative decisions are
> [v0.8.4](iterations/v0.8.4.md); the words are [the two vocabularies](vocabulary.md). Older
> sections of this page use *item* only in the legacy queue sense — a file in a queue.

The question is simple: **can one Task hold more than one question?** Yes. A Task is **one sitting
over one or more TaskParts**, and a **TaskPart** is one question — one Prompt from the Package, or
one free-form judgment about a file or folder — instantiated in that Task. A Task with one part is
exactly the older Task.

The three counts this exposes are different, and the app should never substitute one for another:

| Count | What it counts | Vocabulary question | Photo | Folder of 3 | Section of 10 |
|---|---|---:|---:|---:|---:|
| **Questions / parts** | TaskParts, the decisions | 1 | 1 | 1 | 10 in one Task, or 10 Tasks |
| **Files / Assets** | presented files; the audio is a Resource | 0 | 1 | 3 | 0..N |
| **Pages** | what the navigator walks | 1 content | 1 file | 3 files | one content page per part, plus files |

So a vocabulary Task is **one question and no files**. Saying "0 Assets" is a correct file count and
a misleading answer to "how much is there to do"; the decision count is 1. A folder of three
pictures is **one question and three files** — one decision, three pieces of material, not three
decisions. A photo is one question and one file.

**A group of questions is a choice the taker makes, not a setter declaration.** A Package may tag
Prompts with a **Section**; the Section is a declared grouping, like a label, not a Task. At import
the taker chooses:

- **One Task per question** (the default): each Prompt is its own Task.
- **One Task per Section**: the Prompts tagged with that Section become TaskParts of one Task,
  answered in one sitting. Untagged Prompts stay their own Tasks.

The same Section of ten can therefore be one Task with ten parts or ten Tasks with one part. The
setter says "these belong together"; the taker decides whether they are one sitting. The manifest
never says "make a Task".

**One question may still need several answers.** A conditional questionnaire — "Did you eat? → What
did you eat? → Where?" — is one Prompt, one part, one decision. The branch's fields live inside
that part; they are not separate parts, Sections or Tasks. A later question that depends on an
earlier *Task's* answer is outside this round: the producer emits a follow-up Task.

**What stays the same.** A file or folder Task still has one part; its files are still Assets in the
same order and the same page list. The Add-on is still one per Todo, Task-level, and after the last
part. The page arrows keep their meaning: `<< >>` move between Tasks, `<>` between files; the part
is a grouping and state unit, not a third arrow. The catalog shows the two counts distinctly
(for example, *3 questions · 2 files*) instead of showing only the file count.

**Re-import keeps the Task.** Adding a question to a Section Task adds a part; removing one detaches
its part and keeps its answer and history; it does not delete the Task or rewrite what was
answered. A change from one question per Task to one Task per Section is a different composition
and makes new Tasks; the old ones are not silently merged.

The word **item** is retired for this decision unit. It survives only in older text and in the
legacy queue sense — a file as it appears in a queue — not as a name for a TaskPart.

## Lists

A **List** is a persistent queue, made from **Tasks you pick**.

Open a Todo and tap **Select**. The catalog becomes a selection surface; tick the Tasks to include —
a folder's box takes the whole folder — and tap **Create List** in the bar along the bottom. The
dialog names the List and lists exactly the Tasks it will hold. See
[Review and actions](review-and-actions.md) for the selection rules.

Selecting is reached from inside a Todo rather than from the Todo tab, because the tab's rows are
Todos and a row cannot say which of its Tasks you meant. The Tasks themselves are the only things
that can.

A List made this way has **no folder behind it**. It holds the Tasks you chose and nothing else, so
there is no disk for it to drift from and no **Source** card on its detail page — its items, its
progress and its export choice are the whole of it.

**Lists made from a folder** — the ones v0.2.x built by scanning a folder — still work exactly as
they did, and they are the only ones with a Source card. That card reports whether the folder has
changed since the List was built, and **Update from folder** reconciles the two: showing exactly what
was added and what went missing, and applying it only on confirmation. Files that vanished stay in
the List, flagged, keeping their checked state, until you clear them.

**Overlap was a rule about folder-backed Lists.** One such List's folder could not contain
another's, so creating one where an existing List already reached asked for confirmation first.
Create List now builds from Tasks you pick, and Tasks have no folder to overlap with, so that
question no longer arises.

**New list** on the Lists tab reaches the same place from the other end: it asks *"Take Tasks from
which Todo?"*, then drops you into that Todo's catalog already in selection mode. It names a Todo
rather than a folder because the Tasks have to come from somewhere, and a Todo is where they are.

Either way there is no way to make an empty List and fill it in by hand: a List is made from Tasks,
and a List with no Tasks has nothing to hold. Lists from earlier versions are kept as they are, with
their items, and are never converted, migrated or deleted.

<p align="center">
  <img src="assets/screenshots/list-detail.jpg" width="300" alt="A List's detail page, showing the Source card with its folder and freshness, the Progress card with a count and the Review button, and the item checklist below">
</p>

## Folder types

Every folder in the browser shows its relationship to a List:

| Type | Meaning |
|---|---|
| **None** | No List covers this folder. |
| **List** | This exact folder owns a List. |
| **Sublist** | An ancestor folder's List already covers this folder's files. |

A **Sublist** folder shows the progress of *its own subtree*, not the owning List's overall total,
and it shows no export state at all: the export belongs to the ancestor List, and repeating it on
every folder underneath would suggest each one could deliver something of its own. One folder, one
export, shown where it is configured.

This type is **about Lists only**. It says nothing about whether a folder's material has been turned
into Tasks, and a Todo exists whatever its Upstream's relationship to a List.

## Quick Review

**Quick Review** is a review over the Tasks **directly in one folder of a Todo**, with no List
involved. It creates nothing, replaces nothing and consults no List.

It appears once the folder you are looking at has Tasks in it, and it reviews those:

- **It never recurses.** A round covers the Tasks at this level, not the ones nested below it. What
  a run produced is a tree, and the tree is how you say which part of it you mean; a round that
  swept in everything underneath would make the folder you chose meaningless. To review a
  subfolder's Tasks, open that subfolder and start a round there.
- **It is not offered where there are no Tasks**, which includes the Todo's own top level when the
  run kept the folder tree: there, as in a browsed folder, the only things in front of you are
  folders.
- It always starts at the first unchecked item.
- It never remembers where it got to.

A Todo holds nothing but Tasks, so unlike a browsed folder there is no listing to reconcile against
the catalog: what is reviewed is exactly what the run put there.

**A Todo round has no List behind it**, which has two visible consequences. It always takes the
*Same as the Todos* path, because there is no List card to have chosen otherwise: each item leaves
by its own Todo's method, and an item with no source Todo goes by webhook — see
[Exports](exports.md#how-a-round-resolves). What a receiver sees is still one shape, with `list.id`
set to `0` and the folder's name standing in as the title. Its Tasks have no registration row of
their own, but they are not nameless: every Task and Asset is given its public id when the row is
created, so a folder round's items arrive with the same names on the first delivery as on the last.
Decisions are saved either way, and are visible everywhere else, because of
[where state lives](#where-state-lives).

Quick Review can be switched off in Settings. Off takes the action out of a Todo and stops the
Upstreams browser drawing what has already been decided. Switching it off is presentation only:
Lists keep their items, completion and actions.

## Rounds and queues

A **queue** is the ordered set of items one review walks. A **round** is one pass through a queue,
from opening the review to leaving it.

Two things decide a queue, composed as an AND:

1. **The page's filter and sort** — the subset you picked out before pressing Review, from the
   folder's *Files* header or the List's *Items* header.
2. **Review unchecked items only** — the standing setting, which only ever removes.

Asking for *Checked* on the page while the setting says unchecked-only yields nothing at all, and
that is the honest answer rather than a reason for either to win.

**The queue is fixed when the review opens.** It is a snapshot, not a live filter: an item you
check stays in front of you and stays swipeable backwards, instead of vanishing under your thumb
and renumbering everything. Leaving and re-entering starts the next round.

**The membership is fixed; the decisions are not.** Which items a round walks is a snapshot — what
has been decided *about* them is read live. Checking or tagging an item shows up on the spot: in the
round you are in, in another round open on the same Task, and on the List's row behind it. The two
were one value once: a round resolved its items on entry and never re-read them, so a tap wrote to
the database and drew nothing back.

Two consequences worth knowing:

- An item added to the List mid-round joins the *next* round, not this one.
- Narrowing changes what is *reviewed* and nothing else. Progress, completion and export
  behaviour always count the whole List or the whole folder. A hundred-item List filtered down to
  fifty and reviewed to the end reads *fifty of a hundred*, never *fifty of fifty*.

See [Review and actions](review-and-actions.md#filtering-and-sorting) for the filter and sort
options themselves.

## Checked state

**Checked** means "I have dealt with this in Listea". It is a completion flag and nothing more — it
does not say what you decided, and it does not imply any action.

A file is checked by swiping left in a review, by the checkbox in the review bar, or from a List's
item row.

## Actions

An **action** is a tag you put on an item to say what should happen to it downstream.

Actions are **buttons on a template version** — see [Templates](#templates) — and each has a name,
a type and a **record value** (what an export sends for it; the editor still labels that field
*Webhook value*, and a txt export carries it exactly as the payload does).

- **★ Favourite** is the button with the fixed id `favorite`. It is a button like any other —
  selected, stored and sent exactly as a minted one is — and only conventionally first. Whether a
  star is drawn at all is the template's business: the built-in carries it, and a template that
  does not leaves rows with no star.
- **A tag** is a button somebody configured. The built-in template arrives with two of them, `C1`
  and `C2`.
- **A file no attached template admits gets no chips at all.** An empty bar rather than a default
  one, and deliberately: a bar nobody chose is worse than a bar that is empty, because the empty
  one is legible.

The type is what the button *means* rather than what it holds — an on/off button like ★ records
that it was pressed, and a tag records that it is on and leaves the meaning to whatever reads the
export. Both store identically, which is honest: a tag is a boolean whose meaning is decided
downstream.

Actions and checked state are independent. Tapping a chip does not check an item; checking an item
does not tag it. Neither implies the other, on screen or on the wire.

An item stores the *id* of each action it carries, never the name or the record value. That is what
makes renaming a button free: future exports change, no stored item is touched, and nothing already
marked is reinterpreted. Removing a button takes it off the bar and leaves the items that carry it
alone — invisible and unsent, but not rewritten.

## Templates

A **template** is what decides which buttons a file offers. It is a thing with a name, a version and
a scope, and each of those answers a different question.

A template is a **lineage** — an id and the name a person gave it — plus its **versions**. A version
is immutable: what it governs and what buttons it offers, written down once and never revised.
Editing a template appends version *n + 1* beside the version it changes, so a Task judged under
version 1 goes on meaning version 1 after the template has moved on, and a completed review stays
interpretable by the definition it was made under. Renaming is the one edit that appends nothing,
because a name is not part of identity.

A **scope** says which files a version governs, and it is a conjunction of rules: every rule has to
admit a file, so adding a rule narrows rather than widens. The only rule shipping is the file's kind
— image, video or PDF, read off the name without opening the file — and an **empty scope admits
nothing**, so a template saved before its scope was filled in governs no file rather than every
file. A rule this build cannot read admits nothing too, for the same reason: a rule silently
skipped would make the template match *more* than it was written for.

**A Todo is attached to one version of each template it names**, and the attachment is a reference —
a template id and a version number — never a copy. That is why editing a template cannot leave the
Todos that chose it holding an old label: there is nothing to hold. A Task records the attachment
its own run was made under, so the two can disagree after an edit, and that disagreement is the
point: the Todo is what the next run will get, the Task is what this one did.

What follows from that — the buttons a version carries, the four rules that decide which of several
attached templates governs a file, where a template is edited, and what the built-in one starts with
— is [Templates](templates.md).

**An add-on is the Task-level counterpart.** Since v0.7.1 a Todo may also carry one **add-on**: a
questionnaire about the Task as a whole, answered on one page after the Task's last page — after
the Prompt body when it has no file. It has the same editing model as a template — a lineage with
immutable versions, an edit appends — but no scope, no buttons and no storage in common: its
answers live in `task_review_answers`, keyed by the Task. It is **not an Asset**: an Asset is a
file a Task presents, and a questionnaire is not a file. A Task imported without an add-on has no
extra page and behaves exactly as before. Since v0.8.2f the Todo card can attach, change or clear
it without another import; the Todo and all its Tasks are re-stamped in one transaction, and answers
the new version no longer asks are kept but hidden. The same page is shown, read-only, by every
surface that browses a Task — the Todo preview and the List item viewer as well as Review — because
a Task has one shape wherever it is looked at; a file that is not a Task has none.

The three layers are deliberately separate: the **Task** (checked, and the add-on's answers), the
**Task–Asset pair** (selections and PDF marks), and the **Asset** (the file itself). See
[Templates → Add-ons](templates.md#add-ons) and
[Templates → Three layers, three vocabularies](templates.md#three-layers-three-vocabularies).

## Where state lives

**A decision belongs to the Task, not to the file — and a mark belongs to one of its files.**
Checked is stored against the Task's own id in `task_state`, a table keyed by `taskId` and cascading
from `tasks`. ★ and every configured action are stored in `task_asset_state.selections`, keyed by the
**Asset**: one row per Task–Asset pair, holding the set of button ids that pair carries. Since
v0.7.0 a PDF passage is a **mark** in its own table, keyed by the same pair: a quote anchor plus the
comment or tag the reviewer attached. It is a Listea record, never a change to the PDF, and a mark
whose passage cannot be found is kept and reported as detached rather than deleted. A surface
that speaks about the whole Task — a List's row, a catalog card, one entry in an export — reads the
union of its pairs, which is what lets a button set on a video be nameable while a photograph is on
screen. Since v0.7.1 an add-on's answers are stored the same Task-level way, in
`task_review_answers`, keyed by `(taskId, questionId)` and cascading from `tasks`. Each row carries
the question's type and the add-on id and version that asked it, which is what lets an answer
survive an edit and be explained later — or be hidden rather than deleted when the question stops
being asked.

**Since `0.8.4-preview`:** a part response and its events are filed under a **TaskPart** inside the
Task, and a Task may hold several parts. Completion stays on the Task (`task_state`); pair state and
marks stay under the Asset. For a one-part Task — every pre-v0.8.4 Task — nothing user-visible
moved, and the sentences above remain true.

The two levels are not a complication for its own sake. A Task holding three files is one thing to
judge and three things to mark, and a mark made on the second of them has to be read back on the
second of them — while completion is one answer whichever file is in front of you. So the pair is
the judgment target for a mark and the Task is the judgment target for a tick, and neither is
derived from the other.

A List stores **membership**: which Tasks it covers, in what order. It *projects* the shared state
rather than owning a copy, and the same is true of a catalog, a folder listing and a review queue.

Between them, those two rules are what make all of these true at once:

- Quick Review needs no List, because it has somewhere to put a decision either way.
- Checking a Task in a Quick Review completes the List that covers it, and runs that List's export,
  without Quick Review knowing the List exists.
- The same Task reachable from two Lists shows the same tick in both. Nothing is copied, so nothing
  can diverge.
- The same *file* behind two Todos is two Tasks with two independent decisions — the case
  [Tasks and Todos](#tasks-and-todos) turns on. Sharing happens at the Task, not at the file.
- Deleting a List discards its membership, not its decisions.
- Deleting a **Todo** does take its Tasks' decisions, because a Task is the intention and the
  decision is what the intention meant — see [Deleting a Todo](#deleting-a-todo). That is the one
  operation here that discards state rather than projecting it, which is why it is behind a
  confirmation and why the confirmation offers to write the decisions down first.
- Nothing resets state as a side effect. Creating a List or opening a Quick Review never clears a
  tick. Re-reviewing checked files is what *Review unchecked items only* is for, and a genuine
  "start fresh" would have to be its own explicit action — *Start it over* is exactly that action,
  made explicit and scoped to one Todo.

**Two things are worth saying separately, and neither is an exception to the rule.** A **manual
item** has no file behind it, so it has no Asset — and the pair table is keyed by one, which is why
a manual item carries no marks and draws no chip band. It is checked like any other Task, because
completion lives on the Task. The Tasks that were manual entries are named in `legacy_manual_tasks`
so that a cleanup can delete them outright when their List's lifecycle removes them, which is the
one thing no other Task may be. And a **folder listing** — the Upstreams browser — is the surface
where the rule is easiest to misread. `file_review_state` still holds one row per file reviewed from
a folder, keyed by the grant and the path, but it is **registration only**: its decision columns are
dead, and the tick the browser draws is projected from `task_state` through the assets join, so a
file reads as checked exactly when a Task over it says so. That is why the browser can show a file
as checked when no List has ever heard of it — and why a file no Task covers is registered and drawn
with an empty tick rather than not drawn at all.

A file's identity to the rest of the app is **the Upstream it is under plus its path beneath it** —
which is what a folder listing, a scan and a re-import all match on. It is not what a decision hangs
off. Replacing a file with a different one of the same name hands the *Task* the new file and keeps
the Task's tick, because the Task is the judgment target and it did not change.

## Item identity

Every **Task** and every **Asset** carries its own 24-character **public id**, assigned when the row
is created and never changed afterwards. Both go onto the wire — `tasks[].id` and `assets[].id` — and
both are safe to use as primary keys on the receiving side.

```
0AT9K3QWMB-4H7ZP2-XC5N0V
└────┬───┘ └──┬─┘ └──┬─┘
   time     file    random
```

- **time** — milliseconds since the epoch, base32, fixed width. Sorting the ids **as strings**
  sorts them by when the row arrived; no parsing needed.
- **file** — a fingerprint of what the row is about: a Task's identity, or an Asset's Task and path
  under its Upstream. Deterministic, so two arrivals of the same material share this field. A hint
  for grouping, not a guarantee.
- **random** — 30 bits, and what actually makes the id unique.

The alphabet is Crockford base32 — no `I`, `L`, `O` or `U` — so an id copied out of a log by hand
cannot be misread.

**The two ids name different things.** A Task's id names one judgment; an Asset's id names one file
*as presented under that Task*. The same file under two Tasks — `cos/x.jpg` imported once
file-as-Task and once inside a folder-as-Task — is two Assets with two ids, because those are two
judgments and a receiver must be able to keep them apart. They are recognisable as the same material
by their matching fingerprint, or by `relativePath` + `title`.

**An id names one *arrival*, not the file itself.** The same file entering Listea again — a new List
over the same folder, a re-sync that re-adds it after it went missing — gets a **new** id, so a
receiver can keep both records without either overwriting the other.

**An upgrade gives existing rows ids without inventing new arrivals.** A Task took the id the old
folder-round path had already minted for it where one existed. A file took its old registration id,
because that was the name the v0.1 wire sent. A file whose Task held exactly one Asset and whose
List item carried an id — the old shape in which the List item *was* the file — inherited that id. A
multi-Asset Task's List id was **not** copied onto one of its files: that id named the Task's
presentation, not any particular file. Everything else was minted at the row's own creation time.

---

Next: [Review and actions](review-and-actions.md) for what happens inside a queue, or
[Exports](exports.md) for how the decisions leave the device — and [Webhooks](webhooks.md) for the
webhook method's own body and failures.
