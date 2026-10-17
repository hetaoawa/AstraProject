#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
buildroot="$root/target"
if [ -n "${ASTRA_CACHE_ROOT:-}" ]; then
    buildroot="$ASTRA_CACHE_ROOT/framework-build"
    set -- "-Dastra.buildRoot=$buildroot" "$@"
fi
if [ -n "${ASTRA_MAVEN_REPOSITORY:-}" ]; then set -- "-Dmaven.repo.local=$ASTRA_MAVEN_REPOSITORY" "$@"; fi
if [ -n "${ASTRA_MAVEN_SETTINGS:-}" ]; then set -- "--settings=$ASTRA_MAVEN_SETTINGS" "$@"; fi
sh ./mvnw --batch-mode verify "$@"
exec sh scripts/package-release.sh --build-root "$buildroot"
