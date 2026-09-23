package de.crocodilandy.sky.mixin;

import de.crocodilandy.sky.SkyChunkGenerator;
import de.crocodilandy.sky.SkyStructureGenerator;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkPos;
import net.minecraft.world.level.chunk.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Structure.class)
public abstract class SkyStructureMixin {
    @Inject(method = "generate", at = @At("RETURN"), cancellable = true)
    private void crocodilandySky$requireIslandFootprint(
            RegistryAccess registryAccess,
            ChunkGenerator chunkGenerator,
            BiomeSource biomeSource,
            RandomState randomState,
            StructureTemplateManager structureTemplateManager,
            long seed,
            ChunkPos chunkPos,
            int references,
            LevelHeightAccessor heightAccessor,
            Predicate<Holder<Biome>> predicate,
            CallbackInfoReturnable<StructureStart> cir
    ) {
        if (!(chunkGenerator instanceof SkyChunkGenerator)) {
            return;
        }

        StructureStart start = cir.getReturnValue();
        if (!start.isValid()) {
            return;
        }

        BoundingBox box = start.getBoundingBox();
        if (!SkyStructureGenerator.isStructureBoundingBoxOnIsland(seed, box)) {
            cir.setReturnValue(StructureStart.INVALID_START);
        }
    }
}
