package com.createdtr.defendtherealm.mixin;

import com.createdtr.defendtherealm.integration.sable.EncounterIntegrity;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class SableSplitMixin {
    @Inject(method = "setSplitFrom", at = @At("RETURN"))
    private void dtr$inherit(ServerSubLevel parent, Pose3d pose, CallbackInfo ci) {
        EncounterIntegrity.inherit((ServerSubLevel) (Object) this, parent);
    }
}
