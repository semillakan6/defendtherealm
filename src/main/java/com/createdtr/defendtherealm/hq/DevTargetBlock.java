package com.createdtr.defendtherealm.hq;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Developer-only target used to exercise the production priority shape. */
public final class DevTargetBlock extends Block {
    private final DevTargetSavedData.Kind kind;

    public DevTargetBlock(DevTargetSavedData.Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (level instanceof ServerLevel server) DevTargetSavedData.get(server).add(kind, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!next.is(this) && level instanceof ServerLevel server) DevTargetSavedData.get(server).remove(kind, pos);
        super.onRemove(state, level, pos, next, moved);
    }
}
