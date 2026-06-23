#!/usr/bin/env bash
# Render Downloads.md + Downloads-Archive.md from GitHub Releases data and
# push them to the project's wiki repo. Idempotent: no commit if content is
# unchanged.
#
# Since the multi-version migration, each loader ships one jar per supported
# Minecraft version, with the MC version encoded as a SemVer build-metadata
# suffix on the filename — e.g. adventureitemnames-fabric-<ver>+1.21.1.jar and
# adventureitemnames-fabric-<ver>+1.20.1.jar (NeoForge is 1.21.1-only). Both
# pages present a loader x MC-version matrix parsed from that suffix. Filenames
# WITHOUT a +<mc> suffix (pre-migration loader jars) resolve to 1.21.1, and the
# pre-v0.2.0 single jar (adventureitemnames-<ver>.jar) still falls back into the
# NeoForge / 1.21.1 slot so historical rows render.
#
# Required env:
#   REPO        owner/name (e.g. bh679/adventureitemnames-mc). Always required.
#   GH_TOKEN    Token for `gh api` calls (workflow's github.token is fine).
#               Not required when RELEASES_FILE is set.
#   WIKI_TOKEN  Personal access token with Contents: write on the parent repo
#               (covers the .wiki.git repo). Not required in dry-run (OUTPUT_DIR).
#   RELEASE_TAG The tag we just released (used in the commit message). Not
#               required in dry-run (OUTPUT_DIR).
#
# Optional env (for local testing — no effect on the production code path):
#   RELEASES_FILE  Path to a JSON file holding the releases array (same shape as
#                  `gh api repos/$REPO/releases`, drafts already filtered out).
#                  When set, the `gh api` call is skipped.
#   OUTPUT_DIR     When set, render the two pages into this directory and skip the
#                  wiki clone/commit/push entirely (dry run).
#
# Local test (live):
#   WIKI_TOKEN=$(gh auth token) GH_TOKEN=$(gh auth token) \
#     REPO=bh679/adventureitemnames-mc RELEASE_TAG=v0.1.1 \
#     bash scripts/publish-wiki.sh
#
# Local test (offline, against a fixture, no push):
#   REPO=bh679/adventureitemnames-mc \
#     RELEASES_FILE=scripts/fixtures/releases-multiversion.json \
#     OUTPUT_DIR=/tmp/wiki-out bash scripts/publish-wiki.sh

set -euo pipefail

: "${REPO:?must be set}"

if [ -z "${RELEASES_FILE:-}" ]; then
  : "${GH_TOKEN:?must be set (or set RELEASES_FILE to a fixture)}"
fi

DRY_RUN=0
if [ -n "${OUTPUT_DIR:-}" ]; then
  DRY_RUN=1
else
  : "${WIKI_TOKEN:?must be set}"
  : "${RELEASE_TAG:?must be set}"
fi

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

# Fetch all releases (newest first by default). Filter out drafts only — we
# keep prereleases because pre-1.0 (`0.x.x`) releases are auto-flagged as
# prereleases by the workflow, and they ARE the available builds for a
# pre-1.0 mod. Once 1.0.0 ships, all stable releases will be non-prerelease
# and the latest-stable picker still works correctly.
RELEASES_JSON="$WORKDIR/releases.json"
if [ -n "${RELEASES_FILE:-}" ]; then
  cp "$RELEASES_FILE" "$RELEASES_JSON"
else
  gh api "repos/$REPO/releases?per_page=100" \
    --jq '[.[] | select(.draft==false)]' \
    > "$RELEASES_JSON"
fi

NUM_RELEASES=$(jq 'length' "$RELEASES_JSON")
if [ "$NUM_RELEASES" -lt 1 ]; then
  echo "::warning::No published releases found for $REPO; skipping wiki update."
  exit 0
fi

LATEST_TAG=$(jq -r '.[0].tag_name' "$RELEASES_JSON")
LATEST_DATE=$(jq -r '.[0].published_at[0:10]' "$RELEASES_JSON")
LATEST_URL=$(jq -r '.[0].html_url' "$RELEASES_JSON")

if [ "$(jq '.[0].assets | length' "$RELEASES_JSON")" -eq 0 ]; then
  echo "::warning::Latest release $LATEST_TAG has no assets; skipping wiki update."
  exit 0
fi

# Format jar size as KB/MB.
human_size() {
  local bytes="$1"
  if [ "$bytes" -ge 1048576 ]; then
    awk "BEGIN { printf \"%.1f MB\", $bytes / 1048576 }"
  else
    awk "BEGIN { printf \"%d KB\", ($bytes + 512) / 1024 }"
  fi
}

