#!/bin/sh
set -eu
[ "${GITHUB_ACTIONS:-}" = true ] || { echo 'Cloud CI build only' >&2; exit 69; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing prerequisite: $1" >&2; exit 69; }; }
release=$1
src="$release/source"
need git; need cc; need c++; need ninja; need bash
# Gitlinks at the fixed parent commit pin all recursively fetched submodules.
git -C "$src" init -q
git -C "$src" remote add origin https://github.com/LuaLS/lua-language-server.git
git -C "$src" fetch --depth=1 origin a58fc7b01e1746c46275c71b0e61bfed40a6296d
git -C "$src" reset --mixed FETCH_HEAD
(cd "$src"; git submodule update --init --recursive; bash make.sh)
