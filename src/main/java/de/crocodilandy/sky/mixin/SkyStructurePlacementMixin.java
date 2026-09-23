package de.crocodilandy.sky.mixin;

import de.crocodilandy.sky.SkyStructureGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructurePlacement.class)
public abstract class SkyStructurePlacementMixin {
    @Inject(method = "isStructureChunk", at = @At("RETURN"), cancellable = true)
    private void crocodilandySky$requireIslandAnchor(
            ChunkGeneratorStructureState state,
            int chunkX,
            int chunkZ,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()) {
            return;
        }

        if (!SkyStructureGenerator.isValidStructureChunk(state.getLevelSeed(), chunkX, chunkZ)) {
            cir.setReturnValue(false);
        }
    }
}
