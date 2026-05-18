package games.brennan.adventureitemnames.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameChainExtension;
import games.brennan.adventureitemnames.api.NameChainOp;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.api.NameSelector;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gson-backed deserializer for the three JSON schemas under
 * {@code data/&lt;ns&gt;/naming/} — pools, chains, and selectors.
 * Optional fields take sensible defaults so a minimal valid file still
 * parses; one bad file is logged by {@link NameRegistry} and skipped.
 */
public final class NameCodec {

    private NameCodec() {}

    public static final class NameParseException extends Exception {
        public NameParseException(String msg) { super(msg); }
        public NameParseException(String msg, Throwable cause) { super(msg, cause); }
    }

    public static NamePool parsePool(InputStream in, ResourceLocation fallbackId) throws NameParseException {
        JsonObject root = readRoot(in);
        ResourceLocation id = idOrFallback(root, fallbackId);
        List<NamePool.PoolEntry> entries = new ArrayList<>();
        JsonElement entriesEl = root.get("entries");
        if (entriesEl == null || !entriesEl.isJsonArray()) {
            throw new NameParseException("pool missing 'entries' array");
        }
        for (JsonElement el : entriesEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            JsonElement textEl = obj.get("text");
            if (textEl == null || !textEl.isJsonPrimitive()) continue;
            String text = textEl.getAsString();
            if (text.isEmpty()) continue;
            entries.add(new NamePool.PoolEntry(text, readResourceList(obj.get("item_types"))));
        }
        return new NamePool(id, List.copyOf(entries));
    }

    public static NamePool parsePool(JsonElement root, ResourceLocation fallbackId) throws NameParseException {
        if (!root.isJsonObject()) throw new NameParseException("pool root is not a JSON object");
        return parsePoolObj(root.getAsJsonObject(), fallbackId);
    }

    private static NamePool parsePoolObj(JsonObject root, ResourceLocation fallbackId) throws NameParseException {
        ResourceLocation id = idOrFallback(root, fallbackId);
        List<NamePool.PoolEntry> entries = new ArrayList<>();
        JsonElement entriesEl = root.get("entries");
        if (entriesEl == null || !entriesEl.isJsonArray()) {
            throw new NameParseException("pool missing 'entries' array");
        }
        for (JsonElement el : entriesEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            JsonElement textEl = obj.get("text");
            if (textEl == null || !textEl.isJsonPrimitive()) continue;
            String text = textEl.getAsString();
            if (text.isEmpty()) continue;
            entries.add(new NamePool.PoolEntry(text, readResourceList(obj.get("item_types"))));
        }
        return new NamePool(id, List.copyOf(entries));
    }

    public static NameChain parseChain(InputStream in, ResourceLocation fallbackId) throws NameParseException {
        return parseChainObj(readRoot(in), fallbackId);
    }

    public static NameChain parseChain(JsonElement root, ResourceLocation fallbackId) throws NameParseException {
        if (!root.isJsonObject()) throw new NameParseException("chain root is not a JSON object");
        return parseChainObj(root.getAsJsonObject(), fallbackId);
    }

    private static NameChain parseChainObj(JsonObject root, ResourceLocation fallbackId) throws NameParseException {
        ResourceLocation id = idOrFallback(root, fallbackId);
        List<NameSegment> segments = new ArrayList<>();
        JsonElement segEl = root.get("segments");
        if (segEl == null || !segEl.isJsonArray()) {
            throw new NameParseException("chain missing 'segments' array");
        }
        for (JsonElement el : segEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            segments.add(parseSegment(el.getAsJsonObject()));
        }
        return new NameChain(id, List.copyOf(segments));
    }

    private static NameSegment parseSegment(JsonObject obj) {
        String name = obj.has("name") && obj.get("name").isJsonPrimitive()
            ? obj.get("name").getAsString() : "";
        List<NameSegment.WeightedRef> refs = parseWeightedRefs(obj.get("refs"));
        float chance = obj.has("chance") ? obj.get("chance").getAsFloat() : 1f;
        String connection = obj.has("connection") ? obj.get("connection").getAsString() : "";
        boolean newline = obj.has("newline") && obj.get("newline").getAsBoolean();
        return new NameSegment(name, refs, chance, connection, newline);
    }

