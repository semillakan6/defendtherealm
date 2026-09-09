package com.createdtr.defendtherealm.encounter;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import com.createdtr.defendtherealm.integration.cbc.CbcAmmunitionSnapshot;
import com.createdtr.defendtherealm.integration.toolgun.BlueprintFacing;
import com.createdtr.defendtherealm.template.TemplateSavedData;
import com.createdtr.defendtherealm.template.MachinerySnapshot;
import com.enxv.aeronauticsstructuretool.blueprint.placement.PlacementTargetMath;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Standalone verification, independent of Minecraft bootstrap and test engines. */
public final class EncounterChecks {
    private static int checks;

    public static void main(String[] args) {
        Encounter e = fresh();
        UUID root = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        expectFailure(() -> e.advance(Encounter.State.ENGAGING));
        e.advance(Encounter.State.SPAWNING);
        e.own(root);
        e.advance(Encounter.State.APPROACHING);
        check(!e.combatEnabled(), "Approach must not fire");
        check(e.recordLoss(1), "First structural loss accepted");
        check(!e.recordLoss(1), "Repeated loss ignored");
        check(!e.recordLoss(99), "Foreign blocks ignored");
        check(e.state() == Encounter.State.APPROACHING, "20% damage must not defeat");
        e.advance(Encounter.State.ENGAGING);
        check(e.combatEnabled(), "Engagement enables combat");
        check(!e.inherit(child, stranger), "Foreign parent cannot claim a fragment");
        check(e.inherit(child, root), "Known parent gives fragment ownership");
        check(!e.inherit(child, root), "Fragment discovery is idempotent");
        e.recordLoss(2);
        check(e.state() == Encounter.State.DESTROYING, "40% boundary defeats exactly");
        check(!e.combatEnabled(), "Defeat immediately disables combat");
        e.terminate(Encounter.Reason.DEFEATED);
        check(e.state() == Encounter.State.DESTROYING, "Repeated defeat preserves destruction delay");
        Encounter restored = Encounter.restore(e.id(), e.weights(), e.lost(), e.owned(), e.state(), e.reason());
        check(restored.owned().equals(Set.of(root, child)), "Restoration preserves ownership");
        check(restored.lostWeight() == 2, "Restoration preserves damage");
        restored.advance(Encounter.State.CLEANING_UP);
        expectFailure(() -> restored.advance(Encounter.State.COMPLETED));
        restored.acknowledgeRemoval(root);
        restored.acknowledgeRemoval(root);
        expectFailure(() -> restored.advance(Encounter.State.COMPLETED));
        restored.acknowledgeRemoval(child);
        restored.advance(Encounter.State.COMPLETED);
        restored.terminate(Encounter.Reason.CANCELLED);
        check(restored.state() == Encounter.State.COMPLETED, "Repeated cleanup cannot resurrect");
        check(restored.reason() == Encounter.Reason.DEFEATED, "Cancellation preserves terminal reason");
        expectFailure(() -> restored.own(UUID.randomUUID()));
        expectFailure(() -> restored.advance(Encounter.State.SPAWNING));

        Encounter cancelled = fresh();
        cancelled.terminate(Encounter.Reason.CANCELLED);
        cancelled.terminate(Encounter.Reason.TIMEOUT);
        check(cancelled.reason() == Encounter.Reason.CANCELLED, "First terminal cause wins");
        cancelled.advance(Encounter.State.COMPLETED);
        expectFailure(() -> new Encounter(UUID.randomUUID(), Map.of()));
        expectFailure(() -> new Encounter(UUID.randomUUID(), Map.of(1L, -1)));
        expectFailure(() -> Encounter.restore(e.id(), e.weights(), Set.of(99L), Set.of(),
                Encounter.State.APPROACHING, Encounter.Reason.NONE));
        expectFailure(() -> Encounter.restore(e.id(), e.weights(), Set.of(), Set.of(root),
                Encounter.State.COMPLETED, Encounter.Reason.CANCELLED));
        expectFailure(() -> Encounter.restore(e.id(), e.weights(), Set.of(1L, 2L), Set.of(),
                Encounter.State.ENGAGING, Encounter.Reason.NONE));
        expectFailure(() -> Encounter.restore(e.id(), e.weights(), Set.of(), Set.of(),
                Encounter.State.CLEANING_UP, Encounter.Reason.NONE));
        checkPersistence();
        checkMachinery();
        checkAmmunition();
        checkAssaultPersistence();
        checkBlueprintFacing();
        System.out.println("Encounter checks passed: " + checks);
    }

