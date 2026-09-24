# Contributing to Enchantaholic

Enchantaholic has two parts: the Java-edition Fabric mod in `fabric/` and the Bedrock add-on in `bedrock/`. Both are
built, tested and released by GitHub Actions from this repository.

## Branch flow

`main` is always releasable, and nobody commits to it directly.

```
main ──●──────────●────────────●──── tag v1.3.0 → release
        \        / squash     /
         feature/vein-miner  fix/xp-cost-overflow
```

### `main` is protected

- Changes land only through a pull request.
- These checks must pass: `branch-name`, `actionlint`, `scripts`, `mod / build`, `mod-26_2 / build`, `addon / build`.
- PRs are **squash-merged**, so history stays linear: one commit per PR.
- No force-pushes to `main`.

### Branch names

Name your branch `<type>/<kebab-name>`, for example `feature/vein-miner` or `fix/xp-cost-overflow`. The `branch-name`
check rejects other names.

| Type | Use for | PR label → release-notes section |
|---|---|---|
| `feature/`, `feat/` | new functionality | `enhancement` → New features |
| `fix/`, `hotfix/` | bug fixes | `bug` → Bug fixes |
| `docs/` | documentation only | `documentation` → Documentation |
| `chore/`, `ci/`, `build/`, `refactor/`, `perf/`, `test/` | everything else | `chore` → Maintenance |
| `release/` | release preparation | (none) → Other changes |

The prefix sets the PR label automatically, and the label decides where the PR appears in the release notes. PRs that
touch `fabric/` or `bedrock/` also get a `fabric` / `bedrock` label.

### PR titles

The PR title becomes a line in the release notes, and from there in the CurseForge changelog. Write it as an imperative
sentence that makes sense to players: "Add Vein Miner enchantment", not "vein miner wip".

- Add the `breaking` label to a PR that breaks worlds, configs or compatibility. It gets its own section at the top.
- Add `skip-changelog` to leave a PR out of the notes (for example a typo fix in CI).

### Releases

- Only maintainers create releases.
- A release is an annotated tag on a commit of `main`: `vMAJOR.MINOR.PATCH`, optionally with `-alpha.N`, `-beta.N` or
  `-rc.N` (no `+build` suffix). A tag on any other branch is rejected.
- Everything after the tag is automated: the jar and the `.mcaddon` are built and attached to a GitHub release, then
  uploaded to CurseForge.
- The version lives **only in the tag**. Never edit `mod_version` in `fabric/gradle.properties` or the versions in
  `bedrock/**/manifest.json` by hand.

Details: [docs/ci/RELEASING.md](docs/ci/RELEASING.md).

## Local checks before opening a PR

Run the same checks as CI:

```bash
(cd fabric && ./gradlew build)                                            # Java 25; compiles, tests, builds the jar
(cd bedrock && npm ci && npm run typecheck && npm run lint && npm test && npm run package)  # Node 24; builds dist/*.mcaddon
```

If you changed `.github/` or `scripts/`:

```bash
shellcheck scripts/*.sh scripts/test/*.sh
bash scripts/test/curseforge-upload.test.sh
bash scripts/check-inlined-script.sh
actionlint                                                                # https://github.com/rhysd/actionlint
```

`scripts/curseforge-upload.sh` has a byte-identical copy inside
`.github/workflows/reusable-publish-curseforge.yml`. Edit the script, then paste it into the workflow's heredoc;
`check-inlined-script.sh` tells you when they differ.

## CI and release pipeline

- `.github/workflows/ci.yml` runs on every PR and on `main`.
- `.github/workflows/release.yml` runs on version tags.
- The `reusable-*.yml` workflows are shared with other projects; changes there affect them too. See
  [docs/ci/REUSABLE_RELEASE_PIPELINE.md](docs/ci/REUSABLE_RELEASE_PIPELINE.md).
