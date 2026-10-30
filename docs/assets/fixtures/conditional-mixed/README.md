# Conditional mixed Package acceptance fixture

One `listea.package/3` Package, one Section, seven Prompts. It is the acceptance fixture for
v0.8.5: a yes/no gate inside a single Task that makes a PDF, an image/video pair, a vocabulary
card, a dating card and a second form apply — while a receipt is part of the Task whatever the gate
says.

## What it exercises

| Prompt | Kind / response | Material | When it applies |
|---|---|---|---|
| `gate` | `form` / `structured` | — | always |
| `menu` | `media` / `freeform` | `menu.pdf` | `gate.choice == yes` |
| `photo` | `media` / `freeform` | `photo.png`, `clip.mp4` | `gate.choice == yes` |
| `word` | `vocabulary` / `single` | `word-audio` Resource | `gate.choice == yes` |
| `dating` | `profile` / `single` | `dating-photo` Resource | `gate.choice == yes` |
| `notes` | `form` / `structured` | — | `gate.choice == yes` |
| `receipt` | `media` / `freeform` | `receipt.pdf` | always |

All seven share Section `main`, so **one Task per Section** puts them in **one Task**. The gate is
first, so every condition names an earlier part; the receipt is last and unconditional.

- **Masked is not missing.** With the gate answered `no`, the five conditional pages are still in
  the page list — the counter does not shrink — and each says *not needed*. Swiping across them
  turns pages; it does not answer them.
- **A closed branch is not owed.** Answering `no` and then the receipt satisfies the Task: the
  `AllRequiredParts` container counts only open required parts.
- **An answer can go stale.** Answer the gate `yes`, answer a conditional part, then change the
  gate to `no`: the answer is kept, marked stale, and no longer counts. Set the gate back to `yes`
  and the page asks for a fresh answer.
- **A reopen, not a retraction.** If the Task was complete when the gate becomes `yes`, it reopens
  with `completionSource = "reopened"` and no `review_events` row is appended for the reopen.

## Import

1. Put this folder (the directory holding `.listea/`) where the app can see it.
2. Create a Todo with **Package** import.
3. Choose **one Task per Section** and the add-on from the existing add-on fixture.

Importing the same Package with **one Task per Prompt** is the diagnosed failure case: a condition
resolves inside one Task, so the conditioned Prompts import unavailable with
`condition-source-outside-task` and the report names them.

## Files

- `.listea/manifest.json` — the package envelope, the seven Prompts and the two Resources.
- `prompts/*.json` — the Prompt bodies.
- `media/` — copies of assets already committed under `../mixed-list/`, `../dating/` and
  `../vocabulary/`; `generate_conditional_mixed.py` refreshes them.
