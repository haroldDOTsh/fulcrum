#!/usr/bin/env sh
set -eu

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker or a compatible container runtime is required before installing fulcrum." >&2
  exit 78
fi

SOURCE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TARGET_DIR="${FULCRUM_INSTALL_DIR:-$HOME/.fulcrum/bin}"
mkdir -p "$TARGET_DIR"
cp "$SOURCE_DIR/bin/fulcrum" "$TARGET_DIR/fulcrum"
chmod +x "$TARGET_DIR/fulcrum"
echo "Installed fulcrum to $TARGET_DIR/fulcrum"
