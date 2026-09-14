package com.createdtr.defendtherealm.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Keeps Sable GameTests inside the native solver's precise coordinate range. */
@Mixin(GameTestServer.class)
public abstract class GameTestOriginMixin {
    @ModifyArg(method = "startTests", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/gametest/framework/StructureGridSpawner;<init>(Lnet/minecraft/core/BlockPos;IZ)V"), index = 0)
    private BlockPos dtr$nearOrigin(BlockPos vanillaRandomOrigin) {
        // Vanilla deliberately randomizes the grid across the full world. Sable's
        // logical pose crosses a float-backed boundary, so sub-block velocity is
        // rounded away at the multi-million-block origins that GameTest chooses.
        return new BlockPos(0, -59, 0);
    }
}
