#!/usr/bin/env bash
# check-inlined-script.sh — verify that the heredoc copy of scripts/curseforge-upload.sh inlined in
# .github/workflows/reusable-publish-curseforge.yml is byte-identical to the script.
# (A called workflow cannot read files of the repo hosting it, hence the inlined copy.)
# Usage: scripts/check-inlined-script.sh   (from anywhere inside the repo)
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$root/scripts/curseforge-upload.sh"
workflow="$root/.github/workflows/reusable-publish-curseforge.yml"

[[ -f "$script" ]] || { echo "::error::missing $script" >&2; exit 1; }
[[ -f "$workflow" ]] || { echo "::error::missing $workflow" >&2; exit 1; }

inlined="$(mktemp)"; trap 'rm -f "$inlined"' EXIT
# lines between <<'CF_UPLOAD_SCRIPT' and the closing CF_UPLOAD_SCRIPT, minus the 10-space YAML indent
sed -n "/<<'CF_UPLOAD_SCRIPT'/,/^ *CF_UPLOAD_SCRIPT$/p" "$workflow" | sed '1d;$d;s/^          //' > "$inlined"

if [[ ! -s "$inlined" ]]; then
  echo "::error::no <<'CF_UPLOAD_SCRIPT' heredoc found in ${workflow#"$root"/}" >&2
  exit 1
fi

if ! diff -u --label "scripts/curseforge-upload.sh" --label "inlined copy in ${workflow#"$root"/}" "$script" "$inlined"; then
  echo "::error::The inlined upload script is out of date: copy the script into the heredoc of ${workflow#"$root"/} (indented 10 spaces)." >&2
  exit 1
fi
echo "OK: inlined copy matches scripts/curseforge-upload.sh"
