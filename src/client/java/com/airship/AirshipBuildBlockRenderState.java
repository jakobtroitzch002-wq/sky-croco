package com.airship;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

public class AirshipBuildBlockRenderState extends BlockEntityRenderState {
    public final BlockModelRenderState blockModel = new BlockModelRenderState();
    public boolean hasCustomTexture;
}
