#!/usr/bin/env bash
# Render Downloads.md + Downloads-Archive.md from GitHub Releases data and
# push them to the project's wiki repo. Idempotent: no commit if content is
# unchanged.
#
# Required env:
#   WIKI_TOKEN  Personal access token with Contents: write on the parent repo
#               (covers the .wiki.git repo).
#   GH_TOKEN    Token for `gh api` calls (workflow's github.token is fine).
#   REPO        owner/name (e.g. bh679/adventureitemnames-mc).
#   RELEASE_TAG The tag we just released (used in the commit message).
#
# Local test:
#   WIKI_TOKEN=$(gh auth token) GH_TOKEN=$(gh auth token) \
#     REPO=bh679/adventureitemnames-mc RELEASE_TAG=v0.1.1 \
#     bash scripts/publish-wiki.sh

set -euo pipefail

: "${WIKI_TOKEN:?must be set}"
: "${GH_TOKEN:?must be set}"
: "${REPO:?must be set}"
: "${RELEASE_TAG:?must be set}"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

# Fetch all releases (newest first by default). Filter out drafts only — we
# keep prereleases because pre-1.0 (`0.x.x`) releases are auto-flagged as
# prereleases by the workflow, and they ARE the available builds for a
# pre-1.0 mod. Once 1.0.0 ships, all stable releases will be non-prerelease
# and the latest-stable picker still works correctly.
RELEASES_JSON="$WORKDIR/releases.json"
gh api "repos/$REPO/releases?per_page=100" \
  --jq '[.[] | select(.draft==false)]' \
  > "$RELEASES_JSON"

NUM_RELEASES=$(jq 'length' "$RELEASES_JSON")
if [ "$NUM_RELEASES" -lt 1 ]; then
  echo "::warning::No published releases found for $REPO; skipping wiki update."
  exit 0
fi

LATEST_TAG=$(jq -r '.[0].tag_name' "$RELEASES_JSON")
LATEST_NAME=$(jq -r '.[0].name // .[0].tag_name' "$RELEASES_JSON")
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

# For a given release index and loader, print "[<filename> (<size>)](<url>)"
# or an empty string if that loader's jar isn't attached. Loaders match the
# filename pattern adventureitemnames-<loader>-<version>.jar produced by the
# multi-loader build. Pre-v0.2.0 releases shipped a single jar named
# adventureitemnames-<version>.jar — we fall back to that for the NeoForge
# slot so the archive table doesn't show empty cells for early builds.
loader_link() {
  local release_idx="$1" loader="$2"
  local asset_json
  asset_json=$(jq -c --argjson i "$release_idx" --arg loader "$loader" '
    .[$i].assets[]
    | select(.name | test("adventureitemnames-" + $loader + "-.*\\.jar$"))
    | { name: .name, url: .browser_download_url, size: .size }
  ' "$RELEASES_JSON" | head -1)
  if [ -z "$asset_json" ] && [ "$loader" = "neoforge" ]; then
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

LATEST_FABRIC=$(loader_link 0 fabric)
LATEST_FORGE=$(loader_link 0 forge)
LATEST_NEOFORGE=$(loader_link 0 neoforge)

# --- Render Downloads.md ---
DL_FILE="$WORKDIR/Downloads.md"
{
  cat <<EOF
# Downloads

Adventure Item Names is a Minecraft 1.21.1 mod for Fabric, Forge, and NeoForge — drop the matching jar into your \`mods/\` folder and launch.

## Latest release: $LATEST_TAG

**Released:** $LATEST_DATE

Choose your loader:

EOF
  [ -n "$LATEST_FABRIC" ]   && echo "- **Fabric** — $LATEST_FABRIC"
  [ -n "$LATEST_FORGE" ]    && echo "- **Forge** — $LATEST_FORGE"
  [ -n "$LATEST_NEOFORGE" ] && echo "- **NeoForge** — $LATEST_NEOFORGE"
  cat <<EOF

[Release notes on GitHub →]($LATEST_URL)

---

Looking for an older build? See [Downloads-Archive](Downloads-Archive).

For datapack extension and the Java API, see the [project README](https://github.com/$REPO#readme).

> This page is auto-updated on every release by \`scripts/publish-wiki.sh\` (run from the \`release.yml\` workflow on every dispatched release).
EOF
} > "$DL_FILE"

# --- Render Downloads-Archive.md ---
ARCH_FILE="$WORKDIR/Downloads-Archive.md"
cat > "$ARCH_FILE" <<EOF
# Downloads — All Releases

> Looking for the most recent build? See [Downloads](Downloads).

| Version | Released | Fabric | Forge | NeoForge | Release notes |
|---|---|---|---|---|---|
EOF

NUM=$(jq 'length' "$RELEASES_JSON")
for i in $(seq 0 $((NUM - 1))); do
  tag=$(jq -r --argjson i "$i" '.[$i].tag_name' "$RELEASES_JSON")
  date=$(jq -r --argjson i "$i" '.[$i].published_at[0:10]' "$RELEASES_JSON")
  html_url=$(jq -r --argjson i "$i" '.[$i].html_url' "$RELEASES_JSON")
  fab=$(loader_link "$i" fabric)
  fge=$(loader_link "$i" forge)
  nfg=$(loader_link "$i" neoforge)
  echo "| $tag | $date | ${fab:-—} | ${fge:-—} | ${nfg:-—} | [Notes]($html_url) |" >> "$ARCH_FILE"
done

cat >> "$ARCH_FILE" <<EOF

> v0.1.0 is the first public release. Earlier development happened inside the dungeon-train-mc repo before this mod was extracted.
>
> This page is auto-updated on every release by \`scripts/publish-wiki.sh\` (run from the \`release.yml\` workflow on every dispatched release).
EOF

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
