#!/bin/sh
set -eu
cd "$(dirname "$0")"
export HF_HOME="$PWD/.cache/huggingface"
export TOKENIZERS_PARALLELISM=false
export HF_HUB_OFFLINE=1
export TRANSFORMERS_OFFLINE=1
exec .venv/bin/python -m uvicorn server:app --host 127.0.0.1 --port "${PORT:-8765}"
