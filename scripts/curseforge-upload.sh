#!/usr/bin/env bash
# curseforge-upload.sh — upload one primary file (+ optional child files) to CurseForge
# via the legacy "Upload API" (https://support.curseforge.com/.../9000197321-curseforge-upload-api).
# Dependencies: bash, curl, jq (all preinstalled on ubuntu-latest runners).
#
# Usage: curseforge-upload.sh <primary-file> [additional-file ...]
#
# Environment:
#   CF_TOKEN            (required) API token (Authors portal -> API tokens). Sent as X-Api-Token.
#   CF_PROJECT_ID       (required) numeric project id.
#   CF_API_BASE         default https://minecraft.curseforge.com  (per-game host; Bedrock: https://minecraft-bedrock.curseforge.com)
#   CF_GAME_VERSIONS    names, comma/newline separated, e.g. "26.2,26.3,Fabric,Java 25,Client,Server"
#   CF_TYPE_PREFIXES    comma list of version-type slug prefixes to search in.
#                       default "minecraft,modloader,java,environment"; set to "" to search all types
#                       (required for Bedrock, whose /game/version-types returns no list; types are then not fetched).
#   CF_RELEASE_TYPE     release | beta | alpha                      (default release)
#   CF_DISPLAY_NAME     display name of the primary file           (default: file name)
#   CF_CHANGELOG        changelog text (or CF_CHANGELOG_FILE=path)
#   CF_CHANGELOG_TYPE   markdown | text | html                     (default markdown)
#   CF_RELATIONS        "slug:type,slug:type"  type in requiredDependency|optionalDependency|
#                       embeddedLibrary|tool|incompatible          e.g. "fabric-api:requiredDependency"
#   CF_DRY_RUN          "true" = resolve ids and print metadata, do not upload
#   CF_RETRY_DELAY      seconds between retries (default 5; uploads wait twice as long). GETs retry on
#                       network errors, 408/429/5xx; uploads retry only when CurseForge was never reached
#                       (DNS/connect/TLS), because a POST that got through may have created the file.
#   CF_VERSIONS_JSON / CF_TYPES_JSON  optional local JSON files instead of calling the API (testing)
# Outputs (when $GITHUB_OUTPUT is set): file-id, file-ids (comma list)
set -euo pipefail

die() { echo "::error::$*" >&2; exit 1; }

for c in curl jq; do command -v "$c" >/dev/null 2>&1 || die "$c is required but not installed"; done
[[ $# -ge 1 ]] || die "usage: $0 <primary-file> [additional-file ...]"
: "${CF_PROJECT_ID:?CF_PROJECT_ID is required}"
[[ "$CF_PROJECT_ID" =~ ^[0-9]+$ ]] || die "CF_PROJECT_ID must be numeric (got '$CF_PROJECT_ID')"
if [[ "${CF_DRY_RUN:-false}" != "true" ]]; then : "${CF_TOKEN:?CF_TOKEN is required}"; fi
for f in "$@"; do [[ -f "$f" ]] || die "file not found: $f"; done
[[ "${CF_GAME_VERSIONS:-}" =~ [^[:space:],] ]] || die "no game versions given (CF_GAME_VERSIONS)"
RETRY_DELAY="${CF_RETRY_DELAY:-5}"
[[ "$RETRY_DELAY" =~ ^[0-9]+$ ]] || die "CF_RETRY_DELAY must be a number of seconds"

API="${CF_API_BASE:-https://minecraft.curseforge.com}"; API="${API%/}/api"
RELEASE_TYPE="${CF_RELEASE_TYPE:-release}"
case "$RELEASE_TYPE" in release|beta|alpha) ;; *) die "CF_RELEASE_TYPE must be release|beta|alpha";; esac
TYPE_PREFIXES="${CF_TYPE_PREFIXES-minecraft,modloader,java,environment}"

