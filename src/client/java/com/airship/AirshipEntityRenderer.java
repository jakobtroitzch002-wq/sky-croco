package com.airship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Renders all blocks of an airship. The resolved block models are cached per entity
 * and only rebuilt when the ship's block data changes.
 */
public class AirshipEntityRenderer extends EntityRenderer<AirshipEntity, AirshipEntityRenderState> {
    private static final BlockDisplayContext DISPLAY_CONTEXT = BlockDisplayContext.create();

    private record Cache(
            int version,
            List<AirshipRenderPart> parts,
            List<AirshipRenderPart> lateParts,
            List<AirshipRenderCushion> cushions
    ) {}

    private final BlockModelResolver blockModelResolver;
    private final Map<AirshipEntity, Cache> caches = new WeakHashMap<>();

    public AirshipEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.blockModelResolver = context.getBlockModelResolver();
    }

    @Override
    public AirshipEntityRenderState createRenderState() {
        return new AirshipEntityRenderState();
    }

    @Override
    public void extractRenderState(AirshipEntity entity, AirshipEntityRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);

        Cache cache = caches.get(entity);
        if (cache == null || cache.version() != entity.getCellVersion()) {
            List<AirshipRenderPart> parts = new ArrayList<>();
            List<AirshipRenderPart> lateParts = new ArrayList<>();
            for (AirshipClientCell cell : entity.getExposedCells()) {
                BlockModelRenderState model = new BlockModelRenderState();
                blockModelResolver.update(model, cell.state(), DISPLAY_CONTEXT);
                boolean seeThrough = isSeeThrough(cell.state());
                AirshipRenderPart part = new AirshipRenderPart(
                        cell.pos().getX(), cell.pos().getY(), cell.pos().getZ(), model,
                        seeThrough ? modelParts(cell.state()) : List.of());
                (seeThrough ? lateParts : parts).add(part);
            }
            // Cushions are entities and cannot be drawn by their own renderer here, so a wool slab of
            // the same colour stands in for them while the ship is in the air.
            List<AirshipRenderCushion> cushions = new ArrayList<>();
            for (AirshipClientCushion cushion : entity.getClientCushions()) {
                BlockModelRenderState model = new BlockModelRenderState();
                blockModelResolver.update(model, AirshipCushions.standInState(cushion.color()), DISPLAY_CONTEXT);
                cushions.add(new AirshipRenderCushion(cushion.x(), cushion.y(), cushion.z(), cushion.yaw(), model));
            }
            cache = new Cache(entity.getCellVersion(), List.copyOf(parts), List.copyOf(lateParts), List.copyOf(cushions));
            caches.put(entity, cache);
        }
        state.parts = cache.parts();
        state.lateParts = cache.lateParts();
        state.cushions = cache.cushions();

        // The base renderer already placed us at the entity's own interpolated position (between the
        // positions of the last two ticks). Move from there to our smoothed position. It is important
        // to subtract exactly what the base renderer used (getPosition(partialTick)), not the current
        // position, otherwise the interpolation is applied twice and the ship jumps back every tick.
        Vec3 smooth = entity.getSmoothPos(partialTick);
        Vec3 base = entity.getPosition(partialTick);
        state.offsetX = smooth.x - base.x;
        state.offsetY = smooth.y - base.y;
        state.offsetZ = smooth.z - base.z;
        state.yaw = entity.getSmoothYaw(partialTick);
    }

    private static List<BlockStateModelPart> modelParts(BlockState state) {
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        return List.copyOf(parts);
    }

    /** Glass, glass panes, ice, slime and honey are drawn with transparency. Matched by name on purpose. */
    private static boolean isSeeThrough(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.endsWith("glass") || path.endsWith("glass_pane")
                || path.equals("ice") || path.equals("frosted_ice")
                || path.equals("slime_block") || path.equals("honey_block");
    }

    @Override
    public void submit(
            AirshipEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera
    ) {
        poseStack.pushPose();
        poseStack.translate((float) state.offsetX, (float) state.offsetY, (float) state.offsetZ);
        // Ship yaw 0 = unrotated; positive yaw turns the ship clockwise seen from above.
        poseStack.rotate(Axis.YP, -state.yaw * Mth.DEG_TO_RAD);
        for (AirshipRenderPart part : state.parts) {
            poseStack.pushPose();
            // The entity position is the centre of the Core block's footprint.
            poseStack.translate(part.x() - 0.5F, part.y(), part.z() - 0.5F);
            part.model().submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        for (AirshipRenderPart part : state.lateParts) {
            poseStack.pushPose();
            poseStack.translate(part.x() - 0.5F, part.y(), part.z() - 0.5F);
            if (AirshipRenderMode.afterTerrain) {
                collector.submitCustom(SubmitRenderPhases.AFTER_TERRAIN,
                        new AirshipGlassFeature.GlassSubmit(poseStack.last().copy(), part.modelParts(), state.lightCoords));
            } else {
                part.model().submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            }
            poseStack.popPose();
        }
        for (AirshipRenderCushion cushion : state.cushions) {
            poseStack.pushPose();
            poseStack.translate((float) cushion.x(), (float) cushion.y(), (float) cushion.z());
            poseStack.rotate(Axis.YP, -cushion.yaw() * Mth.DEG_TO_RAD);
            // The real cushion is a flat pillow almost one block wide and 0.25 blocks high.
            poseStack.scale(0.98F, 0.5F, 0.98F);
            poseStack.translate(-0.5F, 0.0F, -0.5F);
            cushion.model().submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }
}