    private static Encounter fresh() { return new Encounter(UUID.randomUUID(), Map.of(1L, 1, 2L, 1, 3L, 3)); }
    private static void checkAssaultPersistence() {
        var data = new EncounterSavedData();
        var encounter = fresh();
        var context = new CompoundTag();
        context.putBoolean("hqAssault", true);
        context.putLongArray("route", new long[] {1, 2, 3});
        context.putInt("routeCursor", 1);
        context.putLong("nextShot", 1234);
        context.putInt("repairs", 2);
        context.putBoolean("targetDestroyedByProjectile", true);
        context.putLong("targetDestroyedTick", 5000);
        context.putInt("postImpactTicksRemaining", AssaultController.POST_IMPACT_LINGER_TICKS);
        data.begin(encounter, context);
        var saved = data.save(new CompoundTag(), null);
        var restored = EncounterSavedData.load(saved, null);
        check(java.util.Arrays.equals(restored.context().getLongArray("route"), new long[] {1, 2, 3}), "Route survives reload");
        check(restored.context().getInt("routeCursor") == 1 && restored.context().getLong("nextShot") == 1234, "Cursor and cooldown survive reload");
        check(restored.context().getBoolean("targetDestroyedByProjectile")
                && restored.context().getLong("targetDestroyedTick") == 5000,
                "Projectile destruction confirmation survives reload");
        check(AssaultController.postImpactTicksRemaining(5000, 5000) == 60,
                "Post-impact linger starts at 60 ticks");
        check(AssaultController.postImpactTicksRemaining(5059, 5000) == 1,
                "Post-impact linger retains its final tick");
        check(AssaultController.postImpactTicksRemaining(5060, 5000) == 0,
                "Post-impact linger completes after 60 ticks");
        saved.putInt("schema", 1);
        check(EncounterSavedData.load(saved, null).encounter().id().equals(encounter.id()), "Legacy schema migrates identity");
        encounter.terminate(Encounter.Reason.TARGET_DESTROYED);
        encounter.advance(Encounter.State.COMPLETED);
        var complete = EncounterSavedData.load(data.save(new CompoundTag(), null), null);
        check(complete.encounter().reason() == Encounter.Reason.TARGET_DESTROYED && !complete.encounter().combatEnabled(), "HQ victory persists and disables firing");
    }
    private static void checkBlueprintFacing() {
        assertFacing(new Quaterniond(), new Vec3(0, 0, 0), new Vec3(0, 0, -20), "north");
        assertFacing(new Quaterniond(), new Vec3(0, 0, 0), new Vec3(20, 0, 0), "east");
        assertFacing(new Quaterniond(), new Vec3(0, 0, 0), new Vec3(0, 0, 20), "south");
        assertFacing(new Quaterniond(), new Vec3(0, 0, 0), new Vec3(-20, 0, 0), "west");
        assertFacing(new Quaterniond(), new Vec3(0, 0, 0), new Vec3(20, 0, 20), "diagonal");
        assertFacing(new Quaterniond().rotateY(Math.toRadians(73)), new Vec3(4, 10, -8),
                new Vec3(-13, -30, 22), "saved root yaw");
        expectFailure(() -> BlueprintFacing.rotationDegrees(new Quaterniond(), Vec3.ZERO, new Vec3(0, 20, 0)));
    }
    private static void assertFacing(Quaterniond saved, Vec3 spawn, Vec3 target, String description) {
        int rotation = BlueprintFacing.rotationDegrees(saved, spawn, target);
        Quaterniond placed = new Quaterniond(saved)
                .mul(PlacementTargetMath.computeExtraRotation(Direction.UP, rotation));
        Vector3d forward = placed.transform(new Vector3d(0, 0, -1));
        Vector3d desired = new Vector3d(target.x - spawn.x, 0, target.z - spawn.z).normalize();
        forward.y = 0;
        check(forward.normalize().dot(desired) > 0.9998, "Blueprint faces " + description + " target");
    }
    private static void checkAmmunition() {
        CompoundTag cannon = new CompoundTag();
        CompoundTag magazine = new CompoundTag();
        ListTag slots = new ListTag();
        CompoundTag slot = new CompoundTag();
        CompoundTag cartridge = new CompoundTag();
        cartridge.putString("id", "createbigcannons:autocannon_cartridge");
        cartridge.putInt("count", 61);
        slot.put("item", cartridge);
        slots.add(slot);
        magazine.put("createbigcannons:ammo", slots);
        cannon.put("Magazine", magazine);
        check(CbcAmmunitionSnapshot.finiteAutocannonCartridges(cannon) == 61,
                "Finite CBC cartridges counted from nested component data");
        cannon.putBoolean("BottomlessSupply", true);
        expectFailure(() -> CbcAmmunitionSnapshot.finiteAutocannonCartridges(cannon));
        cannon.putBoolean("BottomlessSupply", false);
        cartridge.putInt("count", -1);
        expectFailure(() -> CbcAmmunitionSnapshot.finiteAutocannonCartridges(cannon));
    }
    private static void checkMachinery() {
        CompoundTag burner = new CompoundTag();
        burner.putString("id", "aeronautics:adjustable_burner");
        burner.putInt("ScrollValue", 135);
        burner.putInt("SignalStrength", 2);
        burner.putInt("x", -4);
        burner.putUUID("UUID", UUID.randomUUID());
        burner.putLong("Network", 12345);
        CompoundTag copy = MachinerySnapshot.capture("aeronautics:adjustable_burner", burner);
        check(copy.getInt("ScrollValue") == 135 && copy.getInt("SignalStrength") == 2, "Burner settings retained");
        check(!copy.contains("x") && !copy.contains("UUID") && !copy.contains("Network"), "Live references excluded");
        check(burner.contains("UUID"), "Capture does not mutate source");
        for (String id : new String[] {"create:analog_lever", "simulated:throttle_lever"}) {
            CompoundTag lever = new CompoundTag();
            lever.putString("id", id);
            lever.putInt("State", 6);
            lever.putInt("ChangeTimer", 10);
            CompoundTag result = MachinerySnapshot.capture(id, lever);
            check(result.getInt("State") == 6 && !result.contains("ChangeTimer"), "Lever setting without transient timer");
        }
        CompoundTag mount = new CompoundTag();
        mount.putString("id", "createbigcannons:cannon_mount");
        mount.putBoolean("Running", true);
        expectFailure(() -> MachinerySnapshot.capture("createbigcannons:cannon_mount", mount));
        mount.putBoolean("Running", false);
        check(MachinerySnapshot.capture("createbigcannons:cannon_mount", mount).getAllKeys().equals(Set.of("id")),
            "Inactive mount rebuilt without live references");
        expectFailure(() -> MachinerySnapshot.capture("minecraft:chest", burner));
        expectFailure(() -> MachinerySnapshot.capture("create:analog_lever", burner));
        burner.remove("ScrollValue");
        expectFailure(() -> MachinerySnapshot.capture("aeronautics:adjustable_burner", burner));
    }
    private static void checkPersistence() {
        EncounterSavedData data = new EncounterSavedData();
        Encounter original = fresh();
        CompoundTag context = new CompoundTag();
        context.putString("template", "test_airship_v1");
        context.putUUID("supervisor", UUID.randomUUID());
        data.begin(original, context);
        context.putString("template", "changed");
        check(data.context().getString("template").equals("test_airship_v1"), "Context is copied on begin");
        expectFailure(() -> data.begin(fresh(), new CompoundTag()));
        original.advance(Encounter.State.SPAWNING);
        UUID ship = UUID.randomUUID();
        original.own(ship);
        original.advance(Encounter.State.APPROACHING);
        for (Encounter.State state : new Encounter.State[] {Encounter.State.APPROACHING,
                Encounter.State.ENGAGING, Encounter.State.DESTROYING, Encounter.State.CLEANING_UP}) {
            if (state == Encounter.State.ENGAGING) original.advance(state);
            if (state == Encounter.State.DESTROYING) { original.recordLoss(1); original.recordLoss(2); }
            if (state == Encounter.State.CLEANING_UP) original.advance(state);
            EncounterSavedData loaded = EncounterSavedData.load(data.save(new CompoundTag(), null), null);
            check(loaded.encounter().id().equals(original.id()), "NBT round trip preserves encounter UUID");
            check(loaded.encounter().state() == state, "NBT round trip preserves " + state);
            check(loaded.encounter().owned().equals(Set.of(ship)), "NBT round trip preserves ship UUID");
            check(loaded.context().getString("template").equals("test_airship_v1"), "NBT round trip preserves context");
            expectFailure(() -> loaded.begin(fresh(), new CompoundTag()));
        }
        original.acknowledgeRemoval(ship);
        original.advance(Encounter.State.COMPLETED);
        EncounterSavedData terminal = EncounterSavedData.load(data.save(new CompoundTag(), null), null);
        check(terminal.encounter().state() == Encounter.State.COMPLETED, "NBT retains completed encounter");
        terminal.begin(fresh(), new CompoundTag());
        check(terminal.encounter().state() == Encounter.State.VALIDATING, "Completed record permits next encounter");
        CompoundTag future = data.save(new CompoundTag(), null);
        future.putInt("schema", 999);
        expectFailure(() -> EncounterSavedData.load(future, null));
        check(EncounterSavedData.load(new EncounterSavedData().save(new CompoundTag(), null), null).encounter() == null,
                "Empty encounter store round trip");
        check(TemplateSavedData.load(new TemplateSavedData().save(new CompoundTag(), null), null).find("absent") == null,
                "Empty template store round trip");
        expectFailure(() -> TemplateSavedData.load(future, null));
    }
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
    private static void expectFailure(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError("Expected invariant violation");
    }
}
