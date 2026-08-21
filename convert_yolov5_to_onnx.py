#!/usr/bin/env python3
"""
Convert a YOLOv5 .pt model to ONNX for mobile inference.
Designed for converting Dou Dizhu card detection models.

Usage:
    python3 convert_yolov5_to_onnx.py \
        --weights app/build/outputs/apk/debug/best.pt \
        --output app/src/main/assets/models/yolo_cards.onnx \
        --imgsz 640
"""
import sys
import os
import argparse

# ======================================================================
# STEP 1: Monkey-patch before importing anything from yolov5
# ======================================================================
# We must patch cv2 BEFORE it gets imported by yolov5 modules.
# This avoids the numpy 1.x / 2.x incompatibility.
import types
_cv2_stub = types.ModuleType('cv2')
_cv2_stub.__version__ = 'stub'
_cv2_stub.setNumThreads = lambda n: None
sys.modules['cv2'] = _cv2_stub

# Patch ultralytics before yolov5 tries to import it
class _StubModule(types.ModuleType):
    """A module stub that auto-creates sub-modules on access."""
    def __init__(self, name):
        super().__init__(name)
        self.__path__ = []  # make it a package
        self.__file__ = f'<stub:{name}>'
        self.__package__ = name
    def __getattr__(self, item):
        if item.startswith('_'):
            raise AttributeError(item)
        sub = _StubModule(f'{self.__name__}.{item}')
        setattr(self, item, sub)
        sys.modules[f'{self.__name__}.{item}'] = sub
        return sub
    def __call__(self, *a, **kw):
        return self
    def __iter__(self):
        return iter([])
    def __enter__(self):
        return self
    def __exit__(self, *a):
        pass

_ultralytics_stub = _StubModule('ultralytics')
_ultralytics_stub.__version__ = 'stub'
sys.modules['ultralytics'] = _ultralytics_stub

# Pre-patch common sub-modules
_ultralytics_utils = _StubModule('ultralytics.utils')
sys.modules['ultralytics.utils'] = _ultralytics_utils
_ultralytics_utils_patches = _StubModule('ultralytics.utils.patches')
sys.modules['ultralytics.utils.patches'] = _ultralytics_utils_patches
_ultralytics_utils_patches.torch_load = lambda *a, **kw: torch.load(*a, **kw)
_ultralytics_data = _StubModule('ultralytics.data')
sys.modules['ultralytics.data'] = _ultralytics_data
_ultralytics_data_converter = _StubModule('ultralytics.data.converter')
sys.modules['ultralytics.data.converter'] = _ultralytics_data_converter

class _StubLOGGER:
    def info(self, *a, **kw): pass
    def warning(self, *a, **kw): pass
    def error(self, *a, **kw): pass
    def debug(self, *a, **kw): pass
    def __call__(self, *a, **kw): pass
LOGGER = _StubLOGGER()

_ultralytics_utils.LOGGER = LOGGER
_ultralytics_utils.TQDM = lambda x, **kw: x
_ultralytics_utils.colorstr = lambda x: str(x)
_ultralytics_utils.get_default_args = lambda f: {}
_ultralytics_utils.profile = lambda *a, **kw: None
_ultralytics_utils.clip_boxes = lambda *a, **kw: a[0] if a else None
_ultralytics_utils.make_divisible = lambda x, *a, **kw: x
_ultralytics_utils.segments2boxes = lambda x: x
_ultralytics_utils.xywh2xyxy = lambda x: x
_ultralytics_utils.xywhn2xyxy = lambda x: x
_ultralytics_utils.xyxy2xywhn = lambda x: x
_ultralytics_utils.TryExcept = lambda *a, **kw: (lambda f: f)
_ultralytics_utils.emojis = lambda x: x
_ultralytics_utils.threaded = lambda *a, **kw: (lambda f: f)
_ultralytics_utils.ASSETS = ''
_ultralytics_utils.SETTINGS = {}
_ultralytics_data_converter.coco80_to_coco91_class = lambda x: x

# Also patch pandas (used by utils/general.py)
_pandas_stub = types.ModuleType('pandas')
sys.modules['pandas'] = _pandas_stub

# Patch torchvision
_torchvision_stub = types.ModuleType('torchvision')
sys.modules['torchvision'] = _torchvision_stub

# Now add yolov5 to path and import
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'yolov5'))

import torch
import onnx
import argparse


def main():
    parser = argparse.ArgumentParser(description='Convert YOLOv5 .pt to ONNX')
    parser.add_argument('--weights', required=True, help='Path to .pt weights file')
    parser.add_argument('--output', required=True, help='Output ONNX file path')
    parser.add_argument('--imgsz', type=int, default=640, help='Input image size')
    parser.add_argument('--opset', type=int, default=12, help='ONNX opset version')
    args = parser.parse_args()

    from models.experimental import attempt_load

    # Load model
    print(f'Loading {args.weights}...')
    model = attempt_load(args.weights, device=torch.device('cpu'))
    model.eval()

    print(f'Model nc={model.nc}, names={model.names}')

    # Verify inference works
    dummy = torch.randn(1, 3, args.imgsz, args.imgsz)
    with torch.no_grad():
        pred = model(dummy)
        print(f'Raw output shapes: {[p.shape if isinstance(p, torch.Tensor) else [x.shape for x in p] for p in pred]}')

    # Export wrapper: returns flattened output [batch, 25200, 5+nc]
    class DetectExport(torch.nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model
        def forward(self, x):
            pred = self.model(x)
            return pred[0]  # flattened output

    export_model = DetectExport(model)

    # Export to ONNX
    os.makedirs(os.path.dirname(args.output) or '.', exist_ok=True)
    print(f'Exporting to {args.output}...')

    torch.onnx.export(
        export_model,
        dummy,
        args.output,
        input_names=['images'],
        output_names=['output0'],
        opset_version=args.opset,
        dynamic_axes={'images': {0: 'batch'}, 'output0': {0: 'batch'}},
    )

    # Verify
    onnx_model = onnx.load(args.output)
    inp_info = [(i.name, [d.dim_value for d in i.type.tensor_type.shape.dim]) for i in onnx_model.graph.input]
    out_info = [(o.name, [d.dim_value for d in o.type.tensor_type.shape.dim]) for o in onnx_model.graph.output]
    print(f'Input:  {inp_info}')
    print(f'Output: {out_info}')
    size_mb = os.path.getsize(args.output) / 1024 / 1024
    print(f'File size: {size_mb:.1f} MB')
    print('Done!')


if __name__ == '__main__':
    main()
