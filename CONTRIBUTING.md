# Contributing to Echoaholic

Echoaholic is a Java-edition Fabric mod in `fabric/`. It is built, tested and released by GitHub Actions from this
repository.

## Branch flow

`main` is always releasable, and nobody commits to it directly.

```
main ──●──────────●───────────────●──── tag v1.3.0 → release
        \        / squash        /
         feature/echo-trails  fix/echo-cursor-drift
```

### `main` is protected

- Changes land only through a pull request.
- These checks must pass: `branch-name`, `actionlint`, `scripts`, `mod / build`, `mod-26_2 / build`.
- PRs are **squash-merged**, so history stays linear: one commit per PR.
- No force-pushes to `main`.

### Branch names

Name your branch `<type>/<kebab-name>`, for example `feature/echo-trails` or `fix/echo-cursor-drift`. The `branch-name`
check rejects other names.

| Type | Use for | PR label → release-notes section |
|---|---|---|
| `feature/`, `feat/` | new functionality | `enhancement` → New features |
| `fix/`, `hotfix/` | bug fixes | `bug` → Bug fixes |
| `docs/` | documentation only | `documentation` → Documentation |
| `chore/`, `ci/`, `build/`, `refactor/`, `perf/`, `test/` | everything else | `chore` → Maintenance |
| `release/` | release preparation | (none) → Other changes |

The prefix sets the PR label automatically, and the label decides where the PR appears in the release notes. PRs that
touch `fabric/` also get a `fabric` label.

### PR titles

The PR title becomes a line in the release notes, and from there in the CurseForge changelog. Write it as an imperative
sentence that makes sense to players: "Add echo path trail", not "trail wip".

- Add the `breaking` label to a PR that breaks worlds, configs or compatibility. It gets its own section at the top.
- Add `skip-changelog` to leave a PR out of the notes (for example a typo fix in CI).

### Releases

- Only maintainers create releases.
- A release is an annotated tag on a commit of `main`: `vMAJOR.MINOR.PATCH`, optionally with `-alpha.N`, `-beta.N` or
  `-rc.N` (no `+build` suffix). A tag on any other branch is rejected.
- Everything after the tag is automated: the jar is built and attached to a GitHub release, then uploaded to
  CurseForge.
- The version lives **only in the tag**. Never edit `mod_version` in `fabric/gradle.properties` by hand.

Details: [docs/ci/RELEASING.md](docs/ci/RELEASING.md).

## Local checks before opening a PR

Run the same checks as CI:

```bash
(cd fabric && ./gradlew build)                                            # Java 25; compiles, tests, builds the jar
```

If you changed `.github/` or `scripts/`:

```bash
shellcheck scripts/*.sh scripts/test/*.sh
bash scripts/test/curseforge-upload.test.sh
bash scripts/test/check-reusable-sync.test.sh
bash scripts/check-inlined-script.sh
bash scripts/check-reusable-sync.sh                                       # needs network (raw.githubusercontent.com)
actionlint                                                                # https://github.com/rhysd/actionlint
```

`scripts/curseforge-upload.sh` has a byte-identical copy inside
`.github/workflows/reusable-publish-curseforge.yml`; `check-inlined-script.sh` tells you when they differ. Both are
vendored from the shared pipeline (see below): do not edit them here.

## CI and release pipeline

- `.github/workflows/ci.yml` runs on every PR and on `main`.
- `.github/workflows/release.yml` runs on version tags.
- Both call the shared reusable workflows of [nezo32/enchantaholic](https://github.com/nezo32/enchantaholic), pinned
  to a full commit SHA. See [docs/ci/REUSABLE_RELEASE_PIPELINE.md](docs/ci/REUSABLE_RELEASE_PIPELINE.md).
- `.github/workflows/reusable-*.yml` and `scripts/curseforge-upload.sh` are byte-identical copies of the upstream files
  at that SHA, kept for review. Change them upstream, never here. `scripts/check-reusable-sync.sh` (CI `scripts` job)
  fails when a copy differs. To bump the pipeline, change the SHA in every `uses:` line of `ci.yml` and `release.yml`,
  re-copy the files from upstream at the new SHA, and run the script.
