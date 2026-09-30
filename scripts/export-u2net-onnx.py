#!/usr/bin/env python3
"""Export U-2-Net to ONNX from the upstream PyTorch checkpoint — for deployments that want a
fully audited provenance chain instead of the rembg-converted artifact the catalog pins.

    pip install torch onnx --index-url https://download.pytorch.org/whl/cpu
    python scripts/export-u2net-onnx.py --weights u2net.pth --variant u2net --out u2net.onnx

The script fetches the pinned upstream model definition (xuebinqin/U-2-Net, model/u2net.py at
MODEL_REV), loads the checkpoint, exports the d0 output (the fused sigmoid saliency map), and
prints the sha256 to record in app/proprietary/src/main/resources/matting/model-catalog.json.

Upstream checkpoints (xuebinqin/U-2-Net README "Model" links):
  u2net.pth           full model, salient object detection
  u2netp.pth          lite variant
  u2net_human_seg.pth trained on Supervisely person dataset
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import sys
import urllib.request
from pathlib import Path

# Pinned so a re-export uses the same model definition. Output is not byte-reproducible
# (protobuf/opset metadata shifts with the exporter), so check equivalence by running both
# graphs, not by hash.
MODEL_URL = (
    "https://raw.githubusercontent.com/xuebinqin/U-2-Net/"
    "5d7e20d5bad583d22123a4829358f25868c2d79f/model/u2net.py"
)
INPUT_SIZE = 320
OPSET = 17

VARIANTS = {
    "u2net": {"cls": "U2NET", "args": (3, 1)},
    "u2netp": {"cls": "U2NETP", "args": (3, 1)},
    "u2net_human_seg": {"cls": "U2NET", "args": (3, 1)},
}


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_model_def(cache: Path):
    """Import the pinned upstream u2net.py (downloads once into --workdir)."""
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not cache.exists():
        print(f"downloading {MODEL_URL}")
        with urllib.request.urlopen(MODEL_URL) as response, cache.open("wb") as out:
            out.write(response.read())
    spec = importlib.util.spec_from_file_location("u2net_upstream", cache)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", required=True, type=Path, help="upstream .pth checkpoint")
    parser.add_argument("--variant", choices=sorted(VARIANTS), required=True)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument(
        "--workdir", type=Path, default=Path("build/u2net-export"), help="scratch dir"
    )
    args = parser.parse_args()

    import torch

    module = load_model_def(args.workdir / "u2net.py")
    cls = getattr(module, VARIANTS[args.variant]["cls"])
    model = cls(*VARIANTS[args.variant]["args"])
    model.load_state_dict(torch.load(args.weights, map_location="cpu", weights_only=True))
    model.eval()

    # The graph returns (d0..d6); downstream code only consumes the fused d0 map.
    class Head(torch.nn.Module):
        def __init__(self, net):
            super().__init__()
            self.net = net

        def forward(self, x):
            return torch.sigmoid(self.net(x)[0])

    wrapped = Head(model)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    dummy = torch.zeros(1, 3, INPUT_SIZE, INPUT_SIZE)
    torch.onnx.export(
        wrapped,
        dummy,
        args.out,
        opset_version=OPSET,
        input_names=["input"],
        output_names=["d0"],
        dynamic_axes={
            "input": {0: "n", 2: "h", 3: "w"},
            "d0": {0: "n", 2: "h", 3: "w"},
        },
    )
    print(f"exported {args.out}  sha256={sha256_of(args.out)}")
    print("record this hash in matting/model-catalog.json for your own mirror")


if __name__ == "__main__":
    main()
