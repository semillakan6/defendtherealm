package com.createdtr.defendtherealm.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

/** Exposes CBC's own mount-to-contraption rotation bridge on the pinned stack. */
@Mixin(value = CannonMountBlockEntity.class, remap = false)
public interface CannonMountRotationInvoker {
    @Invoker("applyRotation")
    void dtr$applyRotation();
}
