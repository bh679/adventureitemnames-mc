# Config Menu v3 — Chains Editor + Custom Selectors

**Status:** Backlog. Implement after **v2 (Spawn Chances + Selectors)** is merged and stable.

This document is a self-contained prompt for a future session — it does not assume you saw the v1 or v2 discussions. Read it cold, then start at Gate 1.

---

## Project context (skim if unfamiliar)

Adventure Item Names is a Minecraft 1.21.1 multi-loader mod (Fabric / Forge / NeoForge via Architectury Loom) that procedurally names loot and mobs. The naming system has three data layers:

- **Pools** (`common/src/main/resources/data/adventureitemnames/naming/pools/*.json`): flat lists of text entries.
- **Chains** (`.../naming/chains/*.json`): ordered segments. Each segment picks one ref from a weighted list and applies it with a `chance`, `connection` string, and optional `newline`.
- **Selectors** (`.../naming/selectors/*.json`): map an item tag (e.g. `minecraft:swords`) to a chain per **tier** (`plain` / `enchanted`).

User config lives in `config/adventureitemnames.json` and is parsed by [`ConfigCodec`](../../common/src/main/java/games/brennan/adventureitemnames/internal/ConfigCodec.java) into a [`LoadedConfig`](../../common/src/main/java/games/brennan/adventureitemnames/internal/LoadedConfig.java) (disables / weight overrides / chance overrides / selector tier overrides). The runtime entry points read everything via [`NamingConfig`](../../common/src/main/java/games/brennan/adventureitemnames/api/NamingConfig.java) which layers `datapack → user → API` for each axis.

## What v1 + v2 shipped (precondition for this work)

