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
        try {
            ServerLevel level = server.overworld();
            ChunkGenerator gen = level.getChunkSource().getGenerator();
            RandomState rs = level.getChunkSource().randomState();
            File dir = new File("terrain_dump");
            dir.mkdirs();
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight();

            // top-down relief, 768 x 768 blocks, 2 blocks per pixel
            int size = 384;
            int step = 2;
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
            BlockPos spawn = level.getSharedSpawnPos();
            OdmModLog.info("Terrain dump written, spawn at " + spawn);
        } catch (Exception e) {
            OdmModLog.error("Terrain dump failed", e);
        }
        server.halt(false);
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
