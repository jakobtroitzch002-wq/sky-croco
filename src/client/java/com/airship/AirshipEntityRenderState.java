package com.airship;

import java.util.List;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

public class AirshipEntityRenderState extends EntityRenderState {
    public List<AirshipRenderPart> parts = List.of();
    public List<AirshipRenderPart> lateParts = List.of();
    public List<AirshipRenderCushion> cushions = List.of();
    /** Difference between the smoothed ship position and the position the base renderer uses. */
    public double offsetX;
    public double offsetY;
    public double offsetZ;
    /** Smoothed yaw of the ship in degrees. */
    public float yaw;
}
