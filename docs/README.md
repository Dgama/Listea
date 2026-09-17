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
| [Review and actions](review-and-actions.md) | The Review screen in full: the frame and what belongs to which level, gestures, zoom, video transport, the Info sheet, rounds, resuming, and how filtering and sorting shape a queue. |
| [Webhooks](webhooks.md) | The four events, when each fires, the exact JSON payload, item ids, the confirmation dialog, webhook history, resending and failures. |
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

One image is on disk but currently used by no page: `folder-quick-review.jpg`. It predates the
Quick Review rework and should be re-shot or deleted rather than reused as it stands.

## A note on what is current

These pages describe **what Listea does today**, verified against the implementation. Anything
about future capability is confined to [Roadmap](roadmap.md) and is labelled as such there.

If a page disagrees with the app, the app is right and the page is a bug — please open an issue.

[Architecture](ARCHITECTURE.md) is the one page of a different kind: it is **normative**, not
descriptive. It says what must stay true, and the app is expected to conform to it rather than the
other way round. Where the two are known to diverge, the divergence is recorded in a blockquote at
the point it arises, with the decision left open rather than quietly taken — two are open as of
v0.4.0, both in [File, Source, and Upstream](ARCHITECTURE.md#file-source-and-upstream) and
[Re-import and reconciliation](ARCHITECTURE.md#re-import-and-reconciliation).
