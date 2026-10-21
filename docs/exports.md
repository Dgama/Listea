# Exports

How decisions made in Listea leave the device.

An **export** is the act. An **export method** is how it happens. Before v0.5.6 the app had one way
out and named everything after its mechanism — a List's switch, a payload filter, a settings page, a
history table all said *webhook* — and every one of those names was answering a question about
*when* or *what* with a word about *how*. This page is the correction: the questions are separate,
and each of them is now asked in one place.

The code and the [iteration notes](iterations/v0.5.6.md) call a method an **export kind**
(`ExportKind`), because a kind is what the built version can perform. **Method** is what every screen
says and what this page says.

Everything here is optional. Listea is fully usable with nothing set to export at all.

- [The two methods](#the-two-methods)
- [Where the choice lives](#where-the-choice-lives)
- [How a round resolves](#how-a-round-resolves)
- [Settings → Exports](#settings--exports)
- [Which methods ask first](#which-methods-ask-first)
- [Nothing is ever exported empty](#nothing-is-ever-exported-empty)
- [The record](#the-record)

## The two methods

| Method | What it does | Asks first | Its own settings |
|---|---|---|---|
| **Webhook** | POSTs a JSON body describing the round to a URL you configure | Yes | [Settings → Exports → Webhook](settings.md#exports) |
| **Txt** | Writes one tab-separated text file per round under `Download/Listea/txt/` | No | [Settings → Exports → Txt](settings.md#exports) — nothing is editable there yet |

The set of methods is closed rather than stored, because a method is **code** — a renderer plus a
destination, and neither is data. The list of methods is the list of exporters this build ships:
adding one costs a class and a branch in the one place that maps a stored word to a method. The app
never meets a stored method it has never heard of except in a database written by a *different*
build — which it [fails closed](#a-method-this-build-cannot-read-exports-nothing) on rather than
serving.

The txt method is deliberately the least ambitious export there could be: no configuration, no
network, and one failure mode — the file did not get written. It exists to prove the webhook was a
method rather than the model. What is in the file is documented in
[Webhooks → The txt body](webhooks.md#the-txt-body), beside the JSON body it mirrors field for field.

## Where the choice lives

**A Todo picks one method when it is created. A List can override it for everything it holds.** Both
are single values, never a set — *"todo创建（import的时候）和list里的export方式都是单选。都是和todo或者list绑定的属性。"*

- **A Todo** — chosen in the import dialog's *When a round over this Todo finishes* group:
  **Don't export**, **Webhook**, or **Txt**. A new Todo starts at *Don't export*, because a choice
  the user did not make should not send anything anywhere. A Todo that existed before v0.5.6
  migrates to **Webhook**, because a migration is preservation: a Todo that exported by webhook
  before the upgrade must still do so after it.
- **A List** — chosen on its detail page, in the **Export** card's **Method** row:
  **Same as the Todos** / **Export nothing** / **Webhook** / **Txt**. A List that has never been
  touched sits on *Same as the Todos*, and that is a real state rather than a blank one — see
  [How a round resolves](#how-a-round-resolves). Since v0.6.2 a newly created List also starts with
  **Export when this list is completed** off: no List begins posting because it was created after an
  upgrade, and the switch is turned on per List.

**The destination is per Todo, and a List may override it.** Since v0.6.2 a Todo stores the webhook
address it posts to, copied from the default when it is configured (a snapshot, not a subscription)
and editable from the Todo card's ⋮ menu. A List's Export card carries an optional **Webhook
address**: empty means *follow each Todo*, and a non-empty value replaces every webhook group that
List would otherwise post. There is still no Test button on a List — the test belongs on the Webhook
method's page, where the default it posts to lives. See
[Webhooks → The destination](webhooks.md#the-destination).

### A method this build cannot read exports nothing

If a stored method is a word this build cannot read — a row written by a later version, or a
corrupted one — the answer is **export nothing**: no file, no request, and no fallback to a default.
A rule this build cannot evaluate must not be quietly dropped, because a drop *widens* what the app
does on the user's behalf, and here it would widen it all the way to the network. This is the same
rule that turns an unrecognised template id into nothing, one level up.

## How a round resolves

**The rule, in one table.** A round is the items a review or a completion covers; each item may have
come from a Todo.

| The List chose | What the round becomes |
|---|---|
| **Webhook** or **Txt** | **One** payload, by that method, carrying the whole round |
| **Export nothing** | No payload, no file, no request — and no question asked |
| **Same as the Todos** | **One payload per source Todo**, each by that Todo's own method and address |
| A Todo set to *Don't export* | Its group is **absent**, not empty |
| An item no Todo owns | Its own group, by **Webhook** to the live default address |
| A non-empty List address | Overrides the address of **every webhook group** of that List |

*Same as the Todos* is the interesting row, and it is why a List's choice is a choice rather than a
switch. A List may hold Tasks from several Todos with several different methods, and a round over
such a List exports more than once — one POST for the items that came from a webhook Todo, one file
for the items that came from a Txt Todo. The split is a **sum computed when the round ends**, never
a list stored on a row, which is why nothing has to be kept in sync: nothing is held.

**Webhook groups that resolve to the same address merge.** Under *Same as the Todos* over items from
two webhook Todos, the round produces one POST if both Todos hold that address and two if they do
not; *Webhook* chosen for the whole List is one POST regardless of how many Todos the Tasks came
from. A blank address is its own group and is recorded as a delivery with no destination rather than
silently disappearing — the failure is the answer to *why did nothing arrive*.

**A List never reads the Settings default on its own.** Its webhook groups resolve through the
Todos; only an item with no owning Todo falls back to the live default. The List's own address is
the one value typed for it, and it is an override rather than a copy.

Groups come out in the order their items arrived, and each is delivered by its own method, so a
round that becomes two payloads announces two results.

**A List that says nothing is not a List that said nothing.** *Same as the Todos* and *Export
nothing* can both end in silence for one round and mean opposite things: the first has not decided,
the second has.

### A folder round

A Quick Review round has no List behind it, so it always takes the *Same as the Todos* path: each
item routes by its own Todo's method, and an item with no source Todo goes by webhook. There is no
List card to configure, so nothing about a folder round is chosen per round.

This is the mode a device pass found broken, and it is worth stating why. A folder round's items are
resolved from files rather than read out of a List's rows, so a row that did not carry its Task id
put **every** item in one group with no source — and a folder round over a *Txt* Todo posted to the
webhook instead of writing a file. The Task id on a folder-resolved row is now the one field that is
read back rather than merely carried into the body.

## Settings → Exports

**Settings → Exports** is a full screen listing the methods — **Webhook**, then **Txt** — each with
its destination on its row, and the export-wide settings below them. The Settings index row reads
*"2 methods · webhook at n8n.example"* (or *"webhook not set"*), describing methods and the default
address only: history has its own row at the bottom of Settings.

It is a screen rather than a dialog because a dialog whose own contents include another dialog is
the shape this avoids.

Three switches live here, and none of them is a statement about HTTP, which is why none of them is
on the Webhook page:

| Switch | What it decides |
|---|---|
| **Export from Quick Review** | Whether finishing a Quick Review queue exports the folder's items |
| **Export when leaving a review** | Whether a review that ends — finished or not — exports the round it covered |
| **Export checked items only** | Whether an export carries only the items that are checked |

Each method's page holds what is *its own*: the Webhook page holds **Preselect the default
address** (whether *Use default* starts selected when configuring — it does not enable delivery),
**Default URL** (the value a new Todo copies), and **Send a test payload**; the Txt page states where
it writes and what is in the file, and is deliberately not editable in this release — no name
template, no format, no schedule. Neither page has a history card any more.

**The history is one list, not one per method.** *What did that review do?* wants one answer, so
every export — whichever method carried it — is kept in the same place, and the method is named on
the row. It is now grouped by the round that produced it and reached from the bottom of Settings.
See [The record](#the-record).

## Which methods ask first

**A method that leaves the device asks. A method that writes a file does not.**

A webhook fired off the back of a swipe was the one thing in Listea that left the device without
anyone asking, which is a lot to hang on a gesture whose whole job is to be fast. So every automatic
webhook delivery puts up a dialog — what event, which List or folder, how many items, which
destination, **and what caused it** — and posts only on **Send**.

A txt write is neither outward-facing nor hard to undo, and asking every round would train the user
to tap through the one dialog that matters. It writes, and then reports.

Asking is a property of the method rather than a special case for txt: a third method that talked to
somebody's server would ask, and one that wrote into the user's own storage would not.

Either way:

- **Not now** exports nothing and **changes nothing else.** No item is checked or unchecked by
  answering either way; completion state is written when you swipe, not when you answer.
- A declined payload is **kept in the history**, exactly as a failed one is, so refusing costs
  nothing — send it later from Settings whenever the receiver is ready.
- Tapping outside the dialog counts as *Not now*. The safe reading of an unanswered question is that
  nothing should leave the device.
- The question comes **after** the checks — a method that is switched off, or a webhook with an
  unusable URL, reports without asking.

The two deliveries you press a button for — **Send a test payload** and **Resend** — do not ask.
Pressing the button is the confirmation.

> **A completion that lands while you are somewhere else is answered for you.** Completing one List
> can complete another, and asking about the second in the middle of reviewing the first puts a
> dialog about one List over the review of a different one. So the app answers *Not now* on your
> behalf: the payload is built and kept, nothing is sent, and no dialog appears. The List's *Last
> export* line records it as not sent.

## Nothing is ever exported empty

**No payload is ever posted and no file is ever written with nothing to carry.** A round where
nothing was checked, a review left without a single swipe, a List that emptied itself — each reports
*Nothing to send* instead. `"tasks": []` would tell a receiver a round happened when none did, and a
file holding only a header says the same lie in a different medium.

Nothing is kept in the history for an empty payload either. There would be nothing to resend, and
nothing was attempted.

**And a round where nothing was configured to carry it says so.** If every Todo the round covered is
set to *Don't export*, there is no method to hand the items to and not even a payload to build — so
the result is **Nothing exported**, named rather than silent. It is not a failure: nothing was
attempted and nothing is broken. It is the app saying what it did not do and why, which is what it
owes the user for a round that was supposed to produce something. Nothing is recorded in the history
for it, because there is no payload whose method would mean anything.

## The record

**Settings → History**, the last row of the Settings index.

**Every export Listea performs is kept, with its body and what became of it** — sent, written,
failed, not sent because a List's switch was off, or declined when it asked — and the rows of one
delivery share a **round id**, so the screen can show what belonged together. It used to keep only
failures, which turned out to be the wrong shape: *did that round actually go out?* is the question
people have, and a page that answers it only when the answer is no is a page you cannot trust.

**A round card** carries the time of its newest record, the List, the event, how many exports it
produced and one aggregate verdict: *Delivered*, *Partly delivered*, *Failed*, *Not sent* or
*Outcome not recorded*. It expands into its exports, each of which keeps its **method**, **address**
(the host is shown), item count and outcome; opening one shows the exact body that was posted or
written, or would have been. **Test** writes one record as a round of one, and rows written before
schema 27 have no round id and are treated as a round of one each.

Three actions per export, where they make sense:

- **Resend** posts the stored body, byte for byte, to **the address that record attempted**,
  prefilled in an editable field. It is offered only on a webhook record — *post this again* says
  nothing about a file that was written to disk. An edited address is temporary: it is used for that
  resend only and is never written back to the Todo, the List or the record; when it differs from
  the recorded one the detail line appends *resent to <host>*. A pre-27 record has no address; the
  field starts from the current default and says so. The record is updated in place with the new
  verdict rather than a second one being appended: the history is a list of payloads and what became
  of each, not a log of every attempt at one.
- **Open payload** shows the exact body.
- **Delete** drops the record, immediately and for good. It is the only thing in the app that can
  lose a payload.

The Settings index row counts both exports and rounds, and carries a warning tone while a record has
still not been delivered. Only that second fact is worth a warning.

**A folder round has no List to record against**, so its exports appear in the history and on no
List's *Last export* line. The history is the only place they are kept.

---

Next: [Webhooks](webhooks.md) for what the webhook method sends and how a delivery fails, or
[File management](file-management.md) for what an export can lead to.