# For a given release index, loader, and Minecraft version, print
# "[`<filename>`](<url>) (<size>)" or an empty string if no matching jar is
# attached. A jar's MC version is parsed from the +<mc> build-metadata suffix on
# its filename; a jar with no suffix (pre-migration loader build) resolves to
# 1.21.1. Pre-v0.2.0 releases shipped a single jar named
# adventureitemnames-<version>.jar — we fall back to that for the NeoForge /
# 1.21.1 slot so the archive table doesn't show empty cells for early builds.
asset_cell() {
  local release_idx="$1" loader="$2" mc="$3"
  local asset_json
  asset_json=$(jq -c --argjson i "$release_idx" --arg loader "$loader" --arg mc "$mc" '
    .[$i].assets[]
    | select(.name | test("adventureitemnames-" + $loader + "-"))
    | . as $a
    | ((($a.name | capture("\\+(?<m>[0-9][0-9.]*)\\.jar$") | .m)?) // "1.21.1") as $amc
    | select($amc == $mc)
    | { name: $a.name, url: $a.browser_download_url, size: $a.size }
  ' "$RELEASES_JSON" | head -1)
  if [ -z "$asset_json" ] && [ "$loader" = "neoforge" ] && [ "$mc" = "1.21.1" ]; then
    asset_json=$(jq -c --argjson i "$release_idx" '
      .[$i].assets[]
      | select(.name | test("adventureitemnames-[0-9].*\\.jar$"))
      | { name: .name, url: .browser_download_url, size: .size }
    ' "$RELEASES_JSON" | head -1)
  fi
  if [ -z "$asset_json" ]; then
    echo ""
    return
  fi
  local name url size size_h
  name=$(echo "$asset_json" | jq -r '.name')
  url=$(echo "$asset_json" | jq -r '.url')
  size=$(echo "$asset_json" | jq -r '.size')
  size_h=$(human_size "$size")
  echo "[\`$name\`]($url) ($size_h)"
}

# Distinct Minecraft versions present in a release's loader jars, sorted
# descending (newest first). Jars with no +<mc> suffix count as 1.21.1. The
# pre-v0.2.0 single jar (no loader segment) is intentionally excluded here — it
# is surfaced only via the NeoForge fallback in asset_cell.
mc_versions() {
  local release_idx="$1"
  jq -r --argjson i "$release_idx" '
    [ .[$i].assets[]
      | select(.name | test("adventureitemnames-[a-z]+-"))
      | (((.name | capture("\\+(?<m>[0-9][0-9.]*)\\.jar$") | .m)?) // "1.21.1")
    ]
    | unique
    | .[]
  ' "$RELEASES_JSON" | sort -t. -k1,1nr -k2,2nr -k3,3nr
}

# Join version strings into prose: "A", "A and B", or "A, B and C".
join_versions() {
  local -a v=("$@")
  local n=${#v[@]}
  case "$n" in
    0) echo "1.21.1"; return ;;
    1) echo "${v[0]}"; return ;;
  esac
  local last="${v[$((n - 1))]}"
  local IFS=", "
  local head="${v[*]:0:$((n - 1))}"
  echo "$head and $last"
}

# Minecraft versions advertised on the latest release (drives both the intro
# wording and the latest-release matrix rows). Fall back to 1.21.1 if the latest
# release somehow exposes no loader jars (e.g. a lone pre-v0.2.0 single jar).
LATEST_MCS=()
while IFS= read -r line; do
  [ -n "$line" ] && LATEST_MCS+=("$line")
done < <(mc_versions 0)
if [ "${#LATEST_MCS[@]}" -eq 0 ]; then
  LATEST_MCS=("1.21.1")
fi
MC_LIST_HUMAN=$(join_versions "${LATEST_MCS[@]}")

# --- Render Downloads.md ---
DL_FILE="$WORKDIR/Downloads.md"
{
  cat <<EOF
# Downloads

Adventure Item Names is a Minecraft mod for Fabric, Forge, and NeoForge, available for Minecraft $MC_LIST_HUMAN — drop the matching jar into your \`mods/\` folder and launch.

## Latest release: $LATEST_TAG

**Released:** $LATEST_DATE

Choose your Minecraft version and loader:

| Minecraft | Fabric | Forge | NeoForge |
|---|---|---|---|
EOF
  for mc in "${LATEST_MCS[@]}"; do
    fab=$(asset_cell 0 fabric "$mc")
    fge=$(asset_cell 0 forge "$mc")
    nfg=$(asset_cell 0 neoforge "$mc")
    echo "| $mc | ${fab:-—} | ${fge:-—} | ${nfg:-—} |"
  done
  cat <<EOF

[Release notes on GitHub →]($LATEST_URL)

---

Looking for an older build? See [Downloads-Archive](Downloads-Archive).

For datapack extension and the Java API, see the [project README](https://github.com/$REPO#readme).

> This page is auto-updated on every release by \`scripts/publish-wiki.sh\` (run from the \`release.yml\` workflow on every dispatched release).
EOF
} > "$DL_FILE"

# --- Render Downloads-Archive.md ---
# One row per release. Columns are a fixed loader x MC-version grid; NeoForge is
# 1.21.1-only so the NeoForge/1.20.1 column is omitted. Legacy 3-jar releases
# fill only the 1.21.1 columns; the pre-v0.2.0 single jar fills only NeoForge
# 1.21.1 via the asset_cell fallback.
ARCH_FILE="$WORKDIR/Downloads-Archive.md"
cat > "$ARCH_FILE" <<EOF
# Downloads — All Releases

> Looking for the most recent build? See [Downloads](Downloads).

| Version | Released | Fabric 1.21.1 | Fabric 1.20.1 | Forge 1.21.1 | Forge 1.20.1 | NeoForge 1.21.1 | Release notes |
|---|---|---|---|---|---|---|---|
EOF

NUM=$(jq 'length' "$RELEASES_JSON")
for i in $(seq 0 $((NUM - 1))); do
  tag=$(jq -r --argjson i "$i" '.[$i].tag_name' "$RELEASES_JSON")
  date=$(jq -r --argjson i "$i" '.[$i].published_at[0:10]' "$RELEASES_JSON")
  html_url=$(jq -r --argjson i "$i" '.[$i].html_url' "$RELEASES_JSON")
  fab_21=$(asset_cell "$i" fabric "1.21.1")
  fab_20=$(asset_cell "$i" fabric "1.20.1")
  fge_21=$(asset_cell "$i" forge "1.21.1")
  fge_20=$(asset_cell "$i" forge "1.20.1")
  nfg_21=$(asset_cell "$i" neoforge "1.21.1")
  echo "| $tag | $date | ${fab_21:-—} | ${fab_20:-—} | ${fge_21:-—} | ${fge_20:-—} | ${nfg_21:-—} | [Notes]($html_url) |" >> "$ARCH_FILE"
done

cat >> "$ARCH_FILE" <<EOF

> v0.1.0 is the first public release. Earlier development happened inside the dungeon-train-mc repo before this mod was extracted.
>
> This page is auto-updated on every release by \`scripts/publish-wiki.sh\` (run from the \`release.yml\` workflow on every dispatched release).
EOF

# --- Dry run: write to OUTPUT_DIR and stop before touching the wiki repo ---
if [ "$DRY_RUN" -eq 1 ]; then
  mkdir -p "$OUTPUT_DIR"
  cp "$DL_FILE" "$OUTPUT_DIR/Downloads.md"
  cp "$ARCH_FILE" "$OUTPUT_DIR/Downloads-Archive.md"
  echo "Dry run: wrote Downloads.md + Downloads-Archive.md to $OUTPUT_DIR"
  exit 0
fi

# --- Push to wiki ---
WIKI_DIR="$WORKDIR/wiki"
git clone --quiet --depth 1 \
  "https://x-access-token:${WIKI_TOKEN}@github.com/${REPO}.wiki.git" \
  "$WIKI_DIR"

cp "$DL_FILE" "$WIKI_DIR/Downloads.md"
cp "$ARCH_FILE" "$WIKI_DIR/Downloads-Archive.md"

cd "$WIKI_DIR"

# `git diff` alone wouldn't see new files since they're untracked after `cp`.
# Stage first, then compare the index against HEAD so both "new file" and
# "modified" cases are detected.
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add Downloads.md Downloads-Archive.md

if git diff --quiet --cached -- Downloads.md Downloads-Archive.md; then
  echo "Wiki Downloads pages already match rendered output for $RELEASE_TAG; nothing to push."
  exit 0
fi

git commit -m "docs: auto-publish Downloads pages for $RELEASE_TAG"
git push origin HEAD

echo "Wiki Downloads pages updated for $RELEASE_TAG."
