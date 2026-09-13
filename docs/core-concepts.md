# Core concepts

The vocabulary Listea uses, and the one rule that explains most of its behaviour.

- [The root](#the-root)
- [Folders](#folders)
- [Files and items](#files-and-items)
- [Lists](#lists)
- [Folder types](#folder-types)
- [Quick Review](#quick-review)
- [Rounds and queues](#rounds-and-queues)
- [Checked state](#checked-state)
- [Actions](#actions)
- [Where state lives](#where-state-lives)
- [Item identity](#item-identity)

## The root

The **root** is the folder you granted through Android's system folder picker, and the top of
everything Listea can see. There is exactly one at a time.

The grant is a Storage Access Framework tree URI. It persists across restarts, and Listea cannot
reach outside it — not into a sibling folder, not into a parent.

Changing the root deletes the Lists linked to the old one. You are shown which Lists those are
before anything happens, and manual Lists are never affected.

## Folders

Folders are browsed, not imported. The **Folder** tab walks the root's tree live, reading whatever
is on disk at the moment you look.

Listea does not modify anything it browses. Nothing is moved, renamed or created. The only writes
to storage happen through three explicit actions — see [File management](file-management.md).

## Files and items

A **file** is what is on disk. An **item** is a file as it appears in a queue: a title, a path, and
whatever has been decided about it.

- **Directories are never items.** Not in a List, not in a Quick Review queue. Only files.
- A List can also hold **manual items** — typed by hand, with no file behind them. These behave
  differently in one specific way, noted under [Where state lives](#where-state-lives).

## Lists

A **List** is a persistent queue.

A folder-backed List is created from a folder and covers **that folder and everything beneath it**.
It stores *which* files it covers and in what order, keeps its own progress, and can carry its own
webhook configuration.

A List does not follow the disk automatically. Its **Source** card reports whether the folder has
changed since the List was built, and **Update from folder** reconciles the two — showing exactly
what was added and what went missing, and applying it only on confirmation. Files that vanished
stay in the List, flagged, keeping their checked state, until you clear them.

**Lists may not overlap.** One List's folder cannot contain another's. Creating a List where an
existing one already reaches is a replacement, and always asks first.

A List can also be created with no folder at all, and filled by typing items into it.

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

A **Sublist** folder shows the progress of *its own subtree*, not the owning List's overall total —
but the webhook shown alongside it belongs to the owning List, because that is whose webhook it is.

This type is **about Lists only**. It has nothing to do with whether a folder can be reviewed:
Quick Review is offered on every folder regardless.

## Quick Review

**Quick Review** is a review over one browsed folder's own files, with no List involved.

It resolves the folder directly, so it works on any folder — inside a List, owning a List, or
unknown to every List. It creates nothing, replaces nothing and consults no List.

- It covers the files **directly in** the folder, not its subfolders.
- It always starts at the first unchecked item.
- It never remembers where it got to.

Your decisions are saved exactly as they are in a full Review, because of
[where state lives](#where-state-lives).

Quick Review can be switched off in Settings, which turns the Folder tab into a plain browser and
gallery. Switching it off is presentation only: Lists keep their items, completion and actions.

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

Two consequences worth knowing:

- An item added to the List mid-round joins the *next* round, not this one.
- Narrowing changes what is *reviewed* and nothing else. Progress, completion and webhook
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

- **★ Favourite** is built in.
- **Custom actions** are yours: any number of them, each with a **display name** (what you see on
  the chip) and a **webhook value** (what the payload sends). Two are configured out of the box.

Actions and checked state are independent. Tapping a chip does not check an item; checking an item
does not tag it. Neither implies the other, on screen or on the wire.

An item stores the *id* of each action it carries, never the display name or wire value. That is
what makes renaming an action free: future payloads change, no stored item is touched, and nothing
already marked is reinterpreted. Removing an action takes it off the bar and leaves the items that
carry it alone — invisible and unsent, but not rewritten.

## Where state lives

**A decision belongs to the file, not to the List you happened to make it from.**

This one rule explains most of Listea's behaviour. Checked, ★ and whichever custom actions an item
carries are stored against the file's identity — the root it is under, plus its path beneath that
root — in a table of their own. A List stores **membership**: which files it covers, in what order.
It *projects* the shared state rather than owning a copy.

That is what makes all of these true at once:

- Quick Review needs no List, because it has somewhere to put a decision either way.
- Checking a file in a folder's Quick Review completes the List that covers it, and fires that
  List's webhook, without Quick Review knowing the List exists.
- Building a second List over the same files shows the same ticks. Nothing is copied, so nothing
  can diverge.
- Deleting a List discards its membership, not its decisions. Rebuilding it brings them back.
- The Folder browser can show a file as checked even when no List has ever heard of it.
- Nothing resets state as a side effect. Creating a List or opening a Quick Review never clears a
  tick. Re-reviewing checked files is what *Review unchecked items only* is for, and a genuine
  "start fresh" would have to be its own explicit action.

**Manual items are the exception, deliberately.** They have no file to be a decision about, so
their state stays on their own row.

A file's identity is **its path under the root**. Replacing a file with a different one of the same
name, outside the app, hands the new file the old one's checked state.

## Item identity

Every item carries a 24-character **public id**, assigned when the row is created and never changed
afterwards. It is what goes onto the webhook, and it is safe to use as a primary key on the
receiving side.

```
0AT9K3QWMB-4H7ZP2-XC5N0V
└────┬───┘ └──┬─┘ └──┬─┘
   time     file    random
```

- **time** — milliseconds since the epoch, base32, fixed width. Sorting the ids **as strings**
  sorts them by when the item arrived; no parsing needed.
- **file** — a fingerprint of what the row is about: a file's path under the root, or a manual
  item's List and title. Deterministic, so two arrivals of the same file share this field. A hint
  for grouping, not a guarantee.
- **random** — 30 bits, and what actually makes the id unique.

The alphabet is Crockford base32 — no `I`, `L`, `O` or `U` — so an id copied out of a log by hand
cannot be misread.

**An id names one *arrival* of a file, not the file itself.** The same file entering Listea again —
a new List over the same folder, a re-sync that re-adds it after it went missing — gets a **new**
id, so a receiver can keep both records without either overwriting the other. Two arrivals of one
file are recognisable by their matching middle field, or by `relativePath` + `title`.

---

Next: [Review and actions](review-and-actions.md) for what happens inside a queue, or
[Webhooks](webhooks.md) for what leaves the device.