cf_get() { # $1 = path
  curl -fsS --retry 4 --retry-connrefused --retry-delay "$RETRY_DELAY" \
    -H "X-Api-Token: ${CF_TOKEN:-}" "$API$1"
}

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
if [[ -n "${CF_VERSIONS_JSON:-}" ]]; then cp "$CF_VERSIONS_JSON" "$tmp/versions.json"; else cf_get /game/versions > "$tmp/versions.json"; fi
# version types are only needed to filter by slug prefix; some hosts (e.g. Bedrock) return no usable list
if [[ -z "$TYPE_PREFIXES" ]]; then echo '[]' > "$tmp/types.json"
elif [[ -n "${CF_TYPES_JSON:-}" ]]; then cp "$CF_TYPES_JSON" "$tmp/types.json"
else cf_get /game/version-types > "$tmp/types.json"; fi
jq -e 'type == "array"' "$tmp/versions.json" >/dev/null 2>&1 || die "GET /game/versions did not return a JSON array (check CF_API_BASE / CF_TOKEN)"
if [[ -n "$TYPE_PREFIXES" ]] && ! jq -e 'type == "array" and length > 0' "$tmp/types.json" >/dev/null 2>&1; then
  die "GET /game/version-types returned no version types; set CF_TYPE_PREFIXES=\"\" to search all types"
fi

# --- resolve game version names -> ids -------------------------------------------------
ids_json='[]'
while IFS= read -r name; do
  name="$(echo "$name" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  [[ -z "$name" ]] && continue
  matches="$(jq -c --arg name "$name" --arg prefixes "$TYPE_PREFIXES" --slurpfile types "$tmp/types.json" '
      ($prefixes | split(",") | map(select(length > 0))) as $p
    | (if ($p | length) == 0 then null
       else $types[0] | map(select(.slug as $s | any($p[]; . as $x | $s | startswith($x)))) | map(.id) end) as $allowed
    | [ .[] | select((.name | ascii_downcase) == ($name | ascii_downcase))
            | select($allowed == null or (.gameVersionTypeID as $t | $allowed | index($t) != null)) ]
    | map({id, name, gameVersionTypeID})' "$tmp/versions.json")"
  count="$(jq 'length' <<<"$matches")"
  if [[ "$count" -eq 0 ]]; then
    hint="$(jq -r --arg n "${name%%.*}" '[.[] | select(.name | startswith($n)) | .name] | unique | .[:15] | join(", ")' "$tmp/versions.json")"
    die "CurseForge game version '$name' not found (type prefixes: '${TYPE_PREFIXES}'). Similar: ${hint:-none}"
  fi
  if [[ "$count" -gt 1 ]]; then
    echo "::warning::'$name' matched $count versions ($(jq -c 'map(.id)' <<<"$matches")); using the highest id"
  fi
  id="$(jq 'max_by(.id).id' <<<"$matches")"
  echo "resolved '$name' -> $id"
  ids_json="$(jq -c --argjson id "$id" '. + [$id] | unique' <<<"$ids_json")"
done < <(tr ',' '\n' <<<"${CF_GAME_VERSIONS:-}")

[[ "$(jq length <<<"$ids_json")" -gt 0 ]] || die "no game versions given (CF_GAME_VERSIONS)"

# --- changelog / relations -----------------------------------------------------------
changelog="${CF_CHANGELOG:-}"
if [[ -z "$changelog" && -n "${CF_CHANGELOG_FILE:-}" ]]; then changelog="$(cat "$CF_CHANGELOG_FILE")"; fi
[[ -n "$changelog" ]] || changelog="No changelog provided."

relations='[]'
IFS=',' read -r -a rels <<<"${CF_RELATIONS:-}"
for r in "${rels[@]}"; do
  r="$(echo "$r" | tr -d '[:space:]')"; [[ -z "$r" ]] && continue
  slug="${r%%:*}"; rtype="${r#*:}"; [[ "$rtype" == "$r" ]] && rtype=requiredDependency
  case "$rtype" in requiredDependency|optionalDependency|embeddedLibrary|tool|incompatible) ;;
    *) die "bad relation type '$rtype' for '$slug'";; esac
  relations="$(jq -c --arg s "$slug" --arg t "$rtype" '. + [{slug:$s, type:$t}]' <<<"$relations")"
