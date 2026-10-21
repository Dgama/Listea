# Listea documentation

Everything about how Listea actually behaves. For what Listea *is* and why, start at the
[project README](../README.md).

Each page below is the canonical home for its subject — where behaviour is described in full, and
where the other pages link to rather than repeat.

## Start here

| Page | What it covers |
|---|---|
| [Getting started](getting-started.md) | Prerequisites, building, installing, adding an Upstream, and running a first review end to end. |
| [Core concepts](core-concepts.md) | The vocabulary: Upstream, Todo, Task, Asset, folder, List, catalog, Quick Review, round, checked state, action, content policy, dissolution, and where a decision is stored. |

## Using it

| Page | What it covers |
|---|---|
| [Review and actions](review-and-actions.md) | The Review screen in full: the frame and what belongs to which level, gestures, zoom, video transport, the PDF reader and its passage marks, the Info sheet, rounds, resuming, and how filtering and sorting shape a queue. |
| [Templates](templates.md) | What a template is: its name, its scope, its buttons, the frame-versus-components boundary, PDF passage tools, why editing appends a version, and how a Todo attaches one. |
| [Exports](exports.md) | What an export is, the two methods (Webhook and Txt), where a Todo's and a List's choices live, how a round resolves into one payload or several, and the export history. |
| [Webhooks](webhooks.md) | The webhook method in full: the destination, the four events, the exact JSON payload including version 3's `marks`, the txt body, item ids, resending and failures. |
| [File management](file-management.md) | Everything that touches storage: deleting checked files, the post-webhook delete offer, saving to a gallery album, sharing, permissions, and what an Upstream's grant bounds. |
| [Settings](settings.md) | Every switch and field on the Settings screen, with links to the page that explains what each one does. |

## Working on it

| Page | What it covers |
|---|---|
| [Architecture](ARCHITECTURE.md) | The normative boundaries: what a Task, Asset, Upstream and List are, what Core does and does not own, identity rules, and the invariants implementations must preserve. Source code cites this document by name. |
| [Development](development.md) | Environment and dependencies, build and test commands, project layout, and the architectural notes worth knowing before changing anything. |
| [Roadmap](roadmap.md) | The current system, the architectural direction away from media-only review, and the possible future workloads that direction is for. Nothing on that page is implemented. |

## Assets

Documentation images live under [`assets/`](assets):

- `assets/screenshots/` — screenshots of the app, used across these pages and the project README.
- `assets/demos/` — animated demos. Empty for now; see [its README](assets/demos/README.md) for
  the naming convention.

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

These pages describe **what Listea does today**, verified against the implementation. Anything
about future capability is confined to [Roadmap](roadmap.md) and is labelled as such there.

If a page disagrees with the app, the app is right and the page is a bug — please open an issue.

[Architecture](ARCHITECTURE.md) is the one page of a different kind: it is **normative**, not
descriptive. It says what must stay true, and the app is expected to conform to it rather than the
other way round. Where the two are known to diverge — or where the code has moved past what the
document said — the divergence is recorded in a blockquote at the point it arises, with the decision
left open rather than quietly taken.

Most of those blockquotes record a decision already taken and the reasoning for it, so a reader who
finds one is not looking at an unresolved bug. **Two are genuinely open.** In
[File, Source, and Upstream](ARCHITECTURE.md#file-source-and-upstream) a retired word survives in
identifiers the code has not let go of, and the code does not settle whether it should. In
[TaskState](ARCHITECTURE.md#taskstate) the decision — `★` and the configured actions live on the
Task–Asset pair — is settled, but the sentence the section is built on no longer describes it, and
whether to reshape the sentence or narrow the rule is left to a later reader.

Two more are worth knowing about because they are recent. [Templates](ARCHITECTURE.md#templates)
records v0.5.4 turning the Asset Control band into stored rows, and
[Exporter / Action](ARCHITECTURE.md#exporter--action) records v0.5.6 splitting the *export* from its
*mechanism* — including the one question that split raised against this document's own boundaries,
which is now stated there as permitted rather than implied. And v0.7.0's record is spread across
[Asset](ARCHITECTURE.md#asset), [Asset Viewer Template](ARCHITECTURE.md#asset-viewer-template) and
[Gestures and semantic actions](ARCHITECTURE.md#gestures-and-semantic-actions): a read-only PDF
viewer, a mark that belongs to the Asset, and the renderer seam that keeps PDFium a choice rather
than an architectural fact.
