package com.airship;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Engine block. Right-click it to open its screen, where fuel is added. While an airship flies, every
 * fuelled engine burns fuel as long as the pilot is thrusting and makes the ship faster.
 */
public class AirshipEngineBlock extends Block implements EntityBlock {
    public AirshipEngineBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AirshipEngineBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hitResult
    ) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        // The menu comes straight from the block entity (a plain Block has no menu provider of its own).
        if (level.getBlockEntity(pos) instanceof MenuProvider provider) {
            player.openMenu(provider);
        }
        return InteractionResult.SUCCESS;
    }
}
