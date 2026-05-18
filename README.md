# Adventure Item Names

A procedural item-naming system for Minecraft. Install it and your
naturally-spawned swords, tools, shields, and armor start coming out
with fun, evocative generated names like **"Whispering Diamond Blade
of Iron"** instead of plain *Diamond Sword*.

- **Loaders:** Fabric / Forge / NeoForge — Minecraft 1.21.1
- **License:** PolyForm Shield 1.0.0 (source-available)
- **Status:** v0.2.0 — multi-loader release
  - **NeoForge + Fabric:** fully tested in dev client (registries load, mod boots cleanly)
  - **Forge:** production jar builds with correct content (mixin config, data, manifest) but dev-launch verification is blocked by an upstream Architectury Loom 1.13 + Forge 1.21.1 JPMS conflict ([architectury/architectury-loom#284](https://github.com/architectury/architectury-loom/issues/284) confirms the pattern). If you're running the Forge jar in a real install and see issues, please open an issue.

## What it does

Every time a vanilla loot table rolls a sword, axe, pickaxe, shovel,
hoe, helmet, chestplate, leggings, boots, or shield, this mod gives
the item a procedurally-composed name with a configurable probability
(30% for plain items, 50% for enchanted). Names are built from a
weighted graph of ~1,400 themed entries spread across 33 pools:

- **Generic flavour:** title prefixes, places, people, feelings,
  animals, colours, math, music, technology, food, …
- **Minecraft-themed:** biomes, structures, hostile mobs, dimensions,
  lore, enchantments, music discs, villager professions, …
- **Material-aware:** items name themselves with their actual material
  (Iron, Diamond, Netherite, …).
- **Type-aware:** swords roll "Blade"/"Edge"/"Cleaver", boots roll
  "Hoofs"/"Steppers", etc.

Items rolled outside loot tables — items you craft, items from `/give`
— stay vanilla-named.

## Install

Pick the jar that matches your loader:

| Loader | Required | Download |
|--------|----------|----------|
| Fabric | [Fabric Loader](https://fabricmc.net/) 0.16+ and [Fabric API](https://modrinth.com/mod/fabric-api) | `adventureitemnames-fabric-0.2.0.jar` |
| Forge | [Forge](https://files.minecraftforge.net/) 1.21.1-52.1.x | `adventureitemnames-forge-0.2.0.jar` |
| NeoForge | [NeoForge](https://neoforged.net/) 21.1.228+ | `adventureitemnames-neoforge-0.2.0.jar` |

Drop the jar into your `mods/` folder and launch the game. No config required.

## Extending with a datapack

The naming corpus is fully data-driven. Drop any of these files into a
datapack and the registry picks them up on `/reload`:

```
data/<your_namespace>/naming/pools/<your_pool>.json
data/<your_namespace>/naming/chains/<your_chain>.json
data/<your_namespace>/naming/selectors/<your_selector>.json
```

The three schemas:

### Pool — a flat list of candidate words

```json
{
  "id": "yourpack:my_pool",
  "entries": [
    { "text": "Wibble" },
    { "text": "Frobnicator" },
    { "text": "Slasher", "item_types": ["minecraft:swords"] }
  ]
}
```

`item_types` is optional — if present, the entry only rolls for items
matching that vanilla tag.

### Chain — an ordered recipe that picks from pools

```json
{
  "id": "yourpack:my_chain",
  "segments": [
    {
      "refs": [
        { "ref": "adventureitemnames:title_prefix", "weight": 1.0 }
      ],
      "chance": 1.0,
      "connection": ""
    },
    {
      "refs": [
        { "ref": "yourpack:my_pool", "weight": 0.7 },
        { "ref": "adventureitemnames:colors", "weight": 0.3 }
      ],
      "chance": 0.5,
      "connection": " of "
    }
  ]
}
```

Each segment fires with probability `chance`, picks one weighted ref,
and prepends `connection` to the accumulated output.

### Selector — bind a chain to an item tag

```json
{
  "id": "yourpack:trident",
  "applies_to": "minecraft:tridents",
  "tiers": {
    "plain": "yourpack:my_chain",
    "enchanted": "yourpack:my_chain"
  }
}
```

Selectors are matched by `applies_to` against the item's vanilla item
tags. The first registered selector that matches an item wins.

### Context refs

The composer recognises one virtual ref that reads from the item stack
instead of a pool:

| Ref id | Resolves to |
|---|---|
| `adventureitemnames:context/item_material` | The item's material prefix: `Iron`, `Diamond`, `Netherite`, `Gold`, `Stone`, `Wood`, `Leather`, `Chainmail`, `Turtle`. Empty string for items without a recognisable prefix. |

## Java API for other mods

If you have your own custom loot system and want named items there
too, depend on this mod and call:

```java
import games.brennan.adventureitemnames.api.NameComposer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.RandomSource;

void onMyCustomLootRoll(ItemStack stack, RandomSource rng) {
    NameComposer.applyName(stack, rng);
    // stack now has CUSTOM_NAME set if a selector matched
}
```

`applyName` mutates the stack — it sets `DataComponents.CUSTOM_NAME`
when a selector matches and the probability roll passes. No-op
otherwise.

Pass a *seeded* `RandomSource` if you need deterministic names per
position/seed (e.g. structures or procedural dungeons): the composer
makes no internal random calls beyond what you provide.

## Roadmap

- **v0.1:** NeoForge 1.21.1, datapack-extensible.
- **v0.2:** Fabric + Forge + NeoForge via Architectury Loom — shared Mixin replaces the v0.1 NeoForge GLM.
- **v0.x:** In-game config screen for naming probabilities.

## Credits

Original Unity word lists from
[Dungeon Train](https://brennanhatton.itch.io/dungeontrain) by
Brennan Hatton (2018). Java port and Minecraft data-driven rewrite
from the Dungeon Train mod ([github.com/bh679/dungeon-train-mc](https://github.com/bh679/dungeon-train-mc)).
