# Releasing Enchantaholic

Maintainer runbook. How the pipeline works in general: [REUSABLE_RELEASE_PIPELINE.md](REUSABLE_RELEASE_PIPELINE.md).
Branch and PR rules: [CONTRIBUTING.md](../../CONTRIBUTING.md).

A release is a tag. Pushing `vX.Y.Z` on a commit of `main` starts `.github/workflows/release.yml`, which in one run:

1. `version`: parses the tag and checks that it is on `main`.
2. `build-mod` and `build-addon`: `./gradlew build -Pmod_version=X.Y.Z` in `fabric/`, and `npm ci`, typecheck,
   lint, test, `npm run package` with `VERSION=X.Y.Z` in `bedrock/`.
3. `github-release`: the release "Enchantaholic X.Y.Z", with generated notes and three assets:
   `enchantaholic-X.Y.Z.jar`, `enchantaholic-X.Y.Z-sources.jar` and `enchantaholic-X.Y.Z.mcaddon`.
4. `curseforge-mod`: uploads the jar with the sources jar as a child file, using the release notes as the changelog.
   `curseforge-addon` does the same for the add-on if a Bedrock project is configured.

Never edit `mod_version` in `fabric/gradle.properties`, `version` in `bedrock/package.json` or the versions in
`bedrock/pack/manifest.json` by hand. They are development placeholders (`0.0.0`; the manifest versions are overwritten
at build time); the tag sets the real version.

## Cutting a release

```bash
git switch main && git pull --ff-only
git log --oneline -5                         # the commit you are about to release
git tag -a v1.2.0 -m v1.2.0
git push origin v1.2.0
```

Then watch Actions → Release. When it is green, check the GitHub release and the CurseForge file page.

Before tagging, look at the merged PRs since the last release: their titles and labels are the release notes. Fix a
title or label on the PR now if needed; the notes are generated when the release job runs.

## Pre-releases

Use a SemVer pre-release suffix:

| Tag | GitHub | CurseForge |
|---|---|---|
| `v1.3.0-alpha.1` | pre-release | alpha |
| `v1.3.0-beta.1` | pre-release | beta |
| `v1.3.0-rc.1` | pre-release | beta |
| `v1.3.0` | latest release | release |

Pre-releases are never marked "latest" on GitHub. Bedrock manifests get `[1, 3, 0]` for all of them, since Bedrock
versions have no pre-release part. Bedrock identifies a pack by UUID and version, so testers who imported
`1.3.0-rc.1` must remove that pack before importing `1.3.0` (or the import is reported as a duplicate).

## Hotfix

1. Branch from `main`: `git switch -c hotfix/<short-name> origin/main`.
2. Fix, open a PR to `main`, and squash-merge it once the checks pass.
3. Tag the merge commit with the next patch version: `git tag -a v1.2.1 -m v1.2.1 && git push origin v1.2.1`.

There are no long-lived release branches. A tag on anything other than a commit of `main` fails in the `version` job.

## Re-running and dry runs

- **Rebuild an existing tag and update its GitHub release** (for example after a failed build): Actions → Release →
  Run workflow, `tag: v1.2.0`, tick `skip-curseforge`.
- **Only a CurseForge job failed:** fix the cause (usually a game version name in the repo variable), then open the
  failed run and use "Re-run failed jobs". The build artifacts are reused.
- **Dry run of the CurseForge upload:** Run workflow with `tag: <existing tag>` and `curseforge-dry-run: true`.
  The CurseForge jobs resolve the version names and print the metadata without uploading. The GitHub release for that
  tag is still rebuilt and updated.
- **CurseForge has no idempotency:** re-running a successful upload creates a second file. Delete the duplicate in the
  CurseForge UI. If an upload failed with HTTP 5xx or a curl error, look at the project's Files page before
  re-running: the file may have been created anyway (the script deliberately does not retry those).
- **Wrong tag pushed:** if the run already failed or has not published yet, delete the tag
  (`git push --delete origin vX.Y.Z && git tag -d vX.Y.Z`) and any draft/created GitHub release. If it already
  reached CurseForge, do not reuse the version: release the next patch.

## One-time repository setup

### Merge settings

Settings → General → Pull Requests: allow **squash merging** only, use the PR title as the default commit message, and
enable "Automatically delete head branches".

### Branch protection for `main`

Settings → Branches (or Rules → Rulesets) for `main`: require a pull request, require these status checks, require
linear history, block force pushes and deletion.

| Required check | From |
|---|---|
| `branch-name` | `ci.yml` (skipped on pushes and fork PRs, which counts as passing) |
| `actionlint` | `ci.yml` |
| `scripts` | `ci.yml` |
| `mod / build` | `ci.yml` → `reusable-build-gradle.yml` |
| `mod-26_2 / build` | `ci.yml` → `reusable-build-gradle.yml` (`-Pmc=26.2`) |
| `addon / build` | `ci.yml` → `reusable-build-node.yml` |

The same with the `gh` CLI (classic branch protection):

