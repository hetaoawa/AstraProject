#!/bin/sh
set -eu
repository=hetaoawa/AstraProject
version=
directory=.
assets=
# Plugin information options are forwarded unchanged to the shared initializer.
while [ "$#" -gt 0 ]; do
    case "$1" in
        --version) version=$2; shift 2 ;;
        --directory) directory=$2; shift 2 ;;
        --repository) repository=$2; shift 2 ;;
        --assets) assets=$2; shift 2 ;;
        *) break ;;
    esac
done
command -v java >/dev/null && command -v jar >/dev/null || { echo 'Java 21 JDK is required.' >&2; exit 1; }
command -v curl >/dev/null || { echo 'curl is required.' >&2; exit 1; }
case "$repository" in *[!A-Za-z0-9_./-]*|*/*/*|/*|*/) echo 'Invalid repository.' >&2; exit 1;; esac
case "$repository" in */*) ;; *) echo 'Expected owner/repository.' >&2; exit 1;; esac
if [ -z "$version" ]; then
    printf 'Framework version (for example 0.1.1; Enter = latest stable release): '
    read -r version || version=
    version=${version:-latest}
fi
version=${version#framework-}
version=${version#v}
if [ "$version" != latest ]; then printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$' || exit 1; fi
mkdir -p "${ASTRA_CACHE_ROOT:-${TMPDIR:-/tmp}}"
stage=$(mktemp -d "${ASTRA_CACHE_ROOT:-${TMPDIR:-/tmp}}/astra-bootstrap.XXXXXXXX")
get_asset() {
    if [ -n "$assets" ]; then cp "$assets/$1" "$2"; else curl --fail --location --retry 2 "$base/$1" -o "$2"; fi
}
if [ "$version" = latest ]; then base="https://github.com/$repository/releases/latest/download"; else base="https://github.com/$repository/releases/download/framework-v$version"; fi
manifest="$stage/astra-release.properties"
get_asset astra-release.properties "$manifest"
property() { sed -n "s/^$1=//p" "$manifest" | tr -d '\r'; }
[ "$(property 'schema\.version')" = 1 ] || { echo 'Unsupported manifest schema.' >&2; exit 1; }
resolved=$(property 'framework\.version')
printf '%s\n' "$resolved" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$' || exit 1
[ "$version" = latest ] || [ "$resolved" = "$version" ] || { echo 'Release version mismatch.' >&2; exit 1; }
base="https://github.com/$repository/releases/download/framework-v$resolved"
file=$(property 'template\.file')
expected=$(property 'template\.sha256')
printf '%s\n' "$file" | grep -Eq '^[A-Za-z0-9._-]+\.zip$' || exit 1
printf '%s\n' "$expected" | grep -Eq '^[a-fA-F0-9]{64}$' || exit 1
get_asset "$file" "$stage/template.zip"
if command -v sha256sum >/dev/null; then actual=$(sha256sum "$stage/template.zip" | cut -d ' ' -f 1); else actual=$(shasum -a 256 "$stage/template.zip" | cut -d ' ' -f 1); fi
[ "$actual" = "$expected" ] || { echo 'Template SHA-256 mismatch.' >&2; exit 1; }
mkdir "$stage/template"
(cd "$stage/template" && jar xf "$stage/template.zip")
if [ -n "$assets" ]; then set -- --assets "$assets" "$@"; fi
exec java "$stage/template/tooling/AstraDev.java" init --template "$stage/template" --manifest "$manifest" --directory "$directory" --repository "$repository" "$@"
