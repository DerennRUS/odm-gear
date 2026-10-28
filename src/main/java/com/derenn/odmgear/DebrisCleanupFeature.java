package com.derenn.odmgear;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.BitSet;

/**
 * Removes small floating rock pieces in the "ODM Crags" world type: any solid piece that is not connected
 * to the ground and is smaller than {@link #MAX_DEBRIS} blocks is turned into air. Pieces are searched within
 * this chunk plus a 16 block margin around it; anything reaching the margin counts as big and stays.
 */
public class DebrisCleanupFeature extends Feature<NoneFeatureConfiguration> {
    public static final int MAX_DEBRIS = 150;
    private static final ResourceKey<NoiseGeneratorSettings> CRAGS =
            ResourceKey.create(Registries.NOISE_SETTINGS, new ResourceLocation(OdmMod.MODID, "crags"));

    public DebrisCleanupFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        ChunkGenerator gen = ctx.chunkGenerator();
        if (!(gen instanceof NoiseBasedChunkGenerator noise) || !noise.generatorSettings().is(CRAGS)) {
            return false;
        }
        WorldGenLevel level = ctx.level();
        int cx = ctx.origin().getX() & ~15;
        int cz = ctx.origin().getZ() & ~15;
        int minY = level.getMinBuildHeight();
        int hgt = level.getMaxBuildHeight() - minY;
        // search box: the chunk plus 16 blocks on every side
        int bx = cx - 16;
        int bz = cz - 16;
        int size = 48;

        BitSet solid = new BitSet(size * size * hgt);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // read the 3x3 chunks section by section (much faster than per-block world lookups)
        for (int dcx = 0; dcx < 3; dcx++) {
            for (int dcz = 0; dcz < 3; dcz++) {
                ChunkAccess chunk = level.getChunk((bx >> 4) + dcx, (bz >> 4) + dcz);
                LevelChunkSection[] sections = chunk.getSections();
                for (int si = 0; si < sections.length; si++) {
                    LevelChunkSection sec = sections[si];
                    if (sec == null || sec.hasOnlyAir()) {
                        continue;
                    }
                    int baseY = si * 16;
                    for (int ly = 0; ly < 16; ly++) {
                        int y = baseY + ly;
                        if (y >= hgt) {
                            break;
                        }
                        for (int lx = 0; lx < 16; lx++) {
                            int x = dcx * 16 + lx;
                            for (int lz = 0; lz < 16; lz++) {
                                BlockState st = sec.getBlockState(lx, ly, lz);
                                if (!st.isAir() && st.getFluidState().isEmpty()) {
                                    solid.set(idx(x, y, dcz * 16 + lz, size, hgt));
                                }
                            }
                        }
                    }
                }
            }
        }

        BitSet big = new BitSet(solid.size());
        BitSet visited = new BitSet(solid.size());
        int[] queue = new int[MAX_DEBRIS + 8];
        boolean changed = false;
        for (int x = 16; x < 32; x++) {
            for (int z = 16; z < 32; z++) {
                for (int y = 8; y < hgt; y++) {
                    int start = idx(x, y, z, size, hgt);
                    // every floating piece has an underside: only start from solid blocks with air below
                    if (!solid.get(start) || big.get(start) || solid.get(start - 1)) {
                        continue;
                    }
                    // flood fill until the piece proves to be big
                    int head = 0;
                    int tail = 0;
                    queue[tail++] = start;
                    visited.set(start);
                    boolean isBig = false;
                    while (head < tail) {
                        int i = queue[head++];
                        int iy = i % hgt;
                        int ixz = i / hgt;
                        int iz = ixz % size;
                        int ix = ixz / size;
                        if (iy < 8 || ix == 0 || iz == 0 || ix == size - 1 || iz == size - 1) {
                            isBig = true;
                            break;
                        }
                        int[] nb = {i - 1, iy < hgt - 1 ? i + 1 : -1, i - hgt, i + hgt, i - size * hgt, i + size * hgt};
                        for (int j : nb) {
                            if (j < 0 || !solid.get(j) || visited.get(j)) {
                                continue;
                            }
                            if (big.get(j) || tail >= MAX_DEBRIS) {
                                isBig = true;
                                break;
                            }
                            visited.set(j);
                            queue[tail++] = j;
                        }
                        if (isBig) {
                            break;
                        }
                    }
                    for (int k = 0; k < tail; k++) {
                        visited.clear(queue[k]);
                    }
                    if (isBig) {
                        for (int k = 0; k < tail; k++) {
                            big.set(queue[k]);
                        }
                    } else {
                        for (int k = 0; k < tail; k++) {
                            int i = queue[k];
                            solid.clear(i);
                            int iy = i % hgt;
                            int ixz = i / hgt;
                            int iz = ixz % size;
                            int ix = ixz / size;
                            // only write inside this chunk; neighbours clean their own part
                            if (ix >= 16 && ix < 32 && iz >= 16 && iz < 32) {
                                m.set(bx + ix, minY + iy, bz + iz);
                                level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
                                changed = true;
                            }
                        }
                    }
                }
            }
        }
        return changed;
    }

    private static int idx(int x, int y, int z, int size, int hgt) {
        return (x * size + z) * hgt + y;
    }
}
