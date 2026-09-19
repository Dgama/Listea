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
- [Item ids](#item-ids)
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

| Event | Fires when | Items | Needs |
|---|---|---|---|
| `list.completed` | A List goes from incomplete to complete | The whole List, or the group a split produced | The method in play to be webhook |
| `review.exited` | A review round ends, finished or not | That review's round | The same, plus *Export when leaving a review* |
| `quickreview.completed` | A Quick Review queue is finished | That folder's round | The same, plus *Export from Quick Review* |
| `export.test` | The **Send a test payload** button | None — one small payload | A usable URL |

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

One shape, for every event.

```json
{
  "event": "list.completed",
  "list": { "id": 1, "title": "2026-07-11", "completedAt": 1767100000000 },
  "items": [
    {
      "id": "0AT9K3QWMB-4H7ZP2-XC5N0V",
      "title": "a.jpg",
      "isCompleted": true,
      "relativePath": "2026-07-11/bilibili",
      "actions": ["favorite", "cust1"]
    }
  ]
}
```

Posted as `Content-Type: application/json; charset=utf-8`. Timestamps are epoch milliseconds. Any
2xx counts as success; every other status code and every network failure is a failure. The timeout
is 10 seconds on both connect and read.

| Field | Meaning |
|---|---|
| `event` | One of the four above. |
| `list.id` | The List's id, or **`0`** for a round with no List — Quick Review, which stands the folder in as the subject. |
| `list.title` | The List's title, or the **folder's name** for a Quick Review round. |
| `list.completedAt` | When the List completed, or `null`. |
| `items[].id` | The item's [public id](#item-ids). Stable, never the database row number. |
| `items[].title` | The file name. |
| `items[].isCompleted` | Whether the item was processed in Listea. |
| `items[].relativePath` | The item's **folder**, relative to the Upstream's folder, without the file name. |
| `items[].actions` | The wire values of the buttons the item carries, in the order its template lists them. |

**`isCompleted` and `actions` mean different things and neither implies the other.** `isCompleted`
says the item was processed in Listea. `actions` says what downstream automation should do with it.
Checking something off is not an action.

**`relativePath`** is the folder only — `title` already carries the file name, so join the two for
the whole path. It is empty (`""`) for an item sitting directly in the Upstream's folder, and `null`
for a manual item with no source file, because a fake path would be worse than none.

**`actions`** carries the **Webhook value** of each button the item's Task carries, as configured
*right now*, resolved at delivery time. Renaming a button changes future payloads without touching a
single stored item. Duplicates are collapsed: two buttons configured with the same wire value emit
it once.

The buttons are the ones the Task's **template** offers, in that template's order. ★ is a button of
the built-in template with the fixed value `favorite`, so an item whose template does not carry ★
sends no `favorite` — and an item whose Task is attached to no matching template sends no actions at
all. Everything else is whatever you set on the template, at
[Settings → Templates](settings.md#templates).

A round finished in Quick Review sends this same shape with the Upstream's folder standing in for
the List, which keeps one payload format on the wire rather than inventing a second one. The
per-item `relativePath` comes out identical to what a List over the same folder would have produced.

**Nothing drops out for want of a registration row.** A round over a Todo holds Tasks that no List
and no folder listing ever registered, so an item arrives with no id of its own. The payload builder
mints a stable one on the first delivery and keeps it in `legacy_task_ids`, which is the same path a
folder-as-Task Task takes — so every item in the round appears, and appears with the same id on
every later round.

## The txt body

The txt method renders the same facts, in the same order, as tab-separated lines. One file per
round, named `<yyyyMMdd-HHmmss>-<event>-<origin>.txt` in local time, with a `-2`, `-3` … suffix if
the name is taken.

```
listea-export	1
event	review.exited
list	export test
source	2026-09-11
at	1767100000000
items	3
item	0AT9K3QWMB-4H7ZP2-XC5N0V	a.jpg	1	2026-07-11/bilibili	favorite,cust1
```

Every line has the same field count, so a reader can split on tabs without counting. Fields are
escaped the way every other Listea record escapes them — a title is a user's filename and may
contain a tab or a newline, and a renderer that let one through would produce a file that reads as
two records. The last field is empty rather than absent when an item has no folder and no actions.

Two things the file has that the JSON does not: a **version line** first, so a payload is never read
without knowing what wrote it, and an **`at`** line, because a file on disk has to say when it was
written in a way a receiver's arrival time cannot. `source` names the Todo a split group came from,
and is empty when the List chose for the whole round.

## Item ids

`id` is a 24-character public id, assigned when the item is created and never changed. **It is safe
to use as a primary key on the receiving side.** The format, what each field means, and why the same
file gets a new id on each arrival are documented in
[Core concepts → Item identity](core-concepts.md#item-identity).

> **Changed in v10.** This field used to be the SQLite row number (`"id": 12`). That number
> restarted at 1 on a reinstall and was drawn from two different sequences depending on which review
> mode sent it, so it was never safe to key on. It is no longer on the wire at all. Existing items
> were given public ids at upgrade, timestamped from when they were actually created.

## Narrowing what is sent

**Export checked items only** narrows **every** export, webhook or txt, the test payload included,
to the items that are checked. What the test button sends stays an honest preview of a real
delivery.

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
The notice says what event it was, which List, how many items, and how it went. The headline names
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
