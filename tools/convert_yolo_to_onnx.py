#!/usr/bin/env python3
"""
Export a YOLOv8 card-detection model to ONNX for the Android app.

The Android [CardDetector] expects an ONNX model with:
    input  : float32[1, 3, 640, 640]   named "images"
    output : float32[1, 4+nc, 8400]   named "output0"

where nc = number of card classes (15 by default:
3,4,5,6,7,8,9,10,J,Q,K,A,2,BJ,RJ).

Two modes:
  --pretrained-yolov8n    Download a generic YOLOv8n from Ultralytics and
                          export it. Useful for testing the pipeline end-to-end
                          on a *non-card* dataset. The class indices will NOT
                          match the card ranks until you fine-tune.
  --weights PATH          Export a YOLOv8 .pt checkpoint that you have
                          already trained (or downloaded) on Dou Dizhu card
                          images. This is what you want for production.

After running this script, copy the resulting .onnx into:
    app/src/main/assets/models/yolo_cards.onnx

Training data + fine-tuning script are out of scope for this repo (the
training pipeline lives in the Ultralytics eco-system). See the README
for a quick guide on how to label screenshots and fine-tune.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path


def export(weights: Path, out: Path, imgsz: int = 640, opset: int = 14) -> None:
    from ultralytics import YOLO
    out.parent.mkdir(parents=True, exist_ok=True)
    print(f"exporting {weights} -> {out} (imgsz={imgsz}, opset={opset})")
    model = YOLO(str(weights))
    path = model.export(
        format="onnx",
        imgsz=imgsz,
        opset=opset,
        simplify=True,
        dynamic=False,  # fixed input shape keeps Android inference simple
        half=False,     # FP32 for now; the runtime quantizes if you want
    )
    exported = Path(path)
    exported.replace(out)
    print(f"OK -> {out}  ({out.stat().st_size} bytes)")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--pretrained-yolov8n", action="store_true",
                     help="download a generic YOLOv8n from Ultralytics")
    src.add_argument("--weights", type=Path,
                     help="path to a .pt YOLOv8 checkpoint (recommended)")
    ap.add_argument("--output", "-o", default=Path("../app/src/main/assets/models/yolo_cards.onnx"),
                    type=Path, help="output path (default: app assets dir)")
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--opset", type=int, default=14)
    args = ap.parse_args()

    weights: Path
    if args.pretrained_yolov8n:
        # Ultralytics auto-downloads yolov8n.pt from their CDN on first use.
        from ultralytics import YOLO
        YOLO("yolov8n.pt")  # triggers download if missing
        weights = Path("yolov8n.pt")
        if not weights.is_file():
            print("Could not locate yolov8n.pt after Ultralytics auto-download.",
                  file=sys.stderr)
            return 1
    else:
        weights = args.weights
        if not weights.is_file():
            print(f"weights not found: {weights}", file=sys.stderr)
            return 1

    export(weights, args.output, args.imgsz, args.opset)
    print("\nNOTE: if you used --pretrained-yolov8n, the model class indices "
          "do NOT correspond to card ranks. You must fine-tune on labelled "
          "Dou Dizhu screenshots for production use. See README.",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
