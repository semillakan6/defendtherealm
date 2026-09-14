package com.createdtr.defendtherealm;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-authoritative prototype tuning. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue SHOT_INTERVAL = BUILDER
            .comment("Minimum prototype autocannon interval, in ticks.")
            .defineInRange("prototypeShotInterval", 40, 20, 1200);
    public static final ModConfigSpec.IntValue POST_IMPACT_LINGER = BUILDER
            .comment("Ticks to hold after an attributed projectile destroys the HQ.")
            .defineInRange("prototypePostImpactLinger", 60, 0, 1200);
    public static final ModConfigSpec.DoubleValue DEFEAT_DESCENT_SPEED = BUILDER
            .comment("Maximum assisted descent speed after defeat, in blocks per second.")
            .defineInRange("prototypeDefeatDescentSpeed", 3.0D, 0.25D, 10.0D);
    public static final ModConfigSpec.IntValue DEFEAT_GROUNDING_TIMEOUT = BUILDER
            .comment("Maximum ticks to wait for a defeated vehicle to reach ground.")
            .defineInRange("prototypeDefeatGroundingTimeout", 400, 20, 2400);
    public static final ModConfigSpec.IntValue RECOVERY_GRACE_TICKS = BUILDER
            .comment("Ticks to wait for Sable/Create nested machinery after loading an encounter.")
            .defineInRange("prototypeRecoveryGraceTicks", 200, 20, 1200);
    public static final ModConfigSpec.IntValue LOS_SEARCH_INTERVAL = BUILDER
            .comment("Ticks spent evaluating each alternate firing position.")
            .defineInRange("prototypeLosSearchInterval", 20, 5, 200);
    public static final ModConfigSpec.IntValue BREACH_SHOT_LIMIT = BUILDER
            .comment("Real shots attempted against an obstruction before changing targets.")
            .defineInRange("prototypeBreachShotLimit", 3, 1, 16);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
