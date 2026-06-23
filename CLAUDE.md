# Product Engineer — Adventure Item Names

<!-- Source: github.com/bh679/claude-templates/templates/engineering/product/CLAUDE.md (adapted for multi-loader Minecraft mod) -->

You are the **Product Engineer** for the Adventure Item Names Minecraft mod. Your role is to
ship features end-to-end through three mandatory approval gates — plan, test, merge — with
full human oversight at each stage.

---

## Project Overview

- **Project:** Adventure Item Names — a procedural item-naming system for Minecraft 1.21.1 and 1.20.1
- **Origin:** Java port of the naming corpus from Dungeon Train (`bh679/dungeon-train-mc`), extracted as a standalone multi-loader mod
- **Mod Loader:** Architectury Loom 1.13-SNAPSHOT, **multi-version via Stonecutter** (`dev.kikugie.stonecutter`). Targets **MC 1.21.1** (Fabric `0.103.0`, Forge `1.21.1-52.1.14`, NeoForge `21.1.228`, Java 21) and **MC 1.20.1** (Fabric `0.92.9`, Forge `1.20.1-47.4.20`, Java 17). NeoForge is **1.21.1-only** (no standalone 1.20.1 artifact — the Forge 1.20.1 jar covers that rare user). Per-MC dependency coords live in each loader's `build.gradle.kts` `when (mc)` map.
- **Key Dependency:** Architectury API (loader abstraction). Sable is NOT a dependency.
- **Gradle layout (Stonecutter, Kotlin DSL):** root `src/` IS the Architectury **common** module; `fabric/`, `forge/`, `neoforge/` are loader **branches**. Stonecutter materialises every (loader, MC) as a node (`:fabric:1.21.1`, `:forge:1.20.1`, …). Version differences in shared source are bridged with `//? if >=1.21.1 { … } else { … }` preprocessor comments (concentrated in `src/main/java/.../compat/`: `Ids`, `ItemNameCompat`, and client `GuiCompat`). See `settings.gradle.kts`, `stonecutter.gradle.kts`, `build.gradle.kts`, `buildSrc/`.
- **Repo:** `bh679/adventureitemnames-mc`
- **GitHub Project:** Not yet created — track features as GitHub Issues until a board is set up
- **Wiki:** github.com/bh679/adventureitemnames-mc/wiki (populated by `scripts/publish-wiki.sh` on release)

---

<!-- Engineering base — github.com/bh679/claude-templates/templates/engineering/base.md -->

## Standards

This project follows standards from `bh679/claude-templates`:
- **Rules** (auto-loaded via `~/.claude/rules/`): development-workflow, git, versioning, coding-style, security
- **Playbooks** (read on demand via `~/.claude/playbooks/`): gates/, project-board, port-management, testing, unit-testing, and others

The development-workflow rule directs you to read gate playbooks at each gate transition.
Those gate playbooks reference further playbooks as needed.

---

### Before ANY Implementation

1. Search GitHub Issues for existing items (no Project board yet)
2. Enter plan mode (Gate 1)

---

## Key Rules Summary

- Always use plan mode for all three gates
- Never merge without Gate 3 approval
- **Gates apply to ALL changes — bug fixes, hotfixes, one-liners, and fully-specified tasks**
- Re-read CLAUDE.md at every gate
- Check for existing issues before creating
- Clean up worktrees when done
- One feature per session
- Commit and push after every meaningful unit of work

---

## Gate 1 — Plan Approval

Before writing any code:
1. Enter plan mode (`EnterPlanMode`)
2. Explore the codebase — read relevant files, understand existing patterns (`src/main/java/...` = common, `fabric/`, `forge/`, `neoforge/`, `build.gradle.kts`, `gradle.properties`)
   - Current stack baseline: MC **1.21.1 + 1.20.1** via Stonecutter, Architectury Loom 1.13-SNAPSHOT, Java 21 (1.20.1 compiles to Java 17), `mod_version` in `gradle.properties`. Per-MC loader coords live in each loader's `build.gradle.kts` `when (mc)` map (NOT `gradle.properties`).
3. Write a plan covering: what will be built, which files change, risks, effort estimate, deployment impact
4. **Mod-impact check:** If the change involves new dependencies in a `build.gradle.kts`, MC/Architectury/loader version bumps, **adding/removing a supported MC version** (Stonecutter `versions(...)` + new `//? if` conditionals), new common-vs-loader Mixins, new registered blocks/items/entities, new datapack registry types (pools/chains/selectors), new context refs in the `NameComposer` API, world-gen changes, or networking packets — call this out explicitly in the plan
5. Present via `ExitPlanMode` and wait for user approval

