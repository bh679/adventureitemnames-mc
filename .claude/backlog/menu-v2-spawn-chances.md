# Config Menu v2 — Spawn Chances + Selectors

**Status:** Backlog. Implement after **v1 (Datapacks)** is merged and stable.

This document is a self-contained prompt for a future session — it does not assume
you saw the v1 discussion. Read it cold, then start at Gate 1.

---

## Project context (skim if unfamiliar)

Adventure Item Names is a Minecraft 1.21.1 multi-loader mod (Fabric / Forge / NeoForge
via Architectury Loom) that procedurally names loot and mobs. The naming system has
three data layers:

- **Pools** (`common/src/main/resources/data/adventureitemnames/naming/pools/*.json`):
  flat lists of text entries.
- **Chains** (`.../naming/chains/*.json`): ordered segments. Each segment picks one
  ref from a weighted list and applies it with a `chance`, `connection` string, and
  optional `newline`.
- **Selectors** (`.../naming/selectors/*.json`): map an item tag (e.g. `minecraft:swords`)
  to a chain per **tier** (`plain` / `enchanted`).

Top-level gates live as `private static final float` constants in
[`NameComposer.java:51-58`](../../common/src/main/java/games/brennan/adventureitemnames/api/NameComposer.java):

| Constant | Default | Trigger |
|---|---:|---|
| `CHANCE_PLAIN` | 0.30 | Plain item matches selector |
| `CHANCE_ENCHANTED` | 0.50 | Enchanted item matches selector |
| `CHANCE_MOB_PASSIVE` | 0.05 | Passive mob spawns (animal / water / golem / allay) |
| `CHANCE_MOB_VILLAGER` | 1.00 | Villager / wandering trader spawns |

User config is stored in `config/adventureitemnames.json` and accessed via
[`NamingConfig`](../../common/src/main/java/games/brennan/adventureitemnames/internal/)
/ [`ConfigCodec`](../../common/src/main/java/games/brennan/adventureitemnames/internal/ConfigCodec.java).
Today it supports enable/disable for pools / chains / selectors / items / mobs.

## What v1 shipped (precondition for this work)

- Custom config screen (likely launched from `/adventureitemnames config` + a keybind,
  with Mod Menu integration on Fabric/NeoForge).
- A top-level menu with three entry points: **Datapacks**, **Spawn Chances**,
  **Chains**. v1 implemented Datapacks only; the other two are placeholder buttons.
- Datapacks screen lets the user edit per-pool weights in `title_combinations` with
  live `%` recalc, enable/disable rows, and a 🎲 **Preview** panel at the top or
  bottom that rolls 10 sample names with current uncommitted edits.
- Edits go into a `weight_overrides` map in `adventureitemnames.json`. A
  **Save to pack** button at the bottom of the screen is greyed out when no changes
  are pending.
- Pools not referenced by `title_combinations` (title_prefix, mob_titles, elements,
  type_synonyms, people, inclusion) show as read-only rows with `—` weight but
  still have enable/disable + per-row preview.

Confirm v1 is on `main` before starting v2.

---

## v2 goal

Make the four spawn-chance gates and the ten selectors editable from the same
config screen. Replace the Java constants with config-backed values without
regressing the hot path.

## v2 scope — Spawn Chances screen (Table 4)

| Trigger | Editable | Default | Notes |
|---|---|---:|---|
| Plain item matches selector | float 0.0–1.0 | 0.30 | replaces `CHANCE_PLAIN` |
| Enchanted item matches selector | float 0.0–1.0 | 0.50 | replaces `CHANCE_ENCHANTED` |
| Passive mob spawns | float 0.0–1.0 | 0.05 | replaces `CHANCE_MOB_PASSIVE` |
| Villager / wandering trader spawns | float 0.0–1.0 | 1.00 | replaces `CHANCE_MOB_VILLAGER` |

Each row has a `↺` reset-to-default button. The screen exposes a
**[Configure selectors →]** button that opens the Selectors sub-table.

## v2 scope — Selectors sub-table (Table 3)

