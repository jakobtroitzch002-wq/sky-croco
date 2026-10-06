package com.airship;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The control block of an airship (one per ship). Right-click it to sit down: the connected structure
 * turns into a flying airship and you are the pilot. Leaving the seat with Shift lands the ship again.
 * <p>
 * The seat has a direction (like a chair): it faces the way the placing player looked, and that
 * direction is the front of the ship. The backrest is on the opposite side.
 */
public class AirshipSeatBlock extends Block {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** Height of the seat surface in blocks (must match the shape below). */
    public static final double SEAT_HEIGHT = 0.5;

    private static final VoxelShape BASE = Shapes.box(0.0, 0.0, 0.0, 1.0, SEAT_HEIGHT, 1.0);
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        // The backrest is on the side opposite to the direction the seat faces.
        SHAPES.put(Direction.NORTH, Shapes.or(BASE, Shapes.box(0.0, SEAT_HEIGHT, 0.8125, 1.0, 1.0, 1.0)));
        SHAPES.put(Direction.SOUTH, Shapes.or(BASE, Shapes.box(0.0, SEAT_HEIGHT, 0.0, 1.0, 1.0, 0.1875)));
        SHAPES.put(Direction.EAST, Shapes.or(BASE, Shapes.box(0.0, SEAT_HEIGHT, 0.0, 0.1875, 1.0, 1.0)));
        SHAPES.put(Direction.WEST, Shapes.or(BASE, Shapes.box(0.8125, SEAT_HEIGHT, 0.0, 1.0, 1.0, 1.0)));
    }

    public AirshipSeatBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit
    ) {
        return AirshipAssembler.assemble(level, pos, player);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }
}
