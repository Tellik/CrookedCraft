package com.tellik.crookedcraft.brewing;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Loads datapack JSON under:
 *   data/-/crookedcraft_brewing/heat_sources.json
 *
         * Multiple datapacks can provide this file; we merge them in load order
 * (later packs override earlier keys).
        */
        public final class HeatSourceReloadListener extends SimpleJsonResourceReloadListener {
            private static final Logger LOGGER = LogUtils.getLogger();
            private static final Gson GSON = new Gson();

            public HeatSourceReloadListener() {
                super(GSON, "crookedcraft_brewing");
            }

            @Override
            protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager resourceManager, ProfilerFiller profiler) {
                HeatSourceManager.ScanMode mode = HeatSourceManager.ScanMode.BELOW_ONLY;

                Map<ResourceLocation, Integer> blockHeat = new HashMap<>();
                Map<ResourceLocation, Integer> fluidHeat = new HashMap<>();

                // Merge any file whose path ends with "heat_sources"
                for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
                    ResourceLocation fileId = entry.getKey();
                    if (!fileId.getPath().endsWith("heat_sources")) continue;

                    JsonObject root = GsonHelper.convertToJsonObject(entry.getValue(), "heat_sources");

                    if (root.has("scan_mode")) {
                        String scanModeStr = GsonHelper.getAsString(root, "scan_mode", "below_only");
                        // v1 only supports below_only, but we parse the string for forward compatibility.
                        if (!"below_only".equalsIgnoreCase(scanModeStr)) {
                            LOGGER.warn("[crookedcraft] heat_sources '{}' requested scan_mode='{}' but v1 only supports 'below_only'. Using below_only.",
                                    fileId, scanModeStr);
                        }
                        mode = HeatSourceManager.ScanMode.BELOW_ONLY;
                    }

                    if (root.has("sources")) {
                        for (JsonElement el : GsonHelper.getAsJsonArray(root, "sources")) {
                            JsonObject src = GsonHelper.convertToJsonObject(el, "source");

                            int boilTicks = GsonHelper.getAsInt(src, "boil_ticks");
                            if (boilTicks <= 0) {
                                LOGGER.warn("[crookedcraft] heat_sources '{}' has non-positive boil_ticks={}, ignoring entry.", fileId, boilTicks);
                                continue;
                            }

                            if (src.has("block")) {
                                String raw = GsonHelper.getAsString(src, "block");
                                ResourceLocation id = ResourceLocation.tryParse(raw);
                                if (id == null) {
                                    LOGGER.warn("[crookedcraft] heat_sources '{}' has invalid block id '{}', ignoring entry.", fileId, raw);
                                    continue;
                                }
                                blockHeat.put(id, boilTicks);
                            } else if (src.has("fluid")) {
                                String raw = GsonHelper.getAsString(src, "fluid");
                                ResourceLocation id = ResourceLocation.tryParse(raw);
                                if (id == null) {
                                    LOGGER.warn("[crookedcraft] heat_sources '{}' has invalid fluid id '{}', ignoring entry.", fileId, raw);
                                    continue;
                                }
                                fluidHeat.put(id, boilTicks);
                            } else {
                                LOGGER.warn("[crookedcraft] heat_sources '{}' entry missing 'block' or 'fluid': {}", fileId, src);
                            }
                        }
                    }
                }

                HeatSourceManager.applyFromDatapack(mode, blockHeat, fluidHeat);
            }
        }
