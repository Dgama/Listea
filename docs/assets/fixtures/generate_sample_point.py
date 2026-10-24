"""Generates `sample-point.png`, the acceptance fixture for v0.8.1's point sampling.

The picture is a grid of flat colour blocks. A long press in the middle of a block reads back
that block's exact colour, which is what makes the reviewer's sample checkable by eye: the swatch
beside Submit must match the name in `README.md`, not merely "some colour".

Run from the repository root with Pillow installed:

    python docs/assets/fixtures/generate_sample_point.py

The bytes are committed, so the device walk never depends on a local Python environment.
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# Grid geometry: four columns, two rows, flat 20 px margins and gutters inside 1100 x 700.
BLOCK_W, BLOCK_H = 250, 320
MARGIN, GUTTER = 20, 20
COLUMNS, ROWS = 4, 2

# Row-major: the order the README names them in.
BLOCKS = [
    ("red", "#E53935", "Red", "white"),
    ("blue", "#1E88E5", "Blue", "white"),
    ("green", "#43A047", "Green", "white"),
    ("yellow", "#FDD835", "Yellow", "black"),
    ("purple", "#8E24AA", "Purple", "white"),
    ("cyan", "#00ACC1", "Cyan", "black"),
    ("orange", "#FB8C00", "Orange", "black"),
    ("brown", "#6D4C41", "Brown", "white"),
]


def main() -> None:
    width = COLUMNS * BLOCK_W + (COLUMNS - 1) * GUTTER + 2 * MARGIN
    height = ROWS * BLOCK_H + (ROWS - 1) * GUTTER + 2 * MARGIN
    image = Image.new("RGB", (width, height), "#FFFFFF")
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default(size=36)

    for index, (_, color, label, ink) in enumerate(BLOCKS):
        column, row = index % COLUMNS, index // COLUMNS
        left = MARGIN + column * (BLOCK_W + GUTTER)
        top = MARGIN + row * (BLOCK_H + GUTTER)
        draw.rectangle([left, top, left + BLOCK_W - 1, top + BLOCK_H - 1], fill=color)
        # The label sits near the top edge, well away from the block's centre: pressing the centre
        # must average nothing but the block's own colour.
        draw.text((left + 16, top + 16), label, fill=ink, font=font)

    out = Path(__file__).with_name("sample-point.png")
    image.save(out, format="PNG", optimize=True)
    print(f"wrote {out} ({width}x{height})")


if __name__ == "__main__":
    main()
