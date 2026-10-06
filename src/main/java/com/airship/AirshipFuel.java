package com.airship;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Which items the engine burns, and for how long (in ticks of thrust; 20 ticks = 1 second). */
public final class AirshipFuel {
    private AirshipFuel() {}

    /** Burn time of one item in ticks, or 0 if the engine cannot burn it. */
    public static int burnTime(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) {
            return 1600;
        }
        if (stack.is(Items.LAVA_BUCKET)) {
            return 24000; // 20 minutes: a full tank
        }
        if (stack.is(Items.COAL_BLOCK)) {
            return 16000;
        }
        if (stack.is(Items.BLAZE_ROD)) {
            return 2400;
        }
        if (stack.is(ItemTags.LOGS_THAT_BURN) || stack.is(ItemTags.PLANKS)) {
            return 300;
        }
        if (stack.is(Items.STICK)) {
            return 100;
        }
        if (stack.is(Items.BAMBOO)) {
            return 50;
        }
        return 0;
    }

    /** What is left over after burning one item (a lava bucket leaves an empty bucket). */
    public static ItemStack remainder(ItemStack stack) {
        if (stack.is(Items.LAVA_BUCKET)) {
            return new ItemStack(Items.BUCKET);
        }
        return ItemStack.EMPTY;
    }

    public static boolean isFuel(ItemStack stack) {
        return burnTime(stack) > 0;
    }

    /** Ticks as m:ss. */
    public static String time(int ticks) {
        int seconds = Math.max(0, ticks) / 20;
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }
}
