package com.airship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;

/**
 * A cushion (a block-attached entity since Minecraft 26.3) that rides along with an airship.
 * It is removed from the world when the ship takes off and re-created when it lands.
 *
 * @param x    position relative to the centre of the Core block's footprint
 * @param y    height relative to the bottom of the Core block
 * @param z    see x
 * @param yaw  rotation of the cushion at takeoff (the ship has yaw 0 then)
 * @param data the cushion's saved entity data (colour etc.), used to re-create it exactly
 */
public record AirshipCushion(double x, double y, double z, float yaw, CompoundTag data) {
    public static final Codec<AirshipCushion> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("x").forGetter(AirshipCushion::x),
            Codec.DOUBLE.fieldOf("y").forGetter(AirshipCushion::y),
            Codec.DOUBLE.fieldOf("z").forGetter(AirshipCushion::z),
            Codec.FLOAT.fieldOf("yaw").forGetter(AirshipCushion::yaw),
            CompoundTag.CODEC.fieldOf("data").forGetter(AirshipCushion::data)
    ).apply(instance, AirshipCushion::new));
}
