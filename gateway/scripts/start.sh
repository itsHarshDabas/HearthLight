#!/usr/bin/env sh
# Linux/macOS: python3 -m venv .venv && .venv/bin/pip install -e . && scripts/start.sh
cd "$(dirname "$0")/.." && exec .venv/bin/local-ai-gateway --config config.toml "$@"
