package com.airship;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class AirshipLift {
    public static final int BLOCKS_PER_BALLOON = AirshipConfig.get().blocksPerBalloon;

    private AirshipLift() {
    }

    public static int countBalloons(
            Level level,
            AirshipStructureDetector.DetectionResult result
    ) {
        int count = 0;

        for (BlockPos pos : result.blocks()) {
            if (level.getBlockState(pos).is(ModBlocks.AIRSHIP_BALLOON)) {
                count++;
            }
        }

        return count;
    }

    public static int requiredBalloons(int blockCount) {
        return Math.max(1, (blockCount + BLOCKS_PER_BALLOON - 1) / BLOCKS_PER_BALLOON);
    }

    public static boolean hasEnoughLift(int balloonCount, int requiredBalloons) {
        return balloonCount >= requiredBalloons;
    }
}
