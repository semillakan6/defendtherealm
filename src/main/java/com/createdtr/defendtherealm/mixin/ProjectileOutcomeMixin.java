package com.createdtr.defendtherealm.mixin;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.encounter.AssaultController;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/** Observe actual block destruction during an attributed CBC projectile tick. */
@Mixin(value = AbstractCannonProjectile.class, remap = false)
public abstract class ProjectileOutcomeMixin {
    @Unique private BlockPos dtr$target;
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
        if (dtr$target == null || !(projectile.level() instanceof ServerLevel level)) return;
        if (!level.getBlockState(dtr$target).is(CreateDefendtheRealm.DEV_HQ.get())) {
            var data = EncounterSavedData.get(level.getServer());
            var context = data.context();
            if (!context.getBoolean("targetDestroyedByProjectile")) {
                context.putBoolean("targetDestroyedByProjectile", true);
                context.putLong("targetDestroyedTick", level.getGameTime());
                context.putUUID("targetDestroyingProjectile", projectile.getUUID());
                context.putInt("postImpactTicksRemaining", AssaultController.POST_IMPACT_LINGER_TICKS);
                data.updateContext(context);
            }
        }
        dtr$target = null;
    }
}
