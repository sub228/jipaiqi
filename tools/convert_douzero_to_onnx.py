#!/usr/bin/env python3
"""
Convert the three pretrained DouZero .ckpt checkpoints to ONNX for use
inside the Android app.

DouZero ships one model per player position:
    landlord.ckpt       -> LandlordLstmModel   (feature dim 373)
    landlord_up.ckpt    -> FarmerLstmModel     (feature dim 484)
    landlord_down.ckpt  -> FarmerLstmModel     (feature dim 484)

Each ONNX model takes:
    z : float32[N, 5, 162]   -- LSTM history input (15 actions grouped as 5x3)
    x : float32[N, 373|484]   -- per-action features
and returns:
    values : float32[N, 1]   -- predicted value per candidate action

The dynamic batch axis (N = number of legal actions) lets the Android
side call the model with whatever size the current move list happens to
have at runtime.

Usage:
    python convert_douzero_to_onnx.py \\
        --douzero-checkpoints ./_downloads \\
        --output-dir ../app/src/main/assets/models

If you don't have the checkpoints yet, run download_douzero_models.py
first to fetch them from Google Drive.

Architecture source:
    https://github.com/kwai/DouZero/blob/main/douzero/dmc/models.py
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import torch
import torch.nn as nn


class LandlordLstmModel(nn.Module):
    """Drop-in copy of DouZero's LandlordLstmModel, but with a clean
    export-friendly forward(z, x) -> values signature."""

    def __init__(self):
        super().__init__()
        self.lstm = nn.LSTM(162, 128, batch_first=True)
        self.dense1 = nn.Linear(373 + 128, 512)
        self.dense2 = nn.Linear(512, 512)
        self.dense3 = nn.Linear(512, 512)
        self.dense4 = nn.Linear(512, 512)
        self.dense5 = nn.Linear(512, 512)
        self.dense6 = nn.Linear(512, 1)

    def forward(self, z: torch.Tensor, x: torch.Tensor) -> torch.Tensor:
        lstm_out, _ = self.lstm(z)
        lstm_out = lstm_out[:, -1, :]
        h = torch.cat([lstm_out, x], dim=-1)
        h = torch.relu(self.dense1(h))
        h = torch.relu(self.dense2(h))
        h = torch.relu(self.dense3(h))
        h = torch.relu(self.dense4(h))
        h = torch.relu(self.dense5(h))
        return self.dense6(h)


class FarmerLstmModel(nn.Module):
    """Drop-in copy of DouZero's FarmerLstmModel (used by both farmers)."""

    def __init__(self):
        super().__init__()
        self.lstm = nn.LSTM(162, 128, batch_first=True)
        self.dense1 = nn.Linear(484 + 128, 512)
        self.dense2 = nn.Linear(512, 512)
        self.dense3 = nn.Linear(512, 512)
        self.dense4 = nn.Linear(512, 512)
        self.dense5 = nn.Linear(512, 512)
        self.dense6 = nn.Linear(512, 1)

    def forward(self, z: torch.Tensor, x: torch.Tensor) -> torch.Tensor:
        lstm_out, _ = self.lstm(z)
        lstm_out = lstm_out[:, -1, :]
        h = torch.cat([lstm_out, x], dim=-1)
        h = torch.relu(self.dense1(h))
        h = torch.relu(self.dense2(h))
        h = torch.relu(self.dense3(h))
        h = torch.relu(self.dense4(h))
        h = torch.relu(self.dense5(h))
        return self.dense6(h)


POSITION_TO_CLASS = {
    "landlord":      LandlordLstmModel,
    "landlord_up":   FarmerLstmModel,
    "landlord_down": FarmerLstmModel,
}


