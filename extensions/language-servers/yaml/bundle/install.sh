#!/bin/sh
set -eu
[ "${GITHUB_ACTIONS:-}" = true ] || { echo 'Cloud CI build only' >&2; exit 69; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing prerequisite: $1" >&2; exit 69; }; }
release=$1
src="$release/source"
need node; need npm
(cd "$src"; npm ci --no-audit --no-fund; npm run build)
