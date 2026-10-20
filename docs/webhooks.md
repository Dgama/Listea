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

**Settings → Exports → Webhook.** Two settings, both app-wide:

- **Default enabled** — whether webhook exports happen at all. With it off, a round that would have
  posted says so instead. It does **not** change anyone's method choice: turning it back on restores
  them exactly as they were.
- **Default URL** — where every webhook in the app posts.

There is **no per-List URL and no per-List test button**, deliberately. A List used to carry its own
copy, seeded from this default when it was created and never read again — so changing the default
reached no existing List, which is the opposite of what a default is for.

**Send a test payload** posts one small payload to the URL above, so a receiver can be checked
without waiting for a round. It asks nothing first, because pressing a button called *Test* is the
confirmation. Its event is `export.test`.

**Resend**, from the [history](exports.md#the-record), posts a stored body byte for byte to this
same URL. It asks only that a URL is filled in — not that *Default enabled* is on, since that switch
is one of the things that might have gone wrong in the first place.

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
themselves. A round whose method is *Txt* fires no webhook at all; a List on *Same as the Todos* over
items from two webhook Todos fires two of these, one per group. See
[Exports → How a round resolves](exports.md#how-a-round-resolves).

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

**`version` is `2`, and the cut from the old body is hard.** The v0.1 body had no version and no
Tasks: it was one flat `items` array in which each entry was a file and the thing judged at the same
time. A receiver built on it must be pointed at the new shape deliberately; a body never says `2`
while meaning the old layout. Stored history is not rewritten — [Resend](#results-and-notices)
replays an old record's bytes exactly as they were.

| Field | Meaning |
|---|---|
| `version` | `2`. |
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

**Keys are always present.** `completedAt`, `relativePath` and a template's `name` may be `null`,
and `assets` or `templates` may be `[]`; none of them is omitted. A receiver can read every field
without asking whether it exists.

A round finished in Quick Review sends this same shape with the Upstream's folder standing in for
the List, which keeps one payload format on the wire rather than inventing a second one. The
per-Asset `relativePath` comes out identical to what a List over the same folder would have produced.

**Nothing drops out for want of a registration row.** Every Task and every Asset is given its public
id when the row is created — at import, not at delivery — so a round over a Todo holds Tasks and
files that no List ever registered, and each still arrives named. See
[Task and asset ids](#task-and-asset-ids).

## The txt body

The txt method renders the same facts, in the same order, as tab-separated lines. One file per
round, named `<yyyyMMdd-HHmmss>-<event>-<origin>.txt` in local time, with a `-2`, `-3` … suffix if
the name is taken.

```
listea-export	2
event	review.exited
list	export test
source	2026-09-11
at	1767100000000
tasks	1
task	0AT9K3QWMB-4H7ZP2-XC5N0V	a.jpg	1	1	1
template	builtin.media.asset-control	1	Media Asset Control	4
asset	0AT9K3QWMB-4H7ZP2-XC5N0V	a.jpg	1	cos	0	2
action	favorite
action	cust1
```

**The hierarchy is linearised, not flattened.** A `task` record is followed by one `template`
record per attachment and one `asset` record per file; each `asset` is followed by one `action`
record per mark. Every record kind has a fixed field count, so a reader splits on tabs and the
first field tells it how many more to expect:

| Record | Fields | Meaning |
|---|---|---|
| `task` | 6 | type, id, title, completed `0\|1`, template count, asset count |
| `template` | 5 | type, id, name-present `0\|1`, name (empty when absent), version |
| `asset` | 7 | type, id, title, path-present `0\|1`, path (empty when absent), `sourceMissing` `0\|1`, action count |
| `action` | 2 | type, wire value |

Actions are records rather than a comma-joined field, so a wire value containing a comma is still
one value. Counts on the parent records say how many children follow.

Fields are escaped the way every other Listea record escapes them — a title is a user's filename
and may contain a tab or a newline, and a renderer that let one through would produce a file that
reads as two records. A not-present path is written as `0` followed by an empty field, so the
column count never changes.

Two things the file has that the JSON does not: a **version line** first, so a payload is never read
without knowing what wrote it, and an **`at`** line, because a file on disk has to say when it was
written in a way a receiver's arrival time cannot. `source` names the Todo a split group came from,
and is empty when the List chose for the whole round.

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
is announced in turn rather than overwriting the other, except that two deliveries found switched
off in the same moment are reported once. A round that split across two Todos announces once per
group.

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
