# The two vocabularies — setter and taker

> **Status:** v0.8.4, implemented 2026-10-27 in `0.8.4-preview` (804). This page is the canonical
> reference for the words the v0.8.4 documentation uses; the terms are now code — TaskPart is the
> decision unit in the schema and the UI, Composition is the import's `compositionKey`, and
> ImportPlan is the shared importer output. The open items at the end remain deliberately
> unaligned. The [architecture](ARCHITECTURE.md) remains the one normative page;
> this one fixes the naming it relies on.
> **Why this page exists:** Listea began as a file-review app with one vocabulary. A Package makes
> the question **setter** a second speaker: the setter declares questions and material, the
> decision-maker takes them on, and the app is the boundary between the two. The file-era words —
> especially *item* and *asset* — now point at two different things. This page separates the two
> vocabularies, states the equalities where they overlap, and marks what has not been aligned yet.
> **Related:** [v0.8.4 design](iterations/v0.8.4.md),
> [architecture](ARCHITECTURE.md#prompt-and-task),
> [Package protocol](package-protocol.md).

## The rule

Three rules decide a word:

1. **A new, independently meaningful concept gets a new word.** *TaskPart* and *Composition* are
   new because a Task can now hold several decisions and the taker can choose how they are grouped.
   Reusing *Asset* or *Item* for them would make both the old and the new meaning unreadable.
2. **A word that still matches the file-import flow keeps its meaning**, and this page gives the
   file-flow and Package-flow examples side by side. *Task*, *Asset*, *Resource*, *Todo*,
   *Upstream*, *Template*, *Viewer* and *Add-on* survive this test.
3. **A word that cannot yet be aligned is marked open**, with the reason and the intended
   direction. `files` versus `materials`, custom compositions, cross-Prompt conditions and per-part
   revisability are in that list.

A fourth rule follows from the first three: **one word, one meaning.** If a sentence needs two
senses, it needs two words. The section [Words not to use](#words-not-to-use) is the enforcement
list.

## The desk

The exam metaphor is the shortest way to show who owns what. It is a **working analogy**, not a
source of rules — the same caveat the Package protocol already carries.

| Desk | Listea | What it owns |
|---|---|---|
| **Question setter** | Package, Prompt, Section, `files`, Resources | what is asked, which answers are legal, which material is needed |
| **Print shop** | Importer, ImportPlan, Viewer | how the setter's material is turned into Tasks, and which renderer prints each kind |
| **Decision-maker** | Todo, Task, TaskPart, Asset, Template, Response, Add-on, Exporter | which questions are taken on, in what grouping and order, with which answer tool, and how the answer is submitted |
| **Grader** | webhook, n8n, downstream consumers | what happens after the answer is submitted |

The analogy breaks in the same places the Package protocol says it does: the print shop does not
choose the pen (the importer does not choose the Template), the printer does not validate an answer
(the Viewer does not own completion), and re-importing a Package is not reprinting a paper (a Task
keeps its id and history).

The question itself — the place the working note asked *"item = asset?"* — is **Prompt** on the
setter's side and **TaskPart** on the taker's. It is neither **Item** (the retired file/queue word)
nor **Asset** (the presented file/page).

## The canonical terms

| Term | Meaning | Not |
|---|---|---|
| **Prompt** | The question setter's smallest declared decision unit. It has a response contract, may present files, and may declare internal steps/fields/conditions in its body. A Prompt is content; it is never stored as a Task. | A Task, a TaskPart, an Asset, a List membership |
| **TaskPart** *(new)* | One Prompt instantiated in one Task: the decision-maker's unit of judgment. It owns its current response(s), its part-level events, its completion contribution and its pages. A media Task has one **degenerate** TaskPart with no Prompt payload. | An Asset, a Prompt, a second Task |
| **Task** | The decision-maker's undertaking. It contains **1..N TaskParts** and remains the durable object for identity, List membership, Task completion, the Add-on and the export envelope. | A Prompt, a Package, a file, a List membership |
| **Asset** | A file **presented as a page** within a TaskPart. It is material, not the decision unit. | A TaskPart, a Resource, a judgment target |
| **Resource** | A file a TaskPart **depends on** but does not present as a page: pronunciation audio, a prompt illustration, a reference image. | An Asset, a page, a decision unit |
| **Section** | A **declared tag** on Prompts, with an entry in the Package's `sections` registry for its title and order. Structurally drawable as a parent, semantically an attribute of the Prompt. | A Task constructor, a semantic boundary, a third Task level |
| **Composition** *(new)* | The decision-maker's rule for which Prompts become TaskParts of one Task, and in what order. It is recorded as the import's `compositionKey`. It may select by Section tag, by individual Prompt, or (later) by a custom list. | A Section, a setter declaration, a Task id |
| **Package** | The setter's exchange envelope: manifest, members, keys, revision. It is an input format, not an internal entity. | A Task, a Todo, a stored owner, a lifecycle |
| **Todo** | The decision-maker's intent and configuration for one run: Upstream, importer, scope, composition rule, templates, one Add-on, export choice. | A question setter, a List, a review context |
| **Upstream** | Registered access to material: a grant, a locator, a name, provenance. | A Package, a Task, a review context |
| **Template** | The answer tool: accepted intents, commit policy, revisability, completion policy. | A Viewer, a Prompt, a workflow type |
| **Viewer** | The renderer for a Prompt/Asset family; it draws the content and emits named intents. | A Template, a completion policy, a database writer |
| **Response** | The recorded answer inside a TaskPart. The **response contract** is the setter's declaration of what a legal answer looks like; the Response is the taker's recorded value. | A Task, a TaskPart, a state edit |
| **Add-on** | A questionnaire about a Task as a whole, answered after its parts. A Task-level current state, not a primary event. | A TaskPart, an Asset, a Prompt |
| **ImportPlan** *(new)* | The normalized output of any importer: Tasks, TaskParts, Assets, Resources, payloads, diagnostics. Both the file importer and the Package importer produce one. | A Package, a renderer, a database write |

## The equalities

Where the two vocabularies overlap, the equality is explicit. These are the only allowed
translations.

| Setter's word | Taker's word | Canonical term(s) | Equality |
|---|---|---|---|
| Prompt (题目) | Question / Part (题目) | **Prompt** declared, **TaskPart** instantiated | One Prompt declared in a Package becomes one TaskPart when a Task takes it on. The Prompt may contain steps or fields; those are **inside** the TaskPart, not separate TaskParts. |
| Section (分组) | Tag / group (标签) | **Section** (declaration) + **Composition** (rule) | Section is a declared tag on Prompts. The taker's Composition may select by that tag. The Section does not itself create a Task. |
| Material / file (素材) | Asset (素材页) / Resource (依赖) | **Asset**, **Resource** | A file the setter lists under a Prompt's `files` and the taker sees as a page is an **Asset**. A file only referenced as a dependency is a **Resource**. The same bytes can be an Asset in one TaskPart and a Resource in another; the role belongs to the use. |
| Response contract (答题规范) | Response (答题卡) | **Response contract**, **Response** | The setter declares what a legal answer is; the taker's commit records a Response. The contract is content; the Response is history. |
| Package (试卷包) | — (no taker entity) | **Package** | The exchange envelope. It is import provenance, not a Task, Todo or stored owner. |
| — | Task (做题任务) | **Task** | The taker's undertaking over 1..N TaskParts. The setter has no equivalent: the setter writes the paper, not the sitting. |
| — | Todo (做题计划) | **Todo** | The taker's intent and configuration for a run. |
| — | Template (文具) | **Template** | The answer tool. |
| — | Add-on (考后问卷) | **Add-on** | A questionnaire about the Task, after its parts. |

The most important row is the first: **Prompt and TaskPart are the same decision seen from two
desks.** A Prompt is the declaration; a TaskPart is the instance. They are not two stored copies of
the same content: the Prompt's body is the TaskPart's payload, and the TaskPart's response is the
taker's answer. The same Prompt taken on by two Todos is two TaskParts with independent state and
history.

## Examples

The examples below are the test of the vocabulary. Each names the setter's declaration, the
composition rule, the resulting Task, its Parts, its Assets and its pages.

### 1. File-as-Task image

```text
Upstream folder
└── photo.jpg          setter: none — the file is raw material
        │
        │ importer: one Task per file
        v
Task ─── TaskPart (degenerate) ─── Asset (photo.jpg)
          no Prompt payload
          response: freeform
```

- **Task**: 1. **TaskPart**: 1. **Asset**: 1. **Pages**: the photo, no Content page. **Add-on**: 0 or 1.
- The TaskPart is the decision ("tag this photo"); the Asset is the file page. The file's pair
  state (chips, marks) hangs off the Asset; completion hangs off the Task.
- This is today's media Task, described in the new words. If the wording is right, nothing about
  the media flow changes.

### 2. Folder-as-Task with three files

```text
Upstream folder
└── candidate-a/
    ├── cv.pdf
    ├── research.pdf
    └── notes.pdf
        │
        │ importer: one Task per folder
        v
Task ─── TaskPart (degenerate) ─── Asset × 3
          one decision about the folder
```

- **Task**: 1. **TaskPart**: 1. **Assets**: 3. **Pages**: 3. **Add-on**: 0 or 1.
- The folder is one decision, so it is one TaskPart. The three files are material, so they are
  three Assets. A mark on the second PDF belongs to the second Asset; the Task has one completion.
- This is the cleanest proof that **TaskPart ≠ Asset**: the part count is 1, the Asset count is 3,
  and both are correct.

### 3. One vocabulary Prompt

```text
Package
└── Prompt: serendipity        setter: stem, options, audio Resource
        │
        │ composition: prompt:serendipity
        v
Task ─── TaskPart ─── Prompt payload
          response: choice.single
          Assets: 0
          Resources: the pronunciation audio
          pages: 1 Content page
```

- **Task**: 1. **TaskPart**: 1. **Asset**: 0. **Resource**: 1. **Pages**: 1. **Add-on**: 0 or 1.
- The catalog should say **1 Part** (and 1 Add-on when present), not "1 Asset". The 0-Asset count
  is correct as a *file* count: a vocabulary card presents no file page; the audio is a Resource.
- The earlier "1 Asset + 1 add-on" sentence for this shape was naming the decision unit with the
  file word. v0.8.4 corrects the name: it is **1 TaskPart + 1 Add-on**, and **0 Assets**.

### 4. A Package Section as one Task

```text
Package
├── Prompt: q1  section: drill-1
├── Prompt: q2  section: drill-1
└── Prompt: q3  section: drill-1
        │
        │ composition: section:drill-1
        v
Task ─── TaskPart q1 ─── payload + Assets
     ├── TaskPart q2 ─── payload + Assets
     └── TaskPart q3 ─── payload + Assets
```

- **Task**: 1. **TaskParts**: 3. **Assets**: whatever each Prompt presents. **Pages**: each part's
  Content page, then its Assets. **Add-on**: one, after the last part.
- The Section is the declaration that these three Prompts belong together. The **Composition** is
  the taker's decision to make them one Task. A different composition (`prompt:q1`, `prompt:q2`,
  `prompt:q3`) makes three Tasks. The Section tag is the same in both; the taker's rule differs.
- The Task's completion is one Task-level state; each part has its own response and event. The
  Add-on is still one page after all parts.

### 5. A conditional questionnaire Prompt

```text
Package
└── Prompt: meal-tree          setter: one decision with internal steps
        body:
          Q1: Did you eat?  (yes/no)
          Q2: What did you eat?      only when Q1 = yes
          Q3: Where?                 only when Q2 = ...
        │
        │ composition: prompt:meal-tree
        v
Task ─── TaskPart (one decision) ─── Prompt payload
          response: structured
          internal steps: Q1, Q2, Q3
          Assets: 0..N
```

- **Task**: 1. **TaskPart**: 1. **Response**: one structured record; the branch is inside the
  TaskPart.
- The user makes one judgment ("what did you eat?"); the show/hide consequences are the setter's
  logic inside the same decision. The part is not split into three TaskParts. The three internal
  questions are **body fields/steps**, not Sections and not TaskParts.
- This is the answer to "is a decision one answer?" — no. A decision is one **judgment**; it may
  contain several answers along one branch.

### 6. A Section of 20, with one conditional pair

```text
Package
└── Section: choice-drill
    ├── Prompt: q1 ... q18      independent single-choice questions
    └── Prompt: q19             a conditional pair, declared as one Prompt
        body: Q19a → Q19b
```

- The setter declares 19 Prompts, not 20: the conditional pair is **one** Prompt because it is one
  logical closure. The Section tag says they belong together; it does not say the taker must do all
  19 at once.
- The taker's compositions can be:
  - `section:choice-drill` — one Task with 19 TaskParts;
  - `prompt:q19` — one Task with the conditional pair alone;
  - `prompt:q1` … `prompt:q18` — eighteen single-question Tasks.
- The user who wants only the pair takes `prompt:q19`. The Section remains a tag; the **Composition**
  is the taker's choice. This is the answer to "why must I do all the choice questions at once?" —
  you do not, unless you choose the Section composition.

### 7. Local file and Package, same model

```text
Local file                         Package Prompt
photo.jpg                          prompts/alice.json
    │                                  │
    │ file importer                    │ Package importer
    v                                  v
TaskPart (degenerate)              TaskPart (declared)
  no payload                         Prompt payload
  Asset: photo.jpg                   Assets: declared files
  response: freeform                 response: choice.single
```

- Both importers produce the same `ImportPlan` shape: Tasks containing TaskParts, Assets and
  Resources. The local importer **synthesizes a degenerate TaskPart**; it does not fake a Package
  or a Prompt. The Package importer **reads declared Prompts**; it does not invent a file layout.
- This is the intended path for aligning media and virtual Tasks: one internal model, two front
  doors. The Package remains an exchange protocol; the TaskPart is the internal decision unit.

## Status of each word

### Aligned with the file-import flow

These keep their meaning and need no new word:

- **Task** — the undertaking; still the unit of identity, List membership, completion and export.
- **Asset** — the presented file/page; a file Task is one Asset, a folder Task has several.
- **Resource** — a dependency; never a page or an Asset.
- **Todo** — the run's intent and configuration.
- **Upstream** — the registered material access.
- **Template** — the answer tool.
- **Viewer** — the renderer.
- **Add-on** — the Task-level questionnaire.

### New in v0.8.4

- **TaskPart** — the decision unit inside a Task; the taker's instance of a Prompt.
- **Composition** — the taker's rule for grouping Prompts into a Task; recorded as
  `compositionKey`.
- **ImportPlan** — the normalized output both importers produce; the unification point.

### Narrowed or corrected

- **Prompt** — still the setter's unit, but no longer "one question that a Task answers one-to-one."
  A Prompt is one decision unit; it may contain internal steps/fields/conditions; a Task may contain
  several Prompts.
- **Section** — still a declared grouping, but no longer a parent that creates Tasks and no longer a
  semantic boundary. It is a **tag**; the Composition is the taker's rule.
- **Asset** — still the presented file/page, but no longer a candidate name for the decision unit.
  The 2026-10-26 note that called a virtual Task's decision unit an Asset is corrected to
  **TaskPart**; the count it described is a part count, not an Asset count.

### Open — not yet aligned

| Word / issue | Why it is open | Intended direction |
|---|---|---|
| `files` vs `materials` / `presentations` | `files` names the storage form; a future Prompt may present a generated or embedded thing that is not a file. | v0.8.4 uses `files` as the short-term v2 manifest field. Revisit when a non-file presentation exists. |
| Custom composition | A user could pick any five Prompts into one Task; this is representable by a `custom:<key>` composition with a stored member list, but the UI and reconciliation are not in v0.8.4. | Scope control, not a model limitation. Add when a real workflow needs it. |
| Cross-Prompt conditions | A later Prompt depending on an earlier Prompt's answer is not represented in v0.8.4. | Out of the v0.8.4 Core. The producer may emit a follow-up Prompt/Task after observing the result. This is a boundary, not a claim that such workflows are wrong. |
| Per-part revisability | v0.8.4 keeps revisability a Template-level policy applied to each part. | A later version may allow different parts of one Task to have different policies. |
| Mixed-contract parts | A Task whose parts need different response contracts needs a composite Template; v0.8.4 scopes a Task to parts the chosen Template can answer. | Diagnose or split the composition; add composite templates when a fixture demands them. |
| UI label for TaskPart | "Part", "question" and "题目" are all natural in different surfaces. | Canonical in code/docs: **TaskPart**. User-facing: **question/题目** when it is a question; **part** in a multi-question Task. Never "item". |
| Package archive (`.listea` zip) | v1/v2 use a directory; an archive is a packaging change, not a semantic one. | Later, with extraction and selection rules. |

## Words not to use

- **Item** — historically a file in a queue or a List entry. It is not the decision unit. Use
  **Task** for the undertaking, **TaskPart** for the decision, **Asset** for the file/page, and
  **ListEntry** for membership. The code's `ReviewItem` and the wire's `itemId` remain legacy names
  for the queue row and the public id; they do not name a TaskPart and are not precedents for new
  vocabulary.
- **Asset** as the decision unit — no. Asset is the presented file/page. The decision unit is the
  TaskPart. The v0.8.3 note's "Asset" wording is corrected in v0.8.4.
- **Section** as a parent or Task constructor — no. Section is a declared tag. The taker's
  Composition is the rule that makes a Task.
- **Prompt** as a Task — no. Prompt is the setter's declaration; TaskPart is the taker's instance.
- **Package** as an internal entity — no. It is an exchange protocol; the internal model is Task,
  TaskPart, Asset, Resource.
- **`assets`** as the v2 manifest field for presented files — no. v2 uses **`files`**; the internal
  term remains **Asset**.

## How to read a Task page

When a screen shows a Task with several decisions, read it in three counts, not one:

| Count | What it counts | Example |
|---|---|---|
| **Parts / questions** | The decision units in the Task | one vocabulary question = 1; a Section of 19 = 19 |
| **Assets / files** | The presented file pages | a vocabulary card = 0; a folder of 3 PDFs = 3 |
| **Pages** | The navigable pages | a part's Content page + its Assets; then the next part; then the Add-on |

The inner arrow walks **pages**. The queue position walks **Tasks**. The part is a grouping and a
state unit, not a third arrow level. A single-part Task behaves exactly as today.

---

Next: [v0.8.4 design](iterations/v0.8.4.md) for the model and its state machine,
[Package protocol](package-protocol.md) for the v1 grammar and the v2 grammar, and
[Core concepts](core-concepts.md) for the shipped behaviour.
