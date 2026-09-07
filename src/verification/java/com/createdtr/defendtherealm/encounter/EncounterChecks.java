package com.createdtr.defendtherealm.encounter;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import com.createdtr.defendtherealm.integration.cbc.CbcAmmunitionSnapshot;
import com.createdtr.defendtherealm.template.TemplateSavedData;
import com.createdtr.defendtherealm.template.MachinerySnapshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

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
        System.out.println("Encounter checks passed: " + checks);
    }

    private static Encounter fresh() { return new Encounter(UUID.randomUUID(), Map.of(1L, 1, 2L, 1, 3L, 3)); }
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
