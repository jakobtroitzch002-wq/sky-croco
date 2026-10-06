package com.airship;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Holds what this Core knows about its ship (all positions are relative to the Core):
 * <ul>
 *   <li><b>registered</b>: the blocks of the ship, saved when the Core is checked (right-click / "Neu prüfen") and
 *       after every landing. Taking off uses exactly this set, so blocks placed later are not part of the ship
 *       until the Core is checked again.</li>
 *   <li><b>terrain</b>: the terrain blocks that touched the ship when it last landed. The structure scan skips
 *       them, so a ship that landed against the ground or a wall does not include the terrain.</li>
 * </ul>
 */
public class AirshipCoreBlockEntity extends BlockEntity {
    private List<BlockPos> terrain = List.of();
    private List<BlockPos> registered = List.of();

    public AirshipCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AIRSHIP_CORE, pos, state);
    }

    public List<BlockPos> getRegistered() {
        return registered;
    }

    public void setRegistered(List<BlockPos> registered) {
        this.registered = List.copyOf(registered);
        setChanged();
    }

    public List<BlockPos> getTerrain() {
        return terrain;
    }

    public void setTerrain(List<BlockPos> terrain) {
        this.terrain = List.copyOf(terrain);
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("Terrain", BlockPos.CODEC.listOf(), new ArrayList<>(terrain));
        output.store("Registered", BlockPos.CODEC.listOf(), new ArrayList<>(registered));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        terrain = input.read("Terrain", BlockPos.CODEC.listOf()).map(List::copyOf).orElse(List.of());
        registered = input.read("Registered", BlockPos.CODEC.listOf()).map(List::copyOf).orElse(List.of());
    }
}
