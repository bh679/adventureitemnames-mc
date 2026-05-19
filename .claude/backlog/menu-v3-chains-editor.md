# Config Menu v3 — Chains editor

**Status:** Backlog. Implement after **v1 (Datapacks)** and **v2 (Spawn Chances + Selectors)**
are merged and stable.

This document is a self-contained prompt for a future session — it does not assume
you saw the v1 / v2 discussion. Read it cold, then start at Gate 1.

---

## Project context (skim if unfamiliar)

Adventure Item Names is a Minecraft 1.21.1 multi-loader mod (Fabric / Forge / NeoForge
via Architectury Loom) that procedurally names loot and mobs. Naming is composed
from three data layers:

- **Pools** (`common/src/main/resources/data/adventureitemnames/naming/pools/*.json`)
  — flat text lists.
- **Chains** (`.../naming/chains/*.json`) — ordered segments. Each segment picks one
  ref from a weighted list (sibling refs sum to whatever — they're renormalized at
  roll time) and applies it with a `chance` (0.0–1.0), `connection` string between
  this segment and the previous output, and an optional `newline` flag.
- **Selectors** (`.../naming/selectors/*.json`) — bind an item tag to a chain per
  tier (`plain` / `enchanted`).

Example chain — [`weapon_name_full.json`](../../common/src/main/resources/data/adventureitemnames/naming/chains/weapon_name_full.json):

```json
{
  "id": "adventureitemnames:weapon_name_full",
  "segments": [
    { "refs": [{ "ref": "adventureitemnames:title_combinations", "weight": 1.0 }],
      "chance": 1.0,  "connection": "",   "newline": false },
    { "refs": [{ "ref": "adventureitemnames:title_combinations", "weight": 1.0 }],
      "chance": 0.4,  "connection": " ",  "newline": false },
    { "refs": [{ "ref": "adventureitemnames:title_combinations", "weight": 1.0 }],
      "chance": 0.75, "connection": ", ", "newline": false },
    { "refs": [{ "ref": "adventureitemnames:title_combinations", "weight": 1.0 }],
      "chance": 0.2,  "connection": ". ", "newline": true }
  ]
}
```

## What v1 + v2 shipped (precondition for this work)

- v1: Custom config screen with three top-level entries (Datapacks, Spawn Chances,
  Chains). Datapacks editor with per-pool weight overrides, enable/disable, and a
  🎲 **Preview** panel at top or bottom of the screen.
- v1: `weight_overrides` and dirty-state **Save to pack** button (greyed when clean).
- v2: Spawn Chances editor — the four `CHANCE_*` constants now live in
  `NamingConfig` under a `chances` block. Selectors sub-screen lets the user remap
  the `plain` / `enchanted` chains per selector via dropdowns, stored as
  `selector_overrides` in config.

Confirm v1 and v2 are on `main` before starting v3.

---

## v3 goal

Expose per-segment chain editing so power users can tune the structure of generated
names — segment chances, connection strings, newline behaviour, and the per-ref
weight list within a segment.

This is the **power-user** screen. Most players will never open it. Make the
experience tolerable for power users without bloating the UI for casuals.

## v3 scope — Chains list screen (Table 2)

| Column | Source | Editable |
|---|---|:---:|
| Chain id | chain JSON `id` | no |
| Segments | count of `segments[]` | no |
| Used by | reverse lookup: selectors + chains that ref this chain | no |
| Enabled | `NamingConfig.isChainEnabled` | toggle |
| Notes | hand-written tooltip if known (e.g. "main pool picker") | no |

Click a row → expand inline (preferred) or open a detail screen for that chain.

## v3 scope — Chain detail / expanded row

For each segment, show:

| # | Refs (`ref → weight`) | Chance | Connection | Newline |
|---:|---|---:|---|:---:|
| 1 | `title_combinations` → 1.0 | 100% | `(none)` | ☐ |
| 2 | `title_combinations` → 1.0 | 40% | ` ` | ☐ |
| ... | ... | ... | ... | ... |

All five cells per segment are editable in v3:

- **Refs list**: add / remove / reorder ref rows. Each ref has a ref id (dropdown
  of discovered pools + chains + context refs like `context/item_material`) and
  a weight (float ≥ 0). v3 ships the dropdown — no free-text ref entry.
- **Chance**: float 0.0–1.0, slider + numeric input.
- **Connection**: short text input, capped at ~16 chars. Preview shows escapes
  (e.g. `\n`) so whitespace-only connections are visible.
- **Newline**: checkbox. Independent of connection — newline applies *after* this
  segment renders.

## v3 scope — chains shipped today