done

# --- upload ---------------------------------------------------------------------------
upload() { # $1 = file, $2 = metadata json
  if [[ "${CF_DRY_RUN:-false}" == "true" ]]; then
    echo "[dry-run] POST $API/projects/$CF_PROJECT_ID/upload-file  file=$1" >&2
    jq . <<<"$2" >&2; echo 0; return
  fi
  local resp code rc attempt
  # metadata goes through a file: an inline -F value would be cut at the first ';' (e.g. in the changelog)
  printf '%s' "$2" > "$tmp/metadata.json"
  # CurseForge uploads are not idempotent: only retry when the request never reached the server
  # (curl exit 5/6 = DNS, 7 = connect, 35 = TLS handshake). HTTP errors and timeouts are never re-sent.
  for attempt in 1 2 3 4; do
    rc=0
    resp="$(curl -sS -w '\n%{http_code}' \
      -H "X-Api-Token: $CF_TOKEN" \
      -F "metadata=<$tmp/metadata.json;type=application/json" \
      -F "file=@$1" \
      "$API/projects/$CF_PROJECT_ID/upload-file")" || rc=$?
    case "$rc" in 5|6|7|35) [[ $attempt -lt 4 ]] || break ;; *) break ;; esac
    echo "::warning::could not reach $API (curl exit $rc), retrying in $((RETRY_DELAY * 2))s" >&2
    sleep "$((RETRY_DELAY * 2))"
  done
  local check="check the project's Files page on CurseForge before re-running: the file may have been created"
  [[ $rc -eq 0 ]] || die "upload of $1 failed (curl exit $rc); $check"
  code="${resp##*$'\n'}"; resp="${resp%$'\n'*}"
  if [[ "$code" == 5* ]]; then die "upload of $1 failed (HTTP $code): $resp; $check"; fi
  [[ "$code" == 2* ]] || die "upload of $1 failed (HTTP $code): $resp"
  jq -er '.id' <<<"$resp" || die "unexpected response: $resp"
}

primary="$1"; shift
meta="$(jq -nc \
  --arg changelog "$changelog" \
  --arg ctype "${CF_CHANGELOG_TYPE:-markdown}" \
  --arg name "${CF_DISPLAY_NAME:-$(basename "$primary")}" \
  --arg rtype "$RELEASE_TYPE" \
  --argjson gv "$ids_json" \
  --argjson rel "$relations" \
  '{changelog:$changelog, changelogType:$ctype, displayName:$name, gameVersions:$gv, releaseType:$rtype}
   + (if ($rel | length) > 0 then {relations:{projects:$rel}} else {} end)')"
file_id="$(upload "$primary" "$meta")"
done_msg="uploaded"; [[ "${CF_DRY_RUN:-false}" == "true" ]] && done_msg="[dry-run] not uploaded"
echo "$done_msg $primary -> file id $file_id"
all_ids="$file_id"

# child files (e.g. sources jar) are attached to the primary via parentFileID; they must NOT carry gameVersions
for extra in "$@"; do
  child_meta="$(jq -nc --arg changelog "$changelog" --arg ctype "${CF_CHANGELOG_TYPE:-markdown}" \
    --arg rtype "$RELEASE_TYPE" --argjson parent "$file_id" --arg name "$(basename "$extra")" \
    '{changelog:$changelog, changelogType:$ctype, displayName:$name, parentFileID:$parent, releaseType:$rtype}')"
  cid="$(upload "$extra" "$child_meta")"
  echo "$done_msg $extra -> file id $cid (child of $file_id)"
  all_ids="$all_ids,$cid"
done

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  { echo "file-id=$file_id"; echo "file-ids=$all_ids"; } >> "$GITHUB_OUTPUT"
fi
