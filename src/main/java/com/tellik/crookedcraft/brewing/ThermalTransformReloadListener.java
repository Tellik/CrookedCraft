package com.tellik.crookedcraft.brewing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads datapack-driven thermal transforms from your CURRENT, EXISTING location:
 *
 *   data/crookedcraft/crookedcraft_brewing/*.json
 *
 * I.e. FOLDER must be "crookedcraft_brewing/".
 *
 * JSON format:
 * {
 *   "cooling": { "<from_block>": { ...single object... }, ... },
 *   "transforms": { "<from_block>": [ { ... }, ... ], ... }
 * }
 */
public final class ThermalTransformReloadListener extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * IMPORTANT:
     * SimpleJsonResourceReloadListener scans:
     *   data/<namespace>/<FOLDER>/*.json
     *
     * Your existing location is:
     *   data/crookedcraft/crookedcraft_brewing/*.json
     */
    private static final String FOLDER = "crookedcraft_brewing";

    public ThermalTransformReloadListener() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsonMap,
                         ResourceManager resourceManager,
                         ProfilerFiller profiler) {

        // Guard rail: if this ever becomes 0 again, it’s a regression and should scream.
        if (jsonMap.isEmpty()) {
            LOGGER.error("[crookedcraft] Thermal transforms reloaded from 0 file(s).");
            LOGGER.error("[crookedcraft] Expected files under: data/<namespace>/{}/<name>.json", FOLDER);
            LOGGER.error("[crookedcraft] For your mod this should be: data/crookedcraft/{}/<name>.json", FOLDER);
            ThermalTransformManager.applyTransforms(new HashMap<>()); // clears safely
            return;
        }

        LOGGER.info("[crookedcraft] Reloading thermal transforms from {} file(s) in folder '{}':", jsonMap.size(), FOLDER);
        for (ResourceLocation rid : jsonMap.keySet()) {
            LOGGER.info("[crookedcraft]  - {}", rid);
        }

        Map<ResourceLocation, List<ThermalTransformManager.ThermalTransform>> out = new HashMap<>();

        for (Map.Entry<ResourceLocation, JsonElement> e : jsonMap.entrySet()) {
            ResourceLocation fileId = e.getKey();
            JsonElement rootEl = e.getValue();

            if (!rootEl.isJsonObject()) {
                LOGGER.warn("[crookedcraft] {} is not a JSON object; skipping.", fileId);
                continue;
            }

            JsonObject root = rootEl.getAsJsonObject();

            // ------------------------------------------------------------
            // "cooling": { "<from>": { ... } }
            // ------------------------------------------------------------
            if (root.has("cooling") && root.get("cooling").isJsonObject()) {
                JsonObject cooling = root.getAsJsonObject("cooling");
                for (Map.Entry<String, JsonElement> ce : cooling.entrySet()) {
                    ResourceLocation fromId = ResourceLocation.tryParse(ce.getKey());
                    if (fromId == null) {
                        LOGGER.warn("[crookedcraft] Invalid from block id '{}' in {} cooling; skipping.", ce.getKey(), fileId);
                        continue;
                    }
                    if (!ce.getValue().isJsonObject()) {
                        LOGGER.warn("[crookedcraft] cooling['{}'] in {} is not an object; skipping.", fromId, fileId);
                        continue;
                    }

                    JsonObject obj = ce.getValue().getAsJsonObject();

                    if (!obj.has("at_or_below_temp_c")) {
                        LOGGER.warn("[crookedcraft] cooling['{}'] in {} missing at_or_below_temp_c; skipping.", fromId, fileId);
                        continue;
                    }

                    float threshold = getFloat(obj, "at_or_below_temp_c", 0f);
                    ResourceLocation setBlock = getRL(obj, "set_block");
                    if (setBlock == null) {
                        LOGGER.warn("[crookedcraft] cooling['{}'] in {} missing/invalid set_block; skipping.", fromId, fileId);
                        continue;
                    }

                    ThermalTransformManager.DropDef drop = parseDrop(obj);
                    boolean untrack = getBool(obj, "untrack", false);

                    Integer requiredLevel = obj.has("required_level") ? getIntObj(obj, "required_level") : null;
                    Integer minLevel = obj.has("min_level") ? getIntObj(obj, "min_level") : null;
                    Integer maxLevel = obj.has("max_level") ? getIntObj(obj, "max_level") : null;

                    ResourceLocation requiresSolid = obj.has("required_solid") ? getRL(obj, "required_solid") : null;

                    // Your schema primarily uses "clear_solid", but allow both
                    boolean consumeSolid = getBool(obj, "clear_solid", false) || getBool(obj, "consume_solid", false);

                    Integer setLevel = obj.has("set_level") ? getIntObj(obj, "set_level") : null;

                    boolean preserveLevel = obj.has("preserve_level")
                            ? getBool(obj, "preserve_level", true)
                            : true;

                    boolean resetBrew = getBool(obj, "reset_brew", false);

                    ThermalTransformManager.ThermalTransform t = new ThermalTransformManager.ThermalTransform(
                            ThermalTransformManager.Mode.COOLING,
                            threshold,
                            setBlock,
                            drop,
                            untrack,
                            requiredLevel,
                            minLevel,
                            maxLevel,
                            requiresSolid,
                            consumeSolid,
                            setLevel,
                            preserveLevel,
                            resetBrew
                    );

                    out.computeIfAbsent(fromId, k -> new ArrayList<>()).add(t);
                }
            }

            // ------------------------------------------------------------
            // "transforms": { "<from>": [ { ... }, ... ] }
            // ------------------------------------------------------------
            if (root.has("transforms") && root.get("transforms").isJsonObject()) {
                JsonObject transforms = root.getAsJsonObject("transforms");
                for (Map.Entry<String, JsonElement> te : transforms.entrySet()) {
                    ResourceLocation fromId = ResourceLocation.tryParse(te.getKey());
                    if (fromId == null) {
                        LOGGER.warn("[crookedcraft] Invalid from block id '{}' in {} transforms; skipping.", te.getKey(), fileId);
                        continue;
                    }
                    if (!te.getValue().isJsonArray()) {
                        LOGGER.warn("[crookedcraft] transforms['{}'] in {} is not an array; skipping.", fromId, fileId);
                        continue;
                    }

                    JsonArray arr = te.getValue().getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonElement el = arr.get(i);
                        if (!el.isJsonObject()) {
                            LOGGER.warn("[crookedcraft] transforms['{}'][{}] in {} is not an object; skipping element.", fromId, i, fileId);
                            continue;
                        }

                        JsonObject obj = el.getAsJsonObject();

                        boolean hasBelow = obj.has("at_or_below_temp_c");
                        boolean hasAbove = obj.has("at_or_above_temp_c");

                        if (hasBelow == hasAbove) {
                            LOGGER.warn("[crookedcraft] transforms['{}'][{}] in {} must have exactly one of at_or_below_temp_c / at_or_above_temp_c; skipping.",
                                    fromId, i, fileId);
                            continue;
                        }

                        ThermalTransformManager.Mode mode;
                        float threshold;

                        if (hasBelow) {
                            mode = ThermalTransformManager.Mode.COOLING;
                            threshold = getFloat(obj, "at_or_below_temp_c", 0f);
                        } else {
                            mode = ThermalTransformManager.Mode.HEATING;
                            threshold = getFloat(obj, "at_or_above_temp_c", 0f);
                        }

                        ResourceLocation setBlock = getRL(obj, "set_block");
                        if (setBlock == null) {
                            LOGGER.warn("[crookedcraft] transforms['{}'][{}] in {} missing/invalid set_block; skipping.", fromId, i, fileId);
                            continue;
                        }

                        ThermalTransformManager.DropDef drop = parseDrop(obj);
                        boolean untrack = getBool(obj, "untrack", false);

                        Integer requiredLevel = obj.has("required_level") ? getIntObj(obj, "required_level") : null;
                        Integer minLevel = obj.has("min_level") ? getIntObj(obj, "min_level") : null;
                        Integer maxLevel = obj.has("max_level") ? getIntObj(obj, "max_level") : null;

                        ResourceLocation requiresSolid = obj.has("required_solid") ? getRL(obj, "required_solid") : null;

                        boolean consumeSolid = getBool(obj, "clear_solid", false) || getBool(obj, "consume_solid", false);

                        Integer setLevel = obj.has("set_level") ? getIntObj(obj, "set_level") : null;

                        boolean preserveLevel = obj.has("preserve_level")
                                ? getBool(obj, "preserve_level", true)
                                : true;

                        boolean resetBrew = getBool(obj, "reset_brew", false);

                        ThermalTransformManager.ThermalTransform t = new ThermalTransformManager.ThermalTransform(
                                mode,
                                threshold,
                                setBlock,
                                drop,
                                untrack,
                                requiredLevel,
                                minLevel,
                                maxLevel,
                                requiresSolid,
                                consumeSolid,
                                setLevel,
                                preserveLevel,
                                resetBrew
                        );

                        out.computeIfAbsent(fromId, k -> new ArrayList<>()).add(t);
                    }
                }
            }
        }

        ThermalTransformManager.applyTransforms(out);
        LOGGER.info("[crookedcraft] Thermal transforms reloaded from {} file(s).", jsonMap.size());
    }

    // -------------------------------------------------------------------------
    // Parsing helpers
    // -------------------------------------------------------------------------

    private static ResourceLocation getRL(JsonObject obj, String key) {
        if (!obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (!el.isJsonPrimitive()) return null;
        return ResourceLocation.tryParse(el.getAsString());
    }

    private static float getFloat(JsonObject obj, String key, float def) {
        if (!obj.has(key)) return def;
        try {
            return obj.get(key).getAsFloat();
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        if (!obj.has(key)) return def;
        try {
            return obj.get(key).getAsBoolean();
        } catch (Throwable t) {
            return def;
        }
    }

    private static Integer getIntObj(JsonObject obj, String key) {
        try {
            return obj.get(key).getAsInt();
        } catch (Throwable t) {
            return null;
        }
    }

    private static ThermalTransformManager.DropDef parseDrop(JsonObject obj) {
        if (!obj.has("drop") || !obj.get("drop").isJsonObject()) return null;

        JsonObject dropObj = obj.getAsJsonObject("drop");
        ResourceLocation itemId = null;
        int count = 1;

        if (dropObj.has("item")) {
            try {
                itemId = ResourceLocation.tryParse(dropObj.get("item").getAsString());
            } catch (Throwable ignored) {}
        }

        if (dropObj.has("count")) {
            try {
                count = Math.max(1, dropObj.get("count").getAsInt());
            } catch (Throwable ignored) {
                count = 1;
            }
        }

        if (itemId == null) return null;
        return new ThermalTransformManager.DropDef(itemId, count);
    }
}