| Chain | Segments | Likely user tweaks |
|---|---:|---|
| `title_combinations` | 2 | rebalance per-ref weights — already exposed via Datapacks v1, surfaces here too for completeness |
| `weapon_name_full` | 4 | drop the trailing newline segment, lower chance on the `, ` extension |
| `weapon_name_short` | 1 | rarely touched |
| `mob_name` | 2 | bump the 5% title chance |
| `material_or_element` | 1 | shift the 50/50 between actual material and `elements` pool |
| `atla_title_combinations` | 2 | when ATLA is enabled — same shape as `title_combinations` |

## v3 scope — out of scope

- Creating new chains from scratch (still requires shipping a JSON file).
- Renaming chain ids.
- Editing context refs (e.g. `context/item_material`) — those are Java-resolved.

---

## Data model

`chain_overrides` block in `adventureitemnames.json`:

```json
{
  "chain_overrides": {
    "adventureitemnames:weapon_name_full": {
      "segments": [
        null,
        { "chance": 0.4, "newline": false },
        { "chance": 0.75, "connection": ", " },
        { "chance": 0.0, "newline": false }
      ]
    }
  }
}
```

- `null` entries mean "no override on this segment".
- Partial segments mean "merge with shipped values; unspecified fields untouched".
- An overridden `refs` array fully replaces the shipped one (no per-ref merge —
  too fiddly).
- Adding segments via override is **not supported** in v3. Removing is done by
  setting `chance: 0.0` — keeps the original index alignment intact and stays
  reversible. If a future version wants true add/remove, design a `segments_full`
  alternative that replaces wholesale.

## Validation

- Reject negative weights and weights > some sane cap (say 1000) with a log warning.
- A segment with all-zero ref weights skips at roll time (existing behaviour) — UI
  should show a warning chip on that segment.
- A ref pointing at a non-existent id logs a warning and is skipped — UI should
  show the broken ref in red.
- Connection strings: strip control characters except `\n` (which `newline`
  handles separately anyway).

---

## UI integration

- Lives on the **Chains** menu entry that v1 stubbed out.
- Reuse v1's dirty-state tracker — any chain edit dirties the **Save to pack**
  button.
- Reuse v1's 🎲 **Preview** panel. v3 adds a per-chain preview: roll 10 names
  forcing entry through *this* chain, so the user can see what their edits do
  without leaving the screen.
- Per-segment 🎲 preview is **optional** — only worthwhile if a segment can be
  tested in isolation (it usually can't, because segments share state with
  earlier output via `connection`). Skip in v3 unless cheap.

---

## Gate 2 testing checklist (Fabric + NeoForge dev clients, Forge prod-jar smoke)

1. Open Chains screen, expand `weapon_name_full`.
2. Set segment 2 `chance` to 1.0, segment 3 and 4 to 0.0.
3. Save, open chest with enchanted weapons — every enchanted weapon has exactly
   two title parts joined by a space, no comma, no newline.
4. Reset segment 4 to default (0.2 chance, newline true) — newlines reappear.
5. Edit `mob_name` segment 2 chance to 1.0 — every passive mob now gets a
   `<name> <title>` instead of bare `<name>`.
6. Edit `material_or_element` segment 1 refs: set `elements` weight to 0.0,
   `context/item_material` weight to 1.0 — every "of X" using this chain now
   resolves to the item's actual material (Iron, Diamond, Netherite).
7. Quit and reopen — overrides persist. Delete config — defaults restore.
8. Verify a chain disabled at the top level (Enabled toggle off) stops contributing
   regardless of segment edits.
9. Cross-loader parity: same scenarios on NeoForge dev client.

## Files likely to touch

- `common/src/main/java/games/brennan/adventureitemnames/internal/NamingConfig.java` —
  new chain-override accessor + merge logic.
- `common/src/main/java/games/brennan/adventureitemnames/internal/ConfigCodec.java` —
  new `chain_overrides` schema.
- `common/src/main/java/games/brennan/adventureitemnames/api/NameComposer.java` —
  apply per-segment overrides during chain resolution. Watch out for the
  `MAX_DEPTH = 16` recursion cap — overrides shouldn't make this easier to hit.
- `common/src/main/java/games/brennan/adventureitemnames/client/screen/` —
  new ChainsScreen + ChainDetailScreen (or expanded-row variant).

## Wiki updates after Gate 3

- `Features.md` — document the chain editor and how to interpret `chance` /
  `connection` / `newline`.
- `Development.md` — document the `chain_overrides` schema for users who edit
  config by hand.

## Open design questions for v3

1. Inline expand vs. detail screen for the chain editor — inline is faster to
   scan but cramps on small windows; detail screen has more room but adds a
   navigation hop.
2. Should adding / removing refs within a segment be in v3, or pushed to v4?
   Adding refs implies a ref-picker dropdown that has to know every available
   pool + chain + context ref. It's the largest UI piece in v3.
3. How to surface that `title_combinations` per-ref weights are *also* editable
   via the Datapacks screen — link button? Read-only here? Edit in either place?
4. Should `chain_overrides` per-segment writes happen on every keystroke (with
   debounce) or only on Save? v1 settled on Save — keep that contract.