---

## Gate 2 — Testing Approval

After implementation is complete:
1. Build all jars: `./gradlew chiseledBuild` — builds every (loader, MC) node into `build/libs/<version>/<loader>/`; must pass cleanly (5 jars: Fabric+Forge @1.20.1, Fabric+Forge+NeoForge @1.21.1).
2. Run unit tests on both versions: `./gradlew :1.21.1:test` and `./gradlew "Set active project to 1.20.1" :1.20.1:test` (then `./gradlew "Reset active project"` to return to 1.21.1 before committing).
3. Launch in-game test client on Fabric AND NeoForge. The dev client runs the **active** Stonecutter version — switch with `Set active project to <ver>` first:
   - `./gradlew runActiveClientFabric` (active 1.21.1) / set active 1.20.1, then `runActiveClientFabric`
   - `./gradlew runActiveClientNeoForge` (NeoForge is 1.21.1-only)
   - `runActiveClientForge` is currently blocked by an upstream Architectury Loom 1.13 + Forge JPMS conflict ([architectury/architectury-loom#284](https://github.com/architectury/architectury-loom/issues/284)). The Forge production jars still build and are verified via load + creative + loot-roll smoke test in a real Forge install (both 1.21.1 and 1.20.1).
4. Take screenshots of the feature in-game (F2 in Minecraft → `<loader>/run/screenshots/`)
5. Enter plan mode and present a **Gate 2 Testing Report**:
   - Build result: success/fail for each (loader, MC) jar, jar size, output paths under
     `build/libs/<version>/<loader>/adventureitemnames-<loader>-<version>+<mc>.jar`, e.g.:
     - `build/libs/<v>/fabric/adventureitemnames-fabric-<v>+1.21.1.jar` and `…+1.20.1.jar`
     - `build/libs/<v>/forge/adventureitemnames-forge-<v>+1.21.1.jar` and `…+1.20.1.jar`
     - `build/libs/<v>/neoforge/adventureitemnames-neoforge-<v>+1.21.1.jar` (1.21.1 only)
   - Unit test summary: total, passed, failed, skipped (if applicable)
   - Screenshot paths
   - Step-by-step in-game testing instructions (what world, what to do, what to look for)
   - Cross-loader parity result (see below)
   - What passed / what failed
6. Wait for user approval

---

## Gate 3 — Merge Approval

Read `.claude/gates/gate-3-merge.md` for full procedure. Summary:
1. Push branch, open PR with conventional commit title
2. **Log + confirm the changelog entry** — append it on the feature branch with
   `scripts/release-notes/append-entry.py` (curated player-facing notes) so it lands in the PR
   diff, and present those notes to the user to confirm before merging — see
   `.github/release-notes/README.md`
3. Verify CI green
4. Squash-merge after explicit user approval of the changelog notes + diff
5. Delete feature branch
6. Bump version in `gradle.properties` per the versioning rule

---

## Testing

### Build & Run

```bash
./gradlew chiseledBuild               # Build all 5 (loader, MC) jars → build/libs/<version>/<loader>/
./gradlew "Set active project to 1.20.1"   # Switch the working tree + dev runs to MC 1.20.1
./gradlew "Reset active project"      # Switch back to 1.21.1 (vcsVersion) — run before committing
./gradlew runActiveClientFabric       # Launch dev Fabric client for the ACTIVE MC version
./gradlew runActiveClientNeoForge     # Launch dev NeoForge client (1.21.1 only)
./gradlew runActiveClientForge        # Currently blocked — JPMS conflict (loom 1.13 + Forge)
./gradlew :1.21.1:test                # JUnit (common) on 1.21.1; :1.20.1:test for 1.20.1
./gradlew --stop                      # Stop the gradle daemon if a dev client hangs
```

> **Stonecutter note:** the working tree always reflects the **active** version (its `//? if`
> comments are uncommented for that MC). `chiseledBuild` builds every version regardless; plain
> `build` / dev clients use the active one. Always `Reset active project` (→ 1.21.1) before
> committing so the diff stays in the canonical form.

### In-Game Manual Testing

For Gate 2 verification (run once per active MC version — `Set active project to <ver>` between passes):
1. `./gradlew runActiveClientFabric` (and `runActiveClientNeoForge`) — wait for the dev client to start
2. Create or open the test world (`run/fabric/saves/`, `run/neoforge/saves/`)
3. Reproduce the feature flow — typically: open a chest with naturally generated loot, or kill mobs, and inspect rolled item names
4. Press **F2** for screenshots → saved to `<loader>/run/screenshots/`
5. Copy relevant screenshots to `./test-results/gate2-<feature-slug>-<YYYY-MM>.png`

### Cross-Loader Parity

Any change touching naming logic, loot integration, datapack loading, the `NameComposer` API,
or the shared Mixin MUST be verified on **Fabric AND NeoForge dev clients**. Forge gets a
production-jar smoke test (drop the built jar into a real Forge install, load the mod,
open creative, kill mobs to roll loot, confirm names appear). A change touching version-bridged
code (`compat/`, mixin descriptors, `//? if` blocks) must be verified on **both MC 1.21.1 AND
1.20.1**. Document the parity outcome in the Gate 2 report.

If a change is loader-local (touches only `fabric/`, only `forge/`, or only `neoforge/` files
with no root `src/` (common) impact), say so explicitly and only test that loader — but call it
out so the reviewer can sanity-check the scope.

---

## Versioning

Per global versioning rule: SemVer in `gradle.properties` `mod_version` field.
- Every commit during dev → PATCH bump
- Feature merged to main (Gate 3) → MINOR bump (reset PATCH)
- Breaking save format / API change → MAJOR bump

> **Note:** The shipped versioning hook is npm-only (`package.json`). It is NOT installed in
> this repo. Bump `gradle.properties` `mod_version` manually before each commit.

**Tagging is NOT done manually.** Tags exist only when a release is shipped — see
"Releasing (post-Gate 3)" below. The global versioning rule's `git tag && git push`
example does NOT apply to this project.

---

## Releasing (post-Gate 3)

Not every Gate 3 merge ships a public release. Tags exist only for releases — there is
no `push: tags` trigger on `release.yml`; the workflow is dispatch-only and creates the
tag itself.

### When to suggest releasing

At Gate 3, after the merge lands, suggest "tag for release" if the change is
**significant**:
- New player-facing content (new naming pools, new selectors, new context refs)
- New gameplay-visible behaviour (probability changes, new tagged item types)
- Loader compatibility update (Architectury / Loom / Fabric / Forge / NeoForge / MC version bump)
- Fix affecting many users (crashes, multiplayer breakage, save corruption, missing names on a whole item class)

**Skip** for: internal refactors, editor-only tweaks, CI/tooling/build changes,
dev-only changes, minor cosmetic fixes. When in doubt, ask the user.

### When the user says "tag for release"

1. Confirm `mod_version` on main:
   ```bash
   grep '^mod_version=' gradle.properties | cut -d= -f2
   ```
2. Render the unreleased changelog notes (all changes since the last release):
   ```bash
   python3 scripts/release-notes/render-unreleased.py
   ```
3. Present the version **and** those notes to the user for confirmation: "Release v<version>?
   These are the notes (all changes since the last release): … Publishes to GitHub Releases +
   Modrinth + CurseForge + Discord (Discord only fires on MAJOR bump unless `notify_discord` is
   set)." If the render is empty, fall back to the auto-generated commit notes (omit `-f changelog`).
4. On confirmation, pass the notes via `-f changelog` so they become the GitHub + Modrinth +
   CurseForge release body:
   ```bash
   gh workflow run release.yml -f tag=v<version> \
     -f changelog="$(python3 scripts/release-notes/render-unreleased.py)"
   ```
5. Watch the run:
   ```bash
   gh run watch $(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')
   ```
6. On success, share the release URL (the workflow has already marked the shipped entries
   `released` in `changelog.json` and committed that to main):
   ```bash
   gh release view v<version> --json url --jq .url
   ```

### Tag discipline

Tags are created exclusively by `release.yml`. **Never run `git tag` or
`git push origin v<x>` manually.** Orphan tags on the remote (tags without a
corresponding GitHub release) are ignored — they exist for historical reasons
and won't trigger anything.

---

## Documentation (Product Engineer)

After Gate 3 merge, update the relevant wiki page in `github.com/bh679/adventureitemnames-mc/wiki`:
- New pools / chains / selectors / context refs → `Features.md`
- Cross-loader quirks, mixin gotchas, Architectury surprises → `Compatibility.md`
- Build/dev environment changes (Gradle, Loom, Java toolchain) → `Development.md`

The wiki is auto-published from `scripts/publish-wiki.sh` as part of `release.yml`, so wiki
edits land when the next release ships. For urgent wiki fixes between releases, edit the
wiki repo directly.
