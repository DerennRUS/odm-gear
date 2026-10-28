package com.derenn.odmgear;

import net.minecraftforge.common.ForgeConfigSpec;

public final class OdmConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue WORLD_SCALE;
    public static final ForgeConfigSpec.BooleanValue SPEEDOMETER;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        WORLD_SCALE = b.comment(
                        "How many Minecraft blocks one AoTTG2 unit equals.",
                        "1.0 = original physics 1:1. Lower = everything (speeds, gravity, rope pull, hook range) shrinks proportionally.")
                .defineInRange("worldScale", 0.447, 0.1, 5.0);
        SPEEDOMETER = b.comment("Show AoTTG-style speed under the crosshair")
                .define("speedometer", true);
        SPEC = b.build();
    }

    private OdmConfig() {
    }

    public static double scale() {
        try {
            return WORLD_SCALE.get();
        } catch (Exception e) {
            return 0.447;
        }
    }

    public static boolean speedometer() {
        try {
            return SPEEDOMETER.get();
        } catch (Exception e) {
            return true;
        }
    }
}
