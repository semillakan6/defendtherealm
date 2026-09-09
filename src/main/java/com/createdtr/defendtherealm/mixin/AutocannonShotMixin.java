package com.createdtr.defendtherealm.mixin;

import com.createdtr.defendtherealm.integration.cbc.ShotAccounting;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

@Mixin(value = MountedAutocannonContraption.class, remap = false)
public abstract class AutocannonShotMixin {
    @Inject(method = "fireShot", at = @At("HEAD"), cancellable = true)
    private void dtr$before(ServerLevel level, PitchOrientedContraptionEntity entity, CallbackInfo ci) {
        if (!ShotAccounting.allow(level, entity)) { ci.cancel(); return; }
        ShotAccounting.begin(level, (MountedAutocannonContraption) (Object) this, entity);
    }
    @Inject(method = "fireShot", at = @At("RETURN"))
    private void dtr$after(ServerLevel level, PitchOrientedContraptionEntity entity, CallbackInfo ci) { ShotAccounting.end(); }
}
