#!/usr/bin/env bash
# Dry-run tests for scripts/curseforge-upload.sh against the fixture JSON in scripts/test/fixtures/.
# No network, no token. Needs bash, jq. Usage: bash scripts/test/curseforge-upload.test.sh
set -euo pipefail
shopt -s extglob # must be on at parse time for the glob checks at the end

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/../curseforge-upload.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

failures=0
pass() { echo "ok   - $*"; }
fail() { echo "FAIL - $*"; failures=$((failures + 1)); }

# dummy build outputs
printf 'jar' > "$tmp/enchantaholic-1.2.0-beta.1.jar"
printf 'src' > "$tmp/enchantaholic-1.2.0-beta.1-sources.jar"

run_upload() { # runs the script in dry-run mode; combined output -> $tmp/out, exit code -> $rc
  rc=0
  env -u CF_TOKEN \
    CF_DRY_RUN=true CF_PROJECT_ID=1 \
    CF_VERSIONS_JSON="$here/fixtures/versions.json" CF_TYPES_JSON="$here/fixtures/types.json" \
    CF_RELEASE_TYPE=beta CF_CHANGELOG='## Changes' \
    GITHUB_OUTPUT="$tmp/github_output" \
    "$@" > "$tmp/out" 2>&1 || rc=$?
}

meta() { # $1 = n: the n-th (1-based) dry-run metadata JSON object printed by the script
  awk -v n="$1" '
    /^\[dry-run\] POST / { c++; grab = (c == n); next }
    grab { print; if ($0 == "}") exit }' "$tmp/out"
}

# --- main case: Fabric mod with a sources jar ---------------------------------------------
: > "$tmp/github_output"
run_upload \
  CF_GAME_VERSIONS='26.2,26.3,Fabric,Java 25,Client,Server' \
  CF_RELATIONS='fabric-api:requiredDependency' \
  bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar" "$tmp/enchantaholic-1.2.0-beta.1-sources.jar"
if [[ $rc -ne 0 ]]; then
  fail "dry-run exited $rc"; cat "$tmp/out"; exit 1
fi
primary="$(meta 1)"; child="$(meta 2)"

# (a) names resolve to the Minecraft/loader/java/environment ids (real Java-host ids); the Bukkit "26.2" (id 200)
#     and "26.3-snapshot" are not picked
if jq -e '.gameVersions == [7499,9638,9639,14454,16498,17045]' <<<"$primary" >/dev/null; then
  pass "(a) game versions resolve, Bukkit 26.2 and 26.3-snapshot excluded"
else
  fail "(a) unexpected gameVersions: $(jq -c .gameVersions <<<"$primary")"
fi

# (b) relations
if jq -e '.relations.projects == [{"slug":"fabric-api","type":"requiredDependency"}]' <<<"$primary" >/dev/null; then
  pass "(b) relations contain fabric-api requiredDependency"
else
  fail "(b) unexpected relations: $(jq -c .relations <<<"$primary")"
fi

# primary metadata basics
if jq -e '.releaseType == "beta" and .changelog == "## Changes" and .changelogType == "markdown"
          and .displayName == "enchantaholic-1.2.0-beta.1.jar"' <<<"$primary" >/dev/null; then
  pass "primary metadata (releaseType, changelog, displayName)"
else
  fail "primary metadata: $primary"
fi

# (c) the child file is attached via parentFileID and carries no gameVersions
if jq -e 'has("parentFileID") and (has("gameVersions") | not)
          and .displayName == "enchantaholic-1.2.0-beta.1-sources.jar"' <<<"$child" >/dev/null; then
  pass "(c) child file has parentFileID and no gameVersions"
else
  fail "(c) unexpected child metadata: ${child:-<none>}"
fi

if grep -qx 'file-id=0' "$tmp/github_output" && grep -q '^\[dry-run\] not uploaded' "$tmp/out"; then
  pass "file-id written to GITHUB_OUTPUT"
else
  fail "GITHUB_OUTPUT: $(cat "$tmp/github_output")"
fi

# --- (d) unknown version fails with suggestions ---------------------------------------------
run_upload CF_GAME_VERSIONS='26.9,Fabric' bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar"
if [[ $rc -ne 0 ]] && grep -q "Similar:" "$tmp/out" && grep -q "'26.9' not found" "$tmp/out"; then
  pass "(d) unknown 26.9 exits $rc with 'Similar:'"
else
  fail "(d) expected failure with 'Similar:', got rc=$rc: $(cat "$tmp/out")"
fi

# --- guards ---------------------------------------------------------------------------------
run_upload CF_DRY_RUN=false CF_GAME_VERSIONS='26.2' bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar"
if [[ $rc -ne 0 ]] && grep -q "CF_TOKEN is required" "$tmp/out"; then
  pass "real upload without CF_TOKEN is refused"
else
  fail "missing token not rejected: rc=$rc $(cat "$tmp/out")"
fi

run_upload CF_GAME_VERSIONS='26.2' CF_RELATIONS='fabric-api:needed' bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar"
if [[ $rc -ne 0 ]] && grep -q "bad relation type" "$tmp/out"; then
  pass "bad relation type is rejected"
