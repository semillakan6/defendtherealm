package com.createdtr.defendtherealm.integration.cbc;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/** Exact fire-call attribution, independent of nearby unrelated cannons. */
@EventBusSubscriber(modid = CreateDefendtheRealm.MODID)
public final class ShotAccounting {
    private record Shot(ServerLevel level, MountedAutocannonContraption cannon, EncounterSavedData data,
            long before, String weaponId, UUID targetPlayer, Long targetBlock,
            long expectedTick, Vec3 expectedSpawn, Vec3 expectedDirection) {}
    private static final ThreadLocal<Shot> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PROJECTILE = new ThreadLocal<>();
    private static final ThreadLocal<ItemStack> CONSUMED = new ThreadLocal<>();
    public static boolean allow(ServerLevel level, PitchOrientedContraptionEntity entity) {
        var data = EncounterSavedData.get(level.getServer());
        var context = data.context();
        if (!context.hasUUID("permittedCannonEntity") || !context.getUUID("permittedCannonEntity").equals(entity.getUUID())) return true;
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
        if (data.encounter() == null || !data.encounter().combatEnabled() || !context.hasUUID("permittedCannonEntity")
                || !context.getUUID("permittedCannonEntity").equals(entity.getUUID())) return;
        ACTIVE.set(new Shot(level, cannon, data, count(level, cannon), context.getString("permittedWeaponId"),
                context.hasUUID("permittedTargetPlayer") ? context.getUUID("permittedTargetPlayer") : null,
                context.contains("permittedTargetBlock") ? context.getLong("permittedTargetBlock") : null,
                context.getLong("expectedProjectileTick"),
                new Vec3(context.getDouble("expectedProjectileSpawnX"), context.getDouble("expectedProjectileSpawnY"),
                        context.getDouble("expectedProjectileSpawnZ")),
                new Vec3(context.getDouble("expectedProjectileDirectionX"),
                        context.getDouble("expectedProjectileDirectionY"),
                        context.getDouble("expectedProjectileDirectionZ"))));
    }
    private static long count(ServerLevel level, MountedAutocannonContraption cannon) {
        return CbcAmmunitionSnapshot.finiteAutocannonCartridges(cannon.writeNBT(level.registryAccess(), false));
    }
    @SubscribeEvent public static void entityJoin(EntityJoinLevelEvent event) {
        Shot shot = ACTIVE.get();
        if (shot == null) return;
        // Sable may route the spent entity through its embedded level while the
        // projectile itself joins the parent ServerLevel. The synchronous
        // authorized fire-call scope is the stable ownership boundary.
        if (event.getEntity() instanceof ItemEntity item && suppressSpentCase(shot, item)) {
            event.setCanceled(true);
            return;
        }
        if (event.getLevel() != shot.level()) return;
        if (!(event.getEntity() instanceof AbstractCannonProjectile projectile)) return;
        projectile.getPersistentData().putUUID("dtrEncounter", shot.data().encounter().id());
        projectile.getPersistentData().putString("dtrWeapon", shot.weaponId());
        if (shot.targetPlayer() != null) projectile.getPersistentData().putUUID("dtrTargetPlayer", shot.targetPlayer());
        if (shot.targetBlock() != null) projectile.getPersistentData().putLong("dtrTargetBlock", shot.targetBlock());
        projectile.getPersistentData().putLong("dtrFiredTick", shot.level().getGameTime());
        var context = shot.data().context();
        ListTag projectiles = context.getList("ownedProjectiles", Tag.TAG_COMPOUND);
        CompoundTag entry = new CompoundTag();
        entry.putUUID("id", projectile.getUUID());
        projectiles.add(entry);
        context.put("ownedProjectiles", projectiles);
        var velocity = projectile.getDeltaMovement();
        context.putDouble("observedProjectileSpeed", velocity.length());
        context.putDouble("observedProjectileVelocityX", velocity.x);
        context.putDouble("observedProjectileVelocityY", velocity.y);
        context.putDouble("observedProjectileVelocityZ", velocity.z);
        context.putDouble("observedProjectileSpawnX", projectile.getX());
        context.putDouble("observedProjectileSpawnY", projectile.getY());
        context.putDouble("observedProjectileSpawnZ", projectile.getZ());
        Vec3 observedSpawn = projectile.position();
        Vec3 observedDirection = velocity.lengthSqr() < 1.0e-9 ? Vec3.ZERO : velocity.normalize();
        long observedTick = shot.level().getGameTime();
        context.putLong("observedProjectileTick", observedTick);
        context.putLong("projectilePoseTickDelta", observedTick - shot.expectedTick());
        context.putBoolean("projectilePoseSameTick", shot.expectedTick() == observedTick);
        context.putDouble("projectileSpawnError", observedSpawn.distanceTo(shot.expectedSpawn()));
        context.putDouble("projectileDirectionError", observedDirection == Vec3.ZERO ? 180 : Math.toDegrees(Math.acos(
                Math.clamp(observedDirection.dot(shot.expectedDirection().normalize()), -1, 1))));
        shot.data().updateContext(context);
        PROJECTILE.set(true);
    }

