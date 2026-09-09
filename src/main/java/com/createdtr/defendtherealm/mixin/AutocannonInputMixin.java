package com.createdtr.defendtherealm.mixin;

import com.createdtr.defendtherealm.integration.cbc.ShotAccounting;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;

@Mixin(value = AbstractAutocannonBreechBlockEntity.class, remap = false)
public abstract class AutocannonInputMixin {
    @Inject(method = "extractNextInput", at = @At("RETURN"))
    private void dtr$consumed(CallbackInfoReturnable<ItemStack> cir) { ShotAccounting.consumed(cir.getReturnValue()); }
}
