# Mixed Package acceptance fixture

One `listea.package/1` Package holding **two Viewers**: a `profile`/`1` dating card and a
`vocabulary`/`1` drill card, with one `image` Resource and one `audio` Resource. It is the smallest
fixture that proves a Package is not per-kind: the same manifest, the same import, two Prompt kinds,
and each Task resolves its own Viewer.

Import with **Package** import and the **Headless Template for Single Choice** Task Review. The
Catalog should then hold two Tasks — `alice` and `serendipity` — and each should draw its own card:

- Alice: the profile card with a photo, Like and Dislike.
- serendipity: the word card with a Listen control and options in `c, b, a` order.

Both are answerable through the same template, because both declare `choice.single` and emit
`choice.select`.

Media bytes are copies of `../dating/media/alice.png` and
`../vocabulary/media/serendipity.mp3`; `generate_mixed.py` refreshes them.