| Column | Source | Editable in v2 |
|---|---|---|
| Selector id | selector JSON `id` | no |
| Item tag | selector JSON `applies_to` | no |
| Plain chain | selector JSON `tiers.plain` | dropdown of discovered chains |
| Enchanted chain | selector JSON `tiers.enchanted` | dropdown of discovered chains |
| Enabled | `NamingConfig.isSelectorEnabled` | toggle |

Selector rows: sword, axe, pickaxe, shovel, hoe, helmet, chestplate, leggings,
boots, shield.

The chain dropdowns are populated from every chain discovered at load time —
including any user-datapack chains. Picking a non-existent chain is impossible
(no free-text entry).

## v2 scope — out of scope

- Editing per-segment chain values (that's v3).
- Adding new selectors via UI (still requires shipping a JSON file).
- Per-tag selector creation.

---

## Java refactor required

Move the four `CHANCE_*` constants from `NameComposer.java:51-58` into `NamingConfig`
with the same defaults. Hot path becomes a single field read instead of a constant —
benchmark first, but it should be negligible (rolling tens of items per chunk).

Suggested config shape addition:

```json
{
  "chances": {
    "plain":        0.30,
    "enchanted":    0.50,
    "mob_passive":  0.05,
    "mob_villager": 1.00
  }
}
```

Validation: clamp to `[0.0, 1.0]` on load. Out-of-range values should warn in the
log and fall back to default rather than crash.

The selector `tiers` map override stores user remappings without touching the shipped
JSON:

```json
{
  "selector_overrides": {
    "adventureitemnames:sword": {
      "plain":     "adventureitemnames:weapon_name_short",
      "enchanted": "adventureitemnames:weapon_name_full"
    }
  }
}
```

Only keys present in the override are applied — missing keys fall through to the
shipped JSON.

---

## UI integration

- v2 lives on the **Spawn Chances** menu entry that v1 stubbed out.
- Reuse the v1 dirty-state tracker and **Save to pack** button — chance edits and
  selector remappings count as dirty.
- Reuse the v1 🎲 **Preview** panel — preview rolls should respect the edited
  chances so the user can see how a 50% boost affects how many items get names.

---

## Gate 2 testing checklist (Fabric + NeoForge dev clients, Forge prod-jar smoke)

1. Open the config screen via command / keybind / Mod Menu.
2. Drag `CHANCE_PLAIN` to 1.0, open a chest with vanilla loot — every plain item
   gets a name.
3. Drag `CHANCE_PLAIN` to 0.0, repeat — no plain items get names, enchanted ones
   still do at 50%.
4. Drag `CHANCE_MOB_PASSIVE` to 1.0, spawn a few cows — every cow gets a name.
5. Open Selectors sub-table, switch `sword` plain chain to `weapon_name_full`,
   verify a plain sword now gets a long multi-part name instead of a short one.
6. Disable a selector (e.g. `helmet`), confirm a fresh helmet stays vanilla-named.
7. Quit and reopen — overrides persist; UI re-renders with edited values.
8. Delete `adventureitemnames.json` — defaults restore.
9. Cross-loader parity: same scenarios on NeoForge dev client.

## Files likely to touch

- `common/src/main/java/games/brennan/adventureitemnames/api/NameComposer.java` —
  read from `NamingConfig` instead of constants.
- `common/src/main/java/games/brennan/adventureitemnames/internal/NamingConfig.java` —
  new getters for chances + selector overrides.
- `common/src/main/java/games/brennan/adventureitemnames/internal/ConfigCodec.java` —
  new sections in schema.
- `common/src/main/java/games/brennan/adventureitemnames/client/screen/` (or
  wherever v1 put the screen) — new SpawnChancesScreen + SelectorsScreen.

## Wiki updates after Gate 3

- `Features.md` — document new config sections and the in-game editor.
- `Compatibility.md` — note that chance constants are no longer hardcoded.

## Open design questions for v2

1. Should chance sliders snap to 5% increments or allow free 0.01 precision?
2. Should the selector dropdown allow `(none)` to disable naming for one tier
   without disabling the whole selector?
3. Should we add a "reset all" button next to "Save to pack", or rely on per-row `↺`?
