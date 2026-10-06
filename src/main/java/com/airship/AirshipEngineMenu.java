package com.airship;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The engine's screen: two fuel slots plus the player's inventory.
 * <ul>
 *   <li>Slot 0 feeds the normal tank.</li>
 *   <li>Slot 1 feeds the turbo tank: it burns {@link AirshipEntity#TURBO_BURN_RATE} times as fast while the pilot
 *       holds the boost key, and makes the ship much faster.</li>
 * </ul>
 * Fuel put into a slot is turned into running time right away (each fuel gives its own time, as long as the tank
 * has room); whatever is left goes back to the player.
 */
public class AirshipEngineMenu extends AbstractContainerMenu {
    private static final int PLAYER_SLOTS = 36;
    private static final int FUEL_SLOTS = 2;

    private final AirshipEngineBlockEntity engine; // null on the client
    private final ContainerData data;
    private final SimpleContainer fuelContainer;
    private final SimpleContainer turboContainer;

    /** Client side. */
    public AirshipEngineMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, null, new SimpleContainerData(2));
    }

    /** Server side. */
    public AirshipEngineMenu(int containerId, Inventory inventory, AirshipEngineBlockEntity engine, ContainerData data) {
        super(ModMenus.ENGINE, containerId);
        this.engine = engine;
        this.data = data;
        this.fuelContainer = new SimpleContainer(1) {
            @Override
            public void setChanged() {
                super.setChanged();
                AirshipEngineMenu.this.slotsChanged(this);
            }
        };
        this.turboContainer = new SimpleContainer(1) {
            @Override
            public void setChanged() {
                super.setChanged();
                AirshipEngineMenu.this.slotsChanged(this);
            }
        };

        addSlot(new Slot(fuelContainer, 0, AirshipEngineLayout.FUEL_X + 1, AirshipEngineLayout.FUEL_Y + 1) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return isFuel(stack);
            }
        });
        addSlot(new Slot(turboContainer, 0, AirshipEngineLayout.TURBO_X + 1, AirshipEngineLayout.TURBO_Y + 1) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return isFuel(stack);
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + column + row * 9,
                        AirshipEngineLayout.INV_X + 1 + column * 18,
                        AirshipEngineLayout.INV_Y + 1 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column,
                    AirshipEngineLayout.INV_X + 1 + column * 18,
                    AirshipEngineLayout.INV_Y + 1 + 58));
        }
        addDataSlots(data);
    }

    public static boolean isFuel(ItemStack stack) {
        return AirshipFuel.isFuel(stack);
    }

    /** Remaining fuel of the normal tank, in ticks of thrust. */
    public int getFuel() {
        return data.get(0) * 20;
    }

    /** Remaining fuel of the turbo tank (it burns {@link AirshipEntity#TURBO_BURN_RATE} times as fast). */
    public int getTurbo() {
        return data.get(1) * 20;
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (container == fuelContainer) {
            absorbFuel(fuelContainer, false);
        } else if (container == turboContainer) {
            absorbFuel(turboContainer, true);
        }
    }

    private void absorbFuel(SimpleContainer container, boolean turbo) {
        if (engine == null) {
            return;
        }
        ItemStack stack = container.getItem(0);
        // Burn items one by one as long as they fit into the tank; the rest stays in the slot.
        while (!stack.isEmpty()) {
            int burn = AirshipFuel.burnTime(stack);
            int current = turbo ? engine.getTurbo() : engine.getFuel();
            int limit = turbo ? AirshipEngineBlockEntity.MAX_TURBO : AirshipEngineBlockEntity.MAX_FUEL;
            if (burn <= 0 || current + burn > limit) {
                break;
            }
            ItemStack remainder = AirshipFuel.remainder(stack);
            if (turbo) {
                engine.addTurbo(burn);
            } else {
                engine.addFuel(burn);
            }
            stack.shrink(1);
            if (stack.isEmpty() && !remainder.isEmpty()) {
                container.setItem(0, remainder); // e.g. the empty bucket after lava
                break;
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            copy = stack.copy();
            if (index < FUEL_SLOTS) {
                if (!moveItemStackTo(stack, FUEL_SLOTS, FUEL_SLOTS + PLAYER_SLOTS, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (isFuel(stack)) {
                // Shift-click fills the normal tank first, then the turbo tank.
                if (!moveItemStackTo(stack, 0, FUEL_SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        if (engine == null) {
            return true;
        }
        return player.distanceToSqr(
                engine.getBlockPos().getX() + 0.5, engine.getBlockPos().getY() + 0.5, engine.getBlockPos().getZ() + 0.5)
                <= 64.0;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide()) {
            clearContainer(player, fuelContainer);
            clearContainer(player, turboContainer);
        }
    }
}
