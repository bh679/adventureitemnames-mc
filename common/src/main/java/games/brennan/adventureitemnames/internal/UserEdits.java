package games.brennan.adventureitemnames.internal;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;

/**
 * Bundle of uncommitted edits flushed from the in-game UI through
 * {@link UserConfigWriter}. Symmetric to {@link LoadedConfig} — the
 * loader pulls fields out of the file, the writer pushes these fields
 * back in. Grouping into a record keeps the writer signature stable as
 * new edit kinds are added.
 */
public record UserEdits(
    Set<ResourceLocation> disabledPools,
    Set<ResourceLocation> enabledPools,
    Map<String, Float> weights,
    EntryOverrides entries
) {}
