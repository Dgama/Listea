# Webhooks

The method that POSTs.

Webhooks are what make actions useful: the sorting happens on the phone, and the acting-on-it
happens wherever you want. Listea POSTs a JSON body describing a round of review; what happens next
is entirely the receiver's business.

**This page is the webhook method's page.** For what an export is, which methods exist, and how a
Todo's and a List's choices resolve into one, see [Exports](exports.md). For the settings screen,
see [Settings](settings.md#exports).

- [The destination](#the-destination)
- [Events](#events)
- [The payload](#the-payload)
- [The txt body](#the-txt-body)
- [Task and asset ids](#task-and-asset-ids)
- [Narrowing what is sent](#narrowing-what-is-sent)
- [A round is reported once](#a-round-is-reported-once)
- [Results and notices](#results-and-notices)
- [Failures](#failures)

## The destination

**A Todo owns the address its exports post to.** The address is taken when the Todo is configured —
at Create Tasks or Refresh Import — and stored on the Todo's row. *Use default* copies the default as
it stands at that moment: it is a snapshot, not a subscription, so a later edit in Settings reaches
no existing Todo. To move a Todo, edit it: **Todo card → ⋮ → Webhook address**. That dialog shows the
address held, offers *Use default* or a custom URL, and saves it on the Todo. The same address
governs the Todo's own rounds, Quick Review rounds involving its Tasks, and the webhook exports of
Lists holding them.

**A List that chose Webhook may override it with an address of its own.** The optional *Webhook
address* field on a List's Export card is drawn **only while the List's Method is Webhook**; it is
empty by default, which means *follow each Todo*. A non-empty value replaces the address of every
webhook group that List would otherwise post, and is the one case where an address is typed for a
List rather than copied from a Todo. Under *Same as the Todos* or a txt method the field is not
drawn and any stored value is ignored: inheritance means the List has no destination of its own.
A List never reads the Settings default on its own; a Task with no owning Todo does fall back to
the live default.

**Settings → Exports → Webhook** holds the app-wide starting point:

- **Preselect the default address** — whether *Use default* is preselected when a Todo is being
  configured. It does **not** enable or disable delivery: turning it off only starts the dialog on
  *Custom*. Delivery is authorised by each Todo's method.
- **Default URL** — the value a new Todo copies. It is not *the* destination, and changing it moves
  only Todos created afterwards.

**Send a test payload** posts one small payload to the default URL, so a receiver can be checked
without waiting for a round. It asks nothing first, because pressing a button called *Test* is the
confirmation. Its event is `export.test`, and it writes one history record — a round of one.

**Resend**, from the [history](exports.md#the-record), posts a stored body byte for byte to the
address the record attempted, prefilled and editable. An edited address is temporary: it is used for
that resend only and written back to neither the Todo, the List nor the record. A row written before
schema 27 has no recorded address; its resend is prefilled with the current default and says the
original was not recorded.

## Events

| Event | Fires when | Tasks | Needs |
|---|---|---|---|
| `list.completed` | A List goes from incomplete to complete | The whole List, or the group a split produced | The method in play to be webhook |
| `review.exited` | A review round ends, finished or not | That review's round | The same, plus *Export when leaving a review* |
| `quickreview.completed` | A Quick Review queue is finished | That folder's round | The same, plus *Export from Quick Review* |
| `export.test` | The **Send a test payload** button | One synthetic Task — a preview, not a round | A usable URL |

**`list.completed`** has never depended on any of the review switches and still does not. It fires
on the transition only — a List that merely stays complete never delivers again.

**Which of these fire at all** depends on what the round's List and Todos chose, not on the events
themselves. A round whose method is *Txt* fires no webhook at all. A List on *Same as the Todos* over
items from two webhook Todos posts once per **resolved address**: one delivery if both Todos share an
address, two if they differ. See [Exports → How a round resolves](exports.md#how-a-round-resolves).

**`review.exited`** also needs *Export when leaving a review*. A queue that finished on screen has
already exported and does not export again on the way out, and leaving a review that had nothing to
queue exports nothing at all.

> **If you watch for `list.completed`, watch for `review.exited` too** once that switch is on. See
> [A round is reported once](#a-round-is-reported-once).

> **Renamed in v0.5.6.** The test event used to be `list.webhook.test`. It now says `export.test`,
> because the old name referred to a List the test no longer has. The other three event names and the
> payload shape are unchanged.

## The payload

One shape, for every event, describing the round as **event → list → task → asset**.

```json
{
  "version": 2,
  "event": "list.completed",
  "list": { "id": 1, "title": "2026-07-11", "completedAt": 1767100000000 },
  "tasks": [
    {
      "id": "0AT9K3QWMB-4H7ZP2-XC5N0V",
      "title": "a.jpg",
      "isCompleted": true,
      "templates": [
        { "id": "builtin.media.asset-control", "name": "Media Asset Control", "version": 4 }
      ],
      "assets": [
        {
          "id": "0AT9K3QWMB-4H7ZP2-XC5N0V",
          "title": "a.jpg",
          "relativePath": "cos",
          "sourceMissing": false,
          "actions": ["favorite", "cust1"]
        }
      ]
    }
  ]
}
```

Posted as `Content-Type: application/json; charset=utf-8`. Timestamps are epoch milliseconds. Any
2xx counts as success; every other status code and every network failure is a failure. The timeout
is 10 seconds on both connect and read.

**`version` is `2` or `3`, and the cut from the old body is hard.** The v0.1 body had no version and
no Tasks: it was one flat `items` array in which each entry was a file and the thing judged at the
same time. A receiver built on it must be pointed at the new shape deliberately; a body never says
`2` while meaning the old layout. **Version 3 adds only the `marks` array** — an Asset writes it
only when it has passages to send, and a body with no such Asset says `2`. Every image and video
body is therefore byte for byte the one v0.6.2 sent. Stored history is not rewritten —
[Resend](#results-and-notices) replays an old record's bytes exactly as they were.

| Field | Meaning |
|---|---|
| `version` | `2`, or `3` when the body carries at least one `marks` array. |
| `event` | One of the four above. |
| `list.id` | The List's id, or **`0`** for a round with no List — Quick Review, which stands the folder in as the subject. |
| `list.title` | The List's title, or the **folder's name** for a Quick Review round. |
| `list.completedAt` | When the List completed, or `null`. |
| `tasks[].id` | The Task's [public id](#task-and-asset-ids). Stable, never the database row number. |
| `tasks[].title` | The name the round showed for this Task — the List row's title, or the name it was imported under. |
| `tasks[].isCompleted` | Whether the Task was processed in Listea. |
| `tasks[].templates[]` | The template versions the Task was imported under: `id`, `name`, `version`. |
| `tasks[].assets[]` | Every file the Task presents, ordered as the Task holds them. `[]` for a Task with no file. |
| `assets[].id` | The Asset's [public id](#task-and-asset-ids). Stable, never the database row number. |
| `assets[].title` | The file name. |
| `assets[].relativePath` | The file's **folder**, relative to the Upstream's linked folder, without the file name. |
| `assets[].sourceMissing` | Whether Listea can no longer reach the file. |
| `assets[].actions` | The wire values recorded on **this file**, in the order its governing template lists them. |
| `assets[].marks` | A PDF's passages: `id`, `kind`, `toolId`, `quote` (`page`, `text`) and `value`. Present **only when non-empty**. |

**`isCompleted` and `actions` mean different things and neither implies the other.** `isCompleted`
is one judgment about one Task however many files it presents. `actions` says what downstream
automation should do with each file. Checking something off is not an action.

**`relativePath` is the material's, not the Task's.** It is resolved from the file's own path under
the Upstream and from nothing else, so it does not change with how the Task was packaged. The same
file reports `cos` whether it arrived as a file-as-Task from inside `cos` or as part of a
folder-as-Task over the Upstream. It is empty (`""`) for a file sitting directly in the Upstream's
linked folder, and `null` for an Asset with no path at all, because a fake path would be worse than
none. It is the containing folder only — `title` already carries the file name, so join the two for
the whole path.

**`actions` is per file.** Each Asset resolves against the control that governs **its own kind**
through the Task's attached template: an image and a video under one Task may carry different
marks, and the body says which file carries which. A kind no attached template admits sends no
actions. This is the same resolution the chip row drew, and deliberately not a Task-wide union,
which would say a mark was on every file the Task holds.

The values are the **Webhook values** of the selected buttons as configured *right now*, resolved at
delivery time; renaming a button changes future payloads without touching a stored decision.
Duplicates are collapsed: two buttons configured with the same wire value emit it once. ★ is a
button of the built-in template with the fixed value `favorite`, so an Asset whose governing control
does not carry ★ sends no `favorite`. Everything else is whatever you set on the template, at
[Settings → Templates](settings.md#templates).

**`templates` names the attached version, never the template's current one.** A Task reviewed under
version 4 goes on meaning version 4 after an edit appends version 5. `name` is `null` only when this
build cannot read that version at all; the reference is still worth sending, so the entry remains.

### Passages, and why they are version 3

A PDF Asset may carry **marks**: passages the reviewer selected and attached a comment or tag to.
They sit beside `actions`, not inside it, because a passage is a statement about a sentence and an
action is a statement about a file. A PDF's `actions` stays `[]`; its passage tools never write
there.

```json
{
  "id": "asset-public-id",
  "title": "Paper.pdf",
  "relativePath": "papers",
  "sourceMissing": false,
  "actions": [],
  "marks": [
    {
      "id": "mark-id",
      "kind": "comment",
      "toolId": "comment-1",
      "quote": { "page": 3, "text": "the sentence the reviewer selected" },
      "value": "I think this overstates the result"
    },
    {
      "id": "mark-id-2",
      "kind": "tag",
      "toolId": "funny-1",
      "quote": { "page": 4, "text": "another passage" },
      "value": "funny_sentence"
    }
  ]
}
```

`quote.page` is the stored page index, counting from **zero** — the renderer's own numbering, and
the number the anchor was verified with. (The viewer's sheet says *Page N* by adding one; the wire
carries the stored fact.) `quote.text` is the stored quotation, not a fresh selection. `kind` is
`comment` or `tag`; `toolId` is the id of the tool that made it, so receivers can group passages by
the reviewer's tool without knowing this build's names.

**A mark is sent only when three facts still hold**, and each is asked of the thing that knows it:

- **The tool is in the version that governs the file now**, as the same kind. Removing the tool or
  the template hides a mark rather than deleting it; a hidden mark is omitted, never sent with a
  stale value. Restoring the same id brings it back on the next export.
- **The document is the one the quote was verified against.** The anchor stores a SHA-256 of the
  file's bytes. If the bytes at the path differ, or cannot be read at all, the mark is omitted: the
  old sentence may no longer be there, and sending it would be a false statement about the file. A
  mark that no longer resolves is listed as **detached** in the file's Info sheet and is not
  exported while it is.
- **The kind is one this build can read.** A row written by a later build is skipped rather than
  sent under a guess.

A **comment** sends the reviewer's stored text. A **tag** sends the tool's **current configured
value**, resolved at delivery time like every other tag, because the tag means *this passage is
funny* and what "funny" sends is current configuration. Marks are ordered by creation, then id.

**No mark is ever invented and no mark is ever sent empty.** An Asset with no surviving passage
writes no `marks` key at all, which is what lets the body keep saying `2`.

**Keys are always present — except `marks`.** `completedAt`, `relativePath` and a template's `name`
may be `null`, and `assets` or `templates` may be `[]`; none of them is omitted. `marks` is the one
exception, present only where it has content, because an empty one on an image would be a shape v2
receivers have never seen. A receiver that needs to branch can read `version`; one that only reads
`actions` sees no change at all.

A round finished in Quick Review sends this same shape with the Upstream's folder standing in for
the List, which keeps one payload format on the wire rather than inventing a second one. The
per-Asset `relativePath` comes out identical to what a List over the same folder would have produced.

**Nothing drops out for want of a registration row.** Every Task and every Asset is given its public
id when the row is created — at import, not at delivery — so a round over a Todo holds Tasks and
files that no List ever registered, and each still arrives named. See
[Task and asset ids](#task-and-asset-ids).

## The txt body

The txt method writes **the same JSON body a webhook would post, byte for byte**. One file per
round, named `<yyyyMMdd-HHmmss>-<event>-<origin>.txt` in local time, with a `-2`, `-3` … suffix if
the name is taken.

```
{"version":2,"event":"review.exited","list":{"id":7,"title":"export test","completedAt":null},"tasks":[{"id":"0AT9K3QWMB-4H7ZP2-XC5N0V","title":"a.jpg","isCompleted":true,…}]}
```

(One line in the file; wrapped here for reading.) It is [the payload](#the-payload) above in a
file, so every field, every `null` and the key order are the same thing, and a receiver parses it
exactly as it parses a POST. A round carrying PDF passages writes the same version 3 body with
`marks`; a round without one stays version 2.

**The one thing the file does not carry is the destination.** That is not an omission: there was
never an address in a body, because where an export went is a fact for
[the record](#the-record), not for its bytes. This makes the two outputs of one round diffable on
a workstation, which is the property the feature exists to give.

**The file name carries what the body does not.** The v2 body has no `at` field and does not name
the Todo a split group came from; the name's stamp says when the round happened, and its origin
says which Todo chose the method — or the List, when the List did.

Since v0.6.2 this identity is deliberate and temporary: it was asked for so the two outputs could
be compared while the release was being accepted, and a later release may give the file a shape of
its own. If it does, `version` inside the file is still what says which shape is being read.

## Task and asset ids

`tasks[].id` and `assets[].id` are both 24-character public ids, assigned when the row is created and
never changed. **Either is safe to use as a primary key on the receiving side**, and neither is the
SQLite row number.

They are different names for different things. A Task's id names one judgment; an Asset's id names
one file *as presented under that Task*. The same file under two Tasks is two Assets with two ids,
because those are two judgments and a receiver must be able to keep them apart. The format, what each
field means, and why the same file gets a new id on each arrival are in
[Core concepts → Item identity](core-concepts.md#item-identity).

A v0.1 receiver keyed on `items[].id` was keying on what is now `assets[].id`; the Task's id is new,
because the old body had no Task. Rows that already existed when this release arrived were given
Task ids at upgrade, taking the id the old folder-round path had minted for the Task where one
existed, and each Asset kept the id its file had been sent under.

> **Changed in v10.** The id used to be the SQLite row number (`"id": 12`). That number restarted at
> 1 on a reinstall and was drawn from two different sequences depending on which review mode sent it,
> so it was never safe to key on. It is no longer on the wire.

## Narrowing what is sent

**Export checked items only** narrows **every** export, webhook or txt, the test payload included,
to the Tasks that are checked — a Task is one membership in a List however many files it presents.
What the test button sends stays an honest preview of a real delivery.

Nothing is filtered out of a *review* by this; only out of what is exported.

Paired with **Review unchecked items only**, each round's payload carries exactly the decisions you
just made:

| Setting | Result |
|---|---|
| Both off | A payload describes everything in scope, and the receiver decides using `isCompleted`. |
| Both on | Each round queues what was unchecked, and reports exactly what you checked in it. |

The three switches that decide *when* a round exports and *what goes into it* live on
[Settings → Exports](settings.md#exports), not here — none of them is a statement about HTTP.

## A round is reported once

`list.completed` and `review.exited` both describe the same List, so a round that completes its List
would otherwise describe the same swipes twice, seconds apart. **The round's own report is the one
that survives, and the completion stands down.**

The round's is the narrower and more honest of the two: with *Review unchecked items only* and
*Export checked items only* both on, `review.exited` carries exactly the decisions you just made,
while `list.completed` would carry the whole List — including items checked weeks ago and already
exported.

This only applies while *Export when leaving a review* is on. With it off there is no second payload
to prefer, so `list.completed` fires exactly as it always has.

## Results and notices

Every delivery that reaches a verdict is announced, including one abandoned over an unusable URL.
The notice says what event it was, which List, how many Tasks, and how it went. The headline names
the method's own result — **Webhook sent** with its HTTP code, **Txt written** with the file name,
**Txt not written**, **Nothing to send**, **Nothing exported**.

One swipe can finish a review **and** complete the List it belongs to, which is two deliveries. Each
is announced in turn rather than overwriting the other. A round that split by address announces once
per destination and its headline says *several webhook addresses*; each group's own line names its
host, so a failure stays specific. A List completion whose switch is off is the only delivery now
recorded as *not sent* — there is no app-wide gate to produce that outcome any more.

A List's **Last export** line records what that List's own exports did — `list.completed`,
`export.test`, and `review.exited` from a full Review. A **Quick Review round** has no List to
record against, so a folder round does not appear on any List's *Last export* line. It is still
announced, and still kept in the history.

Whether and when a delivery asks for confirmation is on
[Exports → Which methods ask first](exports.md#which-methods-ask-first).

## Failures

A failure is any non-2xx status, or any network error. Both are reported in the same dialog a
success is, and both are kept in the history to be resent. The txt method's one failure mode is *the
file did not get written*, reported the same way.

Network errors are described in terms of the delivery rather than the JDK — *Timed out reaching
example.com:443*, *Host not found: example.com:443*, *Could not connect to…*, *TLS failed for…*.
The host and port come from the URL Listea actually opened, not from the resolved IP address, so
nothing in the message looks like your URL was rewritten.

Response bodies are never stored or shown. Failures log a short snippet to logcat only; filter with
`adb logcat -s ListeaWebhook`.

If a delivery fails and **Ask to delete after webhook** is on, nothing is deleted and you are told
where the round went. See [File management](file-management.md#ask-to-delete-after-webhook).

---

Next: [Exports](exports.md) for the choice this method is one answer to, or
[File management](file-management.md) for what a delivered webhook can lead to.
