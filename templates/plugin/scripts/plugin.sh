#!/bin/sh
# SPDX-License-Identifier: EPL-2.0
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
command=${1:-dev}
case "$command" in setup|package|dev) ;; *) echo 'Expected setup, package or dev.' >&2; exit 1;; esac
exec java "$root/.astra-tools/AstraDev.java" "$command" --project "$root"
