package com.airship;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * An assembled airship. All blocks of the ship live inside this entity (relative to the former
 * Core position). The ship stays assembled until a player lands it (sneak + right-click),
 * hovers in place when nobody is flying it, and turns to face the way the pilot looks.
 */
public class AirshipEntity extends Entity {
    // --- flight tuning (blocks per tick) ---
    /** Top speeds, in blocks per tick (the numbers are given in blocks per second): no fuel, fuel, turbo. */
    public static final double BASE_SPEED = AirshipConfig.get().speedNoFuel / 20.0;
    public static final double FUEL_SPEED = AirshipConfig.get().speedFuel / 20.0;
    public static final double TURBO_SPEED = AirshipConfig.get().speedTurbo / 20.0;
    /** The turbo tank burns this many times as fast as the normal one ... */
    public static final int TURBO_BURN_RATE = AirshipConfig.get().turboBurnRate;
    /** ... and, while it burns, the ship also accelerates this many times as fast. */
    public static final double TURBO_ACCEL_MULTIPLIER = 3.0;
    private static final double VERTICAL_SPEED = 0.08;
    private static final double HORIZONTAL_ACCEL = 0.012;
    private static final double VERTICAL_ACCEL = 0.02;
    /** Maximum turn rate in degrees per tick. */
    private static final float MAX_TURN = 3.5F;

    private List<AirshipCell> cells = List.of();
    private final Map<BlockPos, BlockState> stateByPos = new HashMap<>();
    private final Map<BlockPos, Integer> engineFuel = new HashMap<>();
    private final Map<BlockPos, Integer> turboFuel = new HashMap<>();
    private Vec3 leashAnchor;
    private List<Vec3> seats = List.of();
    private List<AirshipCushion> cushions = List.of();
    private List<AirshipClientCushion> clientCushions = List.of();
    private BlockPos primarySeat;
    private int landCooldown;
    private int leadRecoveryTicks;
    private int minCellY;
    private int maxCellY;
    private boolean landed;
    private BlockPos[] surface;
    private int[] surfaceMask;
    private List<AirshipClientCell> exposed;
    private int cellVersion;

    // server only
    private Vec3 velocity = Vec3.ZERO;
    // seat assignment: passenger entity id -> seat index (0 = control seat); and the ship's "front"
    private final Map<Integer, Integer> seatByPassenger = new HashMap<>();
    private float controlYaw;

    // client only: buffered snapshots from the server, played back a little behind real time
    private record Snapshot(long tick, Vec3 pos, float yaw) {}

    private static final double INTERPOLATION_DELAY_TICKS = 2.0;
    private final List<Snapshot> snapshots = new ArrayList<>();
    private long latestTick;
    private double renderTick;
    private boolean snapshotsReady;

    // server only: what the clients were told last
    private Vec3 lastSentPos;
    private float lastSentYaw;
    private long lastSentTick = Long.MIN_VALUE / 2;

    // client only: positions of the last two ticks, used to render smoothly between them
    private Vec3 prevPos;
    private Vec3 curPos;
    private float prevYaw;
    private float curYaw;
    private boolean smoothReady;

    public AirshipEntity(EntityType<? extends AirshipEntity> type, Level level) {
        super(type, level);
    }

    // ------------------------------------------------------------------ data

    public void setCells(List<AirshipCell> newCells) {
        this.cells = List.copyOf(newCells);
        rebuild();
    }

    /** Client side: replace the block data with what the server sent. */
    public void setClientCells(List<AirshipClientCell> clientCells) {
        List<AirshipCell> converted = new ArrayList<>(clientCells.size());
        for (AirshipClientCell cell : clientCells) {
            converted.add(new AirshipCell(cell.pos(), cell.state(), Optional.empty(), 0, 0));
        }
        setCells(converted);
    }

