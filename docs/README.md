# Listea documentation

Everything about how Listea actually behaves. For what Listea *is* and why, start at the
[project README](../README.md).

Each page below is the canonical home for its subject — where behaviour is described in full, and
where the other pages link to rather than repeat.

## Start here

| Page | What it covers |
|---|---|
| [Getting started](getting-started.md) | Prerequisites, building, installing, adding an Upstream, and running a first review end to end. |
| [Core concepts](core-concepts.md) | The vocabulary: Upstream, Todo, Task, Asset, add-on, folder, List, catalog, Quick Review, round, checked state, action, content policy, dissolution, and where a decision is stored — plus the v0.8.4 question/part model. |
| [The two vocabularies](vocabulary.md) | The canonical words for a question and a decision: Prompt, TaskPart, Task, Asset, Resource, Section, Composition, Package — with equalities, examples and the words that are deliberately not used. |

## Using it

| Page | What it covers |
|---|---|
| [Review and actions](review-and-actions.md) | The Review screen in full: the frame and what belongs to which level, gestures, zoom, video transport, the PDF reader and its passage marks, the Info sheet, rounds, resuming, and how filtering and sorting shape a queue. |
| [Templates](templates.md) | What a template is: its name, its scope, its buttons, the frame-versus-components boundary, PDF passage tools, why editing appends a version, how a Todo attaches one — and **add-ons**, the Task-level questionnaire that is neither a template nor an Asset. |
| [Exports](exports.md) | What an export is, the two methods (Webhook and Txt), where a Todo's and a List's choices live, how a round resolves into one payload or several, and the export history. |
| [Webhooks](webhooks.md) | The webhook method in full: the destination, the four events, the exact JSON payload including version 3's `marks` and version 4's `addOn`, the txt body, item ids, resending and failures. |
| [File management](file-management.md) | Everything that touches storage: deleting checked files, the post-webhook delete offer, saving to a gallery album, sharing, permissions, and what an Upstream's grant bounds. |
| [Settings](settings.md) | Every switch and field on the Settings screen, with links to the page that explains what each one does. |

## Working on it

