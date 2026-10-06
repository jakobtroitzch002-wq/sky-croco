package com.airship;

import net.minecraft.client.renderer.block.BlockModelRenderState;

/** A cushion drawn as a stand-in model while the ship flies. Position is relative to the ship's origin. */
public record AirshipRenderCushion(double x, double y, double z, float yaw, BlockModelRenderState model) {}
