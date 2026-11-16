"""Download a complete MLX snapshot into this project."""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent
os.environ.setdefault("HF_HOME", str(ROOT / ".cache" / "huggingface"))
from huggingface_hub import snapshot_download

MODEL = "mlx-community/Qwen3-TTS-12Hz-1.7B-CustomVoice-8bit"
REVISION = "41d3337e8b7f2843a75841595fc14e4b9a7a4b96"
if __name__ == "__main__":
    path = snapshot_download(MODEL, revision=REVISION,
                             local_dir=ROOT / "models" / MODEL.split("/")[-1])
    print(f"Model downloaded to {path}")
