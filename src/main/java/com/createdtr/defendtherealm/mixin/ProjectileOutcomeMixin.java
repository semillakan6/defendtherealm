package com.createdtr.defendtherealm.mixin;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.encounter.AssaultController;
import com.createdtr.defendtherealm.encounter.AssaultTargeting;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;

/** Observe actual block destruction during an attributed CBC projectile tick. */
@Mixin(value = AbstractCannonProjectile.class, remap = false)
public abstract class ProjectileOutcomeMixin {
    @Unique private BlockPos dtr$target;
    @Unique private ServerPlayer dtr$cbcHitPlayer;
    @Unique private float dtr$healthBeforeCbcHit;

    /**
     * Sable can move a player with a sublevel before the parent level's section
     * index reflects the new world-space position. Correct only CBC's existing
     * candidate query; CBC retains its own swept intersection, damage source,
     * invulnerability, knockback and penetration handling.
     */
    @Redirect(method = "clipAndDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;",
            remap = true))
    private List<Entity> dtr$includeSelectedPlayer(Level level, Entity except, AABB bounds) {
        List<Entity> candidates = level.getEntities(except, bounds);
        var projectile = (AbstractCannonProjectile) (Object) this;
        if (!(level instanceof ServerLevel serverLevel)
                || !projectile.getPersistentData().hasUUID("dtrEncounter")) return candidates;
        var data = EncounterSavedData.get(serverLevel.getServer());
        var encounter = data.encounter();
        var context = data.context();
        if (encounter == null || !encounter.combatEnabled()
                || !encounter.id().equals(projectile.getPersistentData().getUUID("dtrEncounter"))
                || !projectile.getPersistentData().hasUUID("dtrTargetPlayer")) return candidates;
        ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(projectile.getPersistentData().getUUID("dtrTargetPlayer"));
        AABB playerBounds = dtr$worldBounds(player);
        if (player == null || player.level() != serverLevel || !player.isAlive()
                || player.isCreative() || player.isSpectator()
                || !bounds.intersects(playerBounds) || candidates.contains(player)) return candidates;
        List<Entity> corrected = new ArrayList<>(candidates.size() + 1);
        corrected.addAll(candidates);
        corrected.add(player);
        if (!context.getBoolean("playerCollisionCandidateRepaired")) {
            context.putBoolean("playerCollisionCandidateRepaired", true);
            context.putLong("playerCollisionCandidateRepairTick", serverLevel.getGameTime());
            data.updateContext(context);
        }
        return corrected;
    }

    /** CBC performs its own precise swept clip after broad-phase discovery. */
    @Redirect(method = "clipAndDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;",
            remap = true))
    private AABB dtr$worldSpaceTargetBounds(Entity entity) {
        var projectile = (AbstractCannonProjectile) (Object) this;
        if (entity instanceof ServerPlayer player && projectile.getPersistentData().hasUUID("dtrTargetPlayer")
                && projectile.getPersistentData().getUUID("dtrTargetPlayer").equals(player.getUUID())) {
            return dtr$worldBounds(player);
        }
        return entity.getBoundingBox();
    }

    @Unique
    private static AABB dtr$worldBounds(ServerPlayer player) {
        if (player == null) return new AABB(0, 0, 0, 0, 0, 0);
        var feet = AssaultTargeting.worldPosition(player);
        return AABB.ofSize(feet.add(0, player.getBbHeight() * 0.5D, 0),
                player.getBbWidth(), player.getBbHeight(), player.getBbWidth());
    }

    @Redirect(method = "onHitEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            remap = true))
    private boolean dtr$observeRealCbcDamage(Entity entity, DamageSource source, float amount) {
        boolean accepted = entity.hurt(source, amount);
        var projectile = (AbstractCannonProjectile) (Object) this;
        if (entity instanceof ServerPlayer player && projectile.level() instanceof ServerLevel level
                && projectile.getPersistentData().hasUUID("dtrEncounter")
                && projectile.getPersistentData().hasUUID("dtrTargetPlayer")
                && projectile.getPersistentData().getUUID("dtrTargetPlayer").equals(player.getUUID())) {
            var data = EncounterSavedData.get(level.getServer());
            var context = data.context();
            context.putBoolean("playerCbcDamageCalled", true);
            context.putBoolean("playerCbcDamageAccepted", accepted);
            context.putFloat("playerCbcDamageRequested", amount);
            data.updateContext(context);
        }
        return accepted;
    }

    @Inject(method = "onHitEntity", at = @At("HEAD"))
    private void dtr$beforeCbcEntityHit(Entity entity, ProjectileContext projectileContext,
            CallbackInfoReturnable<Boolean> cir) {
        dtr$cbcHitPlayer = null;
        var projectile = (AbstractCannonProjectile) (Object) this;
        if (entity instanceof ServerPlayer player && projectile.getPersistentData().hasUUID("dtrEncounter")
                && projectile.getPersistentData().hasUUID("dtrTargetPlayer")
                && projectile.getPersistentData().getUUID("dtrTargetPlayer").equals(player.getUUID())) {
            dtr$cbcHitPlayer = player;
            dtr$healthBeforeCbcHit = player.getHealth() + player.getAbsorptionAmount();
        }
    }

    @Inject(method = "onHitEntity", at = @At("RETURN"))
    private void dtr$afterCbcEntityHit(Entity entity, ProjectileContext projectileContext,
            CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer player = dtr$cbcHitPlayer;
        dtr$cbcHitPlayer = null;
        var projectile = (AbstractCannonProjectile) (Object) this;
        if (player == null || !(projectile.level() instanceof ServerLevel level)) return;
        var data = EncounterSavedData.get(level.getServer());
        var encounter = data.encounter();
        if (encounter == null || !encounter.id().equals(projectile.getPersistentData().getUUID("dtrEncounter"))) return;
        float after = player.getHealth() + player.getAbsorptionAmount();
        var context = data.context();
        context.putBoolean("playerHitProcessedByCbc", true);
        boolean damaged = after < dtr$healthBeforeCbcHit;
        context.putBoolean("playerHitDamaged", damaged);
        context.putFloat("playerHitDamageObserved", Math.max(0, dtr$healthBeforeCbcHit - after));
        context.putUUID("lastPlayerContactTarget", player.getUUID());
        context.putUUID("lastPlayerContactProjectile", projectile.getUUID());
        context.putLong("lastPlayerContactTick", level.getGameTime());
        if (damaged) {
            context.putUUID("lastPlayerHitTarget", player.getUUID());
            context.putUUID("lastPlayerHitProjectile", projectile.getUUID());
            context.putLong("lastPlayerHitTick", level.getGameTime());
            context.putString("lastPlayerHitWeapon", projectile.getPersistentData().getString("dtrWeapon"));
        }
        data.updateContext(context);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void dtr$before(CallbackInfo ci) {
        dtr$target = null;
        var projectile = (AbstractCannonProjectile) (Object) this;
        if (!(projectile.level() instanceof ServerLevel level) || !projectile.getPersistentData().hasUUID("dtrEncounter")) return;
        var data = EncounterSavedData.get(level.getServer());
        if (data.encounter() == null || !data.encounter().combatEnabled()
                || !data.encounter().id().equals(projectile.getPersistentData().getUUID("dtrEncounter"))) return;
        var tag = data.context().getCompound("target");
        BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
        if (level.hasChunkAt(pos) && level.getBlockState(pos).is(CreateDefendtheRealm.DEV_HQ.get())) dtr$target = pos;
    }
    @Inject(method = "tick", at = @At("RETURN"))
    private void dtr$after(CallbackInfo ci) {
        var projectile = (AbstractCannonProjectile) (Object) this;
        if (!(projectile.level() instanceof ServerLevel level)) return;
        if (dtr$target != null && !level.getBlockState(dtr$target).is(CreateDefendtheRealm.DEV_HQ.get())) {
            var data = EncounterSavedData.get(level.getServer());
            var context = data.context();
            if (!context.getBoolean("targetDestroyedByProjectile")) {
                context.putBoolean("targetDestroyedByProjectile", true);
                context.putLong("targetDestroyedTick", level.getGameTime());
                context.putUUID("targetDestroyingProjectile", projectile.getUUID());
                context.putInt("postImpactTicksRemaining", com.createdtr.defendtherealm.Config.POST_IMPACT_LINGER.get());
                data.updateContext(context);
            }
        }
        dtr$target = null;
    }
}
