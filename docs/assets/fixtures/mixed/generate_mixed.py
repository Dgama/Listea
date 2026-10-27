"""Copies the two media bytes the mixed fixture uses from its sibling fixtures.

The Package itself is the point: one manifest, one profile Prompt and one vocabulary Prompt, one
image Resource and one audio Resource. The bytes are the same committed files the dating and
vocabulary fixtures ship, which keeps the three fixtures from drifting into different-looking
material. Run from the repository root:

    python docs/assets/fixtures/mixed/generate_mixed.py
"""

import shutil
from pathlib import Path

FIXTURES = Path(__file__).resolve().parent.parent
COPIES = [
    (FIXTURES / "dating" / "media" / "alice.png", "alice.png"),
    (FIXTURES / "vocabulary" / "media" / "serendipity.mp3", "serendipity.mp3"),
]


def main() -> None:
    out_dir = Path(__file__).with_name("media")
    out_dir.mkdir(exist_ok=True)
    for source, name in COPIES:
        shutil.copyfile(source, out_dir / name)
        print(f"copied {source} -> {out_dir / name}")


if __name__ == "__main__":
    main()
