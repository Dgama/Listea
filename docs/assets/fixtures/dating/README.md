# Dating acceptance fixture (v0.8.2)

A Package of three single-choice Prompts. It is the end-to-end proof that a Package's Prompts can
be imported as Tasks, rendered by a Structured Viewer, answered through the normal primary
submission path, reopened, and exported.

## Layout

```text
dating/
├── .listea/manifest.json     protocol envelope: keys, kinds, descriptions, Resources
├── prompts/alice.json        Prompt body: stem, profile fields, photo reference, options
├── prompts/bob.json
├── prompts/carol.json
├── media/alice.png           flat portrait cards, generated
├── media/bob.png
├── media/carol.png
└── generate_dating.py        regenerates the three PNGs
```

The directory containing `.listea/manifest.json` is the Package root. Import the **parent** of
`dating/` (or `dating/` itself when the picker allows it); discovery finds the Package.

## What it exercises

- `protocol` is `listea.package/1`; the Package key is `dating-demo`; revision 1.
- Three Prompts: `alice`, `bob`, `carol`, each `kind = profile`, `schema = 1`,
  `response = choice.single`.
- Each body declares two options, in this order: `like` labelled **Like**, `dislike` labelled
  **Dislike**. A committed answer is `choice.select` with value `<id>\t<label>`.
- The photos are **Resources**, not Assets. They do not appear in the Asset count, the file chip
  row, or the page count. Each Prompt has exactly one content page.
- Alice's and Bob's photos are **required**. Carol's is **optional**: when it is missing, the
  Viewer shows a placeholder and the choices remain usable. Alice's missing photo refuses
  submission.
- Revisability is on: Like, then Dislike, appends a second event. The latest answer is current;
  the first is still history. The dating case is the reason the policy exists.

## Variations used by the walk

Make these as **copies** so the committed fixture bytes stay stable.

- **Revision 2.** Copy the folder, set `revision` to 2, and change one bio. Import it into the same
  Todo: the Task id, its judgment, and its old events stay; the payload updates.
- **Unknown kind.** Copy the folder and set one Prompt's `kind` to `mystery`. It imports as
  unavailable, is excluded with a named reason, and the other two still import.
- **Missing photo.** Rename `media/alice.png` (required) or `media/carol.png` (optional) and
  re-import.

## Regenerating the images

```text
python docs/assets/fixtures/dating/generate_dating.py
```

The PNGs are committed, so the device walk does not need Python. Regenerating changes the bytes;
do it only when the fixture itself is being revised.
