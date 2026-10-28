package com.derenn.odmgear;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Development helper (only active with -Dodmgear.dumpTerrain=true): renders terrain previews of the
 * overworld generator and stops the server. Used by CI to tune the "ODM Crags" world type.
 */
@Mod.EventBusSubscriber(modid = OdmMod.MODID)
public final class TerrainDump {
    private TerrainDump() {
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("odmgear.dumpTerrain")) {
            return;
        }
        MinecraftServer server = event.getServer();
        Thread t = new Thread(() -> dump(server), "odmgear-terrain-dump");
        t.setDaemon(true);
        t.start();
    }

    private static void dump(MinecraftServer server) {
        try {
            ServerLevel level = server.overworld();
            ChunkGenerator gen = level.getChunkSource().getGenerator();
            RandomState rs = level.getChunkSource().randomState();
            File dir = new File("terrain_dump");
            dir.mkdirs();
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight();

            // top-down relief, 768 x 768 blocks, 2 blocks per pixel
            int size = 256;
            int step = 3;
            int[][] h = new int[size][size];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    h[i][j] = gen.getBaseHeight(i * step - 384, j * step - 384, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                }
            }
            BufferedImage top = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    int hh = h[i][j];
                    double t = (hh - minY) / (double) (maxY - minY);
                    int dx = h[Math.min(i + 1, size - 1)][j] - h[Math.max(i - 1, 0)][j];
                    int dz = h[i][Math.min(j + 1, size - 1)] - h[i][Math.max(j - 1, 0)];
                    double shade = Math.max(0.25, Math.min(1.3, 0.85 - (dx + dz) * 0.03));
                    int r, g, b;
                    if (hh <= gen.getSeaLevel()) {
                        r = 40; g = 70; b = 160;
                    } else {
                        r = (int) (60 + 195 * t);
                        g = (int) (110 + 120 * t);
                        b = (int) (50 + 190 * t);
                    }
                    r = clamp(r * shade); g = clamp(g * shade); b = clamp(b * shade);
                    top.setRGB(i, j, (r << 16) | (g << 8) | b);
                }
            }
            ImageIO.write(top, "png", new File(dir, "top.png"));

            // vertical cross sections (1 block per pixel) to see overhangs, arches and ledges
            int[] zs = {0, 160, -200};
            for (int k = 0; k < zs.length; k++) {
                int width = 640;
                BufferedImage sec = new BufferedImage(width, maxY - minY, BufferedImage.TYPE_INT_RGB);
                for (int x = 0; x < width; x++) {
                    NoiseColumn col = gen.getBaseColumn(x - 320, zs[k], level, rs);
                    for (int y = minY; y < maxY; y++) {
                        BlockState st = col.getBlock(y);
                        int c;
                        if (st.isAir()) {
                            c = 0xBFD8F0;
                        } else if (!st.getFluidState().isEmpty()) {
                            c = 0x3050A0;
                        } else {
                            c = y > 300 ? 0x9A9A9A : 0x5E5248;
                        }
                        sec.setRGB(x, maxY - 1 - y, c);
                    }
                    if (x % 64 == 0) {
                        for (int y = 0; y < maxY - minY; y += 4) {
                            sec.setRGB(x, y, 0xFF0000);
                        }
                    }
                }
                for (int y = minY; y < maxY; y += 64) {
                    for (int x = 0; x < width; x += 2) {
                        sec.setRGB(x, maxY - 1 - y, 0xFF0000);
                    }
                }
                ImageIO.write(sec, "png", new File(dir, "section_" + k + ".png"));
            }
            debrisStats(server, level, dir);
            BlockPos spawn = level.getSharedSpawnPos();
            OdmModLog.info("Terrain dump written, spawn at " + spawn);
        } catch (Exception e) {
            OdmModLog.error("Terrain dump failed", e);
        }
        server.halt(false);
    }


    /** Counts disconnected solid pieces in a 160x160 column area by size (6-neighbour flood fill). */
    private static void debrisStats(MinecraftServer server, ServerLevel level, File dir) throws Exception {
        int n = 160;
        int minY = level.getMinBuildHeight();
        int hgt = level.getMaxBuildHeight() - minY;
        boolean[] solid = new boolean[n * n * hgt];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int ox = 1008;
        int oz = 1008;
        for (int ccx = 0; ccx < n / 16; ccx++) {
            for (int ccz = 0; ccz < n / 16; ccz++) {
                final int chx = (ox >> 4) + ccx;
                final int chz = (oz >> 4) + ccz;
                net.minecraft.world.level.chunk.ChunkAccess chunk = server.submit(() -> level.getChunk(chx, chz)).join();
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int x = ccx * 16 + lx;
                        int z = ccz * 16 + lz;
                        for (int y = 0; y < hgt; y++) {
                            m.set(ox + x, y + minY, oz + z);
                            BlockState st = chunk.getBlockState(m);
                            solid[(x * n + z) * hgt + y] = !st.isAir() && st.getFluidState().isEmpty()
                                    && !st.is(net.minecraft.tags.BlockTags.LEAVES) && !st.is(net.minecraft.tags.BlockTags.LOGS);
                        }
                    }
                }
            }
        }
        boolean[] seen = new boolean[solid.length];
        int[] stack = new int[solid.length];
        int[] bins = new int[6];
        int[] limits = {1, 30, 100, 500, 5000, Integer.MAX_VALUE};
        long floatingVolume = 0;
        for (int start = 0; start < solid.length; start++) {
            if (!solid[start] || seen[start]) {
                continue;
            }
            int sp = 0;
            stack[sp++] = start;
            seen[start] = true;
            int size = 0;
            boolean touchesBottom = false;
            boolean touchesEdge = false;
            while (sp > 0) {
                int i = stack[--sp];
                size++;
                int y = i % hgt;
                int xz = i / hgt;
                int z = xz % n;
                int x = xz / n;
                if (y < 8) touchesBottom = true;
                if (x == 0 || z == 0 || x == n - 1 || z == n - 1) touchesEdge = true;
                int[] nb = {
                        y > 0 ? i - 1 : -1, y < hgt - 1 ? i + 1 : -1,
                        z > 0 ? i - hgt : -1, z < n - 1 ? i + hgt : -1,
                        x > 0 ? i - n * hgt : -1, x < n - 1 ? i + n * hgt : -1};
                for (int j : nb) {
                    if (j >= 0 && solid[j] && !seen[j]) {
                        seen[j] = true;
                        stack[sp++] = j;
                    }
                }
            }
            if (touchesBottom || touchesEdge) {
                continue;
            }
            floatingVolume += size;
            for (int b = 0; b < limits.length; b++) {
                if (size <= limits[b]) {
                    bins[b]++;
                    break;
                }
            }
        }
        String stats = "floating pieces fully inside 160x160 area: single=" + bins[0] + " 2-30=" + bins[1] + " 31-100=" + bins[2]
                + " 101-500=" + bins[3] + " 501-5000=" + bins[4] + " >5000=" + bins[5] + " floatingVolume=" + floatingVolume;
        java.nio.file.Files.writeString(new File(dir, "stats.txt").toPath(), stats + "\n");
        OdmModLog.info(stats);
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, v));
    }

    private static final class OdmModLog {
        private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

        static void info(String s) {
            LOG.info("[odmgear] " + s);
        }

        static void error(String s, Exception e) {
            LOG.error("[odmgear] " + s, e);
        }
    }
}
