#!/usr/bin/env bash
# Send a rich Discord webhook embed announcing an Adventure Item Names release.
#
# Required env (release.yml provides these):
#   DISCORD_WEBHOOK_URL          Discord channel webhook URL (secret).
#   RELEASE_TAG                  e.g. v0.1.1
#   REPO                         e.g. bh679/adventureitemnames-mc
#   PRERELEASE                   "true" or "false" — controls embed color + label.
#   MODRINTH_FABRIC_VERSION      mc-publish output for the Fabric upload; non-empty on success.
#   MODRINTH_FORGE_VERSION       …Forge…
#   MODRINTH_NEOFORGE_VERSION    …NeoForge…
#   CURSEFORGE_FABRIC_VERSION    mc-publish output for the CurseForge Fabric upload.
#   CURSEFORGE_FORGE_VERSION     …Forge…
#   CURSEFORGE_NEOFORGE_VERSION  …NeoForge…
#   GH_TOKEN                     gh CLI auth (already set by GitHub Actions for `${{ github.token }}`).
#
# Idempotence: Discord webhooks always create a new message. Re-firing
# workflow_dispatch against the same tag will produce a duplicate
# announcement. Acceptable for now.

set -euo pipefail

: "${DISCORD_WEBHOOK_URL:?required}"
: "${RELEASE_TAG:?required}"
: "${REPO:?required}"
: "${PRERELEASE:?required}"

# Per-platform success markers across all three loader uploads.
# ✅ when all three loaders uploaded, ⚠️ on any miss (full or partial).
mark_for_platform() {
  local fab="$1" fge="$2" nfg="$3"
  if [ -n "$fab" ] && [ -n "$fge" ] && [ -n "$nfg" ]; then
    echo "✅"
  else
    echo "⚠️"
  fi
}
MR_MARK=$(mark_for_platform "${MODRINTH_FABRIC_VERSION:-}" "${MODRINTH_FORGE_VERSION:-}" "${MODRINTH_NEOFORGE_VERSION:-}")
CF_MARK=$(mark_for_platform "${CURSEFORGE_FABRIC_VERSION:-}" "${CURSEFORGE_FORGE_VERSION:-}" "${CURSEFORGE_NEOFORGE_VERSION:-}")

# Discord embed color: orange for beta builds, Discord-green for stable.
if [ "$PRERELEASE" = "true" ]; then
  COLOR=16753920    # 0xFF8C00 — orange
  TYPE_LABEL="Beta release"
else
  COLOR=5763719     # 0x57F287 — Discord-native green
  TYPE_LABEL="Release"
fi

# First ~500 chars of the GitHub release notes, used as the embed body.
NOTES=$(gh release view "$RELEASE_TAG" --repo "$REPO" --json body --jq '.body[:500]' 2>/dev/null || echo "")

LOGO_URL="https://raw.githubusercontent.com/$REPO/main/common/src/main/resources/logo.png"
LANDING_URL="https://github.com/$REPO/wiki/Downloads"
GH_RELEASE_URL="https://github.com/$REPO/releases/tag/$RELEASE_TAG"
# Each release now produces three loader-specific versions on Modrinth
# (v0.2.0+fabric / +forge / +neoforge), so link to the versions list rather
# than guessing a single version slug.
MODRINTH_URL="https://modrinth.com/mod/adventureitemnames/versions"
CURSEFORGE_URL="https://www.curseforge.com/minecraft/mc-mods/adventureitemnames/files"

PAYLOAD=$(jq -n \
  --arg title "Adventure Item Names $RELEASE_TAG" \
  --arg landing "$LANDING_URL" \
  --arg type "$TYPE_LABEL" \
  --arg notes "$NOTES" \
  --argjson color "$COLOR" \
  --arg cf_status "$CF_MARK" \
  --arg mr_status "$MR_MARK" \
  --arg cf_url "$CURSEFORGE_URL" \
  --arg mr_url "$MODRINTH_URL" \
  --arg gh_url "$GH_RELEASE_URL" \
  --arg logo "$LOGO_URL" \
  '{
    username: "Adventure Item Names",
    avatar_url: $logo,
    embeds: [{
      title: $title,
      url: $landing,
      description: ("**" + $type + "** — a new build is available.\n\n" + $notes),
      color: $color,
      thumbnail: { url: $logo },
      fields: [
        { name: "CurseForge", value: ($cf_status + " [Download](" + $cf_url + ")"), inline: true },
        { name: "Modrinth",   value: ($mr_status + " [Download](" + $mr_url + ")"), inline: true },
        { name: "GitHub",     value: ("✅ [Download](" + $gh_url + ")"),             inline: true }
      ],
      footer: { text: "By Brennan Hatton" }
    }]
  }')

# Allow caller to dry-run the script (build payload, skip POST) by setting DRY_RUN=1.
if [ "${DRY_RUN:-}" = "1" ]; then
  echo "$PAYLOAD"
  exit 0
fi

curl -fsS -X POST -H "Content-Type: application/json" -d "$PAYLOAD" "$DISCORD_WEBHOOK_URL" >/dev/null
echo "✓ Notified Discord for $RELEASE_TAG"
