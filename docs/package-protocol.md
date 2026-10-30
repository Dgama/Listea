# The Listea Package protocol — v1, v2 and v3

> **Status:** **v1 implemented for v0.8.2 (2026-10-24); v2 for `0.8.4-preview` (804); v3 for
> `0.8.5-preview` (805, 2026-10-28).** All are unreleased. The reader accepts all three identifiers
> and reads each under its own grammar; [v2](#v2-implemented-for-v084) adds `files`, `sections` and
> `structured`; [v3](#v3-implemented-for-v085) adds the part-level `when` condition and the media
> Prompt. v1 remains the compatibility contract for every Package written before v2. A breaking
> change bumps the identifier; additive, backward-compatible changes are made only when the reader
> can be extended without guessing.
> **Audience:** the person preparing a Package, and the coding agent implementing the reader.
> **Related:** [ARCHITECTURE → Framework evolution](ARCHITECTURE.md#framework-evolution--v08x),
> [v0.8.2 plan](iterations/v0.8.2-plan.md), [v0.8.4 design](iterations/v0.8.4.md),
> [v0.8.5 design](iterations/v0.8.5.md).

## Why a protocol

A Package is how Listea receives **questions** from outside the app. It is not a folder of files
with a naming convention, and it is not a second database. It is one declared envelope — a
manifest, a revision, a set of members — that the app can validate before it writes anything.

The envelope exists so that **the shape of an incoming question is not the shape of a workflow**.
A dating profile, a vocabulary card, a questionnaire, and (conceptually) a folder of pictures to
tag all arrive with the same outer structure. What differs is the content kind inside it and the
response contract it asks for. The app can carry a kind it cannot draw; it says so, and it does not
pretend the question is reviewable.

## Vocabulary (v0.8.2)

These words are used precisely in this document and in the code that implements it.

| Term | Meaning | Not |
|---|---|---|
| **Upstream** | Registered access to material, and therefore to the question setter's work: a grant, a locator, a name, provenance. | A Package, a Task, a review context |
| **Prompt** | One question setter's unit: a statement, a response contract, and the materials it needs. A Package stores Prompts. | A Task, a TaskPart, an Asset, a List, a review event |
| **Decision-maker intent** | A Todo: which Prompts a person takes on, under which upstream, in what scope and order, printed how, and answered with which tool. | The question setter's intent; a List; a Package |
| **Task** | A decision-maker's undertaking to answer one or more Prompts in one Todo, and the durable record of that undertaking and its judgments. A v1 Task answers one Prompt; a v2 Task may hold 1..N **TaskParts**. | A file, an image, a Prompt, a List membership |
| **Asset** | Material a Prompt **presents**: a page the person looks at. In a Task, it belongs to one TaskPart and is never the decision unit. | A Resource, a prompt body, a TaskPart |
| **Resource** | Material a Prompt **depends on** but does not present: a photo beside a profile, pronunciation audio, a reference image. | An Asset, a page, a judgment target |
| **Viewer** | The renderer for a Prompt family. It draws the Prompt, resolves Resources, and emits named intents. | A template, a completion policy, a database writer |
| **Template** | The answer tool: which intents it accepts, when it commits, whether a commit may be revised, and what a commit does to Task state. | A viewer, a Prompt, a workflow type |
| **Import** | The print shop: validate the Package, apply the Todo's printing conventions, and create Tasks. | Rendering, answering, exporting |
| **Package** | The protocol envelope: a manifest, local members, keys, a revision. Import provenance, not a durable owner. | A Task, a Todo, a List, a stored entity |

### Prompt and Task are different things

A `.listea` Package does not store Tasks. It stores **Prompts**. A Task appears when a
decision-maker — a Todo — takes a Prompt on. The same Prompt may be taken on by two Todos, which
is two Tasks with two independent judgments. Re-importing the same Package into the same Todo is
the same Task, reconciled by key.

**Since `0.8.4-preview`:** a Task may hold several Prompts as **TaskParts**, and the taker's
**Composition** decides the grouping — one Prompt per Task, or all Prompts tagged with a Section in
one Task. The same Prompt taken on by two Todos is still two TaskParts with independent judgments.
In a multi-part Task the Task reconciles on `(todoId, packageKey, compositionKey)` and each Prompt
is a part within it; a one-Prompt Task keeps today's behaviour. The protocol still declares no
Tasks and no composition.

An image is not a Task. "Look at this image and tag it" is a degenerate Prompt: it has materials
but no declared constraint. The decision-maker's act of tagging it is the Task. Today's picture
classification and PDF annotation are conceptually this shape — a free-form Prompt — although
v0.8.x does not migrate them onto the protocol. See *Degenerate Prompts* below.

### Decision-maker intent and question-setter intent

There are two intentions in every run, and they must not be confused:

- the **question setter** (upstream) decides what is asked, what options exist, and what the
  materials are;
- the **decision-maker** (the Todo) decides which questions to take on, from which upstream, in
  what scope and order, with what printing convention, and with which answer tool.

A List is a review context over the Tasks the decision-maker has taken on. It is not a third
intent: it orders and groups, and owns no template and no question.

### Viewers and templates are compatible, not identical

The relationship is a **compatibility**, not a one-to-one mapping:

- one Viewer may serve several Prompt kinds, as an image viewer serves JPEG and PNG;
- one Prompt kind may be served by more than one Viewer, as a questionnaire viewer may render a
  single-choice Prompt that a dating viewer also knows how to draw;
- one Template may answer several response contracts, as a tag template works on both images and
  videos;
- one response contract may be answered by several Templates.

Resolution is by declared capability, never by a naming convention. A Viewer declares the kinds,
schemas, and response contracts it understands. A Template declares the intents it accepts and the
commit policy it enforces.

### A working analogy

The exam-and-print-shop analogy is useful for explaining the model to a person. It is **not** a
source of rules; the sections below are authoritative.

| Listea | Analogy | Why it helps |
|---|---|---|
| Upstream | the source of the exam paper | where the questions come from |
| Package | the sealed exam paper: cover sheet, questions, figures | the same envelope for every subject |
| Prompt | one question: stem, choices, figures | the question setter's unit |
| Import | the print shop: choose binding (single/double-sided), check the paper, print | file-as-Task, folder-as-Task, Package are printing conventions |
| Viewer | the printer for a question type | different question types print differently |
| Template | the candidate's pen, pencil, eraser | the answer tool, including whether a mark can be erased |
| Todo | the candidate's decision to sit this paper, these questions, in this order | the decision-maker's intent |
| Task | the sitting: actually answering one or more questions | the verb, not the paper |
| TaskPart (v0.8.4) | one question of that sitting | the decision unit inside the Task |

## Package layout

A Package is a directory. The directory that contains `.listea/manifest.json` is the Package root.

```text
dating-demo/
├── .listea/
│   └── manifest.json
├── prompts/
│   ├── alice.json
│   └── bob.json
└── media/
    ├── alice.jpg
    └── bob.jpg
```

Rules:

- The manifest filename is exactly `.listea/manifest.json`, lowercase.
- All member references are relative to the Package root, use `/` as the separator, and must
  remain inside the Package root after resolution.
- Members are ordinary local files under the granted directory. **Archives, archives-within-
  archives, embedded binary blobs, remote URLs, and code are not supported in v1.** A `.zip` is
  rejected before extraction.
- A Package may contain zero Prompts and zero Resources; that is a valid, empty Package.
- A Todo's selected scope may contain several Packages. Each is planned and diagnosed
  independently. A failed Package is never interpreted as a list of deleted Tasks.

Discovery: a Package root is a directory containing `.listea/manifest.json`. The Package importer
searches the selected scope for those directories up to the discovery depth limit. A directory
without `.listea` is not a Package; a file named `manifest.json` elsewhere is not a manifest.

**The selected scope may itself be the Package root.** A Package found at the top of the scope adds
no path segment: the stored scope path is the scope's own path, with no trailing separator, and its
members stay Package-relative (`media/alice.png`, never `dating/media/alice.png` or
`dating//media/alice.png`). A Package in a subdirectory stores the joined path (`dating/nested`) and
still keeps each member relative to its own root.

## Manifest

The manifest is the only required file. It is strict JSON (UTF-8, no BOM, no comments, no trailing
commas, no duplicate object keys).

```json
{
  "protocol": "listea.package/1",
  "packageKey": "dating-demo",
  "revision": 1,
  "title": "Dating demo",
  "prompts": [
    {
      "promptKey": "alice",
      "kind": "profile",
      "schema": 1,
      "response": "choice.single",
      "title": "Alice, 28",
      "description": "prompts/alice.json",
      "resources": [
        { "resourceKey": "alice-photo", "required": true }
      ]
    }
  ],
  "resources": [
    {
      "resourceKey": "alice-photo",
      "kind": "image",
      "member": "media/alice.jpg"
    }
  ]
}
```

### Top-level fields

| Field | Required | Type | Meaning |
|---|---|---|---|
| `protocol` | yes | string | Exactly `listea.package/1`. Any other value rejects the whole Package. |
| `packageKey` | yes | key | Stable identity of this Package under its Todo. |
| `revision` | yes | integer ≥ 1 | Monotonic revision of the Package's content. See *Revision*. |
| `title` | yes | string | Short label shown for the Package scope. 1–200 characters. |
| `prompts` | yes | array | 0–2,000 Prompt entries. Order is the default catalog order. |
| `resources` | no | array | 0–5,000 Resource declarations. Absent means none. |

Unknown top-level fields are rejected in v1. A future protocol version that adds fields gets a new
`protocol` identifier; an older app rejects the whole Package rather than silently ignoring a field
that may change meaning.

### Prompt entry

Every Prompt entry has the same outer shape, whatever its kind. Fields:

| Field | Required | Type | Meaning |
|---|---|---|---|
| `promptKey` | yes | key | Stable identity under the Package. |
| `kind` | yes | key | Content family of the description. Open vocabulary; e.g. `profile`, `prompt-card`, `form`. |
| `schema` | yes | integer ≥ 1 | Version of the description's schema. The Viewer declares which range it understands. |
| `response` | yes | string | Response contract: `choice.single`, `choice.multiple`, or `freeform` in v1. |
| `title` | yes | string | Short queue/page label. 1–200 characters. The long statement lives in the description. |
| `description` | yes | member reference | A `.json` file with the Prompt body. |
| `assets` | no | array | 0–N presented files. Absent means none. Order is page order. |
| `resources` | no | array | 0–N Resource uses. Absent means none. |

An Asset reference:

```json
{ "member": "files/report.pdf", "name": "report.pdf" }
```

`member` is required and must resolve to a real local file. `name` is an optional display name;
when absent the file name is used. An Asset is a **presented** file: it becomes a page and appears
in the Task's `assets` and in export bodies that carry assets. A Resource is not a page.

A Resource use:

```json
{ "resourceKey": "alice-photo", "required": true }
```

`resourceKey` must name a declaration in the top-level `resources` array. `required` defaults to
`true`; a missing required Resource blocks submission, a missing optional one disables the
affordance that needs it. Requiredness is per Prompt use: one file may be required for one Prompt
and optional for another.

### Resource declaration

```json
{ "resourceKey": "alice-photo", "kind": "image", "member": "media/alice.jpg" }
```

| Field | Required | Meaning |
|---|---|---|
| `resourceKey` | yes | Stable identity under the Package. |
| `kind` | yes | A content hint (`image`, `audio`, `video`, `pdf`, `other`). It helps a Viewer pick an adapter; it does not select the Viewer. |
| `member` | yes | A member reference to a real local file. |

A Resource declaration may be unused in one revision; it is still valid. An unresolved use is a
diagnostic and makes the using Prompt unavailable until the declaration exists.

**Storage note (v0.8.2, deviates from the original plan).** `package_resources.rootUri` holds the
**scanned document URI of the Resource file itself** (`…/media/alice.jpg`), not the Package tree's
root URI. The plan described one root per Package; storing the document URI for each Resource is
what lets a Viewer open the photo directly, without re-walking the tree on every Content page. The
column's name is unchanged; its value is narrower. `relativePath` still carries the Package-relative
member path, so the pair describes both where the file lives and where it was declared.

## Keys

`packageKey`, `promptKey`, and `resourceKey` match:

```text
[a-z0-9][a-z0-9._-]{0,63}
```

They are case-sensitive and must be unique in their scope:

- one `packageKey` per Package; two Packages with the same key under one Todo are a conflict;
- one `promptKey` per Package;
- one `resourceKey` per Package.

A key is not a path, a filename, or a display name. It is the identity import reconciliation uses:
`(todoId, packageKey, promptKey)` names a Task, and `(todoId, packageKey, resourceKey)` names a
Resource. Changing a key creates a new Task; changing a title does not. Keys are internal addresses
and are never shown where a person reads provenance: the List, the queue guide and the Info sheet
show the Package's manifest `title` instead of the synthetic `<packageKey>/<promptKey>` catalog key
the importer stores in the Task's path columns. Since v0.8.2f the same rule applies everywhere
provenance is displayed.

**Since `0.8.4-preview`:** the Task's reconciliation key is
`(todoId, packageKey, compositionKey)`, and `promptKey` identifies a **TaskPart** within the Task.
For the default one-Prompt composition the composition key is `prompt:<promptKey>`, so renaming the
Prompt is a new Task exactly as before. In a `section:<sectionKey>` composition a renamed
`promptKey` detaches the old part and mints a new one while the Task id survives; the old part's
response and history are kept. A `sectionKey` follows the same key grammar and uniqueness as the
other keys.

## Prompt description

The `description` member is a JSON object. Its **outer envelope is fixed by this protocol only for
the response contract**; the rest of the body belongs to the `kind`/`schema` and is interpreted by
the Viewer for that capability.

Common fields for v1:

| Field | Required | Meaning |
|---|---|---|
| `stem` | for `choice.*` | The question text, 1–10,000 characters. |
| `options` | for `choice.*` | Ordered array of `{ "id": key, "label": string }`. At least two, at most 100. `id` unique within the Prompt. |
| other fields | no | Defined by the `kind`/`schema` and the Viewer. Core never branches on them. |

For `freeform`, `options` must be absent or empty; the Template's controls define what can be
recorded.

A dating-profile description:

```json
{
  "stem": "Alice, 28 — likes hiking and old films.",
  "profile": {
    "name": "Alice",
    "age": 28,
    "bio": "Likes hiking and old films."
  },
  "photo": "alice-photo",
  "options": [
    { "id": "like", "label": "Like" },
    { "id": "dislike", "label": "Dislike" }
  ]
}
```

`profile` and `photo` are the `profile` schema's business. The protocol only knows that the Prompt
declares its options and that `alice-photo` is a Resource use; the Viewer for `profile@1` knows how
to draw the rest.

A vocabulary description (`kind` `vocabulary`, `schema` 1):

```json
{
  "stem": "Choose the meaning of the word.",
  "word": "serendipity",
  "phonetic": "/ˌserənˈdɪpɪti/",
  "definition": "the occurrence of events by chance in a happy way",
  "example": "a fortunate stroke of serendipity",
  "audio": "serendipity-audio",
  "answer": "b",
  "options": [
    { "id": "c", "label": "a formal apology" },
    { "id": "b", "label": "a happy accident" },
    { "id": "a", "label": "a long journey" }
  ]
}
```

The `vocabulary`/1 Viewer's own fields:

| Field | Required | Meaning |
|---|---|---|
| `word` | yes | The word being drilled, 1–200 characters. |
| `phonetic` | no | Pronunciation line, 1–200 characters. |
| `definition` | no | Meaning, 1–1,000 characters. |
| `example` | no | Usage example, 1–1,000 characters. |
| `audio` | no | A Resource key naming an `audio` Resource. It is played from the Resource, not from an Asset. |
| `answer` | no | The id of one declared option. A read-only card may reveal it; the recorded event is still the option the person chose. |

Unknown fields are refused by this Viewer, exactly as `profile`/1 refuses them. A body it cannot
read is a Prompt this build cannot draw, not a card drawn from a guess.

**The declared option order is the contract.** The array order is what the card draws; nothing in
the importer, the store or the renderer sorts or shuffles it. The fixture's `c, b, a` order exists to
catch a layer that does. Any future shuffle must be an explicit body or template option, never a
default.

The pronunciation Resource is declared once at Package level and may be used by more than one
Prompt:

```json
{ "resourceKey": "serendipity-audio", "kind": "audio", "member": "media/serendipity.mp3" }
```

It is a Resource, so it is neither an Asset nor a page of the Task. A `required: true` use that is
missing blocks submission; an optional missing one only disables the Listen control.

## Response contracts (v1)

The response contract says what a valid answer looks like. It is part of the Prompt, not of the
Viewer, because it is what the question setter asked for.

| `response` | Valid answer | Viewer intent |
|---|---|---|
| `choice.single` | exactly one option id from the Prompt | `choice.select` |
| `choice.multiple` | one or more option ids, no duplicates | one `choice.select` per selected id, in payload order |
| `freeform` | no declared option set; the Template decides | template-defined |

`choice.single` is the only contract implemented end to end. It serves both the dating `profile`/1
and the vocabulary `vocabulary`/1 Viewer, because the contract is what the question setter asked
for and both cards emit the same `choice.select` intent. `choice.multiple` is a declared protocol
value and is rejected as unsupported until a Viewer and Template for it exist; `freeform` is
reserved for the degenerate Prompts described below.

**`structured` belongs to v2**, and a v1 manifest declaring it is rejected as an unsupported
contract. In v2 the Prompt body declares a field set, the Viewer for `kind`/`schema` interprets it,
and the protocol validates the envelope rather than the fields. A structured response is still one
TaskPart; its fields are internal answers, not parts. See
[v2 implemented for v0.8.4](#v2-implemented-for-v084).

A choice event's stored value is:

```text
<optionId>\t<label>
```

`label` is the option's label at the time of the answer, with tabs and newlines replaced by spaces
and bounded to 200 characters. The id is the machine identity; the label is the human interpretation
that survives a later Package revision. Both are stored in the response line and in
`review_event_answers`, using the same grammar.

## Commit and revisability

Two independent policies live on the Template, not on the Viewer and not on the Prompt.

**Commit** says how an answer becomes a judgment:

| `commit` | Meaning | Example |
|---|---|---|
| `immediate:<intent>` | The intent that carries the answer is itself the commit. | Tapping Like or Dislike commits at once. |
| `confirm:control:<id>` | The intent selects; a template control named `<id>` commits. | Choose a point, then press Submit. |
| `confirm:intent:<intent>` | The intent selects; another declared intent confirms. | Select a card, then swipe left to accept. |

**Revisability** says whether a later commit may supersede the current answer:

| `revisable` | Meaning | Example |
|---|---|---|
| `true` | A later valid commit is accepted; a new event is appended and the latest valid answer is current. History is not rewritten. | Dating: Like, then change to Dislike after reconsidering. |
| `false` | After the first valid commit, further commits are refused. The first event stands. | Vocabulary: a recall answer is final. |

The two axes are independent. The three cases a product usually needs are:

| Intended behaviour | `commit` | `revisable` |
|---|---|---|
| click once, irreversible | `immediate:choice.select` | `false` |
| click again to overwrite | `immediate:choice.select` | `true` |
| choose, then confirm | `confirm:control:submit` (or `confirm:intent:...`) | either |

A refused commit writes neither an event nor a state change. An accepted commit appends exactly
one primary event and updates the permitted Task state, in the same transaction.

## Revision and reconciliation

`revision` is a positive integer that the producer increments whenever any member the manifest
references changes. The app computes a fingerprint over the canonical manifest and the referenced
`description` files (not over Asset or Resource bytes in v1). The pair `(revision, fingerprint)` is
stored in the Package checkpoint.

On re-import of the same `(todoId, packageKey)`:

| Observed | Meaning | Action |
|---|---|---|
| same revision, same fingerprint | unchanged Package | no-op; refresh availability and attachments only |
| same revision, different fingerprint | content changed without a revision bump | diagnose `revision-conflict`; do **not** overwrite the stored payload |
| higher revision, different fingerprint | a new Package revision | reconcile by key; keep Task ids and judgments; update payloads, Assets, Resources |
| lower revision | stale input | diagnose `stale-revision`; do not overwrite |
| new key | a new Prompt | create a new Task |
| key absent | the Prompt is gone from this revision | do not delete the Task or its history; mark the Task unavailable / detached from the Package |

Absence is never deletion. A Task's identity, its judgments, and its List memberships survive a
Package that stops mentioning it, exactly as a missing file does not delete a Task.

Two Todos over the same Package produce independent Tasks: the reconciliation key includes the
Todo id.

### Deleting a Package Todo

Deleting a Todo removes everything that Todo held: its Tasks, their payloads and attachments, the
Package checkpoint, the Prompt links, and the Resource declarations. The last three name the Todo
directly and cannot outlive it — a Prompt link survives a revision that stopped mentioning the
Prompt, so it may still be present when the Todo goes — and the delete clears them before the
Todo's Source row, in the same transaction. The Package in the upstream folder is untouched: the
same folder imports fresh as a new Todo, with new Task ids and an empty checkpoint.

## Limits

The reader enforces these before writing anything. A value over a limit rejects the Package with
`limit-exceeded`; it is never partially parsed.

| Limit | v1 value |
|---|---|
| Manifest size | 256 KiB |
| Description size, per Prompt | 64 KiB |
| Prompts per Package | 2,000 |
| Resources per Package | 5,000 |
| Asset uses per Prompt | 200 |
| Resource uses per Prompt | 50 |
| Options per Prompt | 100 |
| Member path length | 512 characters |
| String value length | 10,000 characters (labels ≤ 200) |
| JSON nesting depth | 16 |
| Discovery depth from the selected scope | 4 |
| Packages per Todo scope | 100 |

Asset and Resource **file bytes** are not read at import. Their presence and type are checked where
they are resolved; a large photo does not make the manifest large. A member that is a directory, a
device file, or a symlink resolving outside the Package root is rejected.

## Rejection rules and diagnostics

A diagnostic is data: it has a `scope` (Package, Prompt, Resource, or member), a stable `code`, and
a human message. The import result reports them; the app does not invent a Task for a record it
could not read.

**Reject the whole Package, before any write:**

- unknown or missing `protocol`;
- malformed JSON, duplicate object key, invalid UTF-8, BOM;
- missing or invalid `packageKey`, `revision`, `title`, `prompts`;
- a duplicate key;
- an unsafe member reference (absolute, `..`, backslash, URL, symlink escape, out-of-grant);
- an archive or embedded binary input;
- a limit exceeded;
- a Package-level envelope that is not an object.

**Diagnose one Prompt or Resource, keep the valid rest of the Package:**

- an unknown `kind`/`schema` — the Prompt is imported as **unavailable**, not as a broken Task;
- a `response` this build does not implement;
- a missing or malformed `description`;
- an unresolved Resource use, or a declared Resource whose member is missing;
- duplicate option ids, an invalid option label, or an option set that does not match the response
  contract;
- an Asset member that is missing or unsafe.

A Prompt with an unsupported kind/schema is still stored, with its payload, so that a later build
with a compatible Viewer can make it reviewable without re-import. It is **not** presented as an
empty page and it is **not** eligible for review.

**Runtime, after a valid import:**

- a member disappears — availability state, not a malformed Package, and not a deletion;
- a required Resource is missing at submission — the write boundary refuses the commit;
- a payload revision no longer matches the one the review opened with — the write boundary refuses
  the commit.

## Encoding and canonicalization

- Files are UTF-8 without a BOM.
- JSON is strict: objects and arrays only where declared; no comments, trailing commas, `NaN`, or
  `Infinity`; duplicate keys rejected.
- Numbers used as keys or versions are integers; `1.0` is not `1`.
- For hashing, the app canonicalizes: object keys sorted by code point, no insignificant
  whitespace, UTF-8, and the same escape rules. The fingerprint is a SHA-256 of that canonical
  form plus the canonical description members.
- Display strings are preserved as written after NFC normalization; keys are ASCII by the key
  pattern above.

## Degenerate Prompts

A Prompt with materials but no declared constraint is **freeform**: picture classification and PDF
annotation are the examples. It has Assets (or Resources) and `response: "freeform"`. The answer
tool — the tag template or the PDF mark template — defines what can be recorded. There is no
`options` array and no single expected answer.

v0.8.2 does not migrate those workflows onto the Package protocol. They stay on the file
importer, where they already work. The concept is recorded here because it is the reason the
protocol has a `freeform` response contract at all, and because a later round may move them into
the same envelope without changing what a Task is.

## v2 implemented for v0.8.4

> **Status: implemented in `0.8.4-preview` (versionCode 804, 2026-10-27), accepted 2026-10-28 and
> pushed; no release published.** The reader accepts `listea.package/2` under the grammar below and
> keeps reading v1 under its own. The design
> is [v0.8.4](iterations/v0.8.4.md); the implementation plan is
> [v0.8.4-plan.md](iterations/v0.8.4-plan.md); the device acceptance is in
> [the design check](iterations/v0.8.4-design-check.md#gate-7--implementation-acceptance).

`listea.package/2` is the same envelope with three changes: presented files are named `files`, a
Package may declare **Sections**, and a Prompt may ask for a **structured** response. It still
declares **Prompts, not Tasks**, and it has no composition field.

| v1 | v2 | Why |
|---|---|---|
| `protocol: "listea.package/1"` | `protocol: "listea.package/2"` | a breaking grammar change |
| `prompts[].assets` | `prompts[].files` | the setter's word for presented material; the internal term remains **Asset** |
| — | `prompts[].section` + top-level `sections[]` | the setter may tag questions; the taker still composes Tasks |
| `choice.single` / `choice.multiple` / `freeform` | adds `structured` | one Prompt may produce a structured answer |

### Manifest

```json
{
  "protocol": "listea.package/2",
  "packageKey": "drill-1",
  "revision": 3,
  "title": "Drill 1",
  "sections": [
    { "sectionKey": "drill-1", "title": "Drill 1", "order": 1 }
  ],
  "prompts": [
    {
      "promptKey": "q1",
      "kind": "vocabulary",
      "schema": 1,
      "response": "choice.single",
      "title": "Word 1",
      "description": "prompts/q1.json",
      "section": "drill-1",
      "files": [
        { "member": "media/q1.png", "name": "q1.png" }
      ]
    },
    {
      "promptKey": "meal-tree",
      "kind": "form",
      "schema": 1,
      "response": "structured",
      "title": "Meal",
      "description": "prompts/meal.json",
      "section": "drill-1"
    }
  ]
}
```

### Top-level additions

| Field | Required | Type | Meaning |
|---|---|---|---|
| `sections` | no | array | 0–2,000 Section declarations. Absent means no Prompt is tagged. |

A Section entry:

```json
{ "sectionKey": "drill-1", "title": "Drill 1", "order": 1 }
```

| Field | Required | Meaning |
|---|---|---|
| `sectionKey` | yes | Stable key under the Package; same grammar and uniqueness as other keys. |
| `title` | yes | Display label, 1–200 characters. |
| `order` | no | Integer ≥ 0; a registry/display hint. Member order is always the `prompts[]` order. |

### Section semantics

- A Section is a **declared tag**, not a parent, not a Task and not a required grouping. A Prompt has
  at most one `section` tag.
- A Prompt's `section` must name an entry in `sections[]`. An unknown tag rejects the whole
  Package, because it is a manifest contradiction, not a content kind this reader cannot draw.
- Duplicate `sectionKey` values reject the Package.
- An untagged Prompt is valid. It can still become its own Task (`prompt:<promptKey>`).
- A Section with no tagged Prompts is a valid declaration; selecting it as a composition is an
  import diagnostic (`section-empty`) and mints no Task.
- The taker's **Composition** decides whether a Section's members become one Task with N TaskParts
  or N Tasks with one part each. The manifest never says which. Requiredness is likewise the
  taker's; a Prompt does not declare Task requiredness.

### Structured response

`response: "structured"` says the Prompt's legal answer is a structured object, not one option. The
field set lives in the Prompt body and belongs to the `kind`/`schema`; the protocol validates the
envelope and the declared contract only. The Viewer interprets the fields, their visibility and
their validation.

`form`/1 is the first structured Viewer, shipped in `0.8.4-preview`. Its body is illustrative of
the Viewer's own contract, not a protocol rule:

```json
{
  "title": "Meal",
  "fields": [
    {
      "key": "ate",
      "type": "choice.single",
      "label": "Did you eat?",
      "options": [
        { "id": "yes", "label": "Yes" },
        { "id": "no", "label": "No" }
      ]
    },
    {
      "key": "what",
      "type": "text",
      "label": "What did you eat?",
      "when": { "field": "ate", "equals": "yes" }
    }
  ]
}
```

- Conditions live **inside the Prompt body only** in v2. There are no manifest-level conditions and
  no cross-Prompt conditions in v0.8.4. A later Prompt depending on an earlier answer is a follow-up
  Task from the producer, or a v3 Package's `when` (see [v3](#v3-implemented-for-v085)).
- A structured response is one TaskPart. Its fields are internal answers, not parts, Sections or
  Tasks.
- A body this build's Viewer cannot validate is a Prompt this build cannot draw. It is imported
  unavailable, like an unknown kind/schema; it is not presented as a broken card.

### Compatibility

- The v0.8.4 reader accepts **both** v1 and v2. A v1 Package imports with the one-Prompt
  composition (`prompt:<promptKey>`) and its `assets` become Assets; it has no Sections and no
  structured contract.
- A v1 reader rejects v2 whole. It does not attempt to read `files` as `assets` or to ignore
  `sections`.
- Within one manifest the grammar is either v1 or v2. `assets` in a v2 manifest and `files` in a v1
  manifest are unknown fields and reject the Package. `structured` in v1 is an unsupported
  response contract.
- A v2 Package still declares no `tasks`, `composition`, `task` or manifest-level `when`; such a
  field is unknown and rejects the Package.

### Limits and diagnostics

The v1 limits apply to the same records, with `files` replacing `assets` and `structured` counted as
a description of the same size and depth. `sections` is bounded at 2,000.

New stable diagnostic codes:

| Code | Scope | Effect |
|---|---|---|
| `section-duplicate` | Package | reject |
| `section-unknown` | Prompt | reject the Package (the tag names no registry entry) |
| `section-invalid` | Package | reject (invalid key/title/order) |
| `section-empty` | Section | diagnose; selecting it produces no Task |
| `files-in-v1` / `assets-in-v2` | Package | reject as an unknown field |
| `structured-body-invalid` | Prompt | import unavailable; no card, no crash |
| `structured-unsupported` | Prompt | import unavailable; no Template/Viewer for the contract |

The protocol does not validate the structured field set itself; that is the Viewer's capability. A
structured Prompt with no compatible Viewer is `structured-unsupported`, not a malformed Package.

### What v2 does not do

- It does not declare Tasks or compositions.
- It does not make a Section a Task, a parent or a required grouping.
- It does not add cross-Prompt conditions.
- It does not add per-part requiredness or per-part revisability to the manifest.
- It does not introduce archives, embedded blobs or remote members; v1's member rules and
  rejections still apply.
- It does not change Asset or Resource meaning. `files` are Assets; `resources` are still
  dependencies and not pages.

## v3 implemented for v0.8.5

> **Status: implemented in `0.8.5-preview` (versionCode 805, 2026-10-28), unreleased.** The reader
> accepts `listea.package/3` under the grammar below and keeps reading v1 and v2 under their own.
> The design is [v0.8.5](iterations/v0.8.5.md); the implementation plan is
> [v0.8.5-plan.md](iterations/v0.8.5-plan.md).

`listea.package/3` is the same envelope as v2 with two additions: a Prompt may declare **when it
applies**, as a condition on an earlier Prompt, and a Prompt may be a **media** Prompt whose answer
is the files themselves. It still declares Prompts, not Tasks, and it still has no composition
field.

| v2 | v3 | Why |
|---|---|---|
| `protocol: "listea.package/2"` | `protocol: "listea.package/3"` | a breaking grammar change |
| — | `prompts[].when` | a later Prompt's applicability may depend on an earlier Prompt's answer |
| — | `kind: "media"` with `response: "freeform"` | a decision whose record is the files, marks and actions |

### Manifest

```json
{
  "protocol": "listea.package/3",
  "packageKey": "meal-1",
  "revision": 1,
  "title": "Lunch",
  "sections": [
    { "sectionKey": "session", "title": "Lunch", "order": 1 }
  ],
  "prompts": [
    {
      "promptKey": "gate",
      "kind": "form",
      "schema": 1,
      "response": "structured",
      "title": "Did you eat?",
      "description": "prompts/gate.json",
      "section": "session"
    },
    {
      "promptKey": "menu",
      "kind": "media",
      "schema": 1,
      "response": "freeform",
      "title": "The menu",
      "description": "prompts/menu.json",
      "files": [{ "name": "menu.pdf" }],
      "section": "session",
      "when": { "prompt": "gate", "field": "choice", "equals": "yes" }
    }
  ]
}
```

`prompts/menu.json` is `{"stem": "Read the menu, then press Done."}`.

### The condition

`when` is one object:

| Field | Required | Meaning |
|---|---|---|
| `prompt` | yes | An **earlier** Prompt's `promptKey`, in the same Task. |
| `field` | yes | A named answer the source exposes. |
| `equals` | one of | A single string value. |
| `in` | one of | 1–50 string values. |

- Exactly one of `equals`/`in`. Values are strings within the existing key/string limits. A value
  that names no option is not an error; it simply never matches.
- The source must be **earlier** in `prompts[]` (acyclic by construction) and **required** in the
  chosen composition.
- The named field is checked through the source Viewer's **answer-field capability**, not by Core
  parsing a body: a `form` Prompt exposes its field keys, a `choice.single` Prompt exposes `choice`,
  and a `media` Prompt exposes none.
- The condition is the setter's declaration of **applicability**. It does not name a Task, a
  composition or a requiredness; the taker still decides those. A condition whose source lands in
  another Task makes the target unavailable (`condition-source-outside-task`), and one whose source a
  revision removed detaches the target (`condition-source-detached`).
- The manifest **fingerprint includes `when`**, so changing a condition is a content change and a
  re-import notices it.

### The media Prompt

- `kind: "media"`, schema 1, `response: "freeform"`, one body `{"stem": "…"}`, at least one file,
  and no options. The stem is required and bounded; the Viewer owns its body shape, the protocol
  owns the envelope.
- Its answer is **answered-empty**: pressing the part judgment's Done commits the part with no typed
  result. The files, their marks and their actions are the record.
- It is a condition **target**, never a source.
- `freeform` is accepted only for media; any other freeform kind remains `response-unsupported`.

### Compatibility

- v1 and v2 remain readable under their own grammars. A v3-only word in a v2 manifest is an error:
  `when` is `when-in-v2`, `kind: "media"` is `media-in-v2`; a freeform body under a pre-v3
  protocol is the existing `response-unsupported`. A v3 manifest that uses `assets` is
  `assets-in-v3`. The v0.8.4 reader rejects a v3 manifest whole as an unknown protocol; the v3
  reader still accepts v1 and v2.
- A v3 manifest still declares no `tasks`, `composition` or `task`; such a field is unknown and
  rejects the Package.

### Limits and diagnostics

The v1/v2 limits apply, with the media body counted as a description of the same size and depth,
and `in` bounded at 50 values.

| Code | Scope | Effect |
|---|---|---|
| `condition-invalid` | Prompt | reject (not an object; unknown keys; both or neither `equals`/`in`; empty or >50 values; bad key) |
| `condition-source-unknown` | Prompt | reject (`prompt` names no Prompt) |
| `condition-source-not-earlier` | Prompt | reject (same or later index) |
| `condition-source-optional` | Prompt | reject when the source is not required in the composition |
| `condition-field-unknown` | Prompt | reject (the source Viewer does not expose the field, or its body is unreadable) |
| `condition-source-outside-task` | Task/part | import the target unavailable; the report names it |
| `condition-source-detached` | Task/part | import the target unavailable when a revision removed its source |
| `when-in-v2` / `media-in-v2` / `assets-in-v3` | Protocol | reject as an unknown field |
| `media-file-missing` | Prompt | reject (a media Prompt declares no files) |
| `media-body-invalid` | Prompt | import unavailable (the Viewer cannot validate the stem) |

### What v3 does not do

- It does not declare Tasks, compositions or requiredness.
- It does not add cross-Task conditions, compound conditions over several sources, or a condition
  on a media answer.
- It does not turn the media Prompt into a new file type; its files are Assets like any other.
- It does not add archives, embedded blobs or remote members.

## Relationship to the current app

- **File-as-Task and folder-as-Task remain.** They are printing conventions, not Packages. A file
  Task is a degenerate Prompt created by the importer rather than declared by a Package. Under
  either, the importer matches files by **format**: pictures, videos and PDFs become Tasks, and a
  file no Viewer can open — a Prompt body, a manifest, a text note — is skipped and counted
  (v0.8.2f). Match by format is the file importer's rule; Package Prompts are matched by the
  manifest, and their `media` members stay Resources.
- **A Package is still a directory, not a file.** `.listea/`, `prompts/` and `media/` are visible in
  the folder tree, and selecting the folder is how a Package is opened. The intended envelope is
  one `dating.listea` archive, zip-like, with the same members inside; v0.8.2f does not implement
  it, because extraction and selection change where a Package begins rather than fix its edges,
  and [archives are rejected](#package-layout) by `listea.package/1`.
- **Creation currently fuses printing and tool choice.** One Todo dialog picks the importer (the
  printing convention) and attaches the Template (the answer tool). The model keeps them distinct —
  a Package and a Viewer never choose a Template — so a later round may split the two steps without
  changing this protocol.
- **Add-ons remain unchanged.** An Add-on is a questionnaire about a Task as a whole, not a
  Prompt; its answers are current state, not a primary event.
- **Media and PDF behavior remain unchanged.** Their Templates and Viewers keep their current
  semantics; a Package Prompt may present the same files as Assets.
- **The Package is not a table.** There is no `packages` entity. Package key, protocol, revision,
  fingerprint, and locator are import provenance on the Todo/Task side.

## Worked example

`dating-demo/.listea/manifest.json`:

```json
{
  "protocol": "listea.package/1",
  "packageKey": "dating-demo",
  "revision": 1,
  "title": "Dating demo",
  "prompts": [
    {
      "promptKey": "alice",
      "kind": "profile",
      "schema": 1,
      "response": "choice.single",
      "title": "Alice, 28",
      "description": "prompts/alice.json",
      "resources": [
        { "resourceKey": "alice-photo", "required": true }
      ]
    },
    {
      "promptKey": "bob",
      "kind": "profile",
      "schema": 1,
      "response": "choice.single",
      "title": "Bob, 31",
      "description": "prompts/bob.json",
      "resources": [
        { "resourceKey": "bob-photo", "required": true }
      ]
    }
  ],
  "resources": [
    { "resourceKey": "alice-photo", "kind": "image", "member": "media/alice.jpg" },
    { "resourceKey": "bob-photo", "kind": "image", "member": "media/bob.jpg" }
  ]
}
```

`prompts/alice.json`:

```json
{
  "stem": "Alice, 28 — likes hiking and old films.",
  "profile": {
    "name": "Alice",
    "age": 28,
    "bio": "Likes hiking and old films."
  },
  "photo": "alice-photo",
  "options": [
    { "id": "like", "label": "Like" },
    { "id": "dislike", "label": "Dislike" }
  ]
}
```

Resolution in this build:

- the `profile@1` Viewer understands the description and draws the profile and photo;
- the single-choice Template accepts `choice.select`, commits immediately, and is revisable;
- selecting Like records one `choice.select` event with value `like\tLike`, completes the Task, and
  advances;
- the photo is a Resource: it is not a page, not an Asset, and not part of the Asset count;
- the event retains the option id and label, and the Prompt's kind/schema/revision — not the
  profile body. The body is content, not a judgment record.

The same manifest shape with one `profile` and one `vocabulary` Prompt is
[`fixtures/mixed`](assets/fixtures/mixed/README.md). It imports as two Tasks, each resolved by its
own Viewer, and both are answerable through the single-choice template.

## Versioning

- A breaking grammar change updates `protocol` to the next identifier; the app supports the versions
  it ships readers for and rejects the rest whole. `0.8.4-preview` took that step to
  `listea.package/2` — `files`, `sections` and `structured` — and `0.8.5-preview` took it again to
  `listea.package/3` — the part-level `when` and the media Prompt — while still reading v1 and v2
  under their own grammars.
- A new `kind` does not change the protocol. It needs a Viewer that declares support.
- A new `response` contract is a protocol change, because it changes what a valid answer is.
- A new optional field inside a description is a schema change for that `kind`; the Viewer
  advertises the schema range it reads.
- Additive manifest fields still require a protocol-version decision: v1 rejects unknown fields so
  that a typo is a diagnostic, not a silent change of meaning.

## Acceptance fixtures

The dating run's exact assertions, fixture bytes, and pass/fail threshold are recorded in
[v0.8.2-plan.md](iterations/v0.8.2-plan.md). The vocabulary round and its fixtures are recorded in
[v0.8.0-vocabulary-plan.md](iterations/v0.8.0-vocabulary-plan.md). The v5 export shape and the
decision to keep the Prompt body out of it are also recorded there; this document fixes only what
enters the app.