    private void rebuild() {
        stateByPos.clear();
        engineFuel.clear();
        turboFuel.clear();
        for (AirshipCell cell : cells) {
            BlockPos pos = cell.pos().immutable();
            stateByPos.put(pos, cell.state());
            if (cell.state().is(ModBlocks.AIRSHIP_ENGINE)) {
                engineFuel.put(pos, cell.fuel());
                turboFuel.put(pos, cell.turbo());
            }
        }

        // Height of the ship (lowest and highest block), used to keep it inside the world.
        this.minCellY = 0;
        this.maxCellY = 0;
        boolean firstCell = true;
        for (BlockPos pos : stateByPos.keySet()) {
            this.minCellY = firstCell ? pos.getY() : Math.min(this.minCellY, pos.getY());
            this.maxCellY = firstCell ? pos.getY() : Math.max(this.maxCellY, pos.getY());
            firstCell = false;
        }

        // The Airship Seat faces the front of the ship.
        this.controlYaw = 0.0F;
        for (BlockState state : stateByPos.values()) {
            if (state.is(ModBlocks.AIRSHIP_SEAT)) {
                this.controlYaw = state.getValue(AirshipSeatBlock.FACING).toYRot();
                break;
            }
        }
        // Leashed mobs hang on the ship; the rope is drawn from the fence closest to the Core.
        this.leashAnchor = null;
        double bestFence = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, BlockState> entry : stateByPos.entrySet()) {
            if (entry.getValue().is(BlockTags.FENCES)) {
                BlockPos p = entry.getKey();
                double distance = (double) p.getX() * p.getX() + (double) p.getY() * p.getY()
                        + (double) p.getZ() * p.getZ();
                if (distance < bestFence) {
                    bestFence = distance;
                    this.leashAnchor = new Vec3(p.getX(), p.getY() + 0.75, p.getZ());
                }
            }
        }
        computeSeats();

