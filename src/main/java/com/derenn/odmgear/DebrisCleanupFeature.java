package com.derenn.odmgear;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
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
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int y = 0; y < hgt; y++) {
                    m.set(bx + x, minY + y, bz + z);
                    BlockState st = level.getBlockState(m);
                    if (!st.isAir() && st.getFluidState().isEmpty()) {
                        solid.set(idx(x, y, z, size, hgt));
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
                    if (!solid.get(start) || big.get(start)) {
                        continue;
                    }
                    // flood fill until the piece proves to be big
                    visited.clear();
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
