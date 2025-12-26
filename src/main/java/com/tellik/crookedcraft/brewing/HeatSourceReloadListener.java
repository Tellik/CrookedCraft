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

public final class HeatSourceReloadListener extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public HeatSourceReloadListener() {
        super(GSON, "crookedcraft_brewing");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> elements, ResourceManager manager, ProfilerFiller profiler) {
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath("crookedcraft", "heat_sources");
        JsonElement rootEl = elements.get(key);

        if (rootEl == null || !rootEl.isJsonObject()) {
            LOGGER.warn("[crookedcraft] Missing or invalid heat_sources.json at data/crookedcraft/crookedcraft_brewing/heat_sources.json");
            HeatSourceManager.applyFromDatapackThermal(
                    HeatSourceManager.ScanMode.BELOW_ONLY,
                    Map.of(),
                    Map.of(),
                    1.0f
            );
            return;
        }

        JsonObject root = rootEl.getAsJsonObject();

        HeatSourceManager.ScanMode mode = HeatSourceManager.ScanMode.BELOW_ONLY;
        if (root.has("scan_mode")) {
            String s = root.get("scan_mode").getAsString();
            if ("below_only".equalsIgnoreCase(s)) mode = HeatSourceManager.ScanMode.BELOW_ONLY;
        }

        float heatPerTickScale = 1.0f;
        if (root.has("heat_per_tick_scale")) {
            heatPerTickScale = root.get("heat_per_tick_scale").getAsFloat();
            if (!(heatPerTickScale > 0.0f) || !Float.isFinite(heatPerTickScale)) heatPerTickScale = 1.0f;
        }

        Map<ResourceLocation, HeatSourceManager.HeatEntry> blockEntries = new HashMap<>();
        Map<ResourceLocation, HeatSourceManager.HeatEntry> fluidEntries = new HashMap<>();

        if (root.has("blocks") && root.get("blocks").isJsonObject()) {
            parseEntriesObject(root.getAsJsonObject("blocks"), blockEntries);
        }
        if (root.has("fluids") && root.get("fluids").isJsonObject()) {
            parseEntriesObject(root.getAsJsonObject("fluids"), fluidEntries);
        }

        HeatSourceManager.applyFromDatapackThermal(mode, blockEntries, fluidEntries, heatPerTickScale);
    }

    private static void parseEntriesObject(JsonObject obj, Map<ResourceLocation, HeatSourceManager.HeatEntry> out) {
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            String idStr = e.getKey();
            JsonElement val = e.getValue();

            ResourceLocation id = ResourceLocation.tryParse(idStr);
            if (id == null) {
                LOGGER.warn("[crookedcraft] Invalid registry id '{}' in heat_sources.json - skipping.", idStr);
                continue;
            }

            if (!val.isJsonObject()) {
                LOGGER.warn("[crookedcraft] Heat entry for '{}' must be an object with max_temp_c + heat_per_tick_c - skipping.", idStr);
                continue;
            }

            JsonObject o = val.getAsJsonObject();

            if (!o.has("max_temp_c") || !o.has("heat_per_tick_c")) {
                LOGGER.warn("[crookedcraft] Heat entry '{}' missing max_temp_c or heat_per_tick_c - skipping.", idStr);
                continue;
            }

            float max = o.get("max_temp_c").getAsFloat();
            float dT = o.get("heat_per_tick_c").getAsFloat();

            // Allow negative/zero max temps. Require a positive finite rate.
            if (!Float.isFinite(max) || !Float.isFinite(dT) || dT <= 0.0f) {
                LOGGER.warn("[crookedcraft] Heat entry '{}' invalid values (max={}, dT={}) - skipping.", idStr, max, dT);
                continue;
            }

            out.put(id, new HeatSourceManager.HeatEntry(new HeatSourceManager.HeatProfile(max, dT)));
        }
    }
}
