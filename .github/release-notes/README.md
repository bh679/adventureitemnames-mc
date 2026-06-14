# Changelog Ledger

`changelog.json` is a curated, per-version record of what changed in this mod. The agent appends
one entry per **Gate 3 merge** (when it best understands what it just built); at release time the
agent renders **all unreleased entries** (everything since the last release), presents them for
confirmation, and passes them to `release.yml` as the release notes. The release workflow then
marks the shipped entries released.

This replaces leaning on GitHub's raw auto-generated commit diff for player-facing notes — the
ledger captures intent at the moment a feature ships.

## Flow

```
Gate 3 merge (agent, on the feature branch)
   └─ scripts/release-notes/append-entry.py
        appends a curated entry to changelog.json (released:false, version = the ship version)
        → lands in the PR diff, reviewed at the gate, squash-merged atomically

Release (agent → CLAUDE.md "Releasing")
   ├─ scripts/release-notes/render-unreleased.py   → Markdown of every released:false entry
   ├─ agent shows it to the user for confirmation
   └─ gh workflow run release.yml -f tag=vX.Y.Z -f changelog="<rendered notes>"

release.yml (CI, on success, when auto == false)
   └─ scripts/release-notes/mark-released.py --released-in vX.Y.Z
        flips released:true + stamps released_in/released_at
        → commits "chore(release-notes): mark vX.Y.Z released [skip ci]" to main
```

## The `released` flag is the boundary (not version numbers)

Inclusion in a release is governed by the per-entry **`released`** flag, never by comparing
version numbers — so two entries that happen to share a version can't hide each other, and a
feature merged right after a release is correctly still unreleased. The `version` field is
descriptive metadata and drives grouping in the rendered notes.

## When entries are marked released

This repo has no auto-release cascade, so every dispatched release is a real release: the mark
step (gated on `success() && inputs.auto == false`) runs on each one and flips all unreleased
entries, so "unreleased" means **since the last release**. The `auto` input is kept for parity
with Dungeon Train and forward-compatibility if a cascade is ever added.

## Entry shape

```json
{
  "entries": [
    {
      "id": "my-feature-slug",
      "version": "0.4.0",
      "type": "feat",
      "title": "Short headline of the change",
      "summary": "Player-facing prose describing what changed.",
      "highlights": ["First bullet", "Second bullet"],
      "pr": 71,
      "date": "2026-06-14",
      "released": false,
      "released_in": null,
      "released_at": null
    }
  ]
}
```

| Field | Required | Notes |
|---|---|---|
| `id` | yes | Unique slug `^[a-z0-9][a-z0-9_-]*$`. Duplicate ids are refused. |
| `version` | yes | `X.Y.Z` the merge ships as (computed from `gradle.properties` at log time). Descriptive only. |
| `type` | yes | One of `feat, fix, content, perf, refactor, chore, docs, ci, test`. |
| `title` | yes | Short headline. |
| `summary` | yes | Player-facing prose. |
| `highlights` | no | Bullet points. |
| `pr` | no | PR number. |
| `date` | yes | UTC `YYYY-MM-DD` set at append time. |
| `released` | yes | `false` until shipped; flipped by `release.yml`. |
| `released_in` / `released_at` | no | Stamped on release (tag + UTC timestamp). |

Validated by `schema/changelog.schema.json` (JSON Schema 2020-12).

## Scripts

| Script | When | What |
|---|---|---|
| `append-entry.py` | Gate 3 (agent) | Append one curated entry. Computes `version` from `gradle.properties` + `date`. |
| `render-unreleased.py` | Release (agent) | Print Markdown of all `released:false` entries, grouped by version (newest first). Empty output when nothing is unreleased. |
| `mark-released.py` | Release (CI) | Flip every `released:false` entry to released; stamp the tag + timestamp. |

```bash
# Gate 3 — log what was built (run on the feature branch before merging):
python3 scripts/release-notes/append-entry.py \
  --id my-feature-slug --type feat \
  --title "Short headline of the change" \
  --summary "Player-facing prose describing what changed." \
  --highlight "First bullet" \
  --highlight "Second bullet" \
  --pr 71

# Release — preview the notes the user will confirm:
python3 scripts/release-notes/render-unreleased.py
```

Path env overrides (used by the tests): `CHANGELOG_FILE`, `GRADLE_PROPERTIES_FILE`.

Tests: `python3 scripts/release-notes/test_release_notes.py` (stdlib + optional `jsonschema`;
local-only, not CI-gated).
