package com.derenn.odmworld;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Places the parts of all giant trees that overlap the current chunk. Trees are far bigger than the area a
 * feature may touch, so every chunk rebuilds the (deterministic) trees around it and only writes its own blocks.
 */
public class GiantTreesFeature extends Feature<NoneFeatureConfiguration> {
    public GiantTreesFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ();
        RandomState rs = level.getLevel().getChunkSource().randomState();
        long seed = level.getSeed();
        int pad = GiantTree.REACH + GiantTree.JITTER + 1;
        int cx0 = Math.floorDiv(minX - pad - GiantTree.CELL / 2, GiantTree.CELL);
        int cx1 = Math.floorDiv(minX + 15 + pad - GiantTree.CELL / 2, GiantTree.CELL) + 1;
        int cz0 = Math.floorDiv(minZ - pad - GiantTree.CELL / 2, GiantTree.CELL);
        int cz1 = Math.floorDiv(minZ + 15 + pad - GiantTree.CELL / 2, GiantTree.CELL) + 1;
        boolean any = false;
        for (int gx = cx0; gx <= cx1; gx++) {
            for (int gz = cz0; gz <= cz1; gz++) {
                int[] c = GiantTree.center(seed, gx, gz);
                if (c[0] + GiantTree.REACH < minX || c[0] - GiantTree.REACH > minX + 15
                        || c[1] + GiantTree.REACH < minZ || c[1] - GiantTree.REACH > minZ + 15) {
                    continue;
                }
                GiantTree tree = GiantTree.get(seed, gx, gz, ctx.chunkGenerator(), level, rs);
                tree.place(level, minX, minZ);
                any = true;
            }
        }
        return any;
    }
}
