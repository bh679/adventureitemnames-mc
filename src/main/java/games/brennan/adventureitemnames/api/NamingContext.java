package games.brennan.adventureitemnames.api;

import java.util.Optional;

/**
 * Optional, immutable bag of dynamic data threaded through the
 * {@link NameComposer} chain walker so context refs (those under
 * {@code adventureitemnames:context/...}) can resolve values that
 * depend on who or what triggered the name roll.
 *
 * <p>Pass {@link #EMPTY} from naming paths that have no extra context
 * (loot, mob spawn, preview UI) — existing behavior is preserved.
 * Use {@link #ofPlayer(String)} from the crafting hook so the
 * {@code context/player_name} ref resolves, and {@link #ofVillager(String)}
 * from the villager-trade hook so {@code context/villager_name} resolves.
 *
 * <p>Never null — the composer treats {@link #EMPTY} as the absent
 * sentinel. A context ref whose value is missing here returns the empty
 * string, which the walker treats as a skipped segment.
 */
public record NamingContext(Optional<String> playerName, Optional<String> villagerName) {

    /** Sentinel for naming paths with no extra context. */
    public static final NamingContext EMPTY = new NamingContext(Optional.empty(), Optional.empty());

    /** Build a context carrying only a player display-name. */
    public static NamingContext ofPlayer(String name) {
        if (name == null || name.isBlank()) return EMPTY;
        return new NamingContext(Optional.of(name), Optional.empty());
    }

    /** Build a context carrying only a villager display-name. */
    public static NamingContext ofVillager(String name) {
        if (name == null || name.isBlank()) return EMPTY;
        return new NamingContext(Optional.empty(), Optional.of(name));
    }
}
