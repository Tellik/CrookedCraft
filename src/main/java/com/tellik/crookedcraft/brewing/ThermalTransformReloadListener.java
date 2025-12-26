package com.tellik.crookedcraft.brewing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

public final class ThermalTransformReloadListener extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public ThermalTransformReloadListener() {
        super(GSON, "crookedcraft_brewing");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> elements, ResourceManager manager, ProfilerFiller profiler) {
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath("crookedcraft", "thermal_transforms");
        JsonElement rootEl = elements.get(key);

        if (rootEl == null || !rootEl.isJsonObject()) {
            LOGGER.warn("[crookedcraft] Missing or invalid thermal_transforms.json at data/crookedcraft/crookedcraft_brewing/thermal_transforms.json");
            ThermalTransformManager.applyCoolingTransforms(Map.of());
            return;
        }

        JsonObject root = rootEl.getAsJsonObject();

        Map<ResourceLocation, ThermalTransformManager.CoolingTransform> cooling = new HashMap<>();

        if (root.has("cooling") && root.get("cooling").isJsonObject()) {
            JsonObject coolingObj = root.getAsJsonObject("cooling");

            for (var e : coolingObj.entrySet()) {
                String fromStr = e.getKey();
                ResourceLocation fromId = ResourceLocation.tryParse(fromStr);
                if (fromId == null) {
                    LOGGER.warn("[crookedcraft] Invalid cooling key '{}' - skipping.", fromStr);
                    continue;
                }

                if (!e.getValue().isJsonObject()) {
                    LOGGER.warn("[crookedcraft] Cooling entry '{}' must be an object - skipping.", fromStr);
                    continue;
                }

                JsonObject o = e.getValue().getAsJsonObject();

                if (!o.has("at_or_below_temp_c") || !o.has("set_block")) {
                    LOGGER.warn("[crookedcraft] Cooling entry '{}' missing at_or_below_temp_c or set_block - skipping.", fromStr);
                    continue;
                }

                float thr = o.get("at_or_below_temp_c").getAsFloat();

                ResourceLocation setBlock = ResourceLocation.tryParse(o.get("set_block").getAsString());
                if (setBlock == null) {
                    LOGGER.warn("[crookedcraft] Cooling entry '{}' has invalid set_block - skipping.", fromStr);
                    continue;
                }

                boolean untrack = true;
                if (o.has("untrack")) untrack = o.get("untrack").getAsBoolean();

                ThermalTransformManager.DropDef drop = null;
                if (o.has("drop") && o.get("drop").isJsonObject()) {
                    JsonObject d = o.getAsJsonObject("drop");
                    if (d.has("item")) {
                        ResourceLocation itemId = ResourceLocation.tryParse(d.get("item").getAsString());
                        if (itemId != null) {
                            int count = d.has("count") ? d.get("count").getAsInt() : 1;
                            drop = new ThermalTransformManager.DropDef(itemId, count);
                        }
                    }
                }

                Integer requiredLevel = o.has("required_level") ? o.get("required_level").getAsInt() : null;
                Integer minLevel      = o.has("min_level")      ? o.get("min_level").getAsInt()      : null;
                Integer maxLevel      = o.has("max_level")      ? o.get("max_level").getAsInt()      : null;

                cooling.put(fromId, new ThermalTransformManager.CoolingTransform(
                        thr, setBlock, drop, untrack,
                        requiredLevel, minLevel, maxLevel
                ));

            }
        }

        ThermalTransformManager.applyCoolingTransforms(cooling);
    }
}