else
  fail "bad relation type not rejected: rc=$rc"
fi

# --- Bedrock host: no usable /game/version-types, so prefixes are "" and types are not read ----
run_upload CF_TYPE_PREFIXES='' CF_TYPES_JSON=/nonexistent CF_VERSIONS_JSON="$here/fixtures/versions-bedrock.json" \
  CF_GAME_VERSIONS='26.50' bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar"
if [[ $rc -eq 0 ]] && jq -e '.gameVersions == [15002]' <<<"$(meta 1)" >/dev/null; then
  pass "Bedrock: CF_TYPE_PREFIXES='' resolves 26.50 without version types"
else
  fail "Bedrock resolution: rc=$rc $(cat "$tmp/out")"
fi

echo '' > "$tmp/empty-types.json"
run_upload CF_TYPES_JSON="$tmp/empty-types.json" CF_GAME_VERSIONS='26.2' bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar"
if [[ $rc -ne 0 ]] && grep -q 'returned no version types' "$tmp/out"; then
  pass "empty version-types with prefixes set fails with a hint"
else
  fail "empty version-types: rc=$rc $(cat "$tmp/out")"
fi

run_upload CF_GAME_VERSIONS=' , ' bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar"
if [[ $rc -ne 0 ]] && grep -q 'no game versions given' "$tmp/out"; then
  pass "empty game versions are rejected"
else
  fail "empty game versions: rc=$rc $(cat "$tmp/out")"
fi

run_upload CF_RELEASE_TYPE=rc CF_GAME_VERSIONS='26.2' bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar"
if [[ $rc -ne 0 ]] && grep -q 'CF_RELEASE_TYPE must be' "$tmp/out"; then
  pass "invalid release type is rejected"
else
  fail "invalid release type: rc=$rc"
fi

mkdir -p "$tmp/nojq"; ln -s "$(command -v bash)" "$tmp/nojq/bash"; ln -s "$(command -v curl)" "$tmp/nojq/curl"
run_upload PATH="$tmp/nojq" CF_GAME_VERSIONS='26.2' bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar"
if [[ $rc -ne 0 ]] && grep -q 'jq is required' "$tmp/out"; then
  pass "missing jq is reported"
else
  fail "missing jq: rc=$rc $(cat "$tmp/out")"
fi

# --- real upload path against a local mock of the Upload API (needs python3) ----------------
mock_pid=""
start_mock() { # $1 = types json ("-" = empty body), $2 = upload status sequence
  stop_mock
  rm -rf "$tmp/mock"; mkdir -p "$tmp/mock"
  python3 "$here/mock_curseforge.py" "$tmp/mock/port" "$tmp/mock" "$3" "$1" "$2" &
  mock_pid=$!
  for _ in $(seq 50); do [[ -s "$tmp/mock/port" ]] && break; sleep 0.1; done
  mock_url="http://127.0.0.1:$(cat "$tmp/mock/port")"
}
stop_mock() { if [[ -n "$mock_pid" ]]; then kill "$mock_pid" 2>/dev/null || true; wait "$mock_pid" 2>/dev/null || true; mock_pid=""; fi; }
trap 'stop_mock; rm -rf "$tmp"' EXIT

run_real() { # like run_upload, but uploads to the mock
  run_upload CF_DRY_RUN=false CF_TOKEN=test-token CF_API_BASE="$mock_url/" CF_RETRY_DELAY=1 \
    CF_VERSIONS_JSON= CF_TYPES_JSON= "$@"
}

