"""Refresh the media bytes of the conditional-mixed acceptance fixture.

The bytes are committed, so this script exists to explain them and to make a refresh a copy rather
than a set of hand-made files. It reuses the Package fixtures already in the tree:

    menu.pdf, receipt.pdf  <- mixed-list/media-mix/reading.pdf (a real two-page PDF)
    photo.png              <- mixed-list/media-mix/photo.png
    clip.mp4               <- mixed-list/media-mix/clip.mp4
    dating.png             <- dating/media/alice.png
    word.m4a               <- vocabulary/media/serendipity.mp3

`word.m4a` keeps the MP3 bytes under the name the fixture README uses; the audio Resource is
played by content, not by extension. Run from anywhere:

    python docs/assets/fixtures/conditional-mixed/generate_conditional_mixed.py
"""

from pathlib import Path
import shutil

HERE = Path(__file__).resolve().parent
FIXTURES = HERE.parent

COPIES = {
    "menu.pdf": FIXTURES / "mixed-list" / "media-mix" / "reading.pdf",
    "receipt.pdf": FIXTURES / "mixed-list" / "media-mix" / "reading.pdf",
    "photo.png": FIXTURES / "mixed-list" / "media-mix" / "photo.png",
    "clip.mp4": FIXTURES / "mixed-list" / "media-mix" / "clip.mp4",
    "dating.png": FIXTURES / "dating" / "media" / "alice.png",
    "word.m4a": FIXTURES / "vocabulary" / "media" / "serendipity.mp3",
}


def main() -> None:
    media = HERE / "media"
    media.mkdir(exist_ok=True)
    for name, source in COPIES.items():
        shutil.copyfile(source, media / name)
        print(f"{source.relative_to(FIXTURES)} -> media/{name}")


if __name__ == "__main__":
    main()
