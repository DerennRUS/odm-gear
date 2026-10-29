package com.derenn.odmworld;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Development helper (only with -Dodmworld.dump=true): generates a region around spawn, writes its blocks and
 * some statistics to world_dump/ and stops the server. Used by CI to preview the world type.
 */
@Mod.EventBusSubscriber(modid = OdmWorld.MODID)
public final class WorldDump {
    private static final Logger LOG = LogManager.getLogger("odmworld");

    private WorldDump() {
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("odmworld.dump")) {
            return;
        }
        MinecraftServer server = event.getServer();
        try {
            dump(server);
        } catch (Exception e) {
            LOG.error("World dump failed", e);
        }
        server.halt(false);
    }

    private static void dump(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        File dir = new File("world_dump");
        dir.mkdirs();
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        long seed = level.getSeed();
        BlockPos spawn = level.getSharedSpawnPos();
        PrintWriter stats = new PrintWriter(new File(dir, "stats.txt"));
        stats.println("spawn " + spawn);

        // terrain relief over 1024 x 1024 blocks
        int hmin = Integer.MAX_VALUE, hmax = Integer.MIN_VALUE;
        for (int x = -512; x < 512; x += 8) {
            for (int z = -512; z < 512; z += 8) {
                int h = gen.getBaseHeight(spawn.getX() + x, spawn.getZ() + z, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                hmin = Math.min(hmin, h);
                hmax = Math.max(hmax, h);
            }
        }
        stats.println("ground height min " + hmin + " max " + hmax + " diff " + (hmax - hmin));

        // tree spacing over 40 x 40 cells
        List<int[]> centers = new ArrayList<>();
        for (int gx = -20; gx < 20; gx++) {
            for (int gz = -20; gz < 20; gz++) {
                centers.add(GiantTree.center(seed, gx, gz));
            }
        }
        double minPair = Double.MAX_VALUE, maxNearest = 0, minNearest = Double.MAX_VALUE;
        for (int i = 0; i < centers.size(); i++) {
            int[] a = centers.get(i);
            if (Math.abs(a[0]) > 1000 || Math.abs(a[1]) > 1000) {
                continue; // skip the border of the sample
            }
            double nearest = Double.MAX_VALUE;
            for (int j = 0; j < centers.size(); j++) {
                if (i == j) {
                    continue;
                }
                int[] b = centers.get(j);
                double d = Math.hypot(a[0] - b[0], a[1] - b[1]);
                nearest = Math.min(nearest, d);
            }
            minPair = Math.min(minPair, nearest);
            maxNearest = Math.max(maxNearest, nearest);
            minNearest = Math.min(minNearest, nearest);
        }
        stats.println(String.format("tree nearest-neighbour distance min %.1f max %.1f", minNearest, maxNearest));

        // block region around spawn
        int half = 112;
        int x0 = spawn.getX() - half, z0 = spawn.getZ() - half;
        int size = half * 2;
        for (int cx = (x0 >> 4); cx <= ((x0 + size - 1) >> 4); cx++) {
            for (int cz = (z0 >> 4); cz <= ((z0 + size - 1) >> 4); cz++) {
                level.getChunk(cx, cz);
            }
        }
        int yLo = hmin - 8;
        int yHi = hmax + 106;
        int sy = yHi - yLo;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(dir, "region.bin")), 1 << 16))) {
            out.writeInt(x0);
            out.writeInt(yLo);
            out.writeInt(z0);
            out.writeInt(size);
            out.writeInt(sy);
            out.writeInt(size);
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    for (int y = 0; y < sy; y++) {
                        out.writeByte(code(level.getBlockState(pos.set(x0 + x, yLo + y, z0 + z))));
                    }
                }
            }
        }
        // tree centres inside the region (with ground height)
        for (int gx = Math.floorDiv(x0 - 40, GiantTree.CELL); gx <= Math.floorDiv(x0 + size + 40, GiantTree.CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - 40, GiantTree.CELL); gz <= Math.floorDiv(z0 + size + 40, GiantTree.CELL); gz++) {
                int[] c = GiantTree.center(seed, gx, gz);
                int g = gen.getBaseHeight(c[0], c[1], Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                stats.println("tree " + c[0] + " " + g + " " + c[1]);
            }
        }
        stats.close();
        LOG.info("World dump written");
    }

    private static int code(BlockState st) {
        if (st.isAir()) {
            return 0;
        }
        if (st.is(Blocks.SPRUCE_LOG)) {
            return 1;
        }
        if (st.is(BlockTags.LOGS)) {
            return 2;
        }
        if (st.is(BlockTags.LEAVES)) {
            return 3;
        }
        if (st.is(Blocks.GRASS_BLOCK)) {
            return 4;
        }
        if (st.is(Blocks.DIRT)) {
            return 5;
        }
        if (!st.getFluidState().isEmpty()) {
            return 8;
        }
        if (st.canBeReplaced()) {
            return 7;
        }
        return 6;
    }
}
