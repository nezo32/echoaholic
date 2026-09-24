#!/usr/bin/env bash
# Offline tests for the SHA parsing of scripts/check-reusable-sync.sh (--print-sha mode, no network).
# Usage: bash scripts/test/check-reusable-sync.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/../check-reusable-sync.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

failures=0
pass() { echo "ok   - $*"; }
fail() { echo "FAIL - $*"; failures=$((failures + 1)); }

sha1=0123456789abcdef0123456789abcdef01234567
sha2=89abcdef0123456789abcdef0123456789abcdef

run() { # $1 = release.yml content; runs --print-sha -> $tmp/out, exit code -> $rc
  rm -rf "$tmp/repo"; mkdir -p "$tmp/repo/.github/workflows"
  printf '%s\n' "$1" > "$tmp/repo/.github/workflows/release.yml"
  rc=0
  SYNC_ROOT="$tmp/repo" bash "$script" --print-sha > "$tmp/out" 2>&1 || rc=$?
}

run "# header mentions nezo32/enchantaholic/.github/workflows/reusable-x.yml@main in a comment
jobs:
  version:
    uses: nezo32/enchantaholic/.github/workflows/reusable-version.yml@$sha1
  build:
    uses: nezo32/enchantaholic/.github/workflows/reusable-build-gradle.yml@$sha1   # trailing comment
  quoted:
    uses: \"nezo32/enchantaholic/.github/workflows/reusable-github-release.yml@$sha1\"
  local:
    uses: ./.github/workflows/other.yml"
if [[ $rc -eq 0 && "$(cat "$tmp/out")" == "$sha1" ]]; then
  pass "one pinned SHA (plain, commented, quoted) is printed; comments and local uses are ignored"
else
  fail "single SHA: rc=$rc $(cat "$tmp/out")"
fi

run "jobs:
  a:
    uses: nezo32/enchantaholic/.github/workflows/reusable-version.yml@$sha1
  b:
    uses: nezo32/enchantaholic/.github/workflows/reusable-build-gradle.yml@$sha2"
if [[ $rc -ne 0 ]] && grep -q 'several refs' "$tmp/out"; then
  pass "two different SHAs are rejected"
else
  fail "mixed SHAs: rc=$rc $(cat "$tmp/out")"
fi

run "jobs:
  a:
    uses: nezo32/enchantaholic/.github/workflows/reusable-version.yml@main"
if [[ $rc -ne 0 ]] && grep -q 'not a full 40-character commit SHA' "$tmp/out"; then
  pass "a branch ref (@main) is rejected"
else
  fail "@main: rc=$rc $(cat "$tmp/out")"
fi

run "jobs:
  a:
    uses: nezo32/enchantaholic/.github/workflows/reusable-version.yml@${sha1:0:12}"
if [[ $rc -ne 0 ]] && grep -q 'not a full 40-character commit SHA' "$tmp/out"; then
  pass "a short SHA is rejected"
else
  fail "short SHA: rc=$rc $(cat "$tmp/out")"
fi

run "jobs:
  a:
    uses: ./.github/workflows/reusable-version.yml"
if [[ $rc -ne 0 ]] && grep -q 'no .uses: nezo32/enchantaholic' "$tmp/out"; then
  pass "a release.yml without upstream references is rejected"
else
  fail "no references: rc=$rc $(cat "$tmp/out")"
fi

# the real release.yml of this repository parses
rc=0; out="$(bash "$script" --print-sha 2>&1)" || rc=$?
if [[ $rc -eq 0 && "$out" =~ ^[0-9a-f]{40}$ ]]; then
  pass "this repository's release.yml pins $out"
else
  fail "repository release.yml: rc=$rc $out"
fi

echo
if [[ $failures -gt 0 ]]; then
  echo "$failures test(s) failed"; exit 1
fi
echo "all tests passed"
