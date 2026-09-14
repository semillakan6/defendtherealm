package com.createdtr.defendtherealm.mixin;

import com.createdtr.defendtherealm.integration.sable.EncounterIntegrity;
import dev.ryanhcode.sable.SableCommonEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SableCommonEvents.class, remap = false)
public abstract class SableBlockChangeMixin {
    @Inject(method = "handleBlockChange", at = @At("HEAD"))
    private static void dtr$physicalDamage(ServerLevel level, LevelChunk chunk, int x, int y, int z,
            BlockState oldState, BlockState newState, CallbackInfo ci) {
        EncounterIntegrity.blockChanged(level, chunk, x, y, z, oldState, newState);
    }
}
