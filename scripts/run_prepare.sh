#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
VENV_DIR="${SCRIPT_DIR}/.venv"

if [[ ! -d "$VENV_DIR" ]]; then
    echo "[setup] Creating Python venv at $VENV_DIR"
    python3 -m venv "$VENV_DIR"
    "$VENV_DIR/bin/pip" install --upgrade pip
    "$VENV_DIR/bin/pip" install -r "$SCRIPT_DIR/requirements.txt"
fi

"$VENV_DIR/bin/python" "$SCRIPT_DIR/prepare_dataset.py" "$@"
