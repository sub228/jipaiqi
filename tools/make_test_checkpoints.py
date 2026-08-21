#!/usr/bin/env python3
"""
Generate placeholder DouZero .ckpt checkpoints with the correct architecture
but random weights. Used only to:
  1. Verify convert_douzero_to_onnx.py works end-to-end.
  2. Produce a runnable APK with the ONNX models in assets/ so the inference
     pipeline can be exercised on-device.

For *real* AI recommendations, run download_douzero_models.py (which fetches
the pretrained .ckpt files from Google Drive) and then
convert_douzero_to_onnx.py to overwrite the placeholder .onnx files with
the pretrained ones.

Output:
    <dst>/landlord.ckpt
    <dst>/landlord_up.ckpt
    <dst>/landlord_down.ckpt
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import torch
import torch.nn as nn


class LandlordLstmModel(nn.Module):
    def __init__(self):
        super().__init__()
        self.lstm = nn.LSTM(162, 128, batch_first=True)
        self.dense1 = nn.Linear(373 + 128, 512)
        self.dense2 = nn.Linear(512, 512)
        self.dense3 = nn.Linear(512, 512)
        self.dense4 = nn.Linear(512, 512)
        self.dense5 = nn.Linear(512, 512)
        self.dense6 = nn.Linear(512, 1)

    def forward(self, z, x):
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
    def __init__(self):
        super().__init__()
        self.lstm = nn.LSTM(162, 128, batch_first=True)
        self.dense1 = nn.Linear(484 + 128, 512)
        self.dense2 = nn.Linear(512, 512)
        self.dense3 = nn.Linear(512, 512)
        self.dense4 = nn.Linear(512, 512)
        self.dense5 = nn.Linear(512, 512)
        self.dense6 = nn.Linear(512, 1)

    def forward(self, z, x):
        lstm_out, _ = self.lstm(z)
        lstm_out = lstm_out[:, -1, :]
        h = torch.cat([lstm_out, x], dim=-1)
        h = torch.relu(self.dense1(h))
        h = torch.relu(self.dense2(h))
        h = torch.relu(self.dense3(h))
        h = torch.relu(self.dense4(h))
        h = torch.relu(self.dense5(h))
        return self.dense6(h)


POSITIONS = {
    "landlord":      LandlordLstmModel,
    "landlord_up":   FarmerLstmModel,
    "landlord_down": FarmerLstmModel,
}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dst", default=Path("./_test_checkpoints"), type=Path)
    args = ap.parse_args()
    args.dst.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(42)
    for pos, cls in POSITIONS.items():
        m = cls()
        m.eval()
        out = args.dst / f"{pos}.ckpt"
        torch.save(m.state_dict(), out)
        print(f"[{pos}] wrote {out}  ({out.stat().st_size} bytes)")
    print("\nNow run convert_douzero_to_onnx.py --douzero-checkpoints",
          args.dst, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
