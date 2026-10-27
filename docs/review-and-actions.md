# Review and actions

The Review screen: every gesture, control and behaviour, and the actions that go on an item.

- [The screen](#the-screen)
- [Gestures](#gestures)
- [The frame: what belongs to the item](#the-frame-what-belongs-to-the-item)
- [The file's controls](#the-files-controls)
- [PDFs](#pdfs)
- [Buttons](#buttons)
- [The Info sheet](#the-info-sheet)
- [Zoom](#zoom)
- [Video](#video)
- [The viewing surfaces](#the-viewing-surfaces)
  - [The Todo preview](#the-todo-preview)
- [Filtering and sorting](#filtering-and-sorting)
- [Rounds](#rounds)
- [Resuming](#resuming)
- [Finishing a queue](#finishing-a-queue)

## The screen

The screen is a frame with the file inside it. Along the top sits the navigator, along the bottom
sits the completion checkbox, and **both are always there** — they belong to the item and to the
queue, and nothing about looking at the file takes them away. What the file itself needs — a
video's transport, the tag chips, Info — is drawn *over* the media and comes and goes with a tap.

<p align="center">
  <img src="assets/screenshots/review-media.jpg" width="300" alt="The Review screen: a video inside the frame, with the item name, filename and queue position along the top and the completion checkbox below it, and the favourite, custom action chips and Info button laid over the bottom of the video">
</p>

Images (JPEG/PNG/WebP), animated GIFs and video all render in place, and since v0.7.0 a PDF does
too — see [PDFs](#pdfs). The frame takes its own room rather than floating over the picture, which
is what makes a control that is drawn over the media mean something different from one that is not.

Full Review and Quick Review are the **same screen**. They differ in where the queue came from and
in a handful of strings, not in what any control does.

## Gestures

| Gesture | What it does |
|---|---|
| **Swipe left** | Go forward one page; off the last page, mark the item checked and advance |
| **Swipe right** | Go back one page; off the first, go back one item. Changes nothing |
| **Pinch** | Zoom, up to 5× — a picture, or a PDF at the point between the fingers |
| **Drag while zoomed** | Pan inside the picture |
| **Drag a PDF** | Scroll its pages vertically; with a selection, drag a handle to extend it |
| **Long-press a PDF** | Start selecting text, then drag |
| **Tap the media** | Show or hide the file's own controls |
| **Back** | Leave the review |

An item holds one file most of the time, and then a swipe is exactly what it has always been: left
checks it off and advances, right steps back. An item holding several — a folder imported as a
Task, or a Task over several questions — is one item and several pages, and a swipe looks at all of
them before it reaches the next item. A Prompt Task's pages start with the Prompt and end with its
add-on, when it has one; the swipe that leaves the add-on page is the one that completes the Task.
For a Task with several questions the swipe turns pages through the parts in order, and committing
a question is the control's act, not the swipe's: see [Tasks with several
parts](#tasks-with-several-parts).

**A page this build cannot answer has nothing to commit.** A Prompt whose body cannot be read,
whose required file is missing, whose Task is blocked, or that no Task Review in force can catch a
choice for draws no judgement control. It is an ordinary page in the run: a horizontal swipe turns
it, and at the Task's edges crosses Tasks as navigation. The add-on page's forward edge follows the
same rule — when the Task cannot be judged, leaving the add-on is a queue move rather than a second
attempt to commit, so a Task is never trapped by a question that cannot be answered. Tapping such a
page, like any other, shows and hides the file's own band.

Both ways of moving exist, and they are deliberately different controls. Swiping turns a **page**:
it moves between the files inside the item, and only leaves the item at either end of them. The
navigator's arrows move the **queue** and skip the files entirely. A swipe that only turned a page
checks nothing.

**Leaving an item is where the judgment is.** An item with several files is checked once, not once
per file.

Swiping right is a navigation, not an undo: it never unchecks anything. To uncheck an item, swipe
back to it and use the checkbox.

## The frame: what belongs to the item

The top band is the navigator, and it says two things at once — which item you are on, and which of
its files is showing.

```
whatsapp                    xxxx.mp4              << ‹ 3(3/3)/3 › >>
```

| Zone | What it is |
|---|---|
| **Left** | Back, then the item's name. The tags it carries are drawn on a line of their own across the bar, under this row. |
| **Middle** | The file on screen. Absent when the item holds only one file, and on a bar too narrow to hold both names beside the arrows and the position. |
| **Right** | The queue position — itself the control that opens the queue guide — bracketed by its arrows. |

The bar carries a second line when the item has tags: one badge per action set on it — ★, and each
tag by its display name. It is the same row a List's item shows under its title, drawn by the same
code from the same template's buttons, so an item reads the same inside a round as it does on the
page it was queued from. It carries no tap of its own: the chips over the media are where a tag is
set, and this is the line it is read back on.

The tags are drawn across the whole bar rather than inside the item's name column. A chip is a label
and cannot be ellipsized without becoming meaningless, so it must not compete with the names for the
same width; the row is measured by the bar and gets its own line. The file's name is the row's first
casualty of the same pressure: on a bar narrower than 400dp — an ordinary phone held in portrait,
where four arrows and a two-level position have already claimed most of the width — it is not drawn
at all, and the item's name takes the leftover width instead of being capped and pushing the queue's
last arrow off the screen. The file is still named in the Info sheet and in the queue guide.

It sits on the frame rather than with those chips because it says something about the *item* and not
about the file. The chips set the record of the file on screen; this line reports the item's whole
answer, which is the fold of every file it presents — so a tag set on its video is legible while its
photograph is on screen, and a tap on the media, which puts the file's own controls away, must not
take that answer with it.

The position reads `3(3/3)/3` on an item with several files: you are on item 3 of 3, showing file 3
of its 3. On an item holding one file — still the overwhelming majority — there is no file level to
speak of, so it is simply `3 / 3` and only the queue's pair is drawn. On a Task of several
questions the middle slot counts **pages across the parts** — `1(2/6)/1` is the second page of the
only Task — because the page list is one list whether the pages came from one part or six. The part
boundary has no number and no arrow of its own; the queue guide is where a part is named. That pair
is **always** `<<` and `>>`, even where it is the only pair: the glyph names the level, not how many
levels there happen to be below it.

Four arrows are drawn on a multi-file item, and the outer and inner pairs do different things:

| Arrow | Moves |
|---|---|
| `<<` `>>` | The queue: previous and next item, whatever file you are on |
| `‹` `›` | The file: previous and next page inside the current item |

A level with nothing to move between is not a level. That is why the inner pair and the filename
only appear once an item holds more than one file, and why the outside pair is drawn greyed at the
ends of a queue rather than removed — a control that vanishes re-measures the bar under your finger.

**The position is also the way through the queue.** The label itself is the control: no chevron,
because the one number on the bar that already means *where you are in a sequence* does not need a
marker to say it can be touched. Tapping it opens a guide under the control, right-aligned to it,
which is two levels deep when the queue is a queue of items.

The first level names what the position's outer number counts: every item in the queue — the Task's
name with its Package title or path underneath, or a file's name in the folder viewer. A Task
imported from a Package carries the Package's manifest title (*Dating demo*), the same name the
Catalog shows; it never shows the synthetic `<packageKey>/<promptKey>` catalog key under which the
Task is addressed internally. A Task whose title and provenance would read the same carries the
second line not at all. The row you are on is tinted
and marked with a check. Tapping a row goes to that item's first file or page, exactly as the outer
arrows do. A row that holds more than one page carries a right arrow of its own; tapping that arrow
opens the second level, that item's pages, under a header naming the item. Tapping a page jumps to
it and closes the guide, exactly as the inner arrows would walk there; tapping the header steps back
out to the item list without closing the guide. Tapping outside the guide or pressing Back closes
it and moves nothing.

The second level is the list the inner arrows already walk. In a review its last row is the add-on
page, labelled `add-on <name>`: an add-on is a page of the Task rather than a file the source holds,
and a row reading only its name would be taken for one. A Task holding one page is a leaf — its row
still goes to that page, but it draws no arrow, because a list of one would be a level that is not a
level.

When the queue holds a single item, the guide starts at that item's page level, because that is what
the position counts and there is no item level to open. A surface with nothing to move between at
either level — one item, one page — keeps the plain label, which is not a control.

The bar has a floor under it, because "the arrows keep their full size" and "the item's name stays
readable" cannot both hold on every screen. The controls, the item's name when both names are
present and the position need more width together than a narrow phone at a large font scale has,
and the name is what is left over. When the bar is narrower than 365dp **every** control in it comes down to
40dp at once, and the name is measured against that. 40dp is under the 48dp touch guideline and is
a deliberate trade rather than an oversight: the name is what tells you which item you are on, and
40dp is still a target a thumb finds. Shrinking is all-or-nothing so that no control changes size
under a finger that is already moving.

The bottom band is the Task's own state, and every template has one. When the review commits from
the completion checkbox — no template at all, or a template whose named commit control is the
checkbox — that checkbox is the band. When a template names a control of its own, the template's
control lives in the file's dismissible band and this bottom band states the result instead: the
same checkbox, read-only, with the state in the fill and no label beside it. A checkbox drawn here
would complete the Task without recording what the template is waiting for; leaving the band empty
would leave a headless Prompt template with no task-level display at all. Read-only is not
disabled — it is the honest form of a state the template's own control owns, and it stays put when
a tap puts the file's controls away.

The band speaks one visual language on every Task: empty means not checked, filled means checked.
Words are not added for the read-only form. v0.8.2f first wrote **Checked** / **Not checked** beside
that checkbox, and in the same round a vocabulary Task and a picture Task ended up stating the same
Task-level fact two different ways. The state is still named for a screen reader through the
checkbox's description; that is access, not a second rendering.

## Tasks with several parts

A Task may be one sitting over several questions. Since `0.8.4-preview` its pages are ordered part
by part: each present part contributes its Content page when it has a Prompt body, followed by its
own files; the Add-on, when there is one, comes after the last part. The inner arrows and a swipe
walk that one list, so they cross from a part's last page into the next part's first without a
separate level. A Task with one part — every file, folder and pre-v0.8.4 Package Task — has exactly
the page list it always had.

**A commit answers a part.** With a template that commits per part, the commit advances to the
**next part's first page**, not through the rest of the committed part's files; after the last part
it lands on the Add-on, and from there the Task completes or the queue advances as it always did. A
failed commit writes nothing and does not advance.

The checkbox still belongs to the Task, and what fills it depends on the Task Review in force:

| Completion policy | The Task completes when… |
|---|---|
| **single part** | the template's commit says the response completed the Task — the rule every pre-v0.8.4 template keeps |
| **all required parts** | every required part is answered and no required part is unavailable |
| **any part** | the first required part is answered |
| **explicit only** | a person checks the box; answers never complete it |

A detached part — one the Package's latest revision no longer names — does not hold the Task open.
A required part this build cannot present still does: not being able to draw a question is not the
Package withdrawing it. An explicit check is a person's fact and survives later part answers; an
automatic completion is recomputed when a commit, a reset or a re-import changes the parts. Checking
the box while required parts are open asks first and names how many are pending.

## The file's controls

Laid over the bottom of the media, in order:

- **★ Favourite** — the built-in button, drawn when the template governing this file carries it.
- **Your buttons** — whatever that template version offers, in that version's own order.
- **Info** — opens the [Info sheet](#the-info-sheet).

A file no attached template admits gets **no chips at all** rather than a default bar — see
[Templates](core-concepts.md#templates) for why. A video adds its own transport above them:
play/pause, elapsed time, a progress bar and mute. That transport is lifted clear of the chips
rather than stacked on top of them, so both stay usable.

**Info belongs to the page, not to the Task.** On a Prompt's Content page — including every
headless Prompt template, which supplies no chips of its own — the band is the Info icon alone. On
a file it is that file template's chips plus Info. On the add-on page it is Info alone, and that
sheet describes the questionnaire, not the file shown a page earlier. The band reports the level
you are on; it never folds another level's record into it.

Tapping the media puts this whole layer away and brings it back. The navigator and the bottom band
do not go with it — they are the item's, and a round cannot be finished by a tap that hid the
control that finishes it.

Checking and tagging are independent. Tapping ★ does not check an item; checking an item does not
tag it. Nothing on this layer implies anything about the checkbox.

## PDFs

A PDF is **read** rather than glanced at, so its viewer differs from a picture's in two ways — and
neither changes what the item means.

**Reading is browsing.** Pages scroll vertically, pinch zooms in on a passage, and a horizontal swipe
at the page edge still means previous / next file. Scrolling, zooming and selecting produce no
record and check nothing.

**Reviewing a passage is a mark.** Long-press starts a selection and dragging extends it; handles
at each end can be nudged afterwards, and the selection snaps to word boundaries. The menu always
offers **Copy**. When the template governing this PDF is scoped to PDFs, its **Tag** and **Comment**
buttons join that menu in template order — choosing one creates one mark on that passage. Applying a
second tool to the same passage is a second selection, not a second line in one menu.

**The file-level chip row is hidden on a PDF**, because a passage tool applied to the whole document
would not describe anything. For the same reason an **On / off** button is inert there: it has no
passage form. The template editor still offers the type, so re-scoping a template never changes what
an existing button means.

**A PDF with no PDF template is still fully readable.** Copy works; nothing else is offered — no
marks are drawn, no tools appear and no passages reach an export.

Stored marks are drawn over their text as highlights. Tapping one opens its sheet, where a comment
can be read and edited and a mark deleted; tapping a spot with several marks lists **every** mark
under that point rather than guessing which was meant. A mark whose passage can no longer be found
reads *Not in this document*: it is kept with its stored quotation, not drawn, and not exported.
None of this writes to the PDF — see
[File management → What Listea does not do](file-management.md#what-listea-does-not-do).

Full model: [Templates → PDFs and passage tools](templates.md#pdfs-and-passage-tools); what a mark
sends: [Exports → PDF passages](exports.md#pdf-passages).

## Buttons

The action chips are the answer to "review is not a yes/no decision". Checking something off says
you dealt with it. A chip says **what dealing with it meant** — and what should happen to it next.

Every chip is a **button** on a template version.

<p align="center">
  <img src="assets/screenshots/settings-custom-actions.jpg" width="300" alt="The Settings page as it drew custom actions before v0.5.4: a Review card holding the Quick Review switch and three custom actions, each with a display name field and a webhook value field, and an Add custom action button">
</p>

They are **buttons on a template version**, and that page is where they are edited now: **Settings →
Templates → <template>**, in its *Buttons* section. Each button has three fields:

| Field | What it is |
|---|---|
| **Display name** | What you see on the chip. Keep it short — the bar is narrow. |
| **Webhook value** | The string an export sends for this button. A txt export carries it exactly as the payload does. |
| **Records** | *On / off*, *Tag* or *Comment*. An on/off button means itself, like ★; a tag's meaning is decided by whatever reads the export; a comment carries text the reviewer writes when applying it to a PDF passage. |

The first two types store identically — a tag is a boolean whose meaning is downstream — so the field
describes what a button *means* rather than what it holds. **Comment** (v0.7.0) is the one that
stores the reviewer's own words, and it exists only as a passage tool: on a picture or a video there
is no passage to write against, so it is not offered there.

Nothing here decides what a chip means. Whether one means *archive*, *upload*, *delete later* or
*ask me again tomorrow* is decided entirely by whatever receives the [export](exports.md).

There can be none, or many: a button can be added, renamed, retyped and removed, and a template with
no buttons draws an empty bar. The built-in template arrives with ★ and two tags.

**A Todo inherits the buttons of the template version it is attached to.** Which of them a given
file offers is resolved per file — the first attached template whose scope admits the file's kind is
the one whose buttons it gets — so one Todo can govern two kinds of file through two templates. See
[Actions](core-concepts.md#actions) for the bar this draws and what a file nothing admits looks
like.

Three properties worth knowing, all of which follow from an item storing the action's **id** and
nothing else:

- **Renaming is free.** Changing a display name or a record value changes future exports and touches
  no stored item. Record values are resolved at delivery time, not at the moment you tapped the
  chip.
- **Removing a button takes it off the bar only.** Items already marked with it keep the mark —
  invisible and unsent, but not rewritten. Listea does not quietly revise decisions you made.
- **Order is the template's.** The bar, the Info sheet and the payload all read the buttons in the
  order the version lists them, and that order changes only by editing the template. ★ first is a
  convention of the built-in rather than a rule of the model: a template may put it anywhere, or
  drop it.

## The Info sheet

What the media screen deliberately stopped saying, scoped to the level it was opened from. It
scrolls, which is what keeps it usable in landscape.

<p align="center">
  <img src="assets/screenshots/review-item-info.jpg" width="300" alt="The Info sheet open over a reviewed file, listing its path in the source folder, owning List, status, actions, type, size and modified time, with Save and Share buttons at the bottom">
</p>

**The sheet describes the page whose Info icon opened it.** A Prompt's Content page is a Task-level
page with no file; a file page is an asset; the add-on page is the questionnaire. The three
inventories are deliberately disjoint: the Info on a picture never lists the Task's add-on answers,
and the Info on the add-on never lists the picture's type, size or actions. That is the asset /
Task / add-on hierarchy kept visible in the sheet as well as on the screen.

| Row | Level | Notes |
|---|---|---|
| **Title** | all | The file's filename, the Task's name on a Content page, or the add-on's name. Wrapped rather than cut. |
| **Package** | Content, Asset | Shown instead of *Path in source folder* when the Task came from a Package: the Package's manifest title (*Dating demo*), never the synthetic `<packageKey>/<promptKey>` catalog key under which the Task is addressed internally. |
| **Path in source folder** | Content, Asset | Where a file Task sits, relative to the queue's folder. |
| **List** | Content, Asset | The owning List. Absent in a Quick Review, which came from no List — saying otherwise would be a claim about where the decision went. |
| **Status** | Content, Asset | *Checked* or *Not checked*. |
| **Actions** | Asset | The actions on this file, or *None*. Not drawn on a PDF, whose tools are passage-scoped. |
| **Recorded** | Content, Asset | The events the template caught on the latest submission: *None*, or *N event(s)* and then each point with its colour, position and the file it was taken on. Shown only when the template accepts events. History, not an editable value — a later submission adds its own record. |
| **Source** | Asset | Only shown when there is something to say: *Manual item — no file*, or *Missing from the source folder*. |
| **Type, Size, Modified** | Asset | Read from the storage provider when the sheet opens, so the sheet appears immediately and these rows fill in. |
| **Add-on** | Add-on | The questionnaire's name, version and answer count, then one row per question with the answer on record or *Not answered*. No other level lists it. |

At the bottom of an **asset** sheet, two controls that act on the **file** rather than on the item:

- **Save to Listea** — copies what you are looking at into a `DCIM/Listea` album.
- **Share** — hands the file to another app through Android's own chooser.

Neither is offered on a Content page, which has no file, or on the add-on page, which is not one.
Neither is a tag, an action or a check. Neither is stored on the item, and no export hears about
either. Full details, including the permission and caching behaviour, are in
[File management](file-management.md#save-to-listea).

## Zoom

A pinch, on every viewing surface and on everything they show — stills, GIFs and video alike.

It goes to **5×** and no further, which is where a photograph stops being detail and starts being
the decoder's guesswork. The picture zooms about the point between your fingers, so whatever you
were looking at stays under them.

**Zooming and paging are not two modes**, and there is nothing to switch between them. Every drag
is offered to the picture as panning first, and only what the picture has no room for is left to
move the card. At fitted size a swipe pages exactly as it always did; zoomed in, the card does not
begin to move until you have panned to the picture's edge, and carrying on from there turns the
page. A pinch never turns a page, however far it wanders. Vertically there is nowhere to page to,
so a zoomed picture simply stops at its top and bottom.

The zoom belongs to the file you are looking at and is dropped when you leave it: every item
arrives at its own fitted size, however closely you were looking at the last one. Rotating the
phone keeps the magnification and pulls the picture back into view rather than leaving half of it
stranded off screen.

On video the transform reaches the picture and stops there — the transport controls sit in the same
box and would otherwise be dragged off screen with it, and a press the player has already taken
never becomes a pan.

## Video

Video plays through Media3 (ExoPlayer), in place, with Listea's own transport rather than Media3's
`PlayerControlView`.

**The transport** is one strip along the bottom of the media, sitting directly above the tag chips:
play/pause, the position and length, a scrubber, playback speed, and mute. Nothing else. It comes
up and goes away with the file's other controls on a tap.

Tapping the strip anywhere that is not a control does nothing, rather than counting as the tap on
the media that puts the chrome away — aiming at pause and missing by a few pixels should not be
read as asking for the controls to leave.

**Speed** is a menu behind its own label: 0.5×, 0.75×, 1×, 1.25×, 1.5×, 2×. A menu rather than a
button that cycles, so 2× is not four taps away from 0.75×. It belongs to the player, so it stays
put across a swipe and resets when you leave the screen.

**Mute belongs to the file, not the player.** Unmute one video and the next one still arrives muted,
because *Start videos muted* is a rule about arriving at a video rather than a mode you have to keep
switching off — so it stays true for every file except the one you decided about. Turn the setting
off and every video simply arrives audible. How loud that is remains the phone's volume keys'
business; Listea has no second volume of its own to get out of step with them.

**Autoplay** and **start muted** are both settings — see [Settings](settings.md#media-and-review-behaviour).

<details>
<summary>Why Listea has its own transport</summary>

Media3's control view dims **every pixel of the surface it is given** and lays its buttons down the
middle of it, so reaching the pause button meant blacking out the thing you were watching — a video
you could control or a video you could see, but not both. It also silently drops its transport row,
scrubber and settings button whenever it is laid out shorter than 192dp, which is roughly what a
landscape phone has left once two bars have taken their share, so half the controls came and went
with the orientation.

A strip of Listea's own is as tall as it draws, dims only itself, and is the same strip in both
orientations.

</details>

## The viewing surfaces

The same renderer and the same gestures, with different amounts of authority:

| Surface | Reached from | Can change state | Chrome |
|---|---|---|---|
| **Review** | A List's Review button | Yes — checkbox and tag chips | Whole frame |
| **Quick Review** | A Todo's Quick Review action, at a folder holding Tasks | Yes — checkbox and tag chips | Whole frame |
| **File Viewer** | Tapping a file in an Upstream's browser | No | Whole frame |
| **List item viewer** | Tapping an item on a List's page | No | Whole frame |
| **Todo preview** | Tapping a Task while browsing a Todo | No | Navigator and steps |

The three readers are plain galleries: they page between siblings and change nothing. That they look
near-identical to each other is the point — none is a place where decisions get made, so none grows
a control that suggests otherwise. Save and Share are offered on the first four; the Info sheet on
all but the Todo preview, which is the one that drops even that.

**The List item viewer follows the frame split.** Its Info icon sits in the dismissible file band,
and beneath it the frame draws the Task's state read-only — the same bare checkbox Review shows —
so a tap hides the file's controls and never the Task's state. Before
v0.8.2f the Info sat in a Task-level bar and no task band was drawn at all. The Todo preview
deliberately keeps neither: it shows the navigator and the steps and nothing that could be mistaken
for a decision.

**A Task has the same pages wherever it is shown.** The two Task readers — the List item viewer and
the Todo preview — derive their count and their pages from the same model Review walks: the Prompt
body when there is one, then its files, then the Task's add-on. That last page is drawn
**read-only** there — the attached questionnaire's questions with the recorded answer, or
*Not answered* — because these surfaces have no write they could make, and a
questionnaire you could answer from a gallery would be one. Before v0.8.2f the List item viewer and
the Todo preview modelled a Task as *Prompt plus files* only, so the same Task had one shape in a
round and another in a preview, and an add-on could not be reached from a look at all. A separate
file opened from a folder is not a Task: the File Viewer keeps its flat file-to-file pager and grows
no add-on page.

**A swipe turns a page inside the Task, and crosses at its edges.** A Prompt's Content page is a
vertically scrolling card rather than a picture, so it never went through the media swipe; on the
List item viewer and the Todo preview that left it feeling locked. Both now take a **horizontal
swipe as a page turn**: from the Content page to the first file, between files, and from the last
file onto the add-on; at the Task's last page, forward walks to the next Task in the level, exactly
as the arrows do. At the near edge, swiping right off the first file returns to the Prompt's Content
page, where the card began. Vertical drags still scroll the card. Nothing is decided by any of it;
these are the surfaces with nothing they could decide.

In Review the same card takes no swipe **while it can be judged**. There the judgment is the tap on
an option — or a template's own Submit — and a forward swipe already means *advance one page,
complete at the end* on the Task's file pages. The Prompt page leaves commitment to the control the
Viewer drew, rather than letting a page turn become an answer. A Prompt page this build cannot
judge — unreadable body, missing required file, blocked Task, or no Task Review that can catch its
choice — has no such control and no answer to give, so it joins the ordinary run and takes the same
swipe; its tap shows and hides the file band. It is the one Review page whose swipe never commits
anything.

**A vocabulary card is the same page, drawn by its own Viewer.** It has a stem, a word, optionally
a phonetic line, and its options as full-width rows in **the order the Package declared**. The rows
are not sorted or shuffled, and the correct answer is not moved to a fixed position. A **Listen**
control plays the `audio` Resource through the screen's existing player; it stops and clears when
the Content page leaves the screen, so a word cannot play over a video page or into the next Task. A
missing *required* pronunciation is the same refusal as any missing required content: the options
are inert and the page takes the ordinary swipe. A missing *optional* one only disables Listen and
leaves the card answerable. Once an answer is on record — or on the read-only List item viewer and
Todo preview, where a look is not an attempt — the card also shows the meaning, the example, and
the declared correct answer. The recorded judgment is still one `choice.select` with the option id
and label; the definition is content, not an answer.

**An accepted judgment lands on the add-on page when the Task has one** (v0.8.2f). Before that, an
option tap under an advancing template moved the queue to the next item, which passed over the
questionnaire the Task was stamped with; a Prompt Todo's add-on could go unasked for the whole
round. The judgment itself is unchanged and is still written by the tap; only the arrival changed.
Forward from the add-on page is then a queue move and does not judge the Task again, and a template
that keeps the reviewer on its own page still does. See [Templates → Add-ons](templates.md#add-ons).
Reaching the add-on with the page arrow instead of a judgment leaves that forward edge inert, even
when an answer from an earlier round is on record: the queue arrows still cross Tasks, but the
swipe waits for a judgment — and on the last Task there is no queue arrow left, so the page has to
be judged (or judged again) before the swipe will finish the round. A Prompt this build cannot
judge is the exception above; its add-on edge always navigates.

**Every row in the table has the queue guide**, including the Todo preview: the tappable position
and its two levels are part of the navigator, which is the one band every surface keeps. Where the
queue holds a single item, the guide starts at that item's pages instead, so the position is never
a label that does nothing; where an item has an add-on page, that page is in its directory beside
its files. Jumping is a look at another item or page, never a judgment about it — on the readers it
changes nothing but which file is on screen, and in a round it takes the same route as the arrows,
so List Review records the position it would have recorded. Jumping between *pages* is not a queue
move: it writes no position, exactly as the inner arrows do not.

### The Todo preview

The odd one out, and deliberately so. It is reached by tapping a Task while browsing a Todo, and it
keeps only the navigator: no tag chips, no completion box, no Info bar — not
disabled, absent. What is left is the one band that belongs to the *Task* rather than to the file,
which is the boundary the frame already draws; everything dropped is the file's own chrome.

This is the one gallery with no Save and no Share either. Not because they would be wrong, but
because the surface exists to answer "what is this?" before it exists to do anything with it, and
every control it grows is a way for browsing to turn into acting by accident. A user who wants to
act on the material has Quick Review at that folder and the Lists built from it.

It has two levels of steps, the same two the List's item viewer has. Steps *within* a Task are kept,
because a Task standing for a folder is several files and paging is the only way to see the rest of
it; a Prompt Task adds its Content page at the front and its add-on page at the end. Steps *between*
Tasks are kept too, and for a reason that does not soften the one above: the preview is opened from
a level and the level is a sequence, so walking that sequence is still answering "what is this?"
about the thing the user was already looking at. `<<` and `>>` walk the Tasks of that level in
catalog order, and swiping off either end of a Task's pages moves to the next Task rather than
stopping — the gesture and the arrows are one behaviour. At the ends of the *level* it stops,
because there is no next Task to reach and nothing to complete. The pair is the queue's on this
surface even when a Task has a single file, because that is what it moves.

This reverses an earlier decision that steps between Tasks were absent. The reasoning then was that
the catalog is a better way to choose the next thing to look at than a sideways step buried inside a
file; what it missed is that the catalog is *behind* the preview, and going back to it to move one
row down costs a screen and a tap to answer a question the arrows answer directly. What was right in
it is kept: a Task is reached this way only if it was already in the level, so the sequence can never
lead anywhere the user could not have tapped, and nothing about the destination changes because of
how it was reached. Stepping sideways is a look at the next one, not a move on it.

The Task is looked up by id in the Todo's catalog rather than carried in as a row, so a preview
open across a refresh shows the Task as the database now has it, and a Task that has gone closes
the screen instead of leaving a file that is no longer in the catalog on display. What the lookup
hands the screen is the whole level, in the catalog's own order, rather than the one row that was
tapped — which is what lets a refresh change the sequence the arrows follow rather than leaving them
walking a list captured at the tap.

## Filtering and sorting

Set from a folder's **Files** header or a List's **Items** header, with a filter icon and a sort
icon. The header says when either is on.

**Filter** — three groups, ANDed together. Each defaults to *All*.

| Group | Options |
|---|---|
| **Media type** | Pictures, Videos, Text, PDFs |
| **Checked status** | Checked, Unchecked |
| **Freshness** | Today, 1d – 3d, 3d – 7d, 7d – 2w, 2w – 1m |

**PDFs** became a kind of its own in v0.7.0 rather than staying in *Text*, because the viewer, the
catalog's viewable badge and this filter now classify a `.pdf` through one function: a file that can
be opened should be nameable where files are narrowed.

**Sort** — Default order, Name (A–Z), Name (Z–A), Newest first, Oldest first, Largest first,
Smallest first, File type, Unchecked first.

*Default order* is not a sort: it leaves the listing as it arrived — a folder's own name order, or
a List's stored order — which is what stops a hand-built List from being re-alphabetised out from
under you. Every other order falls back to name as its tie-breaker, so nothing swaps places between
one listing and the next for no reason.

Two rules hold throughout:

- **An unknown fact never excludes anything.** A file with no timestamp survives every freshness
  filter, and a file whose checked state is not tracked survives every checked filter. Sorting
  takes the opposite line: unknowns sink to the bottom in both directions.
- **Freshness is measured in whole local calendar days**, so "Today" means today whether it is nine
  in the morning or eleven at night. Nothing covers files older than a month — anything further
  back is reached by asking for *All*.

**The arrangement carries into the review.** Pressing Review queues exactly the subset the page was
showing, in the order it was showing it. That is the main thing filtering is for: pick out the
videos, review only those.

By default one filter and one sort are **shared by every folder and every List**. Switch *Same
filter and sort everywhere* off in Settings and each keeps its own. Switching it discards nothing —
both the shared arrangement and the per-listing ones are kept either way, so turning it on to sweep
through several folders and turning it back off returns each to what it had. Up to 200 per-folder
arrangements are remembered; past that the least recently set is dropped.

Filtering and sorting change **what is shown and what a review walks**, and nothing else. Progress,
completion and exports always count the whole folder or the whole List — see
[Rounds and queues](core-concepts.md#rounds-and-queues).

## Rounds

With **Review unchecked items only** on, opening either review queues only the items that were
unchecked at that moment, and keeps that queue for the round: an item you check stays in front of
you and stays swipeable backwards. Leaving and re-entering starts the next round.

Pair it with **Export checked items only** and each round's export carries exactly the decisions you
just made, however many rounds it takes to get through a folder.

When a queue comes out empty, the screen says which of the three reasons emptied it — the List has
no items, nothing matches the filter, or everything left is already checked — because only one of
those is fixed by changing the filter and you cannot tell them apart from an empty screen.

## Resuming

**Full Review** remembers where it got to, when *Remember review position* is on (it is by
default). Position is tracked by item id and stored on the List, so a re-sync, a cleanup or a
deletion cannot leave it pointing at the wrong item.

The setting decides what is **read** on entry, never what is written: the position keeps being
recorded either way, so switching resuming back on picks up where Review actually got to rather
than from wherever it was when you switched it off.

**Quick Review never resumes.** It is Todo-scoped and temporary, and always starts at the first
unchecked item.

## Finishing a queue

Running out of items shows a completion screen naming what you finished and the progress of the
whole List or folder — never the round's own arithmetic. A filtered round that finishes has not
finished the List, and the page it returns to will still say so.

From there you can review again — which starts a new round — or leave.

If anything is configured to export, this is where it happens. The round is reported the moment the
queue is done rather than when you leave the completion screen, so an export that has already left
the device cannot be lost to the app being killed while that screen sits there.

Finishing can produce more than one export. A round whose List leaves each Todo to answer for its
own Tasks is split — one payload per source Todo, each by that Todo's own method — so one swipe can
post a webhook and write a text file. Only the webhook asks first: a txt export writes and then
reports, because asking every round would train you to tap through the one dialog that matters. The
notice names what happened — *Webhook sent*, *Txt written*, *Txt not written*, *Nothing exported* —
and every payload the round builds is kept in the history, sent or not. See
[Exports](exports.md#how-a-round-resolves) for how a round resolves and [Webhooks](webhooks.md) for
the webhook body and its failures.

---

Next: [Exports](exports.md) for what happens to the decisions, or [Settings](settings.md) for the
switches mentioned above.
