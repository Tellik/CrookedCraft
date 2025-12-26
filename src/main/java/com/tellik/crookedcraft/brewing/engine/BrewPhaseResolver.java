package com.tellik.crookedcraft.brewing.engine;

import java.util.EnumSet;

public final class BrewPhaseResolver {
    private BrewPhaseResolver() {}

    public static EnumSet<BrewPhase> resolve(VesselSnapshot s) {
        EnumSet<BrewPhase> out = EnumSet.noneOf(BrewPhase.class);

        // No-fluid phases
        if (!s.hasFluid) {
            out.add(BrewPhase.EMPTY);
            if (s.heatPresent) out.add(BrewPhase.IDLE);
            return out; // EMPTY/IDLE are exclusive of temp/progress phases
        }

        // Temperature phases (multi-phase allowed)
        if (!s.heatPresent) {
            out.add(BrewPhase.COLD);
        } else {
            // If melting is possible, include MELTING
            if (s.canMelt) out.add(BrewPhase.MELTING);

            // “Heating” means we have heat and (currently) consider this fluid heatable
            // We keep it broad now; later, HEATING becomes "canBoil true but not boiling yet".
            out.add(BrewPhase.HEATING);
        }

        if (s.boiling && s.canBoil) {
            out.add(BrewPhase.BOILING);
        }

        // Cooling as a derived phase (conservative for now)
        if (s.canCool && !s.boiling) {
            out.add(BrewPhase.COOLING);
        }

        // Progress phases (allowed to overlap temp phases)
        if (s.mixProgress) out.add(BrewPhase.MIXING);
        if (s.brewProgress) out.add(BrewPhase.BREWING);
        if (s.hasBrew) out.add(BrewPhase.READY);

        return out;
    }
}
