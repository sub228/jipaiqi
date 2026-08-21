#!/usr/bin/env python3
"""
Download the three pretrained DouZero .ckpt checkpoints from Google Drive.

The DouZero repo (https://github.com/kwai/DouZero) provides the following
Google Drive links in its README; we wrap them with gdown so the download
survives Google's anti-virus "confirm" page for large files.

Output layout:
    <dst>/landlord.ckpt
    <dst>/landlord_up.ckpt
    <dst>/landlord_down.ckpt

After this script completes, run convert_douzero_to_onnx.py to produce
the .onnx files the Android app actually consumes.

If a direct Google Drive download fails (rate limiting, region, etc.),
you can manually drop the three .ckpt files into <dst>/ and skip this
script entirely.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Google Drive file IDs from the DouZero README. Update if upstream changes.
FILE_IDS = {
    "landlord":      "1oi1Z5rfyoNtmXhO6Q4hJaok7Y6Z-Ntkh",
    "landlord_up":   "1wDE2g5wMDQ9MWEScrE6d3WvuyQynCkTe",
    "landlord_down": "17CondPz1TRxIBRdQ83ITqd5nMm4rDTpR",
}


def download_one(position: str, dst: Path) -> Path:
    import gdown
    dst.parent.mkdir(parents=True, exist_ok=True)
    fid = FILE_IDS[position]
    url = f"https://drive.google.com/uc?id={fid}"
    print(f"[{position}] downloading {url}")
    # fuzzy=True lets gdown handle the "virus scan" confirmation page.
    gdown.download(url, str(dst), quiet=False, fuzzy=True)
    if not dst.is_file():
        raise RuntimeError(f"download failed for {position}")
    print(f"[{position}] OK -> {dst}  ({dst.stat().st_size} bytes)")
    return dst


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dst", default=Path("./_downloads"), type=Path,
                    help="directory to save the .ckpt files")
    ap.add_argument("--positions", nargs="+",
                    default=list(FILE_IDS.keys()))
    args = ap.parse_args()
    for pos in args.positions:
        if pos not in FILE_IDS:
            print(f"unknown position {pos!r}; choices: {list(FILE_IDS)}",
                  file=sys.stderr)
            return 1
        download_one(pos, args.dst / f"{pos}.ckpt")
    print(f"\nAll done. Files in: {args.dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
