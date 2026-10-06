package com.airship;

import java.util.Locale;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Helpers for carrying Minecraft 26.3 cushions on an airship. */
public final class AirshipCushions {
    /** Longest names first so "light_blue" is not mistaken for "blue". */
    private static final String[] COLORS = {
            "light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime", "pink",
            "gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    private static final TagKey<Block> WOOL_SLABS =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("minecraft", "wool_slabs"));

    /** Height above the cushion entity's position where a passenger sits (relative to the ship's blocks). */
    public static final double SEAT_HEIGHT = 0.2;

    private AirshipCushions() {}

    public static boolean isCushion(Entity entity) {
        return entity.getType() == EntityTypes.CUSHION;
    }

    /**
     * The colour name of a cushion, read from its saved data. The exact layout of that data is not
     * relied on: we look for a colour name after the "cushion/color" component (or anywhere as a fallback).
     */
    public static String colorOf(CompoundTag data) {
        String text = data.toString().toLowerCase(Locale.ROOT);
        int index = text.indexOf("cushion/color");
        String haystack = index >= 0 ? text.substring(index) : text;
        for (String color : COLORS) {
            if (haystack.contains(color)) {
                return color;
            }
        }
        return "white";
    }

    /**
     * A wool slab of the cushion's colour, used to draw the cushion while the ship is in the air.
     * If that exact slab is not found, any wool slab is used, and as a last resort nothing is drawn.
     */
    public static BlockState standInState(String color) {
        String wanted = color + "_wool_slab";
        BlockState anySlab = null;
        for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(WOOL_SLABS)) {
            Block block = holder.value();
            if (BuiltInRegistries.BLOCK.getKey(block).getPath().equals(wanted)) {
                return block.defaultBlockState();
            }
            if (anySlab == null) {
                anySlab = block.defaultBlockState();
            }
        }
        return anySlab != null ? anySlab : Blocks.AIR.defaultBlockState();
    }
}
