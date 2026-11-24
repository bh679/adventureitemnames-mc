package games.brennan.adventureitemnames.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import games.brennan.adventureitemnames.api.NameSegment;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON for a segment's {@code chance_if} list (see {@link NameSegment#chanceIf()}):
 * <pre>{"when": [{"segment": 1, "refs": ["ns:pool"]}, {"segment": 3}], "chance": 0.5}</pre>
 * A rule with no usable condition or no numeric chance is dropped; a bad condition drops its
 * whole rule (a rule that silently lost a condition would fire too often). Chance is clamped
 * to 0..1.
 */
public final class ChanceRuleCodec {

    private ChanceRuleCodec() {}

    public static List<NameSegment.ChanceRule> parse(JsonElement el) {
        if (el == null || !el.isJsonArray()) return List.of();
        List<NameSegment.ChanceRule> out = new ArrayList<>();
        for (JsonElement r : el.getAsJsonArray()) {
            NameSegment.ChanceRule rule = parseRule(r);
            if (rule != null) out.add(rule);
        }
        return List.copyOf(out);
    }

    private static NameSegment.ChanceRule parseRule(JsonElement r) {
        if (!r.isJsonObject()) return null;
        JsonObject obj = r.getAsJsonObject();
        JsonElement chanceEl = obj.get("chance");
        JsonElement whenEl = obj.get("when");
        if (chanceEl == null || !chanceEl.isJsonPrimitive() || !chanceEl.getAsJsonPrimitive().isNumber()) return null;
        if (whenEl == null || !whenEl.isJsonArray() || whenEl.getAsJsonArray().isEmpty()) return null;
        List<NameSegment.Condition> when = new ArrayList<>();
        for (JsonElement c : whenEl.getAsJsonArray()) {
            NameSegment.Condition cond = parseCondition(c);
            if (cond == null) return null;
            when.add(cond);
        }
        return new NameSegment.ChanceRule(when, chanceEl.getAsFloat());
    }

    private static NameSegment.Condition parseCondition(JsonElement c) {
        if (!c.isJsonObject()) return null;
        JsonObject obj = c.getAsJsonObject();
        JsonElement segEl = obj.get("segment");
        if (segEl == null || !segEl.isJsonPrimitive() || !segEl.getAsJsonPrimitive().isNumber()) return null;
        double d = segEl.getAsDouble();
        if (d < 0 || d != Math.floor(d) || d > Integer.MAX_VALUE) return null;
        List<ResourceLocation> refs = new ArrayList<>();
        JsonElement refsEl = obj.get("refs");
        if (refsEl != null) {
            if (!refsEl.isJsonArray()) return null;
            for (JsonElement idEl : refsEl.getAsJsonArray()) {
                if (!idEl.isJsonPrimitive()) return null;
                ResourceLocation id = ResourceLocation.tryParse(idEl.getAsString());
                if (id == null) return null;
                refs.add(id);
            }
        }
        return new NameSegment.Condition((int) d, refs);
    }

    public static JsonArray write(List<NameSegment.ChanceRule> rules) {
        JsonArray arr = new JsonArray();
        for (NameSegment.ChanceRule rule : rules) {
            JsonObject rObj = new JsonObject();
            JsonArray when = new JsonArray();
            for (NameSegment.Condition c : rule.when()) {
                JsonObject cObj = new JsonObject();
                cObj.add("segment", new JsonPrimitive(c.segment()));
                if (!c.refs().isEmpty()) {
                    JsonArray refs = new JsonArray();
                    for (ResourceLocation id : c.refs()) refs.add(new JsonPrimitive(id.toString()));
                    cObj.add("refs", refs);
                }
                when.add(cObj);
            }
            rObj.add("when", when);
            rObj.add("chance", new JsonPrimitive(rule.chance()));
            arr.add(rObj);
        }
        return arr;
    }

    /**
     * Rewrite the segment indices in {@code rules} through {@code newPos} (old → new index),
     * dropping any rule that mentions a segment no longer present. Used when a dev Save writes a
     * reordered chain.
     */
    public static List<NameSegment.ChanceRule> remap(List<NameSegment.ChanceRule> rules, Map<Integer, Integer> newPos) {
        List<NameSegment.ChanceRule> out = new ArrayList<>();
        outer:
        for (NameSegment.ChanceRule rule : rules) {
            List<NameSegment.Condition> when = new ArrayList<>();
            for (NameSegment.Condition c : rule.when()) {
                Integer mapped = newPos.get(c.segment());
                if (mapped == null) continue outer;
                when.add(new NameSegment.Condition(mapped, c.refs()));
            }
            out.add(new NameSegment.ChanceRule(when, rule.chance()));
        }
        return List.copyOf(out);
    }
}
