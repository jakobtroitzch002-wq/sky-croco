package com.airship;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Stores the remaining fuel (in ticks of thrust) of one engine block. */
public class AirshipEngineBlockEntity extends BlockEntity implements MenuProvider {
    /** Normal tank size in ticks: 1 hour of thrust. */
    public static final int MAX_FUEL = AirshipConfig.get().maxFuelTicks();
    /** Turbo tank size in ticks: it burns 5x as fast, so this lasts 10 minutes of turbo. */
    public static final int MAX_TURBO = AirshipConfig.get().maxTurboTicks();

    private int fuel;
    private int turbo;

    /** Lets the menu read the fuel; the menu never writes it through this. */
    private final ContainerData fuelData = new ContainerData() {
        /** Sent in seconds: the menu data is only 16 bits wide, 1 hour in ticks (72000) would not fit. */
        @Override
        public int get(int index) {
            return (index == 0 ? fuel : turbo) / 20;
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return 2;
        }
    };

    public AirshipEngineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AIRSHIP_ENGINE, pos, state);
    }

    public int getFuel() {
        return fuel;
    }

    public void setFuel(int fuel) {
        this.fuel = Math.max(0, Math.min(MAX_FUEL, fuel));
        setChanged();
    }

    public void addFuel(int ticks) {
        setFuel(fuel + ticks);
    }

    /** Turbo tank: burns {@link AirshipEntity#TURBO_BURN_RATE} times faster and makes the ship much faster. */
    public int getTurbo() {
        return turbo;
    }

    public void setTurbo(int turbo) {
        this.turbo = Math.max(0, Math.min(MAX_TURBO, turbo));
        setChanged();
    }

    public void addTurbo(int ticks) {
        setTurbo(turbo + ticks);
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Luftschiff-Antrieb");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new AirshipEngineMenu(containerId, inventory, this, fuelData);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("fuel", fuel);
        output.putInt("turbo", turbo);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        fuel = input.getIntOr("fuel", 0);
        turbo = input.getIntOr("turbo", 0);
    }
}