    private static List<NameSegment.WeightedRef> parseWeightedRefs(JsonElement refsEl) {
        List<NameSegment.WeightedRef> refs = new ArrayList<>();
        if (refsEl == null || !refsEl.isJsonArray()) return List.copyOf(refs);
        for (JsonElement r : refsEl.getAsJsonArray()) {
            if (!r.isJsonObject()) continue;
            JsonObject ro = r.getAsJsonObject();
            JsonElement refIdEl = ro.get("ref");
            if (refIdEl == null || !refIdEl.isJsonPrimitive()) continue;
            ResourceLocation refId = ResourceLocation.tryParse(refIdEl.getAsString());
            if (refId == null) continue;
            float w = ro.has("weight") ? ro.get("weight").getAsFloat() : 1f;
            if (w <= 0f) continue;
            refs.add(new NameSegment.WeightedRef(refId, w));
        }
        return List.copyOf(refs);
    }

    public static NameChainExtension parseChainExtension(JsonElement root, ResourceLocation fallbackId) throws NameParseException {
        if (!root.isJsonObject()) throw new NameParseException("chain extension root is not a JSON object");
        return parseChainExtensionObj(root.getAsJsonObject(), fallbackId);
    }

    public static NameChainExtension parseChainExtension(InputStream in, ResourceLocation fallbackId) throws NameParseException {
        return parseChainExtensionObj(readRoot(in), fallbackId);
    }

    private static NameChainExtension parseChainExtensionObj(JsonObject root, ResourceLocation fallbackId) throws NameParseException {
        ResourceLocation id = idOrFallback(root, fallbackId);
        JsonElement targetEl = root.get("target");
        if (targetEl == null || !targetEl.isJsonPrimitive()) {
            throw new NameParseException("chain extension missing 'target'");
        }
        ResourceLocation target = ResourceLocation.tryParse(targetEl.getAsString());
        if (target == null) {
            throw new NameParseException("chain extension 'target' is not a valid resource id");
        }
        JsonElement opsEl = root.get("operations");
        if (opsEl == null || !opsEl.isJsonArray()) {
            throw new NameParseException("chain extension missing 'operations' array");
        }
        JsonArray opsArr = opsEl.getAsJsonArray();
        if (opsArr.isEmpty()) {
            throw new NameParseException("chain extension 'operations' is empty");
        }
        List<NameChainOp> operations = new ArrayList<>();
        for (JsonElement el : opsArr) {
            if (!el.isJsonObject()) continue;
            NameChainOp op = parseChainOp(el.getAsJsonObject());
            if (op != null) operations.add(op);
        }
        return new NameChainExtension(id, target, List.copyOf(operations));
    }

    private static NameChainOp parseChainOp(JsonObject obj) {
        JsonElement typeEl = obj.get("type");
        if (typeEl == null || !typeEl.isJsonPrimitive()) return null;
        String type = typeEl.getAsString();
        return switch (type) {
            case "add_refs" -> parseAddRefs(obj);
            case "add_segment" -> parseAddSegment(obj);
            default -> null;
        };
    }

    private static NameChainOp.AddRefs parseAddRefs(JsonObject obj) {
        NameChainOp.SegmentRef target = parseSegmentRef(obj.get("segment"));
        if (target == null) return null;
        List<NameSegment.WeightedRef> refs = parseWeightedRefs(obj.get("refs"));
        if (refs.isEmpty()) return null;
        return new NameChainOp.AddRefs(target, refs);
    }

    private static NameChainOp.AddSegment parseAddSegment(JsonObject obj) {
        NameChainOp.InsertPos at = parseInsertPos(obj.get("at"));
        JsonElement segEl = obj.get("segment");
        if (segEl == null || !segEl.isJsonObject()) return null;
        NameSegment segment = parseSegment(segEl.getAsJsonObject());
        return new NameChainOp.AddSegment(at, segment);
    }

