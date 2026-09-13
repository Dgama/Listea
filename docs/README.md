# Listea documentation

Everything about how Listea actually behaves. For what Listea *is* and why, start at the
[project README](../README.md).

Each page below is the canonical home for its subject — where behaviour is described in full, and
where the other pages link to rather than repeat.

## Start here

| Page | What it covers |
|---|---|
| [Getting started](getting-started.md) | Prerequisites, building, installing, granting a folder, and running a first review end to end. |
| [Core concepts](core-concepts.md) | The vocabulary: root, folder, item, List, Quick Review, round, checked state, action. And the one rule that explains most of Listea's behaviour — a decision belongs to the file. |

## Using it

| Page | What it covers |
|---|---|
| [Review and actions](review-and-actions.md) | The Review screen in full: gestures, zoom, video transport, the action bar, the Info sheet, rounds, resuming, and how filtering and sorting shape a queue. |
| [Webhooks](webhooks.md) | The four events, when each fires, the exact JSON payload, item ids, the confirmation dialog, webhook history, resending and failures. |
| [File management](file-management.md) | Everything that touches storage: deleting checked files, the post-webhook delete offer, saving to a gallery album, sharing, permissions and root boundaries. |
| [Settings](settings.md) | Every switch and field on the Settings screen, with links to the page that explains what each one does. |

## Working on it

| Page | What it covers |
|---|---|
| [Development](development.md) | Environment and dependencies, build and test commands, project layout, and the architectural notes worth knowing before changing anything. |
| [Roadmap](roadmap.md) | The current system, the architectural direction away from media-only review, and the possible future workloads that direction is for. Nothing on that page is implemented. |

## Assets

Documentation images live under [`assets/`](assets):

- `assets/screenshots/` — screenshots of the app, used across these pages and the project README.
- `assets/demos/` — animated demos. Empty for now; see [its README](assets/demos/README.md) for
  the naming convention.

## A note on what is current

These pages describe **what Listea does today**, verified against the implementation. Anything
about future capability is confined to [Roadmap](roadmap.md) and is labelled as such there.

If a page disagrees with the app, the app is right and the page is a bug — please open an issue.