- **v1 (PR #16):** in-game config screen launched via `/adventureitemnames config` + keybind (Mod Menu integration on Fabric/NeoForge). Hub with three buttons — **Datapacks**, **Spawn Chances**, **Chains**. **Datapacks** screen lets the user edit per-pool weights in `title_combinations` with live `%` recalc + enable/disable + a six-slot 🎲 **Preview** strip + a **Save to pack** flush button.
- **v2 (PR #19, ships in 0.11.0):** **Spawn Chances** screen edits the four naming-probability gates (plain item, enchanted item, passive mob, villager) with slider + 2-decimal text-box + ↺ reset per row. A **Configure selectors →** footer button opens a **Selectors** sub-screen — 2-column grid of 11 selectors (sword, axe, pickaxe, shovel, hoe, bow, helmet, chestplate, leggings, boots, shield) with icon + two chain-dropdowns + enabled checkbox per cell. Clicking a dropdown opens a modal popup picker with the full chain list (including a `(none)` sentinel that suppresses one tier). Config schema gained `chances` and `selector_overrides` blocks. The four `CHANCE_*` constants moved out of `NameComposer` into a layered `NamingConfig` surface. A `+ Add custom selector` row at the bottom of the Selectors list opens a v3 placeholder.

**Chains** is still a placeholder on the hub. Custom selector creation is still a placeholder on the Selectors list.

Confirm v2 is on `main` before starting v3.

---

## v3 goal

Make the two remaining placeholders work — let the user edit the inside of a chain (per-segment chance / connection / newline / per-ref weights) and add brand-new selectors via UI without writing a JSON file. Both flows go through the same EditBuffer + Save-to-pack pipeline that v1 + v2 already established.

## v3 scope — Chains screen

Replaces the **Chains** placeholder on the hub. Two screens deep:

**Top-level chain list** — one row per registered chain (auto-discovered via `NameRegistry.allChains()`, sorted by id; first-party `adventureitemnames:*` chains first, then datapack chains). Each row:

| Column | Source | Editable |
|---|---|---|
| Chain id | `NameChain.id()` | no |
| Pack id | `NameRegistry.packIdOfChain(id)` | no |
| Segment count | `NameChain.segments().size()` | no |
| Enabled | `NamingConfig.isChainEnabled(id)` | toggle |
| Open | — | button → opens per-chain editor |

**Per-chain editor** — one row per segment. Each row:

| Column | Source | Editable in v3 |
|---|---|---|
| Segment idx | int | no |
| Chance | `NameSegment.chance()` | slider + text-box (0.0–1.0) |
| Connection | `NameSegment.connection()` | text-box (single-line) |
| Newline | `NameSegment.newline()` | checkbox |
| Refs | `NameSegment.refs()` | button → opens nested per-ref editor |

**Per-ref editor** (nested inside a segment) — one row per `WeightedRef`. Each row:

| Column | Source | Editable |
|---|---|---|
| Ref id | `WeightedRef.ref()` | dropdown picker (chain or pool) |
| Weight | `WeightedRef.weight()` | text-box (float) |
| % share | computed | read-only |

A footer **+ Add ref** button on the per-ref editor lets the user attach a new ref to the current segment. A `🗑️` per-row deletes a ref.

Editable values flow through the v1 EditBuffer pattern. Weight edits already plumb through `weight_overrides` — v3 extends the buffer + schema for chance / connection / newline / ref-list overrides.

## v3 scope — Custom selectors

Wires up the `+ Add custom selector` row at the bottom of the v2 Selectors screen. Click → opens a creation popup with:

| Field | Widget | Required |
|---|---|---|
| Selector id | text-box (auto-derived from tag path) | yes |
| Item tag | tag picker (dropdown of existing item tags) | yes |
| Plain chain | chain dropdown (same picker as v2) | yes |
| Enchanted chain | chain dropdown | optional (defaults to plain) |

Submit → buffer holds a "new selector" entry. On Save-to-pack, a new `custom_selectors` block is written to `adventureitemnames.json`:

```json
{
  "custom_selectors": {
    "adventureitemnames:mace": {
      "applies_to": "minecraft:maces",
      "tiers": {
        "plain":     "adventureitemnames:weapon_name_short",
        "enchanted": "adventureitemnames:weapon_name_full"
      }
    }
  }
}
```

These selectors get loaded by a new path in `UserConfigLoader` → injected into `NameRegistry.SELECTORS` alongside the shipped JSON selectors. Same id-collision rule as datapacks: first-declared wins; UI surfaces the conflict.

Tag picker source: every `TagKey<Item>` known to the client at load time (server tags pushed during world join). Limited to vanilla + datapack tags — the picker is a one-screen modal similar to `ChainPicker`.

## v3 scope — out of scope

- Creating new chains or pools via UI (still requires shipping a JSON file).
- Editing pool entries / weights from inside the chain editor (that's the Datapacks screen).
- Adding new tags (out of scope for this mod entirely — tags are vanilla / datapack content).
- "Reset all" button next to **Save to pack** (revisit if v3 testing shows per-row ↺ isn't enough).
- Live preview of `connection` / `newline` edits in the bottom preview strip — too niche to wire up.

---

## Java refactor required

### Chain-segment overrides

Sibling of [`WeightOverrides`](../../common/src/main/java/games/brennan/adventureitemnames/internal/WeightOverrides.java) — new `internal/SegmentOverrides.java`:

```java
public final class SegmentOverrides {
    // chainId#segmentIdx → SegmentEdit (nullable per-field)
    public final Map<String, SegmentEdit> edits = new HashMap<>();
    public record SegmentEdit(Float chance, String connection, Boolean newline) {}
}
```

[`NamingConfig`](../../common/src/main/java/games/brennan/adventureitemnames/api/NamingConfig.java)
- Add `USER_SEGMENTS` + `API_SEGMENTS` layers + setters.
- `effectiveSegmentChance(chainId, idx, shipped)` / `effectiveSegmentConnection(...)` / `effectiveSegmentNewline(...)` getters with the same `API → user → shipped` precedence as `effectiveWeight`.

[`NameComposer.compose`](../../common/src/main/java/games/brennan/adventureitemnames/api/NameComposer.java#L253) reads each segment's effective chance / connection / newline instead of the record's fields directly.

### Custom selectors

New `internal/CustomSelectors.java` + loader path:

- `LoadedConfig` gains a `Map<ResourceLocation, NameSelector>` for user-defined selectors.
- `UserConfigLoader.reload` calls `NameRegistry.installUserSelectors(map)` which merges them into the post-datapack-load `SELECTORS` view (insertion order honoured; user selectors after datapack ones).
- `NameRegistry.allSelectors()` should keep returning the merged view so the v2 Selectors screen auto-picks user selectors up.

### Ref-list overrides (the +/🗑️ flow)

Trickier — `NameSegment.refs()` is currently an immutable list on the record. Two options:

1. Add a per-segment `refs` override in `SegmentOverrides` that *replaces* the shipped ref list when present. Schema adds an array.
2. Add separate `addRef` + `removeRef` lists (delta-style). More compact for small edits but more code.

Recommend **option 1** for simplicity — the per-ref weight overrides already use `weight_overrides[chainId#segIdx#refId]` which composes cleanly with a replaced ref list.

---

## Config schema additions

```json
{
  "segment_overrides": {
    "adventureitemnames:title_combinations#1": {
      "chance":     0.85,
      "connection": " of ",
      "newline":    false
    }
  },
  "custom_selectors": {
    "adventureitemnames:mace": {
      "applies_to": "minecraft:maces",
      "tiers": {
        "plain":     "adventureitemnames:weapon_name_short",
        "enchanted": "adventureitemnames:weapon_name_full"
      }
    }
  }
}
```

Both optional; missing keys fall through to shipped JSON.

Per-segment-replacement ref lists (if needed): nested under `segment_overrides[id#idx].refs` as an array of `{ "ref": "...", "weight": 1.0 }`.

---

## UI integration

- Re-use v1's `EditBuffer` dirty-state + `Save to pack` pattern. New pending state: `pendingSegmentEdits`, `pendingCustomSelectors`.
- Re-use v2's `ChainPicker` modal for the chain dropdowns in both the per-ref editor and the custom-selector creation popup.
- New `TagPicker` modal (parallels `ChainPicker`) for the custom-selector creation flow.
- Both new screens get a bottom-docked gated `PreviewPanel` like the v2 screens, so segment / selector edits show in the live preview.

---

## Gate 2 testing checklist (Fabric + NeoForge dev clients)

1. Open the config screen via command / keybind / Mod Menu.
2. **Chains screen → title_combinations**:
   - Edit segment 1's chance to 0.0, save → no segment-1 fragment shows in rolled names.
   - Edit segment 1's connection to ` of `, save → connection visible in rolled names.
   - Toggle segment 1's newline, save → name wraps to two lines in chest tooltips.
3. **Per-ref editor on title_combinations[1]**:
   - Bump `mc_technoblade` weight from default to 10.0 → preview shows technoblade dominating.
   - Add a new ref pointing at a different pool → preview rolls it in.
   - Delete a ref → preview rolls no longer pick it.
4. **Custom selectors**:
   - Click `+ Add custom selector` → popup opens.
   - Pick `minecraft:music_discs` as the tag, `adventureitemnames:weapon_name_short` as plain → save → preview a music disc → it gets named.
   - Quit and reopen → custom selector persists; UI re-renders.
5. **Reload** (delete `adventureitemnames.json` → defaults restore).
6. Cross-loader parity on NeoForge dev client.

## Files likely to touch

- `common/src/main/java/games/brennan/adventureitemnames/api/NameComposer.java` — segment field reads via `NamingConfig`.
- `common/src/main/java/games/brennan/adventureitemnames/api/NamingConfig.java` — new layers + getters.
- `common/src/main/java/games/brennan/adventureitemnames/internal/ConfigCodec.java` — `segment_overrides` + `custom_selectors` blocks.
- `common/src/main/java/games/brennan/adventureitemnames/internal/LoadedConfig.java` — two new fields.
- `common/src/main/java/games/brennan/adventureitemnames/internal/UserConfigLoader.java` + `UserConfigWriter.java` — read/write the new blocks.
- `common/src/main/java/games/brennan/adventureitemnames/internal/NameRegistry.java` — `installUserSelectors` injection point.
- `common/src/main/java/games/brennan/adventureitemnames/client/EditBuffer.java` — pending segment edits + pending custom selectors.
- `common/src/main/java/games/brennan/adventureitemnames/client/ConfigScreen.java` — wire the **Chains** button to the new screen.
- New: `internal/SegmentOverrides.java`, `internal/CustomSelectors.java`, `client/ChainsListScreen.java`, `client/ChainEditorScreen.java`, `client/RefEditorScreen.java`, `client/TagPicker.java`, `client/AddSelectorPopup.java`.
- `common/src/main/resources/assets/adventureitemnames/lang/en_us.json` — new strings.

## Wiki updates after Gate 3

- `Features.md` — Chains editor + custom selectors flow.
- `Compatibility.md` — note that user-defined selectors take effect at config-load time; datapacks loaded after user config still override.

## Open design questions for v3

1. Should the per-ref editor's `🗑️` actually remove the ref from the segment, or just zero its weight (cheap suppression — preserves the entry if the user wants to bring it back)?
2. Custom selector ids — auto-derive from the tag path (`minecraft:maces` → `adventureitemnames:mace`) or require the user to type one?
3. Should the Chains list also show pack-id grouping (like the v1 Datapacks screen does) or stay flat with a "Pack" column?
4. Tag picker — flat alphabetical list, or grouped by namespace?
5. Connection string editor — single-line text-box is enough, or should we also expose newline + tab characters via a small "literal char" menu? Most chain authors will be happy with a plain space / ` of ` / `, ` so simple text-box probably enough.
6. When a user-defined selector's `applies_to` tag doesn't exist, what happens — silently skip on registry install, or surface a UI warning in the row?