| Page | What it covers |
|---|---|
| [Architecture](ARCHITECTURE.md) | The normative boundaries: what a Task, Asset, Upstream and List are, what Core does and does not own, identity rules, and the invariants implementations must preserve. Source code cites this document by name. |
| [v0.8 — framework (draft)](iterations/v0.8.md) | The v0.8.x arc: Package protocol, Prompt payloads, Resources, Structured Viewer and Headless Review Template, the typed page model, ReviewSubmission, and ExportMapping. |
| [Package protocol](package-protocol.md) | `listea.package/1`: the Prompt vocabulary, manifest and member layout, response contracts, commit/revisability, reconciliation rules, limits, and the dating example. Also [`listea.package/2`](package-protocol.md#v2-implemented-for-v084), shipped in `0.8.4-preview`, with `files`, Sections and `structured`. |
| [v0.8.4 design](iterations/v0.8.4.md) | The framework round: Task = 1..N TaskParts, Section as a tag, the taker's Composition, part-level state and Task-level completion, protocol v2, schema 33 and export v6 — delivered as `0.8.4-preview`. |
| [v0.8.4 plan](iterations/v0.8.4-plan.md) | The implementation plan for v0.8.4: phases, the schema-33 DDL and 32→33 migration, import composition, page model, completion policy, export v6, tests and device acceptance. |
| [v0.8.4 design check](iterations/v0.8.4-design-check.md) | The gate before code — vocabulary, scenario walks, invariants, compatibility matrix, adversarial cases and the sign-off table — and Gate 7, the implementation acceptance recorded after the device walk. |
| [v0.8.2 plan](iterations/v0.8.2-plan.md) | The v0.8.2 implementation plan: schema 32, Package import, the typed content page, the first Structured Viewer and Headless Template, and the dating acceptance walk. |
| [v0.8.2f fixes](iterations/v0.8.2f.md) | The six issues the v0.8.2 acceptance found — Todo rename, card-level add-on edit, the one Templates list with nothing required, format-matched import, Prompt Tasks exporting no templates, and the stuck page swipe — plus the seventh found while verifying them: a judgment no longer skips the add-on page. The eighth makes every Task surface show the same pages: the read-only previews gain the add-on page, and one shared page model governs the count, the swipe and the guide. Its fixes ship in the `0.8.3-preview` build. |
| [Vocabulary round plan](iterations/v0.8.0-vocabulary-plan.md) | The vocabulary round, delivered as the unreleased `0.8.3-preview`: a `vocabulary`/1 Viewer behind the same Package protocol, page model, single-choice template, add-on and v5 export, plus the fixtures for the mixed-List acceptance walk and the follow-up fixes the walk produced. |
| [Development](development.md) | Environment and dependencies, build and test commands, project layout, and the architectural notes worth knowing before changing anything. |
| [Roadmap](roadmap.md) | The current system, the architectural direction away from media-only review, and the possible future workloads that direction is for. Nothing on that page is implemented. |

## Assets

Documentation images live under [`assets/`](assets):

- `assets/screenshots/` — screenshots of the app, used across these pages and the project README.
- `assets/demos/` — animated demos. Empty for now; see [its README](assets/demos/README.md) for
  the naming convention.
- `assets/fixtures/` — acceptance fixtures with their generators and READMEs, including the
  sampled-point image and the [dating Package](assets/fixtures/dating/README.md).

### Screenshots still to take

The surfaces below are described in prose and have **no image yet**. Where one belongs, the page
carries an HTML comment naming it — invisible on the rendered page, findable with
`grep -rn '<!-- screenshot:' docs/` — and the shot goes in `assets/screenshots/` under the name
given.

| Name | What it should show |
|---|---|
| `upstreams-tab` | The Upstreams tab with a few cards, each with its folder line and its folder/file/size counts |
| `create-tasks-dialog` | The **Create tasks?** dialog: name, both grouping rows, the keep-folder-tree box, the three content-policy rows |
| `history-recovery` | The **Decisions left behind** block, with *Start fresh* and one kept history |
| `import-report` | The **Import complete** report, including the *started over* and *replaced* lines |
| `todo-card` | One Todo card: its importer line, the *When a Task's content changes:* line, Refresh Import, the overflow menu |
| `catalog-selecting` | A Todo's catalog in selection mode, a folder ticked with one Task unticked under it, and the selection bar |
| `settings-templates` | The Templates section on Settings, and one template's page with its scope line and its button list |
| `settings-exports` | The Exports screen: the method rows with their destinations, the three export-wide switches, and the history row |

**Three images on disk are stale.** `settings-webhook.jpg` is now used by no page at all — the
Webhook section it shows became a page of its own under Exports — so it should be re-shot as
`settings-exports` or deleted. `settings-custom-actions.jpg` is still used by
[Review and actions](review-and-actions.md), with alt text that says plainly what it shows and when:
action buttons moved out of Settings and onto a template, so it needs a re-shoot as
`settings-templates`. `folder-quick-review.jpg` is used by no page either, and predates the Quick
Review rework; it should be re-shot or deleted.

## A note on what is current

These pages describe **what Listea does today**, verified against the implementation. Product
direction lives in [Roadmap](roadmap.md). v0.8.1, v0.8.2 and its fix round v0.8.2f are
implemented and unreleased (schema 31 and 32); v0.8.2 passed device acceptance on 2026-10-24, and
the six issues it found — and the two that verifying the fixes turned up — are implemented and
device-verified in [v0.8.2f](iterations/v0.8.2f.md). Its grammar is in
[Package protocol](package-protocol.md) and its implementation in
[v0.8.2 plan](iterations/v0.8.2-plan.md). The wider direction is recorded in
[Architecture](ARCHITECTURE.md#framework-evolution--v08x) and
[v0.8 (draft)](iterations/v0.8.md).

The current build is **`0.8.4-preview`** (versionCode 804, unreleased):
[v0.8.4](iterations/v0.8.4.md) on top of the `0.8.3-preview` line. It makes a Task 1..N
**TaskParts**, adds the taker's Composition, protocol `listea.package/2` with `files`, `sections`
and `structured`, schema 33 and export v6 (v5 kept for compatible single-part bodies), and the
conditional `form` Viewer with its two Task Reviews. It is device-verified through 2026-10-27.

Neither preview closes its release. `0.8.3-preview` remains a preview of the vocabulary round, and
`0.8.4-preview` of the TaskParts round; the payload-revision policy, the independent-Todo proof and
the protocol/capability freeze are carried forward rather than dropped. See
[v0.8 (draft)](iterations/v0.8.md#v083--shared-resources-and-the-reveal-card-proof),
[v0.8.4 design](iterations/v0.8.4.md#inherited-v083-open-items) and
[the implementation acceptance](iterations/v0.8.4-design-check.md#gate-7--implementation-acceptance).

If a page disagrees with the app, the app is right and the page is a bug — please open an issue.

[Architecture](ARCHITECTURE.md) is the one page of a different kind: it is **normative**, not
descriptive. It says what must stay true, and the app is expected to conform to it rather than the
other way round. Where the two are known to diverge — or where the code has moved past what the
document said — the divergence is recorded in a blockquote at the point it arises, with the decision
left open rather than quietly taken.

Most of those blockquotes record a decision already taken and the reasoning for it, so a reader who
finds one is not looking at an unresolved bug. **One is genuinely open.** In
[TaskState](ARCHITECTURE.md#taskstate) the decision — `★` and the configured actions live on the
Task–Asset pair — is settled, but the sentence the section is built on no longer describes it, and
whether to reshape the sentence or narrow the rule is left to a later reader. The former second open
item, the retired word in
[File, Source, and Upstream](ARCHITECTURE.md#file-source-and-upstream), now records the v0.8.x
decision: the table name stays, the word survives only as external material, and the remaining
Todo-dialog wording is a later wording pass.

Two more are worth knowing about because they are recent. [Templates](ARCHITECTURE.md#templates)
records v0.5.4 turning the Asset Control band into stored rows, and
[Exporter / Action](ARCHITECTURE.md#exporter--action) records v0.5.6 splitting the *export* from its
*mechanism* — including the one question that split raised against this document's own boundaries,
which is now stated there as permitted rather than implied. And v0.7.0's record is spread across
[Asset](ARCHITECTURE.md#asset), [Asset Viewer Template](ARCHITECTURE.md#asset-viewer-template) and
[Gestures and semantic actions](ARCHITECTURE.md#gestures-and-semantic-actions): a read-only PDF
viewer, a mark that belongs to the Asset, and the renderer seam that keeps PDFium a choice rather
than an architectural fact.
