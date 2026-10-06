package com.airship;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

public class AirshipBuildBlock extends BaseEntityBlock {
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty HAS_TEXTURE =
            net.minecraft.world.level.block.state.properties.BooleanProperty.create("has_texture");
    private static final String DISPLAY_BLOCK = "display_block";

    public AirshipBuildBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HAS_TEXTURE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HAS_TEXTURE);
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        float destroySpeed = state.getDestroySpeed(level, pos);
        if (destroySpeed == -1.0F) {
            return 0.0F;
        }

        float miningSpeed = player.getDestroySpeed(state);
        ItemStack held = player.getMainHandItem();

        // 26.3 moved vanilla hoes to the data-driven Tool component. The normal
        // block-tag path is kept as well, but use the hoe's component speed as a
        // fallback so this block still gets the proper tier speed if the tag is
        // not present on an already-running world/resource reload.
        if (held.is(ItemTags.HOES)) {
            Tool tool = held.get(DataComponents.TOOL);
            if (tool != null) {
                float componentSpeed = tool.rules().stream()
                        .flatMap(rule -> rule.speed().stream())
                        .max(Float::compare)
                        .orElse(tool.defaultMiningSpeed());
                miningSpeed = Math.max(miningSpeed, componentSpeed);
            }
        }

        int modifier = held.is(ItemTags.HOES) || player.hasCorrectToolForDrops(state) ? 30 : 100;
        return miningSpeed / destroySpeed / (float) modifier;
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = new ArrayList<>();
        BlockEntity blockEntity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);

        if (blockEntity instanceof AirshipBuildBlockEntity buildEntity && buildEntity.hasCustomTexture()) {
            BlockState displayState = buildEntity.getDisplayState();
            if (!displayState.isAir()) {
                drops.add(new ItemStack(displayState.getBlock()));
            }
        }

        // The build block itself is always dropped empty. Its contents are never
        // serialized into the dropped build block anymore.
        drops.add(new ItemStack(this));
        return drops;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
        super.setPlacedBy(level, pos, state, by, itemStack);
        if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof AirshipBuildBlockEntity buildEntity)) return;
        CustomData customData = itemStack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return;
        var tag = customData.copyTag().get(DISPLAY_BLOCK);
        if (tag == null) return;
        Optional<BlockState> displayState = BlockState.CODEC.parse(NbtOps.INSTANCE, tag).result();
        displayState.filter(value -> !value.isAir() && value.isSolidRender()).ifPresent(buildEntity::setDisplayState);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack itemStack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!(itemStack.getItem() instanceof BlockItem blockItem)) return InteractionResult.PASS;
        if (ModBlocks.isAirshipBuildBlock(blockItem.getBlock())) return InteractionResult.FAIL;
        BlockState newDisplayState = blockItem.getBlock().defaultBlockState();
        if (!newDisplayState.isSolidRender()) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof AirshipBuildBlockEntity buildEntity)) return InteractionResult.PASS;

        boolean hadOldTexture = buildEntity.hasCustomTexture();
        BlockState oldDisplayState = buildEntity.getDisplayState();
        buildEntity.setDisplayState(newDisplayState);

        if (!player.isCreative()) itemStack.shrink(1);
        if (hadOldTexture && !oldDisplayState.isAir()) {
            popResource(level, pos, new ItemStack(oldDisplayState.getBlock()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        return InteractionResult.PASS;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AirshipBuildBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }
}
