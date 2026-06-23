package games.brennan.adventureitemnames.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the dependency-free building blocks of villager-trade
 * naming (the {@code VillagerTradeMixin} → {@link NameComposer#applyVillagerTradeNaming}
 * pipeline): the {@link NamingContext} villager carrier, the three new
 * {@link ChanceKind} gates, and their {@link NamingConfig} accessors + override
 * precedence.
 *
 * <p>Pure record / enum / static-config logic, so it runs without a bootstrapped
 * Minecraft registry (mirrors {@code NameComposerPlayerMobTest} /
 * {@code NamingConfigMobGateTest}). The {@code ItemStack}- and tag-gated parts of
 * {@code applyVillagerTradeNaming} need a live game and are covered by the in-game
 * Gate 2 checks instead.
 */
class VillagerTradeNamingTest {

    private static final float EPS = 1e-6f;

    @BeforeEach
    @AfterEach
    void resetChanceOverrides() {
        // API_CHANCES is process-global static state — isolate each test and never
        // leak an override into other test classes.
        NamingConfig.clearChanceOverride(ChanceKind.TRADE_ITEM);
        NamingConfig.clearChanceOverride(ChanceKind.PURCHASED_DESCRIPTION);
        NamingConfig.clearChanceOverride(ChanceKind.TRADE_STOCK_LIMIT);
    }

    // ── NamingContext ────────────────────────────────────────────────

    @Test
    void ofVillager_carriesVillagerNameOnly() {
        NamingContext ctx = NamingContext.ofVillager("Grumblefoot");
        assertEquals("Grumblefoot", ctx.villagerName().orElse(null));
        assertTrue(ctx.playerName().isEmpty(), "villager context must not set a player name");
    }

    @Test
    void ofVillager_blankOrNull_isEmpty() {
        assertSame(NamingContext.EMPTY, NamingContext.ofVillager(null));
        assertSame(NamingContext.EMPTY, NamingContext.ofVillager("   "));
    }

    @Test
    void ofPlayer_unaffectedByVillagerField() {
        // Regression: widening the record with villagerName must not disturb the player path.
        NamingContext ctx = NamingContext.ofPlayer("Steve");
        assertEquals("Steve", ctx.playerName().orElse(null));
        assertTrue(ctx.villagerName().isEmpty(), "player context must not set a villager name");
    }

    @Test
    void empty_hasNeitherName() {
        assertTrue(NamingContext.EMPTY.playerName().isEmpty());
        assertTrue(NamingContext.EMPTY.villagerName().isEmpty());
    }

    // ── ChanceKind ───────────────────────────────────────────────────

    @Test
    void newChanceKinds_haveExpectedKeysAndDefaults() {
        assertEquals(0.75f, ChanceKind.TRADE_ITEM.defaultValue(), EPS);
        assertEquals(1.00f, ChanceKind.PURCHASED_DESCRIPTION.defaultValue(), EPS);
        assertEquals(0.00f, ChanceKind.TRADE_STOCK_LIMIT.defaultValue(), EPS);

        assertEquals("trade_item", ChanceKind.TRADE_ITEM.key());
        assertEquals("purchased_description", ChanceKind.PURCHASED_DESCRIPTION.key());
        assertEquals("trade_stock_limit", ChanceKind.TRADE_STOCK_LIMIT.key());
    }

    @Test
    void fromKey_resolvesNewKinds() {
        assertEquals(ChanceKind.TRADE_ITEM, ChanceKind.fromKey("trade_item"));
        assertEquals(ChanceKind.PURCHASED_DESCRIPTION, ChanceKind.fromKey("purchased_description"));
        assertEquals(ChanceKind.TRADE_STOCK_LIMIT, ChanceKind.fromKey("trade_stock_limit"));
        assertNull(ChanceKind.fromKey("not_a_kind"));
    }

    // ── NamingConfig accessors + precedence ──────────────────────────

    @Test
    void tradeChanceAccessors_returnDefaults() {
        assertEquals(0.75f, NamingConfig.chanceTradeItem(), EPS);
        assertEquals(1.00f, NamingConfig.chancePurchasedDescription(), EPS);
        assertEquals(0.00f, NamingConfig.chanceTradeStockLimit(), EPS);
    }

    @Test
    void apiOverride_takesPrecedence_thenClears() {
        NamingConfig.overrideChance(ChanceKind.TRADE_ITEM, 0.5f);
        assertEquals(0.5f, NamingConfig.chanceTradeItem(), EPS);

        NamingConfig.clearChanceOverride(ChanceKind.TRADE_ITEM);
        assertEquals(0.75f, NamingConfig.chanceTradeItem(), EPS);
    }

    @Test
    void stockLimitOverride_modelsDungeonTrainPack() {
        // The Dungeon Train pack ships trade_stock_limit=1.0 via the datapack layer;
        // exercise the same effect through the API layer (highest precedence).
        assertEquals(0.00f, NamingConfig.chanceTradeStockLimit(), EPS);
        NamingConfig.overrideChance(ChanceKind.TRADE_STOCK_LIMIT, 1.0f);
        assertEquals(1.00f, NamingConfig.chanceTradeStockLimit(), EPS);
    }
}
