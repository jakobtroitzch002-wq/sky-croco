package com.airship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One block of an assembled airship.
 *
 * @param pos         position relative to the Core block
 * @param state       the block state
 * @param blockEntity saved block entity data (chests, signs, ...), if the block had one
 * @param fuel        remaining fuel if this is an engine, otherwise 0
 * @param turbo       remaining turbo fuel if this is an engine, otherwise 0
 */
public record AirshipCell(BlockPos pos, BlockState state, Optional<CompoundTag> blockEntity, int fuel, int turbo) {
    public static final Codec<AirshipCell> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(AirshipCell::pos),
            BlockState.CODEC.fieldOf("state").forGetter(AirshipCell::state),
            CompoundTag.CODEC.optionalFieldOf("be").forGetter(AirshipCell::blockEntity),
            Codec.INT.optionalFieldOf("fuel", 0).forGetter(AirshipCell::fuel),
            Codec.INT.optionalFieldOf("turbo", 0).forGetter(AirshipCell::turbo)
    ).apply(instance, AirshipCell::new));
}
