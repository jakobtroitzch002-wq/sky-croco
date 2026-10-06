package com.airship;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Swept collision of a (possibly rotated) airship against the world.
 * <p>
 * Only surface cells whose exposed faces point towards the movement direction are tested.
 * Each cell is approximated by an axis-aligned box that is slightly larger when the ship is
 * rotated (conservative: the ship may stop a little early, but never flies into terrain).
 * Airship build blocks are ignored: ships fly straight through them.
 */
public final class AirshipCollision {
    private static final double EPS = 1.0E-7;

    private AirshipCollision() {}

    public record Result(Vec3 pos, boolean hitX, boolean hitY, boolean hitZ) {}

    public static Result move(Level level, AirshipEntity ship, Vec3 start, Vec3 delta) {
        double x = start.x;
        double y = start.y;
        double z = start.z;
        boolean hitX = false;
        boolean hitY = false;
        boolean hitZ = false;
        float yaw = ship.getYRot();

        if (delta.y != 0.0) {
            double allowed = clip(level, ship, yaw, x, y, z, 1, delta.y);
            hitY = allowed != delta.y;
            y += allowed;
        }
        if (delta.x != 0.0) {
            double allowed = clip(level, ship, yaw, x, y, z, 0, delta.x);
            hitX = allowed != delta.x;
            x += allowed;
        }
        if (delta.z != 0.0) {
            double allowed = clip(level, ship, yaw, x, y, z, 2, delta.z);
            hitZ = allowed != delta.z;
            z += allowed;
        }
        return new Result(new Vec3(x, y, z), hitX, hitY, hitZ);
    }

