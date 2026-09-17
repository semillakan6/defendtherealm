package com.createdtr.defendtherealm.combat;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/** Server-data profiles. Invalid or unsupported vehicle families fail closed on reload/spawn. */
@EventBusSubscriber(modid = CreateDefendtheRealm.MODID)
public final class VehicleCombatProfiles {
    private static final Gson GSON = new Gson();
    private static volatile Map<String, VehicleCombatProfile> byTemplate = Map.of();
    private VehicleCombatProfiles() {}

    @SubscribeEvent
    public static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener((ResourceManagerReloadListener) VehicleCombatProfiles::reload);
    }

    private static void reload(ResourceManager manager) {
        Map<String, VehicleCombatProfile> loaded = new HashMap<>();
        for (var entry : manager.listResources("vehicle_profiles", id -> id.getPath().endsWith(".json")).entrySet()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                VehicleCombatProfile profile = parse(entry.getKey(), JsonParser.parseReader(reader).getAsJsonObject());
                String key = normalize(profile.template());
                if (loaded.putIfAbsent(key, profile) != null) throw new IllegalArgumentException("Duplicate template profile: " + profile.template());
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Invalid DTR vehicle profile " + entry.getKey(), exception);
            }
        }
        byTemplate = Map.copyOf(loaded);
        CreateDefendtheRealm.LOGGER.info("Loaded {} DTR vehicle combat profile(s)", loaded.size());
    }

    public static VehicleCombatProfile requireForTemplate(String template) {
        VehicleCombatProfile profile = byTemplate.get(normalize(template));
        if (profile == null) throw new IllegalArgumentException("No supported combat profile for template: " + template);
        return profile;
    }

    public static VehicleCombatProfile parse(ResourceLocation source, JsonObject json) {
        String id = string(json, "id");
        String template = string(json, "template");
        VehicleFamily family = VehicleFamily.valueOf(string(json, "vehicle_family").toUpperCase(Locale.ROOT));
        String navigation = string(json, "navigation_profile");
        var weapons = json.getAsJsonArray("weapons").asList().stream().map(element -> {
            JsonObject weapon = element.getAsJsonObject();
            JsonObject range = weapon.getAsJsonObject("range");
            JsonObject trajectory = weapon.getAsJsonObject("trajectory");
            BlockPos mount = null;
            if (weapon.has("mount_offset")) {
                var values = weapon.getAsJsonArray("mount_offset");
                if (values.size() != 3) throw new IllegalArgumentException("mount_offset must have three integers");
                mount = new BlockPos(values.get(0).getAsInt(), values.get(1).getAsInt(), values.get(2).getAsInt());
            }
            return new WeaponProfile(string(weapon, "id"), WeaponBehavior.valueOf(string(weapon, "behavior").toUpperCase(Locale.ROOT)),
                    new WeaponRangeProfile(number(range, "minimum"), number(range, "preferred_minimum"),
                            number(range, "preferred_maximum"), number(range, "maximum"), number(range, "maximum_firing_speed")),
                    new TrajectoryProfile(TrajectoryType.valueOf(string(trajectory, "type").toUpperCase(Locale.ROOT)),
                            number(trajectory, "projectile_speed"), number(trajectory, "gravity_per_tick"),
                            trajectory.has("drag_per_tick") ? trajectory.get("drag_per_tick").getAsDouble() : 0,
                            trajectory.has("maximum_flight_ticks") ? trajectory.get("maximum_flight_ticks").getAsInt() : 200),
                    weapon.has("breach_capable") && weapon.get("breach_capable").getAsBoolean(),
                    mount);
        }).toList();
        return new VehicleCombatProfile(id, template, family, navigation, weapons);
    }

    private static String string(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()) throw new IllegalArgumentException("Missing " + key);
        return json.get(key).getAsString();
    }
    private static double number(JsonObject json, String key) {
        if (!json.has(key)) throw new IllegalArgumentException("Missing " + key);
        return json.get(key).getAsDouble();
    }
    private static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
}
