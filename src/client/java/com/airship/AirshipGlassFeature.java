package com.airship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.fabricmc.fabric.api.client.renderer.v1.render.ChunkSectionLayerHelper;
import net.fabricmc.fabric.api.client.rendering.v1.FeatureRendererRegistry;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;

/**
 * Draws the see-through blocks of a ship (glass, ice, ...) in the "after terrain" phase.
 * <p>
 * Minecraft draws entities, and so the ship, before the see-through terrain (water). Glass drawn together with the
 * ship also writes depth, so the water behind it was cut away. In the after-terrain phase the water is already
 * drawn, so the glass is simply blended over it.
 */
final class AirshipGlassFeature {
    static final FeatureRendererType<GlassSubmit> TYPE = FeatureRendererType.create("airship_glass");
    private static final Direction[] DIRECTIONS = Direction.values();

    private AirshipGlassFeature() {}

    static void register() {
        FeatureRendererRegistry.register(TYPE, Renderer::new);
    }

    /** One glass block: its pose (relative to the camera), its model parts and the light to draw it with. */
    record GlassSubmit(PoseStack.Pose pose, List<BlockStateModelPart> parts, int light) implements SubmitNode {
        @Override
        public FeatureRendererType<? extends SubmitNode> featureType() {
            return TYPE;
        }
    }

    private static class Renderer extends RenderTypeFeatureRenderer<GlassSubmit> {
        private final QuadInstance quadInstance = new QuadInstance();

        @Override
        protected void buildGroup(FeatureFrameContext context, List<GlassSubmit> submits) {
            for (GlassSubmit submit : submits) {
                quadInstance.setLightCoords(submit.light());
                quadInstance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
                for (BlockStateModelPart part : submit.parts()) {
                    for (Direction direction : DIRECTIONS) {
                        quadInstance.setColor(shadeColor(direction));
                        putQuads(part.getQuads(direction), submit.pose());
                    }
                    quadInstance.setColor(shadeColor(null));
                    putQuads(part.getQuads(null), submit.pose());
                }
            }
        }

        /**
         * Blocks are not lit evenly: the top is brightest, the bottom darkest, the sides in between (the same
         * values Minecraft uses). Without this the glass looked overexposed next to the rest of the ship.
         */
        private static int shadeColor(Direction direction) {
            float shade;
            if (direction == null) {
                shade = 0.9F;
            } else {
                shade = switch (direction) {
                    case UP -> 1.0F;
                    case DOWN -> 0.5F;
                    case NORTH, SOUTH -> 0.8F;
                    case EAST, WEST -> 0.6F;
                };
            }
            int value = Math.round(255.0F * shade);
            return 0xFF000000 | (value << 16) | (value << 8) | value;
        }

        private void putQuads(List<BakedQuad> quads, PoseStack.Pose pose) {
            for (BakedQuad quad : quads) {
                VertexConsumer buffer = getVertexBuilder(
                        ChunkSectionLayerHelper.getMovingBlockRenderType(quad.materialInfo().layer()));
                buffer.putBakedQuad(pose, quad, quadInstance);
            }
        }
    }
}