    /** True if the ship, placed at pos with the given yaw (degrees), would overlap solid terrain. */
    public static boolean collides(Level level, AirshipEntity ship, Vec3 pos, float yaw) {
        double angle = -Math.toRadians(yaw);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double half = 0.5 * (Math.abs(cos) + Math.abs(sin));

        BlockPos[] cells = ship.surfaceCells();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (BlockPos cell : cells) {
            double cx = pos.x + cell.getX() * cos + cell.getZ() * sin;
            double cz = pos.z + cell.getZ() * cos - cell.getX() * sin;
            double cy = pos.y + cell.getY() + 0.5;
            AABB box = new AABB(cx - half, cy - 0.5, cz - half, cx + half, cy + 0.5, cz + half).deflate(1.0E-4);

            int x0 = Mth.floor(box.minX);
            int y0 = Mth.floor(box.minY);
            int z0 = Mth.floor(box.minZ);
            int x1 = Mth.floor(box.maxX);
            int y1 = Mth.floor(box.maxY);
            int z1 = Mth.floor(box.maxZ);
            for (int bx = x0; bx <= x1; bx++) {
                for (int by = y0; by <= y1; by++) {
                    for (int bz = z0; bz <= z1; bz++) {
                        cursor.set(bx, by, bz);
                        if (!level.hasChunkAt(cursor) || level.isOutsideBuildHeight(cursor)) {
                            return true; // unloaded terrain and everything above or below the world is a wall
                        }
                        BlockState state = level.getBlockState(cursor);
                        if (ModBlocks.isAirshipBuildBlock(state.getBlock())) {
                            continue;
                        }
                        VoxelShape shape = state.getCollisionShape(level, cursor);
                        if (shape.isEmpty()) {
                            continue;
                        }
                        for (AABB part : shape.toAabbs()) {
                            if (part.move(bx, by, bz).intersects(box)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Returns how far (signed) the ship may move along the axis (0 = x, 1 = y, 2 = z). */
    private static double clip(
            Level level, AirshipEntity ship, float yaw, double px, double py, double pz, int axis, double d
    ) {
        double angle = -Math.toRadians(yaw);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double half = 0.5 * (Math.abs(cos) + Math.abs(sin));
        double[] size = {2.0 * half, 1.0, 2.0 * half};

        // Direction of travel in world space.
        Vec3 travel = new Vec3(axis == 0 ? Math.signum(d) : 0.0, axis == 1 ? Math.signum(d) : 0.0,
                axis == 2 ? Math.signum(d) : 0.0);

        // Which local faces point (roughly) the way the ship moves?
        int activeFaces = 0;
        for (Direction face : Direction.values()) {
            Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()).yRot((float) angle);
            if (normal.dot(travel) > 0.1) {
                activeFaces |= 1 << face.ordinal();
            }
        }

        BlockPos[] cells = ship.surfaceCells();
        int[] masks = ship.surfaceMasks();

        double allowed = d;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        double[] cellMin = new double[3];

        for (int i = 0; i < cells.length; i++) {
            if ((masks[i] & activeFaces) == 0) {
                continue;
            }
            if (allowed == 0.0) {
                return 0.0;
            }
            BlockPos cell = cells[i];

            double cx = px + cell.getX() * cos + cell.getZ() * sin;
            double cz = pz + cell.getZ() * cos - cell.getX() * sin;
            cellMin[0] = cx - half;
            cellMin[1] = py + cell.getY();
            cellMin[2] = cz - half;

            double[] sweepMin = {cellMin[0], cellMin[1], cellMin[2]};
            double[] sweepMax = {cellMin[0] + size[0], cellMin[1] + size[1], cellMin[2] + size[2]};
            if (allowed > 0) {
                sweepMax[axis] += allowed;
            } else {
                sweepMin[axis] += allowed;
            }

            int x0 = Mth.floor(sweepMin[0]);
            int y0 = Mth.floor(sweepMin[1]);
            int z0 = Mth.floor(sweepMin[2]);
            int x1 = Mth.floor(sweepMax[0] - EPS);
            int y1 = Mth.floor(sweepMax[1] - EPS);
            int z1 = Mth.floor(sweepMax[2] - EPS);

            for (int bx = x0; bx <= x1; bx++) {
                for (int by = y0; by <= y1; by++) {
                    for (int bz = z0; bz <= z1; bz++) {
                        cursor.set(bx, by, bz);

                        if (!level.hasChunkAt(cursor) || level.isOutsideBuildHeight(cursor)) {
                            // Treat unloaded terrain and everything above or below the world as a solid wall,
                            // so ships never fly into it or out of the build height.
                            allowed = clipAgainst(new AABB(bx, by, bz, bx + 1, by + 1, bz + 1),
                                    cellMin, size, axis, allowed);
                            continue;
                        }

                        BlockState state = level.getBlockState(cursor);
                        if (ModBlocks.isAirshipBuildBlock(state.getBlock())) {
                            continue;
                        }
                        VoxelShape shape = state.getCollisionShape(level, cursor);
                        if (shape.isEmpty()) {
                            continue;
                        }
                        for (AABB box : shape.toAabbs()) {
                            allowed = clipAgainst(box.move(bx, by, bz), cellMin, size, axis, allowed);
                        }
                    }
                }
            }
        }
        return allowed;
    }

    private static double clipAgainst(AABB obstacle, double[] cellMin, double[] size, int axis, double allowed) {
        for (int k = 0; k < 3; k++) {
            if (k == axis) {
                continue;
            }
            if (!(max(obstacle, k) > cellMin[k] + EPS && min(obstacle, k) < cellMin[k] + size[k] - EPS)) {
                return allowed;
            }
        }
        if (allowed > 0) {
            double gap = min(obstacle, axis) - (cellMin[axis] + size[axis]);
            if (gap >= -EPS && gap < allowed) {
                return Math.max(gap, 0.0);
            }
        } else {
            double gap = max(obstacle, axis) - cellMin[axis];
            if (gap <= EPS && gap > allowed) {
                return Math.min(gap, 0.0);
            }
        }
        return allowed;
    }

    private static double min(AABB box, int axis) {
        return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
    }

    private static double max(AABB box, int axis) {
        return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
    }
}
