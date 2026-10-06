package com.airship;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionResult;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Turns a block structure into a flying {@link AirshipEntity} and back. */
public final class AirshipAssembler {
    /**
     * Placing/removing ship blocks must not drop items, pop off torches or trigger shape updates,
     * otherwise attached blocks (torches, buttons, carpets, ...) would break while the ship is moved.
     */
    private static final int WRITE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private static final List<int[]> LANDING_OFFSETS = buildLandingOffsets();

    private AirshipAssembler() {}

    // ------------------------------------------------------------------ assemble

    /** Right-click on the Core: check the ship and open the Core screen, without taking off. */
    public static InteractionResult inspect(Level level, BlockPos corePos, Player player) {
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            sendInspection(serverLevel, corePos, serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }

    /** Checks the ship around this Core and sends the result to the player (the Core screen shows it). */
    public static void sendInspection(ServerLevel level, BlockPos corePos, ServerPlayer player) {
        ServerPlayNetworking.send(player, analyze(level, corePos, true));
    }

    /**
     * Scans the structure around the Core and counts what it has and what it is missing. With {@code register}
     * the found blocks are saved in the Core as the ship (taking off later uses exactly these blocks).
     */
    public static AirshipCoreInfoPayload analyze(ServerLevel level, BlockPos corePos, boolean register) {
        Set<BlockPos> ignored = new HashSet<>();
        if (level.getBlockEntity(corePos) instanceof AirshipCoreBlockEntity coreEntity) {
            for (BlockPos relative : coreEntity.getTerrain()) {
                ignored.add(corePos.offset(relative).immutable());
            }
        }
        AirshipStructureDetector.DetectionResult detection = AirshipStructureDetector.detect(level, corePos, ignored);
        int max = AirshipStructureDetector.MAX_BLOCKS;
        if (detection.capped()) {
            return new AirshipCoreInfoPayload(
                    corePos, false, "Zu groß oder berührt das Gelände", max, max, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        Set<BlockPos> found = detection.blocks();
        if (register && level.getBlockEntity(corePos) instanceof AirshipCoreBlockEntity coreEntity) {
            List<BlockPos> relative = new ArrayList<>(found.size());
            for (BlockPos pos : found) {
                relative.add(new BlockPos(
                        pos.getX() - corePos.getX(), pos.getY() - corePos.getY(), pos.getZ() - corePos.getZ()));
            }
            coreEntity.setRegistered(relative);
        }
        return describe(level, corePos, found);
    }

    /** Counts what the given set of ship blocks has and what it is missing, without changing anything. */
    private static AirshipCoreInfoPayload describe(ServerLevel level, BlockPos corePos, Set<BlockPos> blocks) {
        int max = AirshipStructureDetector.MAX_BLOCKS;
        AABB area = boundsOf(blocks);
        int cushions = level.getEntitiesOfClass(
                Entity.class, area, e -> AirshipCushions.isCushion(e) && isAttachedToShip(e, blocks)).size();

        int balloons = 0;
        int seats = 0;
        int engines = 0;
        int fuel = 0;
        for (BlockPos pos : blocks) {
            BlockState state = level.getBlockState(pos);
            if (state.is(ModBlocks.AIRSHIP_BALLOON)) {
                balloons++;
            } else if (state.is(ModBlocks.AIRSHIP_SEAT)) {
                seats++;
            } else if (state.is(ModBlocks.AIRSHIP_ENGINE)) {
                engines++;
                if (level.getBlockEntity(pos) instanceof AirshipEngineBlockEntity engine) {
                    fuel += engine.getFuel();
                }
            }
        }

        int required = AirshipLift.requiredBalloons(blocks.size());
        String problem = "";
        if (blocks.size() > max) {
            // e.g. a ship registered when the limit was higher
            problem = "Zu groß: " + blocks.size() + " von " + max + " Blöcken";
        } else if (seats == 0) {
            problem = "Kein Steuersitz";
        } else if (seats > 1) {
            problem = "Mehr als ein Steuersitz";
        } else if (!AirshipLift.hasEnoughLift(balloons, required)) {
            problem = "Zu wenige Ballons";
        }
        if (problem.isEmpty()) {
            int onBoard = blockingEntities(level, area, blocks).size();
            if (onBoard > 0) {
                problem = "Items/Rahmen an Bord: " + onBoard;
            }
        }
        return new AirshipCoreInfoPayload(
                corePos, problem.isEmpty(), problem, blocks.size(), max, balloons, required, seats, cushions,
                engines, fuel, engines * AirshipEngineBlockEntity.MAX_FUEL,
                shipCarried(level, area, blocks).size());
    }

    /**
     * Right-click on the Airship Seat (the control block): turn the connected structure into a flying
     * airship. The clicking player becomes the pilot, cushion sitters stay on their cushions.
     */
    public static InteractionResult assemble(Level level, BlockPos startPos, Player player) {
        return run(level, startPos, player, true);
    }

    private static InteractionResult run(Level level, BlockPos startPos, Player player, boolean launch) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        // 1. Find the Core this block belongs to.
        BlockPos corePos = AirshipStructureDetector.findCore(level, startPos);
        if (corePos == null) {
            tell(player, "Kein Luftschiff-Kern verbunden");
            return InteractionResult.SUCCESS;
        }

        if (!(player instanceof ServerPlayer starter)) {
            return InteractionResult.SUCCESS;
        }
        // The ship is what the Core registered when it was checked. Blocks placed since then are not part of
        // it (they stay where they are) until the Core is checked again.
        AirshipCoreBlockEntity coreEntity =
                level.getBlockEntity(corePos) instanceof AirshipCoreBlockEntity c ? c : null;
        if (coreEntity == null || coreEntity.getRegistered().isEmpty()) {
            fail(starter, analyze(serverLevel, corePos, false), "Nicht registriert: Kern prüfen");
            return InteractionResult.SUCCESS;
        }
        Set<BlockPos> blocks = new HashSet<>();
        blocks.add(corePos.immutable());
        for (BlockPos relative : coreEntity.getRegistered()) {
            BlockPos pos = corePos.offset(relative);
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || ModBlocks.isAirshipBuildBlock(state.getBlock())) {
                continue; // a registered block that was removed since
            }
            blocks.add(pos.immutable());
        }

        // Everything that is wrong (no seat, not enough balloons ...) is shown on the Core screen.
        AirshipCoreInfoPayload info = describe(serverLevel, corePos, blocks);
        if (!info.ready()) {
            ServerPlayNetworking.send(starter, info);
            return InteractionResult.SUCCESS;
        }
        if (!blocks.contains(startPos)) {
            fail(starter, info, "Sitz nicht registriert: Kern prüfen");
            return InteractionResult.SUCCESS;
        }

        // Cushions (entities attached to blocks) on the ship fly along as passenger seats.
        AABB area = boundsOf(blocks);
        List<Entity> cushionEntities = serverLevel.getEntitiesOfClass(
                Entity.class, area, e -> AirshipCushions.isCushion(e) && isAttachedToShip(e, blocks));

        // Mobs standing on the ship come along automatically (as passengers, where they stand). Leash knots on
        // the ship's fences are removed with the fences: the leash is released and the lead goes back to the pilot.
        List<Entity> carriedMobs = shipCarried(serverLevel, area, blocks);
        List<Entity> knots = shipKnots(serverLevel, area, blocks);

        int balloons = 0;
        int seatBlocks = 0;
        int engines = 0;
        BlockPos controlSeat = null;
        for (BlockPos pos : blocks) {
            BlockState state = level.getBlockState(pos);
            if (state.is(ModBlocks.AIRSHIP_BALLOON)) {
                balloons++;
            } else if (state.is(ModBlocks.AIRSHIP_SEAT)) {
                seatBlocks++;
                controlSeat = pos;
            } else if (state.is(ModBlocks.AIRSHIP_ENGINE)) {
                engines++;
            }
        }
        int cushionCount = cushionEntities.size();

        ServerPlayer pilot = starter;

        // Seats relative to the Core: index 0 is the control seat, then the cushions (nearest first).
        double originX = corePos.getX() + 0.5;
        double originY = corePos.getY();
        double originZ = corePos.getZ() + 0.5;
        List<SeatSpot> spots = new ArrayList<>();
        BlockPos controlRelative = new BlockPos(
                controlSeat.getX() - corePos.getX(),
                controlSeat.getY() - corePos.getY(),
                controlSeat.getZ() - corePos.getZ());
        spots.add(new SeatSpot(
                new Vec3(controlRelative.getX(), controlRelative.getY() + AirshipSeatBlock.SEAT_HEIGHT,
                        controlRelative.getZ()),
                controlRelative, null));
        List<SeatSpot> cushionSpots = new ArrayList<>();
        for (Entity cushion : cushionEntities) {
            cushionSpots.add(new SeatSpot(
                    new Vec3(cushion.getX() - originX,
                            cushion.getY() - originY + AirshipCushions.SEAT_HEIGHT,
                            cushion.getZ() - originZ),
                    null, cushion));
        }
        cushionSpots.sort(Comparator.comparingDouble((SeatSpot spot) -> spot.local().lengthSqr()));
        spots.addAll(cushionSpots);

        // The pilot, everybody sitting on a cushion and everybody standing on the ship come along.
        Set<ServerPlayer> riderSet = new LinkedHashSet<>();
        riderSet.add(pilot);
        for (Entity cushion : cushionEntities) {
            for (Entity passenger : cushion.getPassengers()) {
                if (passenger instanceof ServerPlayer sitting) {
                    riderSet.add(sitting);
                }
            }
        }
        for (ServerPlayer other : serverLevel.getEntitiesOfClass(ServerPlayer.class, area, p -> isOnShip(p, blocks))) {
            riderSet.add(other);
        }
        List<ServerPlayer> riders = new ArrayList<>(riderSet);
        if (riders.size() > 1 + cushionCount) {
            fail(starter, info, "Zu wenige Sitzkissen: " + (riders.size() - 1) + " Mitfahrer, " + cushionCount + " Kissen");
            return InteractionResult.SUCCESS;
        }

        // Fixed seat for everybody: the pilot on the control seat (0), cushion sitters on their own cushion,
        // the others on the nearest free cushion.
        int[] seatIndex = new int[riders.size()];
        Set<Integer> taken = new HashSet<>();
        seatIndex[0] = 0;
        taken.add(0);
        for (int i = 1; i < riders.size(); i++) {
            seatIndex[i] = -1;
            for (int s = 1; s < spots.size(); s++) {
                if (!taken.contains(s) && spots.get(s).cushion() != null
                        && riders.get(i).getVehicle() == spots.get(s).cushion()) {
                    seatIndex[i] = s;
                    taken.add(s);
                    break;
                }
            }
        }
        for (int i = 1; i < riders.size(); i++) {
            if (seatIndex[i] >= 0) {
                continue;
            }
            double best = Double.MAX_VALUE;
            for (int s = 1; s < spots.size(); s++) {
                if (taken.contains(s)) {
                    continue;
                }
                double distance = new Vec3(originX, originY, originZ).add(spots.get(s).local())
                        .distanceToSqr(riders.get(i).position());
                if (distance < best) {
                    best = distance;
                    seatIndex[i] = s;
                }
            }
            taken.add(seatIndex[i]);
        }
        List<Vec3> seatOrder = new ArrayList<>();
        for (SeatSpot spot : spots) {
            seatOrder.add(spot.local());
        }
        // Mobs, boats and minecarts ride along as passengers at the spot where they stand (the ship has no floor).
        Map<Entity, Integer> mobSpots = new HashMap<>();
        for (Entity mob : carriedMobs) {
            seatOrder.add(new Vec3(mob.getX() - originX, mob.getY() - originY, mob.getZ() - originZ));
            mobSpots.put(mob, seatOrder.size() - 1);
        }

        // Take the cushions off the ship so they can be carried along.
        List<AirshipCushion> cushions = new ArrayList<>();
        for (Entity cushion : cushionEntities) {
            cushion.ejectPassengers();
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            cushion.saveWithoutId(output);
            cushions.add(new AirshipCushion(
                    cushion.getX() - originX, cushion.getY() - originY, cushion.getZ() - originZ,
                    cushion.getYRot(), output.buildResult()));
            cushion.discard();
        }

        // Snapshot all blocks, then remove them from the world.
        List<AirshipCell> cells = new ArrayList<>(blocks.size());
        for (BlockPos pos : blocks) {
            BlockState state = level.getBlockState(pos);
            Optional<CompoundTag> blockEntityTag = Optional.empty();
            int fuel = 0;
            int turbo = 0;

            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof AirshipEngineBlockEntity engine) {
                fuel = engine.getFuel();
                turbo = engine.getTurbo();
            } else if (blockEntity != null) {
                blockEntityTag = Optional.of(blockEntity.saveWithFullMetadata(level.registryAccess()));
                if (blockEntity instanceof Clearable clearable) {
                    clearable.clearContent(); // otherwise chests would drop their items when removed
                }
            }

            BlockPos relative = new BlockPos(
                    pos.getX() - corePos.getX(),
                    pos.getY() - corePos.getY(),
                    pos.getZ() - corePos.getZ());
            cells.add(new AirshipCell(relative, state, blockEntityTag, fuel, turbo));
        }
        for (BlockPos pos : blocks) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), WRITE_FLAGS);
        }

        AirshipEntity ship = new AirshipEntity(ModEntities.AIRSHIP, level);
        ship.setPos(originX, originY, originZ);
        ship.setCells(cells);
        ship.setCushions(cushions);
        ship.setSeatOrder(seatOrder);
        serverLevel.addFreshEntity(ship);
        // The mobs on board get on, and the fence knots are removed (their leashes are released).
        int riding = 0;
        for (Entity mob : carriedMobs) {
            if (mob.startRiding(ship, true, true)) {
                riding++;
            }
        }
        for (Entity knot : knots) {
            knot.discard();
        }
        if (!knots.isEmpty()) {
            ship.recoverLeads();
        }
        if (!carriedMobs.isEmpty()) {
            tell(starter, "Tiere mitgenommen: " + riding + " von " + carriedMobs.size());
        }

        Map<Integer, Integer> assignment = new HashMap<>();
        for (int i = 0; i < riders.size(); i++) {
            riders.get(i).startRiding(ship, true, true);
            assignment.put(riders.get(i).getId(), seatIndex[i]);
        }
        for (Map.Entry<Entity, Integer> entry : mobSpots.entrySet()) {
            assignment.put(entry.getKey().getId(), entry.getValue());
        }
        ship.setSeatAssignment(assignment);
        AirshipSeatMapPayload seatMap = ship.seatMapPayload();
        for (ServerPlayer tracking : PlayerLookup.tracking(ship)) {
            ServerPlayNetworking.send(tracking, seatMap);
        }

        return InteractionResult.SUCCESS;
    }

    /** A place to sit, relative to the Core: a seat block (blockPos set) or a cushion (cushion set). */
    private record SeatSpot(Vec3 local, BlockPos blockPos, Entity cushion) {}

    // ------------------------------------------------------------------ disassemble

    /**
     * Turns the ship back into blocks, snapped to the nearest 90 degrees.
     * Returns false if there is no free space to land.
     */
    public static boolean disassemble(ServerLevel level, AirshipEntity ship) {
        List<AirshipCell> cells = snappedCells(ship);
        BlockPos anchor = findLandingAnchor(level, ship, cells);
        if (anchor == null) {
            return false;
        }

        for (AirshipCell cell : cells) {
            level.setBlock(anchor.offset(cell.pos()), cell.state(), WRITE_FLAGS);
        }
        for (AirshipCell cell : cells) {
            BlockPos target = anchor.offset(cell.pos());
            BlockEntity blockEntity = level.getBlockEntity(target);
            if (blockEntity == null) {
                continue;
            }
            if (blockEntity instanceof AirshipEngineBlockEntity engine) {
                engine.setFuel(cell.fuel());
                engine.setTurbo(cell.turbo());
            } else if (cell.blockEntity().isPresent()) {
                CompoundTag tag = cell.blockEntity().get();
                blockEntity.loadWithComponents(
                        TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
                blockEntity.setChanged();
            }
        }

        rememberTerrainContacts(level, cells, anchor);
        registerLanded(level, cells, anchor);
        restoreCushions(level, ship.getCushions(), anchor, snappedYaw(ship.getYRot()));

        for (Entity passenger : new ArrayList<>(ship.getPassengers())) {
            if (!(passenger instanceof LivingEntity)) {
                Vec3 spot = ship.landingSpot(passenger);
                passenger.setPos(spot.x, spot.y, spot.z);
            }
        }
        ship.markLanded();
        ship.ejectPassengers();
        ship.discard();
        return true;
    }

    private static String entityName(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
    }

    private static boolean isLeashKnot(Entity entity) {
        return entityName(entity).equals("leash_knot");
    }

    /** A leash knot on a block of the ship (or just above one, in case the knot sits a little high). */
    private static boolean knotOnShip(Entity entity, Set<BlockPos> blocks) {
        if (!isLeashKnot(entity)) {
            return false;
        }
        BlockPos position = entity.blockPosition();
        return blocks.contains(position) || blocks.contains(position.below());
    }

    private static List<Entity> shipKnots(ServerLevel level, AABB area, Set<BlockPos> blocks) {
        return level.getEntitiesOfClass(Entity.class, area, e -> knotOnShip(e, blocks));
    }

    private static boolean isCarriedVehicle(Entity entity) {
        String name = entityName(entity);
        return name.endsWith("boat") || name.endsWith("raft") || name.endsWith("minecart");
    }

    /**
     * Mobs, boats and minecarts standing on (or in) the ship that are not riding something already: they come
     * along as passengers.
     */
    private static List<Entity> shipCarried(ServerLevel level, AABB area, Set<BlockPos> blocks) {
        return level.getEntitiesOfClass(Entity.class, area,
                entity -> (entity instanceof Mob || isCarriedVehicle(entity))
                        && entity.getVehicle() == null && isAttachedToShip(entity, blocks));
    }

    /**
     * Things on the ship that cannot come along: dropped items, item frames and paintings (mobs ride along, players
     * and cushions are handled separately). They would be left behind or fall through the ship.
     */
    private static List<Entity> blockingEntities(ServerLevel level, AABB area, Set<BlockPos> blocks) {
        return level.getEntitiesOfClass(Entity.class, area, entity -> {
            String name = entityName(entity);
            boolean relevant = entity instanceof ItemEntity
                    || name.equals("item_frame") || name.equals("glow_item_frame") || name.equals("painting");
            return relevant && isAttachedToShip(entity, blocks);
        });
    }

    private static boolean isAttachedToShip(Entity entity, Set<BlockPos> blocks) {
        BlockPos at = entity.blockPosition();
        BlockPos below = BlockPos.containing(entity.getX(), entity.getY() - 0.01, entity.getZ());
        return blocks.contains(at) || blocks.contains(below);
    }

    /** Re-creates the ship's cushions on the landed ship (the blocks they sit on exist again by now). */
    private static void restoreCushions(ServerLevel level, List<AirshipCushion> cushions, BlockPos anchor, float snappedYaw) {
        Vec3 origin = new Vec3(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
        float rotation = (float) -Math.toRadians(snappedYaw);
        for (AirshipCushion cushion : cushions) {
            Entity entity = EntityTypes.CUSHION.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
            if (entity == null) {
                continue;
            }
            CompoundTag data = cushion.data().copy();
            data.remove("UUID"); // the original is gone, the copy gets a fresh identity
            entity.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));

            Vec3 world = origin.add(new Vec3(cushion.x(), cushion.y(), cushion.z()).yRot(rotation));
            entity.setPos(world.x, world.y, world.z);
            entity.setYRot(cushion.yaw() + snappedYaw);
            level.addFreshEntity(entity);
        }
    }

    /** The landed ship is registered again (its blocks are at new, possibly rotated, positions). */
    private static void registerLanded(ServerLevel level, List<AirshipCell> cells, BlockPos anchor) {
        for (AirshipCell core : cells) {
            if (!core.state().is(ModBlocks.AIRSHIP_CORE)) {
                continue;
            }
            if (level.getBlockEntity(anchor.offset(core.pos())) instanceof AirshipCoreBlockEntity coreEntity) {
                List<BlockPos> relative = new ArrayList<>(cells.size());
                for (AirshipCell cell : cells) {
                    relative.add(new BlockPos(
                            cell.pos().getX() - core.pos().getX(),
                            cell.pos().getY() - core.pos().getY(),
                            cell.pos().getZ() - core.pos().getZ()));
                }
                coreEntity.setRegistered(relative);
            }
        }
    }

    /**
     * Stores, in every Core of the landed ship, which neighbouring blocks are terrain (anything next to
     * the ship that is not part of it), so the next scan does not pull that terrain into the ship.
     */
    private static void rememberTerrainContacts(ServerLevel level, List<AirshipCell> cells, BlockPos anchor) {
        Set<BlockPos> shipBlocks = new HashSet<>();
        List<BlockPos> cores = new ArrayList<>();
        for (AirshipCell cell : cells) {
            BlockPos target = anchor.offset(cell.pos()).immutable();
            shipBlocks.add(target);
            if (cell.state().is(ModBlocks.AIRSHIP_CORE)) {
                cores.add(target);
            }
        }

        Set<BlockPos> contacts = new HashSet<>();
        for (BlockPos block : shipBlocks) {
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = block.relative(direction);
                if (shipBlocks.contains(neighbour)) {
                    continue;
                }
                BlockState state = level.getBlockState(neighbour);
                if (state.isAir() || state.canBeReplaced() || ModBlocks.isAirshipBuildBlock(state.getBlock())) {
                    continue;
                }
                contacts.add(neighbour.immutable());
            }
        }

        for (BlockPos core : cores) {
            if (level.getBlockEntity(core) instanceof AirshipCoreBlockEntity coreEntity) {
                List<BlockPos> relative = new ArrayList<>(contacts.size());
                for (BlockPos contact : contacts) {
                    relative.add(new BlockPos(
                            contact.getX() - core.getX(), contact.getY() - core.getY(), contact.getZ() - core.getZ()));
                }
                coreEntity.setTerrain(relative);
            }
        }
    }

    /** The ship's cells rotated by the ship's yaw, rounded to a multiple of 90 degrees. */
    private static List<AirshipCell> snappedCells(AirshipEntity ship) {
        int quarterTurns = Math.floorMod(Math.round(ship.getYRot() / 90.0F), 4);
        Rotation rotation = switch (quarterTurns) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };

        List<AirshipCell> result = new ArrayList<>();
        for (AirshipCell cell : ship.getCellsWithFuel()) {
            int x = cell.pos().getX();
            int z = cell.pos().getZ();
            for (int i = 0; i < quarterTurns; i++) {
                int rotatedX = -z;
                z = x;
                x = rotatedX;
            }
            result.add(new AirshipCell(
                    new BlockPos(x, cell.pos().getY(), z),
                    cell.state().rotate(rotation),
                    cell.blockEntity(),
                    cell.fuel(),
                    cell.turbo()));
        }
        return result;
    }

    /** The block grid position (of the Core) a ship at this position lands on. */
    public static BlockPos snappedAnchor(Vec3 pos) {
        return BlockPos.containing(Math.round(pos.x - 0.5), Math.round(pos.y), Math.round(pos.z - 0.5));
    }

    /**
     * Where the ship lands: its origin after snapping to 90 degrees, turning around the pilot's seat
     * (so the seat stays where it is while the rest of the ship swings into the grid).
     */
    public static BlockPos landingAnchor(AirshipEntity ship) {
        Vec3 pivot = ship.getPivot();
        Vec3 shift = AirshipEntity.rotateLocal(pivot, ship.getYRot())
                .subtract(AirshipEntity.rotateLocal(pivot, snappedYaw(ship.getYRot())));
        return snappedAnchor(ship.position().add(shift));
    }

    /** The ship's yaw rounded to the nearest multiple of 90 degrees. */
    public static float snappedYaw(float yaw) {
        return Math.round(yaw / 90.0F) * 90.0F;
    }

    private static BlockPos findLandingAnchor(ServerLevel level, AirshipEntity ship, List<AirshipCell> cells) {
        BlockPos base = landingAnchor(ship);
        for (int[] offset : LANDING_OFFSETS) {
            BlockPos anchor = base.offset(offset[0], offset[1], offset[2]);
            if (fits(level, cells, anchor)) {
                return anchor;
            }
        }
        return null;
    }

    private static boolean fits(ServerLevel level, List<AirshipCell> cells, BlockPos anchor) {
        for (AirshipCell cell : cells) {
            BlockPos target = anchor.offset(cell.pos());
            if (level.isOutsideBuildHeight(target) || !level.hasChunkAt(target)) {
                return false;
            }
            BlockState existing = level.getBlockState(target);
            if (!existing.isAir() && !existing.canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    /** Candidate shifts around the rounded position, nearest first, preferring upwards. */
    private static List<int[]> buildLandingOffsets() {
        List<int[]> offsets = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    offsets.add(new int[] {x, y, z});
                }
            }
        }
        offsets.sort(Comparator
                .comparingInt((int[] o) -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2])
                .thenComparingInt(o -> -o[1]));
        return List.copyOf(offsets);
    }

    // ------------------------------------------------------------------ helpers

    private static AABB boundsOf(Set<BlockPos> blocks) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : blocks) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new AABB(minX - 1.0, minY - 1.0, minZ - 1.0, maxX + 2.0, maxY + 3.0, maxZ + 2.0);
    }

    private static boolean isOnShip(ServerPlayer player, Set<BlockPos> blocks) {
        BlockPos feet = BlockPos.containing(player.getX(), player.getY(), player.getZ());
        BlockPos below = BlockPos.containing(player.getX(), player.getBoundingBox().minY - 0.01, player.getZ());
        return blocks.contains(feet) || blocks.contains(below);
    }

    /** Short text above the hotbar (not in the chat). Only for cases without a screen, e.g. no Core nearby. */
    private static void tell(Player player, String message) {
        player.sendOverlayMessage(Component.literal(message));
    }

    /** Shows a problem on the Core screen. */
    private static void fail(ServerPlayer player, AirshipCoreInfoPayload base, String problem) {
        ServerPlayNetworking.send(player, new AirshipCoreInfoPayload(
                base.pos(), false, problem, base.blocks(), base.maxBlocks(), base.balloons(), base.balloonsRequired(),
                base.seatBlocks(), base.cushions(), base.engines(), base.fuelTicks(), base.maxFuelTicks(),
                base.animals()));
    }
}
