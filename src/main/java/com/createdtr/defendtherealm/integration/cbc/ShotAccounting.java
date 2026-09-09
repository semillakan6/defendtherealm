package com.createdtr.defendtherealm.integration.cbc;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.encounter.Encounter;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/** Exact fire-call attribution, independent of nearby unrelated cannons. */
@EventBusSubscriber(modid = CreateDefendtheRealm.MODID)
public final class ShotAccounting {
    private record Shot(ServerLevel level, MountedAutocannonContraption cannon, EncounterSavedData data, long before, ItemStack cartridge) {}
    private static final ThreadLocal<Shot> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PROJECTILE = new ThreadLocal<>();
    private static final ThreadLocal<ItemStack> CONSUMED = new ThreadLocal<>();
    public static boolean allow(ServerLevel level, PitchOrientedContraptionEntity entity) {
        var data = EncounterSavedData.get(level.getServer());
        var context = data.context();
        if (!context.hasUUID("cannonEntity") || !context.getUUID("cannonEntity").equals(entity.getUUID())) return true;
        return data.encounter() != null && data.encounter().combatEnabled()
                && context.getLong("permittedShotTick") == level.getGameTime();
    }
    public static void consumed(ItemStack stack) {
        if (ACTIVE.get() != null && !stack.isEmpty()) CONSUMED.set(stack.copyWithCount(1));
    }
    public static void begin(ServerLevel level, MountedAutocannonContraption cannon, PitchOrientedContraptionEntity entity) {
        ACTIVE.remove(); PROJECTILE.remove(); CONSUMED.remove();
        var data = EncounterSavedData.get(level.getServer());
        var context = data.context();
        if (data.encounter() == null || !data.encounter().combatEnabled() || !context.hasUUID("cannonEntity")
                || !context.getUUID("cannonEntity").equals(entity.getUUID())) return;
        ACTIVE.set(new Shot(level, cannon, data, count(level, cannon), ItemStack.EMPTY));
    }
    private static long count(ServerLevel level, MountedAutocannonContraption cannon) {
        return CbcAmmunitionSnapshot.finiteAutocannonCartridges(cannon.writeNBT(level.registryAccess(), false));
    }
    @SubscribeEvent public static void projectile(EntityJoinLevelEvent event) {
        Shot shot = ACTIVE.get();
        if (shot == null || event.getLevel() != shot.level() || !(event.getEntity() instanceof AbstractCannonProjectile projectile)) return;
        projectile.getPersistentData().putUUID("dtrEncounter", shot.data().encounter().id());
        PROJECTILE.set(true);
    }
    public static void end() {
        Shot shot = ACTIVE.get(); boolean fired = Boolean.TRUE.equals(PROJECTILE.get());
        ItemStack cartridge = CONSUMED.get();
        ACTIVE.remove(); PROJECTILE.remove(); CONSUMED.remove();
        if (shot == null || !fired) return;
        var context = shot.data().context();
        context.putInt("shots", context.getInt("shots") + 1);
        context.putLong("permittedShotTick", -1);
        long after = count(shot.level(), shot.cannon());
        context.putLong("ammunitionBeforeShot", shot.before());
        context.putLong("ammunitionAfterShot", after);
        context.putLong("observedAmmunitionDelta", shot.before() - after);
        if (cartridge == null || cartridge.isEmpty()) {
            context.putString("replenishmentFailure", "CBC did not expose the consumed cartridge");
            shot.data().encounter().terminate(Encounter.Reason.WEAPON_FAILURE);
        } else {
            ItemStack remainder = shot.cannon().insertItemIntoCannon(cartridge, false);
            if (!remainder.isEmpty()) {
                // A linked finite magazine may refill the breech during the
                // shot, leaving no input slot yet. Persist the exact consumed
                // stack as controller-owned reserve and feed it back as soon as
                // CBC exposes space; total effective ammunition stays constant.
                ListTag pending = context.getList("pendingCartridges", Tag.TAG_COMPOUND);
                pending.add(remainder.save(shot.level().registryAccess()));
                context.put("pendingCartridges", pending);
            }
            context.putInt("replenishments", context.getInt("replenishments") + 1);
            context.remove("replenishmentFailure");
        }
        context.putInt("pendingReplenishments", context.getList("pendingCartridges", Tag.TAG_COMPOUND).size());
        shot.data().updateContext(context);
    }

    public static void flush(ServerLevel level, MountedAutocannonContraption cannon, net.minecraft.nbt.CompoundTag context) {
        ListTag pending = context.getList("pendingCartridges", Tag.TAG_COMPOUND);
        while (!pending.isEmpty()) {
            ItemStack cartridge = ItemStack.parseOptional(level.registryAccess(), pending.getCompound(0));
            if (cartridge.isEmpty()) throw new IllegalStateException("Invalid persisted replenishment cartridge");
            if (!cannon.insertItemIntoCannon(cartridge, false).isEmpty()) break;
            pending.remove(0);
        }
        context.put("pendingCartridges", pending);
        context.putInt("pendingReplenishments", pending.size());
    }
}
