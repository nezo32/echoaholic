#!/usr/bin/env bash
# check-reusable-sync.sh — verify that the vendored copies of the shared release pipeline match upstream.
#
# ci.yml and release.yml call the reusable workflows of nezo32/enchantaholic pinned to a full commit SHA
# (docs/ci/REUSABLE_RELEASE_PIPELINE.md). This repository keeps byte-identical copies of those files so they can be
# read and reviewed here. This script:
#   1. reads the pinned SHA from the "uses:" lines of .github/workflows/release.yml (all must use the same one),
#   2. checks that ci.yml uses the same SHA and that every workflow the callers use is vendored locally,
#   3. downloads each vendored file from https://raw.githubusercontent.com/<upstream>/<sha>/<path> and compares it.
#
# Bumping the pipeline: change the SHA in every "uses:" line of release.yml and ci.yml, re-copy the files listed by
# this script from upstream at that SHA, then run it.
#
# Usage: scripts/check-reusable-sync.sh               (from anywhere inside the repo; needs curl + network)
#        scripts/check-reusable-sync.sh --print-sha   (only parse and print the pinned SHA; no network)
# Env:   SYNC_ROOT  repository root to check (default: the repo containing this script; used by the tests)
set -euo pipefail

upstream="nezo32/enchantaholic"
root="${SYNC_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
release="$root/.github/workflows/release.yml"
ci="$root/.github/workflows/ci.yml"

err() { echo "::error::$*" >&2; exit 1; }

# pinned_refs <file>: prints "<workflow-file> <ref>" for every `uses: <upstream>/.github/workflows/<x>.yml@<ref>` line
pinned_refs() {
  sed -nE "s|^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]*[\"']?${upstream}/\\.github/workflows/([A-Za-z0-9._-]+\\.ya?ml)@([^\"'[:space:]#]+).*|\\2 \\3|p" "$1"
}

# pinned_sha <file>: the single full SHA all upstream references in <file> use
pinned_sha() {
  local file="$1" refs shas
  [[ -f "$file" ]] || err "missing ${file#"$root"/}"
  refs="$(pinned_refs "$file")"
  [[ -n "$refs" ]] || err "no 'uses: ${upstream}/.github/workflows/<x>.yml@<sha>' line in ${file#"$root"/}"
  shas="$(cut -d' ' -f2 <<<"$refs" | sort -u)"
  [[ "$(wc -l <<<"$shas")" -eq 1 ]] || err "${file#"$root"/} pins ${upstream} to several refs: $(tr '\n' ' ' <<<"$shas")"
  [[ "$shas" =~ ^[0-9a-f]{40}$ ]] || err "${file#"$root"/} pins ${upstream} to '$shas', not a full 40-character commit SHA"
  echo "$shas"
}

sha="$(pinned_sha "$release")"
if [[ "${1:-}" == "--print-sha" ]]; then
  echo "$sha"
  exit 0
fi
[[ $# -eq 0 ]] || err "unknown argument: $1 (usage: $0 [--print-sha])"

if [[ -f "$ci" ]] && [[ -n "$(pinned_refs "$ci")" ]]; then
  ci_sha="$(pinned_sha "$ci")"
  [[ "$ci_sha" == "$sha" ]] || err "ci.yml pins ${upstream}@${ci_sha}, release.yml pins @${sha}: use the same SHA"
fi

# every workflow the callers use must be vendored
while read -r wf _; do
  [[ -f "$root/.github/workflows/$wf" ]] || err "callers use ${upstream}/.github/workflows/$wf, but .github/workflows/$wf is not vendored"
done < <({ pinned_refs "$release"; [[ -f "$ci" ]] && pinned_refs "$ci"; } | sort -u)

files=()
for f in "$root"/.github/workflows/reusable-*.yml; do
  [[ -e "$f" ]] && files+=("${f#"$root"/}")
done
files+=(scripts/curseforge-upload.sh)
[[ ${#files[@]} -gt 1 ]] || err "no vendored .github/workflows/reusable-*.yml found"

command -v curl >/dev/null || err "curl is required"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
failures=0
for path in "${files[@]}"; do
  url="https://raw.githubusercontent.com/${upstream}/${sha}/${path}"
  if ! curl -fsSL --retry 3 --retry-connrefused -o "$tmp/upstream" "$url"; then
    echo "::error::could not download $url (does the file exist at that SHA?)" >&2
    failures=$((failures + 1)); continue
  fi
  if cmp -s "$tmp/upstream" "$root/$path"; then
    echo "ok   $path"
  else
    echo "::error::$path differs from ${upstream}@${sha}. Re-copy it: curl -fsSL $url -o $path" >&2
    diff -u --label "upstream $path" --label "local $path" "$tmp/upstream" "$root/$path" | head -40 >&2 || true
    failures=$((failures + 1))
  fi
done

if [[ $failures -gt 0 ]]; then
  echo "::error::$failures vendored file(s) out of sync with ${upstream}@${sha}" >&2
  exit 1
fi
echo "OK: ${#files[@]} vendored files match ${upstream}@${sha}"
