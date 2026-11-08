#!/bin/sh
set -eu
[ "${GITHUB_ACTIONS:-}" = true ] || { echo 'Cloud CI build only' >&2; exit 69; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing prerequisite: $1" >&2; exit 69; }; }
release=$1
src="$release/source"
need cargo; need rustc; need cc
[ "$(rustc --version | cut -d ' ' -f 2)" = "1.98.0" ] || { echo 'Rust 1.98.0 required' >&2; exit 69; }
(cd "$src"; cargo build --locked --release --package rust-analyzer --bin rust-analyzer)