if command -v python3 >/dev/null; then
  # changelog with markdown, quotes, backslashes, curl -F specials (; , = @ <), tabs, CRLF and unicode
  changelog=$'## What\'s new\n\n* "quoted" & \'single\' `code` \\backslash\\ $HOME $(id)\n* semi;colon, comma; type=text/plain;filename=x\n@notafile <notafile\n\tTab — ünïcödé ✨ 日本語\r\nlast line'
  mkdir -p "$tmp/extra dir"; printf 'x' > "$tmp/extra dir/my mod-1.0.0-extra.jar"
  start_mock "$here/fixtures/types.json" 200 "$here/fixtures/versions.json"
  : > "$tmp/github_output"
  run_real CF_CHANGELOG="$changelog" CF_DISPLAY_NAME='Enchantaholic 1.2.0 "beta" (Fabric)' \
    CF_GAME_VERSIONS=$'26.2\nFabric' CF_RELATIONS=' fabric-api : requiredDependency , modmenu:optionalDependency ' \
    bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar" "$tmp/enchantaholic-1.2.0-beta.1-sources.jar" "$tmp/extra dir/my mod-1.0.0-extra.jar"
  u0="$tmp/mock/upload-0.json"
  if [[ $rc -eq 0 ]] && jq -e --arg c "$changelog" '.metadata.changelog == $c and .token == "test-token"
        and .metadata_content_type == "application/json" and .path == "/api/projects/1/upload-file"
        and .metadata.displayName == "Enchantaholic 1.2.0 \"beta\" (Fabric)" and .metadata.gameVersions == [7499,16498]
        and .metadata.relations.projects == [{"slug":"fabric-api","type":"requiredDependency"},{"slug":"modmenu","type":"optionalDependency"}]
        and .filename == "enchantaholic-1.2.0-beta.1.jar" and .file == "jar"' "$u0" >/dev/null; then
    pass "upload: tricky changelog/display name survive multipart + JSON escaping byte-for-byte"
  else
    fail "upload metadata: rc=$rc $(cat "$tmp/out"; cat "$u0" 2>/dev/null)"
  fi
  if jq -e '.metadata.parentFileID == 1001 and (.metadata | has("gameVersions") | not) and .filename == "my mod-1.0.0-extra.jar"' \
       "$tmp/mock/upload-2.json" >/dev/null 2>&1 \
     && grep -qx 'file-id=1001' "$tmp/github_output" && grep -qx 'file-ids=1001,1002,1003' "$tmp/github_output"; then
    pass "upload: multiple child files (incl. space in name) attach to the primary; outputs file-id(s)"
  else
    fail "child uploads: $(cat "$tmp/github_output"; cat "$tmp/mock/upload-2.json" 2>/dev/null)"
  fi

  # a 5xx may still have created the file on CurseForge, so the POST must not be re-sent
  start_mock "$here/fixtures/types.json" 503,200 "$here/fixtures/versions.json"
  run_real CF_GAME_VERSIONS='26.2' bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar"
  if [[ $rc -ne 0 ]] && [[ -f "$tmp/mock/upload-0.json" && ! -f "$tmp/mock/upload-1.json" ]] \
     && grep -q 'HTTP 503.*Files page' "$tmp/out"; then
    pass "upload: HTTP 503 is not retried (no duplicate file) and says to check CurseForge"
  else
    fail "no retry on 5xx: rc=$rc $(cat "$tmp/out")"
  fi

  start_mock "$here/fixtures/types.json" 400 "$here/fixtures/versions.json"
  run_real CF_GAME_VERSIONS='26.2' bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar"
  if [[ $rc -ne 0 ]] && grep -q 'failed (HTTP 400).*mock failure 400' "$tmp/out" && [[ ! -f "$tmp/mock/upload-1.json" ]]; then
    pass "upload: HTTP 400 fails once (no retry) and shows the API error"
  else
    fail "HTTP 400: rc=$rc $(cat "$tmp/out")"
  fi

  start_mock - 200 "$here/fixtures/versions-bedrock.json"
  run_real CF_TYPE_PREFIXES='' CF_GAME_VERSIONS='26.50' bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar"
  if [[ $rc -eq 0 ]] && jq -e '.metadata.gameVersions == [15002]' "$tmp/mock/upload-0.json" >/dev/null; then
    pass "upload: Bedrock-like host (empty version-types body) works with CF_TYPE_PREFIXES=''"
  else
    fail "Bedrock mock: rc=$rc $(cat "$tmp/out")"
  fi

  # CF_RETRY_DELAY=1 with 4 retries would take >= 4s if the 403 were retried
  started=$SECONDS
  run_real CF_TOKEN=wrong CF_GAME_VERSIONS='26.2' bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar"
  if [[ $rc -ne 0 ]] && [[ ! -f "$tmp/mock/upload-1.json" ]] && (( SECONDS - started < 3 )); then
    pass "upload: rejected token (403) fails at once, before any upload"
  else
    fail "bad token: rc=$rc $(cat "$tmp/out")"
  fi
  stop_mock

  # nothing listening: the request never reached the server, so the upload is retried, then fails
  run_upload CF_DRY_RUN=false CF_TOKEN=test-token CF_API_BASE="http://127.0.0.1:1" CF_RETRY_DELAY=0 \
    CF_GAME_VERSIONS='26.2' bash "$script" "$tmp/enchantaholic-1.2.0-beta.1.jar"
  if [[ $rc -ne 0 ]] && [[ "$(grep -c 'could not reach' "$tmp/out")" -eq 3 ]] && grep -q 'curl exit 7' "$tmp/out"; then
    pass "upload: connection errors are retried 3 times, then fail"
  else
    fail "connection retry: rc=$rc $(cat "$tmp/out")"
  fi
else
  echo "skip - python3 not found: mock upload tests"
fi

# --- file globs used by the callers (release.yml / release-caller.yml) ------------------------
if (
  shopt -s nullglob
  cd "$tmp"
  primary_glob=( enchantaholic-!(*-sources).jar )
  template_glob=( !(*-sources).jar )
  [[ "${primary_glob[*]}" == "enchantaholic-1.2.0-beta.1.jar" && "${template_glob[*]}" == "enchantaholic-1.2.0-beta.1.jar" ]]
); then
  pass "primary-file globs match exactly the main jar"
else
  fail "primary-file globs"
fi

echo
if [[ $failures -gt 0 ]]; then
  echo "$failures test(s) failed"; exit 1
fi
echo "all tests passed"
