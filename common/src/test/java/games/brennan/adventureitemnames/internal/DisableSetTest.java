package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.MobCategory;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for {@link DisableSet}. These don't bootstrap
 * Minecraft — only {@code ResourceLocation.tryParse} and
 * {@code fromNamespaceAndPath} are exercised, which are stateless static
 * helpers.
 */
class DisableSetTest {

    private static ResourceLocation rl(String s) {
        return ResourceLocation.tryParse(s);
    }

    @Test
    void idSet_addsExactIds() {
        DisableSet.IdSet set = new DisableSet.IdSet();
        assertTrue(set.addRaw("adventureitemnames:discord_people"));
        assertTrue(set.contains(rl("adventureitemnames:discord_people")));
        assertFalse(set.contains(rl("adventureitemnames:colors")));
        assertFalse(set.contains(rl("minecraft:bedrock")));
    }

    @Test
    void idSet_wildcardMatchesNamespace() {
        DisableSet.IdSet set = new DisableSet.IdSet();
        assertTrue(set.addRaw("adventureitemnames:*"));
        assertTrue(set.contains(rl("adventureitemnames:colors")));
        assertTrue(set.contains(rl("adventureitemnames:discord_people")));
        assertTrue(set.contains(rl("adventureitemnames:anything_else")));
        assertFalse(set.contains(rl("minecraft:diamond_sword")));
    }

    @Test
    void idSet_wildcardCoexistsWithExactIds() {
        DisableSet.IdSet set = new DisableSet.IdSet();
        set.addRaw("adventureitemnames:*");
        set.addRaw("minecraft:bedrock");
        assertTrue(set.contains(rl("adventureitemnames:colors")));
        assertTrue(set.contains(rl("minecraft:bedrock")));
        assertFalse(set.contains(rl("minecraft:diamond_sword")));
    }

    @Test
    void idSet_rejectsInvalidIds() {
        DisableSet.IdSet set = new DisableSet.IdSet();
        assertFalse(set.addRaw(""));
        assertFalse(set.addRaw(null));
        // Path with illegal characters (uppercase, space)
        assertFalse(set.addRaw("Invalid:ID With Space"));
        assertTrue(set.isEmpty());
    }

    @Test
    void idSet_unqualifiedDefaultsToMinecraft() {
        DisableSet.IdSet set = new DisableSet.IdSet();
        // ResourceLocation.tryParse("bedrock") yields minecraft:bedrock
        assertTrue(set.addRaw("bedrock"));
        assertTrue(set.contains(rl("minecraft:bedrock")));
    }

    @Test
    void idSet_emptyContainsNothing() {
        DisableSet.IdSet set = new DisableSet.IdSet();
        assertTrue(set.isEmpty());
        assertFalse(set.contains(rl("minecraft:diamond_sword")));
        assertFalse(set.contains(null));
    }

    @Test
    void idSet_removeOnlyRemovesExact() {
        DisableSet.IdSet set = new DisableSet.IdSet();
        set.addRaw("adventureitemnames:colors");
        set.addRaw("adventureitemnames:*");
        // Remove only takes ResourceLocation, not the wildcard
        set.remove(rl("adventureitemnames:colors"));
        // Wildcard still matches
        assertTrue(set.contains(rl("adventureitemnames:colors")));
    }

    @Test
    void idSet_addAllUnionsBothCollections() {
        DisableSet.IdSet a = new DisableSet.IdSet();
        a.addRaw("adventureitemnames:colors");
        a.addRaw("adventureitemnames:*");

        DisableSet.IdSet b = new DisableSet.IdSet();
        b.addRaw("minecraft:bedrock");
        b.addRaw("minecraft:*");

        a.addAll(b);
        assertTrue(a.contains(rl("adventureitemnames:colors")));
        assertTrue(a.contains(rl("adventureitemnames:anything")));
        assertTrue(a.contains(rl("minecraft:bedrock")));
        assertTrue(a.contains(rl("minecraft:diamond_sword")));
    }

    @Test
    void disableSet_mergeUnionsAllFields() {
        DisableSet a = new DisableSet();
        a.pools.addRaw("adventureitemnames:colors");
        a.mobCategories.add(MobCategory.VILLAGER);
        a.itemIds.addRaw("minecraft:bedrock");

        DisableSet b = new DisableSet();
        b.pools.addRaw("adventureitemnames:discord_people");
        b.mobCategories.add(MobCategory.PASSIVE);
        b.entityTags.addRaw("minecraft:raiders");

        a.mergeFrom(b);

        assertTrue(a.pools.contains(rl("adventureitemnames:colors")));
        assertTrue(a.pools.contains(rl("adventureitemnames:discord_people")));
        assertTrue(a.mobCategories.contains(MobCategory.VILLAGER));
        assertTrue(a.mobCategories.contains(MobCategory.PASSIVE));
        assertTrue(a.itemIds.contains(rl("minecraft:bedrock")));
        assertTrue(a.entityTags.contains(rl("minecraft:raiders")));
    }

    @Test
    void disableSet_clearWipesEverything() {
        DisableSet ds = new DisableSet();
        ds.pools.addRaw("adventureitemnames:colors");
        ds.chains.addRaw("adventureitemnames:*");
        ds.selectors.addRaw("adventureitemnames:sword");
        ds.itemTags.addRaw("minecraft:swords");
        ds.itemIds.addRaw("minecraft:bedrock");
        ds.mobCategories.add(MobCategory.VILLAGER);
        ds.entityTags.addRaw("minecraft:raiders");
        ds.entityIds.addRaw("minecraft:cow");

        ds.clear();

        assertTrue(ds.pools.isEmpty());
        assertTrue(ds.chains.isEmpty());
        assertTrue(ds.selectors.isEmpty());
        assertTrue(ds.itemTags.isEmpty());
        assertTrue(ds.itemIds.isEmpty());
        assertTrue(ds.mobCategories.isEmpty());
        assertTrue(ds.entityTags.isEmpty());
        assertTrue(ds.entityIds.isEmpty());
    }
}
