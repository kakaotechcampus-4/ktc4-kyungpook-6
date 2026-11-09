#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
if [[ -x .venv/bin/python ]]; then
    python_bin=.venv/bin/python
else
    python_bin=python3
fi
exec "$python_bin" -m verification.cache_tts "$@"
