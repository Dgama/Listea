"""Generates the media for the mixed-List acceptance walk.

Two immediate child folders, because the device walk imports this scope with **Folder as Task**:

- `single-pdf/` — one PDF, so the Task is a single PDF file;
- `media-mix/` — one PDF, one PNG and one MP4, so the Task holds three Assets of three kinds.

The Bytes are committed. Run from the repository root with Pillow and ffmpeg available:

    python docs/assets/fixtures/mixed-list/generate_mixed_list.py
"""

import shutil
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

FIELDS = {
    "single": ("#37474F", "#ECEFF1"),
    "pdf": ("#4E342E", "#EFEBE9"),
    "png": ("#1B5E20", "#E8F5E9"),
}


def page(title: str, subtitle: str, field: str, ink: str, size=(720, 960)) -> Image.Image:
    width, height = size
    image = Image.new("RGB", size, field)
    draw = ImageDraw.Draw(image)
    big = ImageFont.load_default(size=40)
    small = ImageFont.load_default(size=24)
    for text, font, y in ((title, big, height * 0.40), (subtitle, small, height * 0.52)):
        box = draw.textbbox((0, 0), text, font=font)
        draw.text(((width - (box[2] - box[0])) / 2, y), text, fill=ink, font=font)
    return image


def _rgb(hex_colour: str) -> str:
    return " ".join(f"{int(hex_colour[i:i + 2], 16) / 255:.4f}" for i in (1, 3, 5))


def text_pdf(path: Path, pages: list[tuple[str, str]], field: str, ink: str) -> None:
    """Writes a PDF whose words are a text layer, not a bitmap.

    Pillow can only save an image wrapped in a PDF: PdfRenderer shows it, but nothing in it can
    be selected, so the passage tools have no characters to point at. The media fixtures exist to
    exercise the PDF viewer's own features, so these two documents draw their field as a filled
    rectangle and their words as Helvetica text — the same look, and a layer PDFium can read.
    """
    width, height = 720, 960
    objects: list[bytes] = []

    def add(body: str) -> int:
        objects.append(body.encode("latin-1"))
        return len(objects)

    catalog = add("<< /Type /Catalog /Pages 2 0 R >>")
    pages_obj = 2  # reserved below
    objects.append(b"")  # placeholder, replaced once the page ids are known
    font_id = 3 + 2 * len(pages)
    kids = []
    for title, subtitle in pages:
        # A page's shapes first: the field colour, then the two lines of text, at the same
        # heights the picture fixtures use (40% and 52% down from the top).
        content = (
            f"q {_rgb(field)} rg 0 0 {width} {height} re f Q\n"
            "BT /F1 40 Tf "
            f"{_rgb(ink)} rg "
            f"{width / 2 - 0.5 * 40 * len(title):.1f} {height * 0.60:.1f} Td ({title}) Tj ET\n"
            "BT /F1 24 Tf "
            f"{_rgb(ink)} rg "
            f"{width / 2 - 0.5 * 24 * len(subtitle):.1f} {height * 0.48:.1f} Td ({subtitle}) Tj ET\n"
        ).encode("latin-1")
        content_id = add(
            f"<< /Length {len(content)} >>\nstream\n" + content.decode("latin-1") + "endstream"
        )
        page_id = add(
            f"<< /Type /Page /Parent {pages_obj} 0 R /MediaBox [0 0 {width} {height}] "
            f"/Resources << /Font << /F1 {font_id} 0 R >> >> /Contents {content_id} 0 R >>"
        )
        kids.append(page_id)
    objects[1] = (
        f"<< /Type /Pages /Kids [{' '.join(f'{kid} 0 R' for kid in kids)}] "
        f"/Count {len(kids)} >>"
    ).encode("latin-1")
    add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")

    out = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{number} 0 obj\n".encode("latin-1") + body + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode("latin-1")
    for offset in offsets[1:]:
        out += f"{offset:010d} 00000 n \n".encode("latin-1")
    out += (
        f"trailer\n<< /Size {len(objects) + 1} /Root {catalog} 0 R >>\nstartxref\n{xref}\n%%EOF\n"
    ).encode("latin-1")
    path.write_bytes(out)


def main() -> None:
    root = Path(__file__).resolve().parent
    single = root / "single-pdf"
    mix = root / "media-mix"
    single.mkdir(exist_ok=True)
    mix.mkdir(exist_ok=True)

    field, ink = FIELDS["single"]
    text_pdf(
        single / "reading.pdf",
        [("A single PDF Task", "Page 1 of 2"), ("A single PDF Task", "Page 2 of 2")],
        field,
        ink,
    )
    print(f"wrote {single / 'reading.pdf'}")

    field, ink = FIELDS["pdf"]
    text_pdf(mix / "reading.pdf", [("A mixed media Task", "The PDF page")], field, ink)
    print(f"wrote {mix / 'reading.pdf'}")

    field, ink = FIELDS["png"]
    page("A mixed media Task", "The PNG page", field, ink, size=(720, 960)).save(
        mix / "photo.png", "PNG", optimize=True
    )
    print(f"wrote {mix / 'photo.png'}")

    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise SystemExit("ffmpeg is not on PATH")
    clip = mix / "clip.mp4"
    subprocess.run(
        [
            ffmpeg,
            "-y",
            "-loglevel",
            "error",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=640x360:rate=24",
            "-t",
            "3",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            str(clip),
        ],
        check=True,
    )
    print(f"wrote {clip}")


if __name__ == "__main__":
    main()
