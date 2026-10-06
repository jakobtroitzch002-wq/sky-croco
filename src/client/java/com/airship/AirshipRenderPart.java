package com.airship;

import java.util.List;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;

/**
 * One visible ship block: its position relative to the Core and its resolved block model.
 * {@code modelParts} are only filled for see-through blocks, which are drawn by {@link AirshipGlassFeature}.
 */
public record AirshipRenderPart(int x, int y, int z, BlockModelRenderState model, List<BlockStateModelPart> modelParts) {}
