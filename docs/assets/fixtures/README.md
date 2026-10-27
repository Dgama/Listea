# Acceptance fixtures

## `sample-point.png`

The picture v0.8.1's device walk uses: eight flat colour blocks, each with its name near the top
edge. A long press in the open middle of a block makes the review surface sample that block's exact
colour, so the swatch it shows can be checked against this table rather than merely glanced at.

Generated — do not hand-edit — by:

```text
python docs/assets/fixtures/generate_sample_point.py
```

The bytes are committed so the walk does not depend on a local Pillow install.

| Block | Position | Colour |
|---|---|---|
| Red | row 1, column 1 | `#E53935` |
| Blue | row 1, column 2 | `#1E88E5` |
| Green | row 1, column 3 | `#43A047` |
| Yellow | row 1, column 4 | `#FDD835` |
| Purple | row 2, column 1 | `#8E24AA` |
| Cyan | row 2, column 2 | `#00ACC1` |
| Orange | row 2, column 3 | `#FB8C00` |
| Brown | row 2, column 4 | `#6D4C41` |

The sampler averages a 5×5 window, so press clearly inside a block and away from the label text;
the value recorded is the block colour above, written uppercase (`#E53935`, not `#e53935`).

Push it to the phone for the walk:

```text
adb push docs/assets/fixtures/sample-point.png /sdcard/Pictures/ListeaSample/
```

## Package fixtures

Each folder is a complete `.listea` Package tree with its own README, generator and committed
bytes. The generator is what explains the bytes; see the folder README before regenerating.

| Folder | Contents | Used by |
|---|---|---|
| [`dating/`](dating/README.md) | `profile`/1 Prompts with photo and color Resources | v0.8.2 dating acceptance |
| [`vocabulary/`](vocabulary/README.md) | four `vocabulary`/1 Prompts with non-alphabetical option orders, shared and per-Prompt pronunciation Resources, one without audio | vocabulary round, acceptances 1, 2, 5 |
| [`mixed/`](mixed/README.md) | one Package holding one `profile` and one `vocabulary` Prompt | vocabulary round, acceptance 3 |
| [`mixed-list/`](mixed-list/README.md) | media files for a mixed List: a single two-page PDF, and a PDF + PNG + MP4 Task | vocabulary round, acceptance 6 |

The dating and media folders used elsewhere are indexed in their own READMEs; only the fixtures
added by the vocabulary round are listed here.
