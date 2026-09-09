package com.createdtr.defendtherealm.hq;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Development target only; no campaign activation or ownership semantics. */
public final class DevHqBlock extends Block {
    public DevHqBlock(Properties properties) { super(properties); }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (level instanceof ServerLevel server) HqSavedData.get(server).add(pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!next.is(this) && level instanceof ServerLevel server) HqSavedData.get(server).remove(pos);
        super.onRemove(state, level, pos, next, moved);
    }
}
