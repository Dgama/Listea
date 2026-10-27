"""Generates the pronunciation tones for the vocabulary acceptance fixture.

Two short MP3s, not speech: the device walk only needs to hear that a Resource resolved and
played. `serendipity` has its own tone; `ubiquitous` and `ephemeral` deliberately share
`common.mp3`, which is the Package-level reuse the protocol allows. `meticulous` declares no audio
at all, so a card without one is exercised too.

Run from the repository root with ffmpeg on PATH:

    python docs/assets/fixtures/vocabulary/generate_vocabulary.py

The bytes are committed, so the device walk never depends on a local ffmpeg.
"""

import shutil
import subprocess
from pathlib import Path

TONES = [
    ("serendipity.mp3", 523),
    ("common.mp3", 392),
]


def main() -> None:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise SystemExit("ffmpeg is not on PATH")
    out_dir = Path(__file__).with_name("media")
    out_dir.mkdir(exist_ok=True)

    for name, frequency in TONES:
        out = out_dir / name
        subprocess.run(
            [
                ffmpeg,
                "-y",
                "-loglevel",
                "error",
                "-f",
                "lavfi",
                "-i",
                f"sine=frequency={frequency}:duration=0.8",
                "-ac",
                "1",
                "-q:a",
                "6",
                str(out),
            ],
            check=True,
        )
        print(f"wrote {out}")


if __name__ == "__main__":
    main()
