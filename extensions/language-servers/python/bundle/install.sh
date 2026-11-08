#!/bin/sh
set -eu
[ "${GITHUB_ACTIONS:-}" = true ] || { echo 'Cloud CI build only' >&2; exit 69; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing prerequisite: $1" >&2; exit 69; }; }
release=$1
src="$release/source"
need python3
bundle=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
python3 -m pip install --require-hashes --only-binary=:all: --target "$release/lib" -r "$bundle/../requirements.lock"
PYTHONPATH="$release/lib" SETUPTOOLS_SCM_PRETEND_VERSION=0.dev0+ga3620069a607 python3 -m pip install --no-build-isolation --no-deps --target "$release/lib" "$src"