    private static boolean suppressSpentCase(Shot shot, ItemEntity entity) {
        // In the inspected CBC fireShot implementation this is the only
        // ItemEntity created synchronously inside the authorized call: its
        // spent cartridge. The thread-local scope ends before unrelated server
        // activity can run, so no proximity scan or later item deletion occurs.
        CompoundTag context = shot.data().context();
        context.putInt("suppressedCasings", context.getInt("suppressedCasings") + entity.getItem().getCount());
        context.putString("lastSuppressedCasing",
                BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()).toString());
        shot.data().updateContext(context);
        return true;
    }

    public static boolean suppressSpawnedItem(ServerLevel level, net.minecraft.world.entity.Entity entity) {
        Shot shot = ACTIVE.get();
        return shot != null && shot.level() == level && entity instanceof ItemEntity item
                && suppressSpentCase(shot, item);
    }

    /**
     * Replaces CBC's spent-item result only during an encounter-authorized
     * fireShot call. Returning EMPTY prevents CBC from constructing or adding
     * a reusable casing entity while leaving the projectile path untouched.
     */
    public static ItemStack suppressSpentItem(ItemStack spent) {
        Shot shot = ACTIVE.get();
        if (shot == null) return spent;
        CompoundTag context = shot.data().context();
        context.putInt("spentItemChecks", context.getInt("spentItemChecks") + 1);
        if (spent.isEmpty()) {
            shot.data().updateContext(context);
            return spent;
        }
        context.putInt("suppressedCasings", context.getInt("suppressedCasings") + spent.getCount());
        context.putString("lastSuppressedCasing", BuiltInRegistries.ITEM.getKey(spent.getItem()).toString());
        shot.data().updateContext(context);
        return ItemStack.EMPTY;
    }

    public static int cleanupProjectiles(ServerLevel level, EncounterSavedData data) {
        CompoundTag context = data.context();
        ListTag projectiles = context.getList("ownedProjectiles", Tag.TAG_COMPOUND);
        int removed = 0;
        for (Tag value : projectiles) {
            CompoundTag entry = (CompoundTag) value;
            if (!entry.hasUUID("id")) continue;
            var entity = level.getEntity(entry.getUUID("id"));
            if (entity != null && entity.getPersistentData().hasUUID("dtrEncounter")
                    && data.encounter() != null
                    && data.encounter().id().equals(entity.getPersistentData().getUUID("dtrEncounter"))) {
                entity.discard();
                removed++;
            }
        }
        context.put("ownedProjectiles", new ListTag());
        context.putInt("removedProjectiles", context.getInt("removedProjectiles") + removed);
        data.updateContext(context);
        return removed;
    }
    public static void end() {
        Shot shot = ACTIVE.get(); boolean fired = Boolean.TRUE.equals(PROJECTILE.get());
        ItemStack cartridge = CONSUMED.get();
        ACTIVE.remove(); PROJECTILE.remove(); CONSUMED.remove();
        if (shot == null || !fired) return;
        var context = shot.data().context();
        context.putInt("shots", context.getInt("shots") + 1);
        context.putString("lastFiredWeapon", shot.weaponId());
        context.putLong("permittedShotTick", -1);
        long after = count(shot.level(), shot.cannon());
        context.putLong("ammunitionBeforeShot", shot.before());
        context.putLong("ammunitionAfterShot", after);
        context.putLong("observedAmmunitionDelta", shot.before() - after);
        if (cartridge == null || cartridge.isEmpty()) {
            context.putString("replenishmentFailure", "CBC did not expose the consumed cartridge");
            context.putBoolean("combatIncapable", true);
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
