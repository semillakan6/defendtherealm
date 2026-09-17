package com.createdtr.defendtherealm.mixin;

import com.createdtr.defendtherealm.integration.cbc.ShotAccounting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.munitions.autocannon.AutocannonAmmoItem;

@Mixin(value = MountedAutocannonContraption.class, remap = false)
public abstract class AutocannonShotMixin {
    @Inject(method = "fireShot", at = @At("HEAD"), cancellable = true)
    private void dtr$before(ServerLevel level, PitchOrientedContraptionEntity entity, CallbackInfo ci) {
        if (!ShotAccounting.allow(level, entity)) { ci.cancel(); return; }
        ShotAccounting.begin(level, (MountedAutocannonContraption) (Object) this, entity);
    }
    @Inject(method = "fireShot", at = @At("RETURN"))
    private void dtr$after(ServerLevel level, PitchOrientedContraptionEntity entity, CallbackInfo ci) { ShotAccounting.end(); }

    @Redirect(method = "fireShot", at = @At(value = "INVOKE",
            target = "Lrbasamoyai/createbigcannons/munitions/autocannon/AutocannonAmmoItem;getSpentItem(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack dtr$suppressSpentCase(AutocannonAmmoItem ammunition, ItemStack firedStack) {
        return ShotAccounting.suppressSpentItem(ammunition.getSpentItem(firedStack));
    }
}
