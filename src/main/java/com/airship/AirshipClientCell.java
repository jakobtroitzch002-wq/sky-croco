package com.airship;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** The part of a ship block the client needs for rendering and seating. */
public record AirshipClientCell(BlockPos pos, BlockState state) {}