        this.surface = null;
        this.surfaceMask = null;
        this.exposed = null;
        this.cellVersion++;
    }

    /** Cushions riding along with the ship (server side; they are re-created when the ship lands). */
    public void setCushions(List<AirshipCushion> newCushions) {
        this.cushions = List.copyOf(newCushions);
        List<AirshipClientCushion> forClients = new ArrayList<>(newCushions.size());
        for (AirshipCushion cushion : newCushions) {
            forClients.add(new AirshipClientCushion(
                    cushion.x(), cushion.y(), cushion.z(), cushion.yaw(), AirshipCushions.colorOf(cushion.data())));
        }
        this.clientCushions = List.copyOf(forClients);
        computeSeats();
        this.cellVersion++;
    }

    /** Sets the order of the seats: the n-th passenger sits on the n-th seat. */
    public void setSeatOrder(List<Vec3> order) {
        this.seats = List.copyOf(order);
    }

    /** Fixed seat for each passenger (passenger entity id -> seat index). */
    public void setSeatAssignment(Map<Integer, Integer> assignment) {
        seatByPassenger.clear();
        seatByPassenger.putAll(assignment);
    }

    public AirshipSeatMapPayload seatMapPayload() {
        List<Integer> ids = new ArrayList<>(seatByPassenger.keySet());
        List<Integer> indices = new ArrayList<>();
        for (int id : ids) {
            indices.add(seatByPassenger.get(id));
        }
        return new AirshipSeatMapPayload(getId(), ids, indices);
    }

    public List<Vec3> getSeats() {
        return seats;
    }

    public List<AirshipCushion> getCushions() {
        return cushions;
    }

    public List<AirshipClientCushion> getClientCushions() {
        return clientCushions;
    }

    /** Client side: apply everything the server sent about this ship. */
    public void setClientData(AirshipBlocksPayload payload) {
        setClientCells(payload.cells());
        this.clientCushions = List.copyOf(payload.cushions());
        computeSeats();
        if (!payload.seats().isEmpty()) {
            setSeatOrder(payload.seats());
        }
        this.cellVersion++;
    }

    /** For a moment after takeoff, dropped leads nearby go back to the pilot (their leashes were released). */
    public void recoverLeads() {
        this.leadRecoveryTicks = 5;
    }

    /** The seat the player who started the ship sits on (it becomes the first seat). */
    public void setPrimarySeat(BlockPos relativeSeat) {
        this.primarySeat = relativeSeat.immutable();
        computeSeats();
    }

    private void computeSeats() {
        List<BlockPos> seatCells = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : stateByPos.entrySet()) {
            if (entry.getValue().is(ModBlocks.AIRSHIP_SEAT)) {
                seatCells.add(entry.getKey());
            }
        }
        // The primary seat first, then the seats closest to the Core.
        seatCells.sort(Comparator
                .comparingInt((BlockPos p) -> p.equals(primarySeat) ? 0 : 1)
                .thenComparingDouble(p -> (double) p.getX() * p.getX() + (double) p.getY() * p.getY()
                        + (double) p.getZ() * p.getZ())
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));

        List<Vec3> found = new ArrayList<>();
        for (BlockPos p : seatCells) {
            found.add(new Vec3(p.getX(), p.getY() + AirshipSeatBlock.SEAT_HEIGHT, p.getZ()));
        }
        // Cushions are seats too, after the seat blocks.
        List<Vec3> cushionSeats = new ArrayList<>();
        for (AirshipClientCushion cushion : clientCushions) {
            cushionSeats.add(new Vec3(cushion.x(), cushion.y() + AirshipCushions.SEAT_HEIGHT, cushion.z()));
        }
        cushionSeats.sort(Comparator.comparingDouble(Vec3::lengthSqr));
        found.addAll(cushionSeats);
        this.seats = List.copyOf(found);
    }

    public List<AirshipCell> getCells() {
        return cells;
    }

    /** All cells with the current engine fuel merged in (used for saving and landing). */
    public List<AirshipCell> getCellsWithFuel() {
        List<AirshipCell> result = new ArrayList<>(cells.size());
        for (AirshipCell cell : cells) {
            result.add(new AirshipCell(
                    cell.pos(), cell.state(), cell.blockEntity(),
                    engineFuel.getOrDefault(cell.pos(), 0),
                    turboFuel.getOrDefault(cell.pos(), 0)));
        }
        return result;
    }

    public List<AirshipClientCell> clientCells() {
        List<AirshipClientCell> result = new ArrayList<>(cells.size());
        for (AirshipCell cell : cells) {
            result.add(new AirshipClientCell(cell.pos(), cell.state()));
        }
        return result;
    }

    public int getCellVersion() {
        return cellVersion;
    }

    /** Cells that can be seen from outside (used by the renderer to skip hidden interior blocks). */
    public List<AirshipClientCell> getExposedCells() {
        if (exposed == null) {
            List<AirshipClientCell> result = new ArrayList<>();
            for (Map.Entry<BlockPos, BlockState> entry : stateByPos.entrySet()) {
                BlockPos pos = entry.getKey();
                boolean visible = false;
                for (Direction direction : Direction.values()) {
                    BlockState neighbour = stateByPos.get(pos.relative(direction));
                    if (neighbour == null || !neighbour.isSolidRender()) {
                        visible = true;
                        break;
                    }
                }
                if (visible) {
                    result.add(new AirshipClientCell(pos, entry.getValue()));
                }
            }
            exposed = result;
        }
        return exposed;
    }

    private void buildSurface() {
        List<BlockPos> cellsOut = new ArrayList<>();
        List<Integer> masksOut = new ArrayList<>();
        for (BlockPos pos : stateByPos.keySet()) {
            int mask = 0;
            for (Direction d : Direction.values()) {
                if (!stateByPos.containsKey(pos.relative(d))) {
                    mask |= 1 << d.ordinal();
                }
            }
            if (mask != 0) {
                cellsOut.add(pos);
                masksOut.add(mask);
            }
        }
        surface = cellsOut.toArray(new BlockPos[0]);
        surfaceMask = new int[masksOut.size()];
        for (int i = 0; i < surfaceMask.length; i++) {
            surfaceMask[i] = masksOut.get(i);
        }
    }

    /** Cells with at least one exposed face (relative to the Core). */
    public BlockPos[] surfaceCells() {
        if (surface == null) {
            buildSurface();
        }
        return surface;
    }

    /** For each surface cell: bit (1 << Direction.ordinal()) is set if that local face is exposed. */
    public int[] surfaceMasks() {
        if (surfaceMask == null) {
            buildSurface();
        }
        return surfaceMask;
    }

    public int getFuel(BlockPos relativePos) {
        return engineFuel.getOrDefault(relativePos, 0);
    }

    // ------------------------------------------------------------------ entity plumbing

    @Override
    public void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public void readAdditionalSaveData(ValueInput input) {
        input.read("Cells", AirshipCell.CODEC.listOf()).ifPresent(this::setCells);
        input.read("Cushions", AirshipCushion.CODEC.listOf()).ifPresent(this::setCushions);
    }

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        output.store("Cells", AirshipCell.CODEC.listOf(), getCellsWithFuel());
        output.store("Cushions", AirshipCushion.CODEC.listOf(), new ArrayList<>(cushions));
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    // ------------------------------------------------------------------ seats

    /** The point the ship turns around: the first seat (the pilot's), so the pilot does not swing around. */
    public Vec3 getPivot() {
        if (seats.isEmpty()) {
            return Vec3.ZERO;
        }
        Vec3 seat = seats.get(0);
        return new Vec3(seat.x, 0.0, seat.z);
    }

    /** A point of the ship (relative to its origin) rotated by the ship's yaw, in degrees. */
    public static Vec3 rotateLocal(Vec3 local, float yawDegrees) {
        return local.yRot((float) -Math.toRadians(yawDegrees));
    }

    /** Called when the ship was landed on purpose, so removing the entity afterwards is fine. */
    public void markLanded() {
        this.landed = true;
    }

    /**
     * The ship's blocks only exist inside this entity. If it is removed in any way other than landing (for
     * example with /kill), it tries to land first instead of destroying everything on board.
     */
    @Override
    public void remove(RemovalReason reason) {
        if (!landed && !cells.isEmpty() && level() instanceof ServerLevel serverLevel
                && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED)) {
            landed = true;
            if (AirshipAssembler.disassemble(serverLevel, this)) {
                return; // the landing removed the ship itself
            }
        }
        super.remove(reason);
    }

    /** Where the ropes of leashed mobs are attached: one of the ship's fences (or above the Core). */
    @Override
    public Vec3 getRopeHoldPosition(float partialTick) {
        boolean client = level().isClientSide();
        Vec3 base = client ? getSmoothPos(partialTick) : position();
        float yaw = client ? getSmoothYaw(partialTick) : getYRot();
        Vec3 local = leashAnchor != null ? leashAnchor : new Vec3(0.0, 1.0, 0.0);
        return base.add(rotateLocal(local, yaw));
    }

    private Vec3 toWorldOffset(Vec3 local) {
        return local.yRot((float) -Math.toRadians(getYRot()));
    }

    @Override
    public boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < seats.size();
    }

    @Override
    public Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float partialTick) {
        if (seats.isEmpty()) {
            return Vec3.ZERO;
        }
        Integer assigned = seatByPassenger.get(passenger.getId());
        int index = assigned != null ? assigned : Math.max(0, getPassengers().indexOf(passenger));
        return toWorldOffset(seats.get(index % seats.size()));
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        if (seats.isEmpty()) {
            return super.getDismountLocationForPassenger(passenger);
        }
        return landingSpot(passenger);
    }

    /**
     * Where a passenger is put down when the ship lands: its own spot on the ship, in the block grid the ship
     * snaps to. Used for living passengers when they get off and for boats, which are placed there directly.
     */
    public Vec3 landingSpot(Entity passenger) {
        // The ship lands as soon as the pilot leaves, snapped to the block grid and to 90 degrees.
        // Put the passenger on its spot of the landed ship (a player on top of the seat block).
        BlockPos anchor = AirshipAssembler.landingAnchor(this);
        Vec3 anchorPos = new Vec3(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
        double snappedRad = -Math.toRadians(AirshipAssembler.snappedYaw(getYRot()));

        Integer assigned = seatByPassenger.get(passenger.getId());
        if (assigned != null && assigned >= 0 && assigned < seats.size()) {
            return anchorPos.add(seats.get(assigned).yRot((float) snappedRad));
        }
        Vec3 best = anchorPos.add(seats.get(0).yRot((float) snappedRad));
        double bestDistance = Double.MAX_VALUE;
        for (Vec3 seat : seats) {
            double distance = position().add(toWorldOffset(seat)).distanceToSqr(passenger.position());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = anchorPos.add(seat.yRot((float) snappedRad));
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ smooth rendering (client)

    public Vec3 getSmoothPos(float partialTick) {
        if (!smoothReady) {
            return position();
        }
        return new Vec3(
                Mth.lerp(partialTick, prevPos.x, curPos.x),
                Mth.lerp(partialTick, prevPos.y, curPos.y),
                Mth.lerp(partialTick, prevPos.z, curPos.z));
    }

    public float getSmoothYaw(float partialTick) {
        if (!smoothReady) {
            return getYRot();
        }
        return prevYaw + Mth.wrapDegrees(curYaw - prevYaw) * partialTick;
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel serverLevel) {
            serverTick(serverLevel);
        } else {
            clientTick();
        }
    }

    private void clientTick() {
        if (cells.isEmpty()) {
            AirshipBlocksPayload pending = AirshipPendingCells.take(getId());
            if (pending != null) {
                setClientData(pending);
            }
        }

        applySnapshots();

        // The server sends the position 20 times per second. Remember the last two positions so the
        // renderer can blend between them instead of jumping.
        if (!smoothReady) {
            prevPos = position();
            curPos = position();
            prevYaw = getYRot();
            curYaw = getYRot();
            smoothReady = true;
        } else {
            prevPos = curPos;
            prevYaw = curYaw;
            curPos = position();
            curYaw = getYRot();
        }
    }

    /** Client side: a new snapshot of the ship's position and rotation arrived from the server. */
    public void receiveSnapshot(AirshipStatePayload payload) {
        Snapshot snapshot = new Snapshot(payload.tick(), new Vec3(payload.x(), payload.y(), payload.z()), payload.yaw());
        if (!snapshots.isEmpty()) {
            Snapshot last = snapshots.get(snapshots.size() - 1);
            if (snapshot.tick() < last.tick()) {
                return; // older than what we already have
            }
            if (snapshot.tick() == last.tick()) {
                snapshots.set(snapshots.size() - 1, snapshot);
                return;
            }
        }
        snapshots.add(snapshot);
        latestTick = snapshot.tick();
        if (!snapshotsReady) {
            snapshotsReady = true;
            renderTick = latestTick - INTERPOLATION_DELAY_TICKS;
        }
        while (snapshots.size() > 16) {
            snapshots.remove(0);
        }
    }

    /**
     * Moves the client ship along the buffered snapshots at a steady pace (one server tick per client tick),
     * a couple of ticks behind the latest one. Uneven packet arrival is absorbed by that buffer.
     */
    private void applySnapshots() {
        if (!snapshotsReady || snapshots.isEmpty()) {
            return;
        }
        renderTick = Mth.clamp(renderTick + 1.0, latestTick - 3.0, (double) latestTick);

        Snapshot first = snapshots.get(0);
        Snapshot last = snapshots.get(snapshots.size() - 1);
        Vec3 pos = last.pos();
        float yaw = last.yaw();
        if (renderTick <= first.tick()) {
            pos = first.pos();
            yaw = first.yaw();
        } else if (renderTick < last.tick()) {
            for (int i = 0; i < snapshots.size() - 1; i++) {
                Snapshot a = snapshots.get(i);
                Snapshot b = snapshots.get(i + 1);
                if (renderTick >= a.tick() && renderTick <= b.tick()) {
                    double f = (renderTick - a.tick()) / (double) (b.tick() - a.tick());
                    pos = new Vec3(
                            a.pos().x + (b.pos().x - a.pos().x) * f,
                            a.pos().y + (b.pos().y - a.pos().y) * f,
                            a.pos().z + (b.pos().z - a.pos().z) * f);
                    yaw = a.yaw() + Mth.wrapDegrees(b.yaw() - a.yaw()) * (float) f;
                    break;
                }
            }
        }

        while (snapshots.size() > 2 && snapshots.get(1).tick() <= renderTick) {
            snapshots.remove(0);
        }
        setPos(pos.x, pos.y, pos.z);
        setYRot(yaw);
    }

    /** Server side: the current state, as sent to clients. */
    public AirshipStatePayload statePayload(long gameTick) {
        Vec3 pos = position();
        return new AirshipStatePayload(getId(), gameTick, pos.x, pos.y, pos.z, getYRot());
    }

    /** Server side: tell everybody who sees the ship where it is (only when it changed, plus a heartbeat). */
    private void broadcastState(ServerLevel level) {
        Vec3 pos = position();
        float yaw = getYRot();
        long now = level.getGameTime();
        if (pos.equals(lastSentPos) && yaw == lastSentYaw && now - lastSentTick < 20) {
            return;
        }
        lastSentPos = pos;
        lastSentYaw = yaw;
        lastSentTick = now;
        AirshipStatePayload payload = statePayload(now);
        for (ServerPlayer tracking : PlayerLookup.tracking(this)) {
            ServerPlayNetworking.send(tracking, payload);
        }
    }

    /** The pilot is whoever sits on the control seat (seat 0). Cushion sitters are only passengers. */
    private ServerPlayer findPilot() {
        List<Entity> passengers = getPassengers();
        for (int i = 0; i < passengers.size(); i++) {
            if (!(passengers.get(i) instanceof ServerPlayer player)) {
                continue;
            }
            Integer seat = seatByPassenger.get(player.getId());
            // Without a seat assignment (e.g. after loading the world) the first passenger is the pilot.
            if (seat != null ? seat == 0 : (seatByPassenger.isEmpty() && i == 0)) {
                return player;
            }
        }
        return null;
    }

    private void serverTick(ServerLevel level) {
        if (cells.isEmpty()) {
            discard();
            return;
        }

        // Without a pilot on the control seat the ship turns back into solid blocks
        // (passengers on cushions are put down on their cushions).
        ServerPlayer pilot = findPilot();
        if (pilot == null) {
            velocity = Vec3.ZERO;
            if (landCooldown > 0) {
                landCooldown--;
            } else if (!AirshipAssembler.disassemble(level, this)) {
                landCooldown = 20;
                if (level.getGameTime() % 100 == 0) {
                    for (ServerPlayer nearby : level.getEntitiesOfClass(
                            ServerPlayer.class, getBoundingBox().inflate(32.0))) {
                        nearby.sendOverlayMessage(Component.literal(
                                "Luftschiff kann hier nicht landen: zu wenig Platz"));
                    }
                }
            }
            return;
        }

        if (leadRecoveryTicks > 0) {
            leadRecoveryTicks--;
            for (ItemEntity dropped : level.getEntitiesOfClass(
                    ItemEntity.class, getBoundingBox().inflate(24.0), item -> item.getItem().is(Items.LEAD))) {
                ItemStack stack = dropped.getItem();
                pilot.getInventory().add(stack);
                if (stack.isEmpty()) {
                    dropped.discard();
                }
            }
        }

        int flags = AirshipControls.flags(pilot, level.getGameTime());

        // --- turn so that the front of the ship (the way the Airship Seat faces) points where the pilot looks ---
        float target = pilot.getYRot() - controlYaw;
        float diff = Mth.wrapDegrees(target - getYRot());
        float step = Mth.clamp(diff, -MAX_TURN, MAX_TURN);
        if (Math.abs(step) > 1.0E-3F) {
            float newYaw = Mth.wrapDegrees(getYRot() + step);
            // Turn around the pilot's seat, not the Core: otherwise the pilot's camera swings around
            // the Core on a circle every time the ship turns, which looks like violent shaking.
            Vec3 pivot = getPivot();
            Vec3 shift = rotateLocal(pivot, getYRot()).subtract(rotateLocal(pivot, newYaw));
            Vec3 newPos = position().add(shift);
            if (!AirshipCollision.collides(level, this, newPos, newYaw)) {
                setPos(newPos.x, newPos.y, newPos.z);
                setYRot(newYaw);
            }
        }

        // --- horizontal input, relative to the pilot's look direction ---
        float lookYaw = pilot.getYRot();
        double yawRad = Math.toRadians(lookYaw);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);

        double inputX = 0.0;
        double inputZ = 0.0;
        if ((flags & AirshipControlPayload.FORWARD) != 0) {
            inputX += forwardX;
            inputZ += forwardZ;
        }
        if ((flags & AirshipControlPayload.BACKWARD) != 0) {
            inputX -= forwardX;
            inputZ -= forwardZ;
        }
        if ((flags & AirshipControlPayload.RIGHT) != 0) {
            inputX += rightX;
            inputZ += rightZ;
        }
        if ((flags & AirshipControlPayload.LEFT) != 0) {
            inputX -= rightX;
            inputZ -= rightZ;
        }
        double length = Math.hypot(inputX, inputZ);
        boolean thrusting = length > 1.0E-4;

        // --- engines: each fuelled engine makes the ship faster and burns fuel while thrusting.
        // While the boost key is held, an engine with turbo fuel burns that instead (5x as fast) for a big
        // speed boost; engines without turbo fuel keep using their normal tank. ---
        boolean wantBoost = thrusting && (flags & AirshipControlPayload.BOOST) != 0;
        int normalActive = 0;
        int turboActive = 0;
        for (Map.Entry<BlockPos, Integer> entry : engineFuel.entrySet()) {
            if (wantBoost && turboFuel.getOrDefault(entry.getKey(), 0) > 0) {
                turboActive++;
            } else if (entry.getValue() > 0) {
                normalActive++;
            }
        }
        int activeEngines = normalActive + turboActive;
        double turboShare = engineFuel.isEmpty() ? 0.0 : (double) turboActive / engineFuel.size();
        // Top speed: BASE with no fuel, FUEL with fuel, TURBO with turbo; engines count by their share.
        int engineCount = engineFuel.size();
        double fuelShare = engineCount == 0 ? 0.0 : (double) activeEngines / engineCount;
        double maxSpeed = BASE_SPEED + (FUEL_SPEED - BASE_SPEED) * fuelShare + (TURBO_SPEED - FUEL_SPEED) * turboShare;
        double accel = HORIZONTAL_ACCEL * (1.0 + (TURBO_ACCEL_MULTIPLIER - 1.0) * turboShare);
        if (thrusting) {
            for (Map.Entry<BlockPos, Integer> entry : engineFuel.entrySet()) {
                int turbo = turboFuel.getOrDefault(entry.getKey(), 0);
                if (wantBoost && turbo > 0) {
                    turboFuel.put(entry.getKey(), Math.max(0, turbo - TURBO_BURN_RATE));
                } else if (entry.getValue() > 0) {
                    entry.setValue(entry.getValue() - 1);
                }
            }
        }

        double targetX = thrusting ? inputX / length * maxSpeed : 0.0;
        double targetZ = thrusting ? inputZ / length * maxSpeed : 0.0;

        // --- vertical: balloons carry the ship, so with no input it simply hovers ---
        double targetY = 0.0;
        boolean up = (flags & AirshipControlPayload.UP) != 0;
        boolean down = (flags & AirshipControlPayload.DOWN) != 0;
        if (up) {
            targetY = VERTICAL_SPEED; // ascending always wins if both keys are held
        } else if (down) {
            targetY = -VERTICAL_SPEED;
        }

        velocity = new Vec3(
                approach(velocity.x, targetX, accel),
                approach(velocity.y, targetY, VERTICAL_ACCEL),
                approach(velocity.z, targetZ, accel)
        );

        // --- move with collision ---
        if (velocity.lengthSqr() > 1.0E-10) {
            AirshipCollision.Result result = AirshipCollision.move(level, this, position(), velocity);
            velocity = new Vec3(
                    result.hitX() ? 0.0 : velocity.x,
                    result.hitY() ? 0.0 : velocity.y,
                    result.hitZ() ? 0.0 : velocity.z);
            Vec3 newPos = result.pos();
            double lowest = level.getMinY() + 1 - minCellY;
            double highest = Math.max(lowest, level.getMaxY() - 1 - maxCellY);
            double clampedY = Mth.clamp(newPos.y, lowest, highest);
            if (clampedY != newPos.y) {
                newPos = new Vec3(newPos.x, clampedY, newPos.z);
                velocity = new Vec3(velocity.x, 0.0, velocity.z);
            }
            setPos(newPos.x, newPos.y, newPos.z);
        }
        setDeltaMovement(velocity);
        broadcastState(level);

        // Flight display for the pilot (a few times per second is plenty).
        if (level.getGameTime() % 5 == 0) {
            int fuelMax = 0;
            int turboMax = 0;
            for (int value : engineFuel.values()) {
                fuelMax = Math.max(fuelMax, value);
            }
            for (int value : turboFuel.values()) {
                turboMax = Math.max(turboMax, value);
            }
            ServerPlayNetworking.send(pilot, new AirshipHudPayload(
                    fuelMax, turboMax, engineFuel.size(), activeEngines,
                    (float) (velocity.length() * 20.0), turboActive > 0));
        }
    }

    private static double approach(double current, double target, double step) {
        return current + Mth.clamp(target - current, -step, step);
    }
}
