package com.createdtr.defendtherealm.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Keeps the opt-in Sable fixture inside the native solver's precise coordinate range. */
@Mixin(GameTestServer.class)
public abstract class GameTestOriginMixin {
    @ModifyArg(method = "startTests", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/gametest/framework/StructureGridSpawner;<init>(Lnet/minecraft/core/BlockPos;IZ)V"), index = 0)
    private BlockPos dtr$nearOrigin(BlockPos vanillaRandomOrigin) {
        return System.getProperty("dtr.assaultBlueprint") == null ? vanillaRandomOrigin : new BlockPos(0, -59, 0);
    }
}