    private static NameChainOp.SegmentRef parseSegmentRef(JsonElement el) {
        if (el == null) return null;
        if (el.isJsonPrimitive()) {
            if (el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString();
                if (s.isEmpty()) return null;
                return new NameChainOp.SegmentRef.ByName(s);
            }
            if (el.getAsJsonPrimitive().isNumber()) {
                int idx = el.getAsInt();
                if (idx < 0) return null;
                return new NameChainOp.SegmentRef.ByIndex(idx);
            }
        }
        return null;
    }

    private static NameChainOp.InsertPos parseInsertPos(JsonElement el) {
        if (el == null) return new NameChainOp.InsertPos.End();
        if (el.isJsonPrimitive()) {
            if (el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString();
                return switch (s) {
                    case "start" -> new NameChainOp.InsertPos.Start();
                    case "end" -> new NameChainOp.InsertPos.End();
                    default -> new NameChainOp.InsertPos.End();
                };
            }
            if (el.getAsJsonPrimitive().isNumber()) {
                return new NameChainOp.InsertPos.At(el.getAsInt());
            }
        }
        return new NameChainOp.InsertPos.End();
    }

    public static NameSelector parseSelector(InputStream in, ResourceLocation fallbackId) throws NameParseException {
        return parseSelectorObj(readRoot(in), fallbackId);
    }

    public static NameSelector parseSelector(JsonElement root, ResourceLocation fallbackId) throws NameParseException {
        if (!root.isJsonObject()) throw new NameParseException("selector root is not a JSON object");
        return parseSelectorObj(root.getAsJsonObject(), fallbackId);
    }

    private static NameSelector parseSelectorObj(JsonObject root, ResourceLocation fallbackId) throws NameParseException {
        ResourceLocation id = idOrFallback(root, fallbackId);
        JsonElement appliesEl = root.get("applies_to");
        if (appliesEl == null || !appliesEl.isJsonPrimitive()) {
            throw new NameParseException("selector missing 'applies_to'");
        }
        ResourceLocation appliesTo = ResourceLocation.tryParse(appliesEl.getAsString());
        if (appliesTo == null) {
            throw new NameParseException("selector 'applies_to' is not a valid tag id");
        }
        Map<String, ResourceLocation> tiers = new LinkedHashMap<>();
        JsonElement tiersEl = root.get("tiers");
        if (tiersEl == null || !tiersEl.isJsonObject()) {
            throw new NameParseException("selector missing 'tiers' object");
        }
        for (Map.Entry<String, JsonElement> e : tiersEl.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonPrimitive()) continue;
            ResourceLocation chainId = ResourceLocation.tryParse(e.getValue().getAsString());
            if (chainId == null) continue;
            tiers.put(e.getKey(), chainId);
        }
        return new NameSelector(id, appliesTo, Map.copyOf(tiers));
    }

    private static JsonObject readRoot(InputStream in) throws NameParseException {
        try {
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) throw new NameParseException("root is not a JSON object");
            return root.getAsJsonObject();
        } catch (NameParseException e) {
            throw e;
        } catch (Exception e) {
            throw new NameParseException("invalid JSON: " + e.getMessage(), e);
        }
    }

    private static ResourceLocation idOrFallback(JsonObject root, ResourceLocation fallback) {
        JsonElement el = root.get("id");
        if (el != null && el.isJsonPrimitive()) {
            ResourceLocation parsed = ResourceLocation.tryParse(el.getAsString());
            if (parsed != null) return parsed;
        }
        return fallback;
    }

    private static List<ResourceLocation> readResourceList(JsonElement el) {
        if (el == null || !el.isJsonArray()) return List.of();
        List<ResourceLocation> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) continue;
            ResourceLocation rl = ResourceLocation.tryParse(item.getAsString());
            if (rl != null) out.add(rl);
        }
        return List.copyOf(out);
    }
}
