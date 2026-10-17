#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec java "$root/scripts/lib/AstraDev.java" new --source "$root" "$@"
