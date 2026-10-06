package com.airship;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.nbt.CompoundTag;

public class AirshipBuildBlockEntity extends BlockEntity {
    private BlockState displayState = Blocks.AIR.defaultBlockState();
    private boolean hasCustomTexture;

    public AirshipBuildBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AIRSHIP_BUILD, pos, state);
    }

    public BlockState getDisplayState() {
        return displayState;
    }

    public boolean hasCustomTexture() {
        return hasCustomTexture;
    }

    public void setDisplayState(BlockState displayState) {
        this.displayState = displayState;
        this.hasCustomTexture = !displayState.isAir();
        setChanged();
        if (level != null) {
            level.setBlock(worldPosition, getBlockState().setValue(AirshipBuildBlock.HAS_TEXTURE, hasCustomTexture), Block.UPDATE_ALL);
        }
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level == null) {
            return;
        }
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("has_custom_texture", hasCustomTexture);
        if (hasCustomTexture) {
            output.store("display_block", BlockState.CODEC, displayState);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        hasCustomTexture = input.getBooleanOr("has_custom_texture", false);
        displayState = input.read("display_block", BlockState.CODEC)
                .orElse(Blocks.AIR.defaultBlockState());
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