```bash
gh api -X PUT repos/nezo32/enchantaholic/branches/main/protection --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["branch-name", "actionlint", "scripts", "mod / build", "mod-26_2 / build", "addon / build"]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": { "required_approving_review_count": 0 },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
```

A check only appears in the picker after it has run once, so open the first PR before configuring this in the UI.

### Labels

The labeler sets labels from the branch prefix and changed paths, and the release notes are grouped by them. Create them
once:

```bash
gh label create enhancement    --color a2eeef --description "New feature"            --force
gh label create bug            --color d73a4a --description "Bug fix"                --force
gh label create chore          --color ededed --description "Maintenance, CI, build" --force
gh label create documentation  --color 0075ca --description "Docs only"              --force
gh label create dependencies   --color 0366d6 --description "Dependency updates"     --force
gh label create breaking       --color b60205 --description "Breaking change"        --force
gh label create skip-changelog --color cccccc --description "Leave out of release notes" --force
gh label create fabric         --color dbab79 --description "Java / Fabric mod"      --force
gh label create bedrock        --color 5319e7 --description "Bedrock add-on"         --force
```

### Releasing from the Actions tab (no local git needed)

Actions → **Release** → Run workflow, branch **main**, tag `vX.Y.Z`. If the tag does not exist yet, the workflow creates it on the tip of `main` and continues with build → GitHub release → CurseForge in the same run. If it already exists, the run rebuilds and re-publishes that release.

### Secrets and variables

Settings → Secrets and variables → Actions. The CurseForge token and project ids may be repository-level **or** live in the GitHub Environment `Enchantaholic` (the upload jobs run in it; override the name with the repository variable `CURSEFORGE_ENVIRONMENT`). A missing project id shows up as a warning in the run, not as a silently skipped job.

| Kind | Name | Value |
|---|---|---|
| secret | `CURSEFORGE_TOKEN` | CurseForge Authors portal → API tokens |
| variable | `CURSEFORGE_PROJECT_ID` | numeric id of the Java project (sidebar of the project page). Empty = CurseForge skipped |
| variable (optional) | `CURSEFORGE_GAME_VERSIONS` | default `26.2,26.3,Fabric,Java 25,Client,Server` |
| variable (optional) | `CURSEFORGE_BEDROCK_PROJECT_ID` | Bedrock add-on project id. Empty = Bedrock upload skipped |
| variable (optional) | `CURSEFORGE_BEDROCK_API_BASE` | default `https://minecraft-bedrock.curseforge.com` |
| variable (optional) | `CURSEFORGE_BEDROCK_GAME_VERSIONS` | default `26.50` (Bedrock version names, e.g. `26.40,26.50`) |

```bash
gh secret set CURSEFORGE_TOKEN
gh variable set CURSEFORGE_PROJECT_ID --body 123456
```

When a new patch of a supported Minecraft version ships (for example `26.2.1`), add it to `CURSEFORGE_GAME_VERSIONS`.

### Confirm the CurseForge names

The defaults were verified against the live API with a real token (September 2026):

- Java host `https://minecraft.curseforge.com`: `26.2` = 16498 (type `minecraft-26-2`), `26.3` = 17045
  (type `minecraft-26-3`), `Fabric` = 7499, `Java 25` = 14454, `Client` = 9638, `Server` = 9639. There is also a
  `26.3-snapshot` (type `minecraft-26-snapshots`); names match exactly, so `26.3` never picks it.
- Bedrock host `https://minecraft-bedrock.curseforge.com`: `/api/game/versions` lists versions such as `26.40` and
  `26.50` (all of one type), while `/api/game/version-types` returns no usable list. The Bedrock job therefore
  passes `version-type-prefixes: ""`, and the script then does not fetch version types at all.

Check again whenever you change the variables:

```bash
T=<token>
curl -fsS -H "X-Api-Token: $T" https://minecraft.curseforge.com/api/game/versions \
  | jq -r '.[] | select(.name | test("^(26\\.|Fabric|Java 25|Client|Server)")) | "\(.id)\t\(.gameVersionTypeID)\t\(.name)"'

# the full check, without uploading:
CF_TOKEN=$T CF_PROJECT_ID=<id> CF_DRY_RUN=true \
CF_GAME_VERSIONS='26.2,26.3,Fabric,Java 25,Client,Server' CF_RELATIONS='fabric-api:requiredDependency' \
  bash scripts/curseforge-upload.sh LICENSE
```

For Bedrock, list the names with `curl -fsS -H "X-Api-Token: $T" https://minecraft-bedrock.curseforge.com/api/game/versions | jq -r '.[].name'`
and dry-run with `CF_API_BASE=https://minecraft-bedrock.curseforge.com CF_TYPE_PREFIXES= CF_GAME_VERSIONS=26.50`.

### First release

Push `v0.0.1-alpha.1` on `main` while `CURSEFORGE_PROJECT_ID` is still unset: you get a GitHub pre-release with both
assets, and the CurseForge jobs are skipped. Then set the variable and run the workflow with
`curseforge-dry-run: true` for that tag before the first real release.