def convert_one(position: str, ckpt_path: Path, out_path: Path) -> None:
    if position not in POSITION_TO_CLASS:
        raise ValueError(f"unknown position: {position}")
    if not ckpt_path.is_file():
        raise FileNotFoundError(f"checkpoint not found: {ckpt_path}")

    print(f"[{position}] loading {ckpt_path}")
    # DouZero .ckpt files are pure state_dicts.
    state = torch.load(str(ckpt_path), map_location="cpu")
    if not isinstance(state, dict):
        raise RuntimeError(
            f"unexpected checkpoint type {type(state)} for {ckpt_path}"
        )
    # Some DouZero checkpoints wrap state_dict under "model_state_dict".
    if "model_state_dict" in state and position in state["model_state_dict"]:
        state = state["model_state_dict"][position]

    model = POSITION_TO_CLASS[position]()
    own = model.state_dict()
    # Filter to keys present in the architecture (DouZero sometimes adds
    # extra keys during training that aren't needed for inference).
    filtered = {k: v for k, v in state.items() if k in own}
    if not filtered:
        raise RuntimeError(
            f"none of the checkpoint keys matched the model for {position}; "
            f"first ckpt keys = {list(state.keys())[:5]}"
        )
    own.update(filtered)
    model.load_state_dict(own)
    model.eval()

    # Sample inputs for tracing. N=2 so the dynamic axis is visible.
    n = 2
    z_sample = torch.zeros((n, 5, 162), dtype=torch.float32)
    x_dim = 373 if position == "landlord" else 484
    x_sample = torch.zeros((n, x_dim), dtype=torch.float32)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    print(f"[{position}] exporting to {out_path}")
    # Use the legacy (TorchScript-based) exporter so the result is a single
    # self-contained .onnx file. The newer dynamo exporter splits weights
    # into a separate `.onnx.data` file which ONNX Runtime Android does not
    # load transparently from the app's assets directory.
    torch.onnx.export(
        model,
        (z_sample, x_sample),
        str(out_path),
        input_names=["z", "x"],
        output_names=["values"],
        dynamic_axes={
            "z":      {0: "n"},
            "x":      {0: "n"},
            "values": {0: "n"},
        },
        opset_version=14,
        dynamo=False,
    )
    print(f"[{position}] OK  ->  {out_path}  ({out_path.stat().st_size} bytes)")

    # Verify by reloading with onnxruntime.
    _verify(out_path, z_sample, x_sample)


def _verify(path: Path, z: torch.Tensor, x: torch.Tensor) -> None:
    import onnxruntime as ort
    sess = ort.InferenceSession(str(path))
    inputs = {sess.get_inputs()[0].name: z.numpy(),
              sess.get_inputs()[1].name: x.numpy()}
    out = sess.run(None, inputs)[0]
    print(f"  verify: shape={out.shape} dtype={out.dtype}  ok")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--douzero-checkpoints", "--src", required=True, type=Path,
        help="directory containing landlord.ckpt, landlord_up.ckpt, landlord_down.ckpt",
    )
    ap.add_argument(
        "--output-dir", "--dst", default=Path("../app/src/main/assets/models"),
        type=Path, help="where to write the .onnx files",
    )
    ap.add_argument(
        "--positions", nargs="+",
        default=["landlord", "landlord_up", "landlord_down"],
        help="subset of positions to convert",
    )
    args = ap.parse_args()

    # Locate checkpoints. DouZero ships them as both "landlord.ckpt" and
    # "<position>_weights_<frames>.ckpt" — accept either.
    for pos in args.positions:
        candidates = [
            args.douzero_checkpoints / f"{pos}.ckpt",
        ]
        # Also pick up the latest <pos>_weights_*.ckpt variant.
        weights = sorted(args.douzero_checkpoints.glob(f"{pos}_weights_*.ckpt"))
        if weights:
            candidates.append(weights[-1])
        ckpt = next((c for c in candidates if c.is_file()), None)
        if ckpt is None:
            print(f"[{pos}] SKIPPED — no checkpoint found in "
                  f"{args.douzero_checkpoints}", file=sys.stderr)
            continue
        convert_one(pos, ckpt, args.output_dir / f"{pos}.onnx")

    print("\nDone. Copy the resulting .onnx files into "
          "app/src/main/assets/models/ before building the APK.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
