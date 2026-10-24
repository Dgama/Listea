"""Generates the three profile photos for the v0.8.2 dating acceptance fixture.

Each picture is a flat portrait card: a field of one colour, the candidate's initial, and the
name. The point is not realism but distinguishability — the reviewer must be able to tell the
three photos apart at a glance, and a failed Resource resolution must be obvious rather than
subtle.

Run from the repository root with Pillow installed:

    python docs/assets/fixtures/dating/generate_dating.py

The bytes are committed, so the device walk never depends on a local Python environment.
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# Stable order, matching the manifest and the README.
PEOPLE = [
    ("alice", "Alice", "#00695C", "#FFFFFF"),
    ("bob", "Bob", "#EF6C00", "#FFFFFF"),
    ("carol", "Carol", "#4527A0", "#FFFFFF"),
]

WIDTH, HEIGHT = 480, 640


def main() -> None:
    out_dir = Path(__file__).with_name("media")
    out_dir.mkdir(exist_ok=True)
    big = ImageFont.load_default(size=160)
    small = ImageFont.load_default(size=40)

    for key, name, field, ink in PEOPLE:
        image = Image.new("RGB", (WIDTH, HEIGHT), field)
        draw = ImageDraw.Draw(image)
        initial = name[0]
        # Centre the initial in the upper two-thirds, the name near the bottom.
        box = draw.textbbox((0, 0), initial, font=big)
        draw.text(
            ((WIDTH - (box[2] - box[0])) / 2, HEIGHT * 0.30),
            initial,
            fill=ink,
            font=big,
        )
        box = draw.textbbox((0, 0), name, font=small)
        draw.text(
            ((WIDTH - (box[2] - box[0])) / 2, HEIGHT * 0.80),
            name,
            fill=ink,
            font=small,
        )
        out = out_dir / f"{key}.png"
        image.save(out, format="PNG", optimize=True)
        print(f"wrote {out} ({WIDTH}x{HEIGHT})")


if __name__ == "__main__":
    main()
