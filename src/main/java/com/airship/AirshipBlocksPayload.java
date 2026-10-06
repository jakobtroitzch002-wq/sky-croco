package com.airship;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Server -> client: the blocks of one airship entity, sent when a player starts tracking it. */
public record AirshipBlocksPayload(
        int entityId, List<AirshipClientCell> cells, List<AirshipClientCushion> cushions, List<Vec3> seats)
        implements CustomPacketPayload {
    public static final Type<AirshipBlocksPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(AirshipMod.MOD_ID, "airship_blocks")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, AirshipBlocksPayload> CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, AirshipBlocksPayload>() {
                @Override
                public AirshipBlocksPayload decode(RegistryFriendlyByteBuf buf) {
                    int entityId = buf.readVarInt();
                    int size = buf.readVarInt();
                    List<AirshipClientCell> cells = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        int x = buf.readShort();
                        int y = buf.readShort();
                        int z = buf.readShort();
                        int stateId = buf.readVarInt();
                        cells.add(new AirshipClientCell(new BlockPos(x, y, z), Block.stateById(stateId)));
                    }
                    int cushionCount = buf.readVarInt();
                    List<AirshipClientCushion> cushions = new ArrayList<>(cushionCount);
                    for (int i = 0; i < cushionCount; i++) {
                        double cx = buf.readFloat();
                        double cy = buf.readFloat();
                        double cz = buf.readFloat();
                        float yaw = buf.readFloat();
                        String color = buf.readUtf();
                        cushions.add(new AirshipClientCushion(cx, cy, cz, yaw, color));
                    }
                    int seatCount = buf.readVarInt();
                    List<Vec3> seats = new ArrayList<>(seatCount);
                    for (int i = 0; i < seatCount; i++) {
                        seats.add(new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat()));
                    }
                    return new AirshipBlocksPayload(entityId, cells, cushions, seats);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, AirshipBlocksPayload payload) {
                    buf.writeVarInt(payload.entityId());
                    buf.writeVarInt(payload.cells().size());
                    for (AirshipClientCell cell : payload.cells()) {
                        buf.writeShort(cell.pos().getX());
                        buf.writeShort(cell.pos().getY());
                        buf.writeShort(cell.pos().getZ());
                        buf.writeVarInt(Block.getId(cell.state()));
                    }
                    buf.writeVarInt(payload.cushions().size());
                    for (AirshipClientCushion cushion : payload.cushions()) {
                        buf.writeFloat((float) cushion.x());
                        buf.writeFloat((float) cushion.y());
                        buf.writeFloat((float) cushion.z());
                        buf.writeFloat(cushion.yaw());
                        buf.writeUtf(cushion.color());
                    }
                    buf.writeVarInt(payload.seats().size());
                    for (Vec3 seat : payload.seats()) {
                        buf.writeFloat((float) seat.x);
                        buf.writeFloat((float) seat.y);
                        buf.writeFloat((float) seat.z);
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
