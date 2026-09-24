# Reusable release pipeline

> This is Echoaholic's copy of the shared document that lives in
> [nezo32/enchantaholic](https://github.com/nezo32/enchantaholic/blob/main/docs/ci/REUSABLE_RELEASE_PIPELINE.md).
> "This repository" below means `nezo32/enchantaholic`, which hosts the workflows. Echoaholic calls them pinned to a
> SHA and keeps byte-identical copies (see [RELEASING.md](RELEASING.md#bumping-the-shared-pipeline)); for Echoaholic
> as a caller, see [Projects without Bedrock](#projects-without-bedrock).

This is the default release pipeline for all of nezo32's projects. You push a SemVer tag, and one workflow run builds
the project, creates the GitHub release with the build outputs attached and, if the project is on CurseForge, uploads
the files there.

The pipeline is five reusable workflows (`on: workflow_call`) in this repository. They contain nothing
Enchantaholic-specific: every project detail comes from the calling workflow's inputs and from repository variables.

| Workflow | Does |
|---|---|
| `reusable-version.yml` | Turns the tag into `version`, `prerelease` and `release-type`, and checks that the tag is on the release branch |
| `reusable-build-gradle.yml` | Runs `./gradlew build -Pmod_version=<version>` (tests included) and uploads the jars as an artifact |
| `reusable-build-node.yml` | Runs `npm ci`, the checks and the package script with env `VERSION=<version>`, and uploads the output |
| `reusable-github-release.yml` | Creates or updates the GitHub release for the tag, attaches the artifacts and generates the notes |
| `reusable-publish-curseforge.yml` | Uploads the artifacts through the CurseForge Upload API (curl + jq, no third-party action) |

Contents: [What it is](#1-what-it-is) · [Quick start](#2-quick-start) · [Build contract](#3-build-contract) ·
[Reference](#4-reference) · [Recipes](#5-recipes) · [CurseForge names](#6-finding-curseforge-version-names) ·
[Tag rules](#7-tag-rules) · [Re-running](#8-re-running-a-release) · [Security](#9-security) ·
[Private host](#10-private-host-repository) · [Moving](#11-moving-to-a-dedicated-repository) ·
[Alternatives](#12-alternatives) · [Projects without Bedrock](#projects-without-bedrock)

## 1. What it is

```
git push origin v1.2.0
        |
        v
  version ----------> build (Gradle and/or Node, in parallel) ----------> GitHub release ----------> CurseForge
  tag -> 1.2.0          -Pmod_version=1.2.0 / VERSION=1.2.0                 notes from merged PRs        release body = changelog
  prerelease=false      artifact: jars / .mcaddon                          assets = artifacts           skipped if no project id
  type=release
  tag on main?
```

Everything happens as jobs of **one** workflow run, the caller's `release.yml`. That is deliberate. A release created
with the built-in `GITHUB_TOKEN` does not trigger other workflows, so a separate "on release published, upload to
CurseForge" workflow would never run. Chaining the jobs in one run needs no personal access token, and build artifacts
move between jobs with `actions/upload-artifact` and `actions/download-artifact`.

## 2. Quick start

For a new Fabric mod repository. For other project types, change step 5 by following [Recipes](#5-recipes).

1. **Copy the caller.** Copy [`.github/templates/release-caller.yml`](https://github.com/nezo32/enchantaholic/blob/main/.github/templates/release-caller.yml)
   to `<your-repo>/.github/workflows/release.yml`.
2. **Pin it.** Replace every `@main` in that file with a full commit SHA of `nezo32/enchantaholic`:
   ```bash
   sha=$(git ls-remote https://github.com/nezo32/enchantaholic refs/heads/main | cut -f1)
   sed -i "s/@main\$/@$sha/" .github/workflows/release.yml
   ```
   `@main` also works, but then every change to the pipeline reaches your project immediately.
3. **Add the secret** `CURSEFORGE_TOKEN` (CurseForge → Authors portal → API tokens) under Settings → Secrets and
   variables → Actions → Secrets. Personal accounts have no account-level Actions secrets, so set it in every
   repository (organizations can use an org secret). Skip this step if the project is not on CurseForge.
   ```bash
   gh secret set CURSEFORGE_TOKEN --repo <owner>/<repo>
   ```
4. **Add the variables** under Settings → Secrets and variables → Actions → Variables:

   | Variable | Example | Required |
   |---|---|---|
   | `CURSEFORGE_PROJECT_ID` | `123456` | yes, for CurseForge. While it is empty the CurseForge job is skipped |
   | `CURSEFORGE_GAME_VERSIONS` | `26.2,26.3,Fabric,Java 25,Client,Server` | yes, for CurseForge |
   | `CURSEFORGE_RELATIONS` | `fabric-api:requiredDependency` | no |

   ```bash
   gh variable set CURSEFORGE_GAME_VERSIONS --repo <owner>/<repo> --body '26.2,26.3,Fabric,Java 25,Client,Server'
   gh variable set CURSEFORGE_RELATIONS     --repo <owner>/<repo> --body 'fabric-api:requiredDependency'
   ```
5. **Meet the build contract and release.** Make the build take its version from the pipeline
   ([Build contract](#3-build-contract)). Then do a first safe run:
   1. Leave `CURSEFORGE_PROJECT_ID` unset and push a pre-release tag from the default branch:
      `git tag -a v0.1.0-alpha.1 -m v0.1.0-alpha.1 && git push origin v0.1.0-alpha.1`.
      This creates a GitHub pre-release and skips CurseForge.
   2. Set `CURSEFORGE_PROJECT_ID`. Run the workflow manually (Actions → Release → Run workflow) with
      `tag: v0.1.0-alpha.1` and `curseforge-dry-run: true`. The CurseForge job resolves your version names and prints
      the upload metadata without uploading anything.
   3. Push the real tag, for example `v0.1.0`.

Optional but recommended: copy `.github/release.yml` (release-notes categories), `.github/labeler.yml` and
`.github/workflows/labeler.yml` (branch prefix → label) from this repository. The generated release notes, and with
them the CurseForge changelog, are then grouped into features, fixes and so on. In the copied `.github/labeler.yml`,
delete or adapt the `fabric` / `bedrock` path rules at the end; they are specific to Enchantaholic's layout.

Before the first tag, commit `release.yml` to the default branch: a tag push runs the workflow file of the tagged
commit, and the "Run workflow" button only appears for workflows on the default branch.

## 3. Build contract

The version lives **only in the tag**. Files in the repository carry a development placeholder, and the pipeline
passes the real version to the build.

`<version>` is the tag without the prefix, for example `1.2.0` or `1.2.0-beta.1`.

### Gradle (`reusable-build-gradle.yml`)

- The workflow runs `./gradlew <gradle-tasks> -P<version-property>=<version>` in `working-directory`. By default that
  is `./gradlew build -Pmod_version=<version>`, so tests and `check` run too. On non-release builds, where the version
  is empty, the `-P` flag is left out and `gradle.properties` applies.
- Commit the Gradle wrapper (`gradlew`, `gradle/wrapper/*`). setup-gradle validates the wrapper checksum.
- Keep a placeholder in `gradle.properties`:
  ```properties
  mod_version=0.0.0-dev
  ```
- Use it in `build.gradle`, and expand it into `fabric.mod.json` (`"version": "${version}"`):
  ```groovy
  version = project.mod_version
  base { archivesName = "mymod" }

  processResources {
      inputs.property "version", project.version
      filesMatching("fabric.mod.json") {
          expand "version": inputs.properties.version
      }
  }
  ```
  For NeoForge/Forge, expand into `META-INF/neoforge.mods.toml` / `mods.toml` the same way.
- Output: `build/libs/<name>-<version>.jar`, plus an optional `<name>-<version>-sources.jar`. Do not leave other jars
  in `build/libs` (`-dev`, `-javadoc`), or narrow `artifact-files` / `primary-file` so that they are excluded.

### Node (`reusable-build-node.yml`)

- The workflow runs `install-command` (`npm ci`), then `check-command` (optional), then `build-command` with env
  `VERSION=<version>`. On non-release builds `VERSION` is empty; fall back to `package.json` `version`.
- Commit `package-lock.json`. The npm cache setup fails without it.
- Minecraft Bedrock manifests take `[major, minor, patch]`. The package script should write the SemVer core of
  `VERSION` into the manifest and name the output after the full version:
  ```js
  // scripts/package.mjs (excerpt)
  const version = process.env.VERSION || pkg.version;           // e.g. "1.2.0-beta.1"
  const core = version.split(/[-+]/)[0].split(".").map(Number);  // [1, 2, 0]
  manifest.header.version = core;
  for (const m of manifest.modules) m.version = core;
  // ...zip the packs into dist/<name>-${version}.mcaddon
  ```

## 4. Reference

All inputs are optional unless marked **required**. Artifact globs are bash extglobs, newline-separated where several
are allowed.

### `reusable-version.yml`

| Input | Default | Description |
|---|---|---|
| `tag` | `""` | Tag to parse. Empty = the tag that triggered the run (`github.ref_name`) |
| `tag-prefix` | `v` | Stripped from the tag to get the version |
| `release-branch` | `""` | When set, fail unless the tagged commit is on this branch. The template passes the default branch |

| Output | Example |
|---|---|
| `tag` | `v1.2.0-beta.1` |
| `version` | `1.2.0-beta.1` |
| `prerelease` | `'true'` / `'false'` (a string: compare with `== 'true'`) |
| `release-type` | `release` / `beta` / `alpha` |

Secrets: none. Permissions: `contents: read` (for the compare API that checks the release branch).

### `reusable-build-gradle.yml`

| Input | Default | Description |
|---|---|---|
| `ref` | `""` | Git ref to build. Empty = the triggering ref. Release callers pass the tag |
| `working-directory` | `.` | Directory that contains `gradlew` |
| `java-version` | `25` | JDK for setup-java |
| `java-distribution` | `temurin` | |
| `gradle-tasks` | `build` | Tasks and arguments for `./gradlew` |
| `version` | `""` | Passed as `-P<version-property>=<version>`. Empty = keep `gradle.properties` |
| `version-property` | `mod_version` | |
| `cache-provider` | `basic` | setup-gradle cache: `basic` (open source) or `enhanced` (proprietary, free for public repos) |
| `artifact-name` | `gradle-libs` | Name of the uploaded artifact |
| `artifact-files` | `build/libs/*.jar` | Files to upload, relative to `working-directory`. Each pattern must match at least one file |
| `retention-days` | `7` | |

Output: `artifact-name`. Secrets: none. Permissions: `contents: read`. When the build fails, the Gradle reports are
uploaded as `<artifact-name>-reports`.

### `reusable-build-node.yml`

| Input | Default | Description |
|---|---|---|
| `ref` | `""` | Git ref to build |
| `working-directory` | `.` | Directory that contains `package.json` and `package-lock.json` |
| `node-version` | `24` | |
| `install-command` | `npm ci` | Trusted shell command, see [Security](#9-security) |
| `check-command` | `""` | Typecheck/lint/test command, for example `npm run typecheck --if-present && npm run lint --if-present && npm test --if-present`. Empty = skip |
| `build-command` | `npm run build` | Runs with env `VERSION` |
| `version` | `""` | Exported to the build as env `VERSION` |
| `artifact-name` | `node-dist` | |
| `artifact-files` | `dist/*.mcaddon` | Files to upload, relative to `working-directory` |
| `retention-days` | `7` | |

Output: `artifact-name`. Secrets: none. Permissions: `contents: read`.

### `reusable-github-release.yml`

| Input | Default | Description |
|---|---|---|
| `tag` | **required** | Tag of the release |
| `name` | `""` | Release title. Empty = the tag |
| `prerelease` | `false` | Boolean. Pre-releases are never marked "latest"; other releases use GitHub's `legacy` rule (newest date + highest SemVer), so re-running an old tag does not make it "latest" |
| `draft` | `false` | |
| `generate-notes` | `true` | Notes from merged PRs, grouped by the caller repo's `.github/release.yml` |
| `artifact-pattern` | `*` | Which artifacts of the run to attach, e.g. `dist` or `{mod,addon}` |
| `files` | `**/*` | Globs inside the downloaded artifacts |

Output: `url`. Secrets: none. Permissions: **`contents: write`**; the caller job must grant it. An existing release for
the tag is updated in place and its assets are overwritten.

### `reusable-publish-curseforge.yml`

| Input | Default | Description |
|---|---|---|
| `project-id` | `""` | Numeric CurseForge project id. Empty = read the variable named by `project-id-var` |
| `project-id-var` | `""` | Name of a repository **or Environment** variable holding the project id (e.g. `CURSEFORGE_PROJECT_ID`). Resolved inside the job; unset = skipped with a warning |
| `environment` | `""` | GitHub Environment for the upload job. Its variables/secrets (e.g. `CURSEFORGE_PROJECT_ID`, `CURSEFORGE_TOKEN`) become available and its protection rules (required reviewers, branch rules) apply |
| `api-base` | `https://minecraft.curseforge.com` | Upload API host of the CurseForge game |
| `artifact-pattern` | **required** | Which artifacts of the run to publish |
| `primary-file` | `!(*-@(sources\|dev\|javadoc)).jar` | Extglob; must match exactly one file |
| `additional-files` | `""` | Extglob for child files attached to the primary file, e.g. `*-sources.jar` |
| `game-versions` | **required** | Comma/newline-separated CurseForge version names, see [section 6](#6-finding-curseforge-version-names) |
| `version-type-prefixes` | `minecraft,modloader,java,environment` | Version types searched for the names. `""` = all types |
| `release-type` | `release` | `release` / `beta` / `alpha`; pass `needs.version.outputs.release-type` |
| `display-name` | `""` | Display name on CurseForge. Empty = file name |
| `relations` | `""` | `slug:type,...`; type is `requiredDependency`, `optionalDependency`, `embeddedLibrary`, `tool` or `incompatible` |
| `changelog` | `""` | Markdown. Empty = body of the GitHub release for `tag` |
| `tag` | `""` | Tag whose GitHub release body becomes the changelog |
| `dry-run` | `false` | Resolve names and print the metadata, but do not upload. Still needs the token (the version list is read from the API) |

| Secret | Description |
|---|---|
| `CURSEFORGE_TOKEN` | Same token under its conventional name. When `environment` is set, an Environment secret named `CURSEFORGE_TOKEN` takes precedence over the value passed by the caller |
| `curseforge-token` | CurseForge Upload API token. Declared `required: false` so that callers without CurseForge still validate; the upload fails loudly without it |

Output: `file-id` (of the primary file; `0` in dry-run). Permissions: `contents: read` (reads the release body).

The upload logic is [`scripts/curseforge-upload.sh`](https://github.com/nezo32/enchantaholic/blob/main/scripts/curseforge-upload.sh). The workflow carries an
inlined, byte-identical copy, because a called workflow cannot read files from the repository that hosts it.
CI runs `scripts/check-inlined-script.sh` to keep the two in sync: edit the script, then paste it into the heredoc.

## 5. Recipes

**Fabric mod.** The template as it is, with:
- `CURSEFORGE_GAME_VERSIONS` = `26.2,26.3,Fabric,Java 25,Client,Server`
- `CURSEFORGE_RELATIONS` = `fabric-api:requiredDependency`

`Client` / `Server` are the environment tags; drop the one your mod does not support.

**NeoForge / Forge mod.** The same, with the loader name in the versions, for example
`26.2,NeoForge,Java 25,Client,Server`. Loader names are found in the `modloader` version type
([section 6](#6-finding-curseforge-version-names)). A Forge-family dependency is a relation like any other, for
example `kotlin-for-forge:requiredDependency`.

**Plain Gradle or Node project, GitHub release only.** Delete the `curseforge` job from the caller. Nothing else is
needed: no secret, no variables.

**Node project.** Delete the Gradle `build` job, uncomment the Node `build` block, and set `primary-file` to your
package, for example `"*.mcaddon"`, with `additional-files: ""`.

**Bedrock add-on on CurseForge.** Bedrock is a separate CurseForge game with its own host (verified with a real
token, September 2026):
```yaml
      api-base: https://minecraft-bedrock.curseforge.com
      primary-file: "*.mcaddon"
      additional-files: ""
      game-versions: "26.50"                               # Bedrock names, e.g. 26.40, 26.50
      version-type-prefixes: ""                            # required: search all version types of that game
```
`version-type-prefixes: ""` is required for Bedrock: its `/api/game/version-types` returns no usable list, and with
empty prefixes the script does not fetch it. All Bedrock versions share one type.

**Several artifacts from one repository (monorepo).** Use one build job per project, each with its own
`artifact-name`; attach all of them with `artifact-pattern: "{a,b}"` on the release job, and use one CurseForge job per
CurseForge project. Enchantaholic's own [`release.yml`](https://github.com/nezo32/enchantaholic/blob/main/.github/workflows/release.yml) is a working example
(a Fabric mod and a Bedrock add-on).

**CI for pull requests.** The build workflows also work without a version, so a project's `ci.yml` can reuse them:
```yaml
  build:
    uses: nezo32/enchantaholic/.github/workflows/reusable-build-gradle.yml@<sha>
```
See Enchantaholic's [`ci.yml`](https://github.com/nezo32/enchantaholic/blob/main/.github/workflows/ci.yml).

## 6. Finding CurseForge version names

`game-versions` takes the names CurseForge shows, and the script resolves them to ids. Names are matched exactly,
case-insensitively, and only within the version types in `version-type-prefixes`. This matters because `26.2` exists
both as a Minecraft version and as a Bukkit version.

List what exists (needs the token):
```bash
T=<your token>; H=https://minecraft.curseforge.com
curl -fsS -H "X-Api-Token: $T" "$H/api/game/version-types" | jq -r '.[] | "\(.id)\t\(.slug)"'
curl -fsS -H "X-Api-Token: $T" "$H/api/game/versions" \
  | jq -r '.[] | select(.name | test("^(26\\.|Fabric|NeoForge|Java|Client|Server)")) | "\(.id)\t\(.gameVersionTypeID)\t\(.name)"'
```

Check a whole configuration locally without uploading:
```bash
CF_TOKEN=$T CF_PROJECT_ID=123456 CF_DRY_RUN=true \
CF_GAME_VERSIONS='26.2,26.3,Fabric,Java 25,Client,Server' CF_RELATIONS='fabric-api:requiredDependency' \
  bash scripts/curseforge-upload.sh path/to/any.jar
```

An unknown name fails the job with a list of similar names, for example
`CurseForge game version '26.9' not found ... Similar: 26.2, 26.3`. In GitHub, the same check is the release
workflow's `curseforge-dry-run` input.

When a new Minecraft version ships, add it to the `CURSEFORGE_GAME_VERSIONS` variable. No code change is needed.

## 7. Tag rules

Tags must be SemVer with the prefix and without build metadata: `vMAJOR.MINOR.PATCH[-prerelease]`. The release
trigger only matches `v<digit>.<digit>.<digit>...`, and `reusable-version.yml` rejects anything else, including
`+build` suffixes (they would end up in some file names and display names but not others).

| Tag | Version | GitHub pre-release | CurseForge type |
|---|---|---|---|
| `v1.2.3` | `1.2.3` | no | release |
| `v1.2.3-beta.1` | `1.2.3-beta.1` | yes | beta |
| `v1.2.3-rc.1` | `1.2.3-rc.1` | yes | beta |
| `v1.2.3-alpha.2` | `1.2.3-alpha.2` | yes | alpha |
| `v1.2.3+b5` | error (build metadata) | | |
| `v1.2` | error | | |

A pre-release starting with `alpha`, `a.`, `dev`, `snapshot` or `nightly` maps to `alpha`; any other pre-release
(`beta`, `rc`, `pre`, ...) maps to `beta`.

The tagged commit must be on the release branch (the default branch, in the template). A tag on a feature branch fails
in the `version` job, before anything is built or published.

## 8. Re-running a release

- **Rebuild and update the GitHub release:** Actions → Release → Run workflow with the existing `tag`. The release is
  updated in place and its assets are replaced. Tick `skip-curseforge` unless you also want a new CurseForge upload.
- **CurseForge is not idempotent.** Every upload creates a new file. Re-running the CurseForge job after a successful
  upload duplicates the file; delete the extra one in the CurseForge UI if that happens. For the same reason the
  script never re-sends an upload that reached CurseForge: it retries only DNS/connect/TLS failures. If the job fails
  with an HTTP 5xx or a curl error, check the project's Files page first; the file may exist anyway.
- **Only the CurseForge job failed** (for example a wrong version name): fix the variable, then use "Re-run failed
  jobs" on the same run. The build artifacts are kept for `retention-days` (7 by default), so nothing is rebuilt.
- **Validate without uploading:** run the workflow with `curseforge-dry-run: true`. Note that this still updates the
  GitHub release for that tag.

## 9. Security

- **Least privilege.** The caller's top level grants `contents: read`, and only the `github-release` job gets
  `contents: write`. A called workflow can never exceed what its caller job grants. Checkouts use
  `persist-credentials: false`.
- **Pass the token explicitly** (`secrets: CURSEFORGE_TOKEN: ${{ secrets.CURSEFORGE_TOKEN }}`), never with
  `secrets: inherit`, so a called workflow only ever sees the one secret it needs.
- **`*-command` inputs are code.** `install-command`, `check-command` and `build-command` are run as shell. Write them
  as literals in your workflow file. Never build them from event data such as PR titles, branch names or issue text.
- **Pin a SHA.** A `@<sha>` reference cannot change under you; a branch reference can. Bump the SHA deliberately
  when you want pipeline changes. Once the workflows live in a repository with release tags
  ([section 11](#11-moving-to-a-dedicated-repository)), Dependabot (`package-ecosystem: github-actions`) can bump
  the references for you.
- The `version` job refuses tags that are not on the release branch, so a tag pushed by mistake on a feature branch
  does not publish. It is a guard against mistakes, not against someone with push access: a tag push runs the
  workflow file of the tagged commit, which that person could have edited. If others can push to the repository,
  restrict who may create `v*` tags with a tag ruleset.
- The CurseForge job prints the changelog (built from PR titles) with workflow commands disabled, so a PR title
  like `::error::...` cannot inject runner commands.

## 10. Private host repository

Other repositories can call these workflows without any setup while the hosting repository is public. If the host is
**private**, open its Settings → Actions → General → Access and choose "Accessible from repositories owned by the user
'nezo32'". Only repositories of the same owner can then call it.

The **calling** repository's Actions policy applies to everything the called workflows use. With the default "Allow
all actions and reusable workflows" nothing needs changing. If the caller restricts actions (Settings → Actions →
General → Actions permissions), allow `nezo32/enchantaholic/.github/workflows/*` plus the actions used inside:
`actions/checkout`, `actions/setup-java`, `actions/setup-node`, `gradle/actions/setup-gradle`,
`actions/upload-artifact`, `actions/download-artifact` and `softprops/action-gh-release`. A policy error shows up as
a startup failure of the whole run, before any job starts.

## 11. Moving to a dedicated repository

The workflows live in `nezo32/enchantaholic` for now. The recommended next step is a dedicated
`nezo32/gh-workflows` repository:

1. Move `.github/workflows/reusable-*.yml`, `scripts/curseforge-upload.sh`, `scripts/check-inlined-script.sh`,
   `scripts/test/`, `.github/templates/release-caller.yml` and this document there, with a CI job that runs
   actionlint, shellcheck, the dry-run tests and the inline check.
2. Tag it `v1.0.0` and a moving `v1` tag. Callers then use
   `nezo32/gh-workflows/.github/workflows/reusable-<x>.yml@v1` (or a SHA), and Dependabot updates them.
3. Switch Enchantaholic's local `./.github/workflows/reusable-*.yml` references to the remote ones.

The interface stays identical, so callers only change the `uses:` prefix.

Do **not** create `v1`-style tooling tags in a mod repository: they collide with the mod's own release tags and would
start its release workflow. `nezo32/.github` workflow templates are not used, because they only help the "New
workflow" screen of organization accounts.

## 12. Alternatives

[mc-publish](https://github.com/Kira-NT/mc-publish) (`Kira-NT/mc-publish@v3`, v3.3+ supports 26.x) can replace the
CurseForge job for Java-edition mods, and also publishes to Modrinth and GitHub. It resolves versions through the
Mojang manifest and only targets the Java-edition host, so it cannot publish Bedrock add-ons. Use it in a caller job
after the release job:
```yaml
  publish:
    needs: [version, github-release]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/download-artifact@v8
        with: { name: dist, path: dist }
      - uses: Kira-NT/mc-publish@v3
        with:
          curseforge-id: ${{ vars.CURSEFORGE_PROJECT_ID }}
          curseforge-token: ${{ secrets.CURSEFORGE_TOKEN }}
          # modrinth-id / modrinth-token for Modrinth
          files: |
            dist/!(*-@(dev|sources|javadoc)).jar
            dist/*-sources.jar
          version: ${{ needs.version.outputs.version }}
          version-type: ${{ needs.version.outputs.release-type }}
          loaders: fabric
          game-versions: |
            26.2
            26.3
          java: 25
          dependencies: fabric-api(required){curseforge:fabric-api}
```

## Using a GitHub Environment for CurseForge settings

You can keep `CURSEFORGE_TOKEN` (secret) and `CURSEFORGE_PROJECT_ID` (variable) in an Environment instead of at repository level, and add protection rules such as required reviewers before anything is published. Set the repository variable `CURSEFORGE_ENVIRONMENT` to the Environment's name (the template passes it as `environment:`); the upload job then runs in that Environment and reads the id and token from it. Job-level `if:` conditions cannot see Environment variables, which is why the id is resolved inside the job (`project-id-var`) and a missing id produces a warning instead of a silently skipped job.

## Projects without Bedrock

Bedrock is optional. A Java-only project (one Gradle build, one CurseForge project) simply leaves out the Node build
job and the Bedrock CurseForge job: no `reusable-build-node.yml` call, no `api-base` / `version-type-prefixes`
overrides and no `CURSEFORGE_BEDROCK_*` variables. The Gradle-only caller in the template is already this setup.

Nothing in the reusable workflows depends on an edition. Only some input defaults are edition-flavoured:
`reusable-build-node.yml` defaults `artifact-files` to `dist/*.mcaddon`, and `reusable-publish-curseforge.yml` defaults
to the Java host (`api-base`, `primary-file` for jars, `version-type-prefixes`). Callers override whatever does not
fit.

[Echoaholic's `release.yml`](https://github.com/nezo32/echoaholic/blob/main/.github/workflows/release.yml) is a
Java-only caller: `version` → `build-mod` (Gradle in `fabric/`) → `github-release` (`artifact-pattern: fabric-mod`) →
`curseforge-mod`, all pinned to one SHA of `nezo32/enchantaholic`. Its
[`ci.yml`](https://github.com/nezo32/echoaholic/blob/main/.github/workflows/ci.yml) reuses `reusable-build-gradle.yml`
for the 26.3 and 26.2 builds and has no `addon / build` check.
