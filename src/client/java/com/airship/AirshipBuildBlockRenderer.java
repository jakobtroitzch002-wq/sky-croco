package com.airship;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;

public class AirshipBuildBlockRenderer implements BlockEntityRenderer<
        AirshipBuildBlockEntity,
        AirshipBuildBlockRenderState
        > {
    private static final BlockDisplayContext DISPLAY_CONTEXT = BlockDisplayContext.create();
    private final BlockModelResolver blockModelResolver;

    public AirshipBuildBlockRenderer(BlockEntityRendererProvider.Context context) {
        this.blockModelResolver = context.blockModelResolver();
    }

    @Override
    public AirshipBuildBlockRenderState createRenderState() {
        return new AirshipBuildBlockRenderState();
    }

    @Override
    public void extractRenderState(
            AirshipBuildBlockEntity blockEntity,
            AirshipBuildBlockRenderState state,
            float partialTick,
            net.minecraft.world.phys.Vec3 cameraPosition,
            net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay breakProgress
    ) {
        BlockEntityRenderer.super.extractRenderState(
                blockEntity, state, partialTick, cameraPosition, breakProgress
        );

        state.hasCustomTexture = blockEntity.hasCustomTexture();

        if (blockEntity.getLevel() != null) {
            state.lightCoords = LightCoordsUtil.getLightCoords(
                    blockEntity.getLevel(),
                    blockEntity.getBlockPos()
            );
        }

        if (state.hasCustomTexture) {
            blockModelResolver.update(
                    state.blockModel,
                    blockEntity.getDisplayState(),
                    DISPLAY_CONTEXT
            );
        } else {
            state.blockModel.clear();
        }
    }

    @Override
    public void submit(
            AirshipBuildBlockRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera
    ) {
        if (!state.hasCustomTexture || state.blockModel.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        state.blockModel.submit(
                poseStack,
                collector,
                state.lightCoords,
                OverlayTexture.NO_OVERLAY,
                0
        );

        poseStack.popPose();
    }
}
