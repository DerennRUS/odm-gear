package com.derenn.odmworld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One giant tree (spruce wood, oak leaves), 100 blocks tall:
 * <ul>
 *   <li>trunk 10 blocks wide at the base, 7 at the end of the bare part (40-45), tapering to a point near the top;</li>
 *   <li>roots around the base, never higher than 5 blocks;</li>
 *   <li>from the bare part up to 75: one thick branch per 7 blocks of height in a random direction, 20 long and
 *       4 thick at the trunk down to 1 at the tip, getting smaller toward the top, flat enough to stand on,
 *       with a little foliage at the tip and on side twigs;</li>
 *   <li>from 75: a crown of smaller rising branches and leaf clusters that shrinks toward the top.</li>
 * </ul>
 * Trees sit on a jittered 60-block grid (offset -6..+6), so neighbours are 48-72 blocks apart along the grid.
 */
public final class GiantTree {
    public static final int CELL = 60;
    public static final int JITTER = 6;
    /** Max horizontal distance of any block from the trunk axis. */
    public static final int REACH = 32;
    static final double HEIGHT = 100;
    static final double TRUNK_TOP = 95;
    static final double CROWN_START = 75;

    private static final BlockState TRUNK = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
    private static final BlockState WOOD = Blocks.SPRUCE_WOOD.defaultBlockState();
    private static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);

    private static final Map<Long, GiantTree> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, GiantTree> eldest) {
            return size() > 512;
        }
    };
    private static long cacheSeed;

    final int cx, cz, y0;
    final double clearHeight;
    final long hashSeed;
    /** Wood tubes: x0, y0, z0, x1, y1, z1, r0, r1 (absolute coordinates). */
    final List<double[]> tubes = new ArrayList<>();
    /** Leaf clusters: x, y, z, rx, ry, rz. */
    final List<double[]> blobs = new ArrayList<>();

    // ------------------------------------------------------------------ placement grid

    private static long mix(long seed, int gx, int gz, long salt) {
        long h = seed ^ (gx * 0x9E3779B97F4A7C15L) ^ (gz * 0xC2B2AE3D27D4EB4FL) ^ salt;
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return h ^ (h >>> 33);
    }

    /** Trunk centre (block corner) of the tree in grid cell (gx, gz). */
    public static int[] center(long seed, int gx, int gz) {
        long h = mix(seed, gx, gz, 0x5EEDL);
        int jx = (int) Math.floorMod(h, 2 * JITTER + 1) - JITTER;
        int jz = (int) Math.floorMod(h >>> 20, 2 * JITTER + 1) - JITTER;
        return new int[]{gx * CELL + CELL / 2 + jx, gz * CELL + CELL / 2 + jz};
    }

    public static GiantTree get(long seed, int gx, int gz, ChunkGenerator gen, LevelHeightAccessor heightAccessor, RandomState rs) {
        long key = ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
        synchronized (CACHE) {
            if (cacheSeed != seed) {
                CACHE.clear();
                cacheSeed = seed;
            }
            GiantTree t = CACHE.get(key);
            if (t != null) {
                return t;
            }
        }
        int[] c = center(seed, gx, gz);
        int ground = gen.getBaseHeight(c[0], c[1], Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, rs);
        GiantTree t = new GiantTree(c[0], c[1], ground, mix(seed, gx, gz, 0x7EE1L));
        synchronized (CACHE) {
            CACHE.put(key, t);
        }
        return t;
    }

    // ------------------------------------------------------------------ shape

    private GiantTree(int cx, int cz, int y0, long seed) {
        this.cx = cx;
        this.cz = cz;
        this.y0 = y0;
        this.hashSeed = seed;
        Random rng = new Random(seed);
        clearHeight = 40 + rng.nextInt(6);
        buildRoots(rng);
        buildMainBranches(rng);
        buildCrown(rng);
    }

    /** Trunk radius at height h above the ground. */
    double trunkRadius(double h) {
        if (h <= clearHeight) {
            return lerp(5.0, 3.5, Math.max(0, h) / clearHeight);
        }
        if (h <= CROWN_START) {
            return lerp(3.5, 2.5, (h - clearHeight) / (CROWN_START - clearHeight));
        }
        return lerp(2.5, 0.8, Math.min(1, (h - CROWN_START) / (TRUNK_TOP - CROWN_START)));
    }

    private void buildRoots(Random rng) {
        int n = 7 + rng.nextInt(3);
        double a0 = rng.nextDouble() * Math.PI * 2;
        for (int i = 0; i < n; i++) {
            double ang = a0 + i * Math.PI * 2 / n + (rng.nextDouble() - 0.5) * 0.5;
            double r0 = 1.6 + rng.nextDouble() * 0.2;
            double h0 = 5.0 - r0 - rng.nextDouble() * 0.6; // top of the root stays within 5 blocks
            double len = 7 + rng.nextDouble() * 4;
            double[] prev = null;
            int steps = 8;
            for (int k = 0; k <= steps; k++) {
                double s = k / (double) steps;
                double dist = 3.5 + s * len;
                double y = h0 * Math.pow(1 - s, 1.6) - 1.8 * s;
                double[] p = {cx + Math.cos(ang) * dist, y0 + y, cz + Math.sin(ang) * dist, lerp(r0, 0.5, s)};
                if (prev != null) {
                    tubes.add(new double[]{prev[0], prev[1], prev[2], p[0], p[1], p[2], prev[3], p[3]});
                }
                prev = p;
            }
        }
    }

    private void buildMainBranches(Random rng) {
        double yaw = rng.nextDouble() * Math.PI * 2;
        for (int b = 0; ; b++) {
            double bucket = clearHeight + 7 * b;
            if (bucket >= CROWN_START - 2) {
                break;
            }
            double f = (bucket - clearHeight) / (CROWN_START - clearHeight); // 0 low .. 1 high
            double r0 = 2.0 - 0.6 * f;            // 4 blocks thick at the bottom, ~3 near the crown
            double len = 20.0 - 8.0 * f;          // 20 long at the bottom, ~12 near the crown
            double room = Math.min(7.0, CROWN_START - bucket) - r0 - 0.5;
            double hb = bucket + r0 + rng.nextDouble() * Math.max(0, room); // bottom of the branch above the bare part
            yaw += Math.toRadians(137.5 + (rng.nextDouble() - 0.5) * 50);
            double wiggle = Math.toRadians((rng.nextDouble() - 0.5) * 24);
            double rise = 2.5 + rng.nextDouble() * 2.0;
            double startR = trunkRadius(hb) * 0.5;
            int steps = 12;
            double[] prev = null;
            double[][] pts = new double[steps + 1][];
            for (int k = 0; k <= steps; k++) {
                double s = k / (double) steps;
                double a = yaw + wiggle * Math.sin(Math.PI * s);
                double dist = startR + s * len;
                double y = hb + rise * (1 - (1 - s) * (1 - s)) - 1.0 * s * s * s;
                double[] p = {cx + Math.cos(a) * dist, y0 + y, cz + Math.sin(a) * dist, lerp(r0, 0.5, s), a};
                pts[k] = p;
                if (prev != null) {
                    tubes.add(new double[]{prev[0], prev[1], prev[2], p[0], p[1], p[2], prev[3], p[3]});
                }
                prev = p;
            }
            double leaf = 1.0 - 0.3 * f;
            double[] tip = pts[steps];
            blobs.add(new double[]{tip[0], tip[1] + 1.0, tip[2], 3.0 * leaf, 2.0 * leaf, 3.0 * leaf});
            // two short side twigs with a little foliage, so the top of the branch stays walkable
            int side = rng.nextBoolean() ? 1 : -1;
            for (double s : new double[]{0.5 + rng.nextDouble() * 0.1, 0.75 + rng.nextDouble() * 0.1}) {
                double[] p = pts[(int) Math.round(s * steps)];
                double a = p[4] + side * Math.toRadians(65 + rng.nextDouble() * 20);
                double tl = (4 + rng.nextDouble() * 2) * leaf;
                double ex = p[0] + Math.cos(a) * tl, ez = p[2] + Math.sin(a) * tl, ey = p[1] + 1.5;
                tubes.add(new double[]{p[0], p[1], p[2], ex, ey, ez, 0.6, 0.4});
                blobs.add(new double[]{ex, ey + 0.5, ez, 2.3 * leaf, 1.7 * leaf, 2.3 * leaf});
                side = -side;
            }
        }
    }

    private void buildCrown(Random rng) {
        double yaw = rng.nextDouble() * Math.PI * 2;
        for (double h = CROWN_START + 1 + rng.nextDouble() * 1.5; h <= 91; h += 2.0 + rng.nextDouble() * 1.2) {
            double t = (h - (CROWN_START + 1)) / (91 - (CROWN_START + 1));
            double len = 11.0 - 7.0 * t;
            double r0 = 1.2 - 0.6 * t;
            double elev = Math.toRadians(25 + 20 * t + (rng.nextDouble() - 0.5) * 10);
            yaw += Math.toRadians(137.5 + (rng.nextDouble() - 0.5) * 40);
            double sx = cx + Math.cos(yaw) * trunkRadius(h) * 0.5, sy = y0 + h, sz = cz + Math.sin(yaw) * trunkRadius(h) * 0.5;
            double hl = Math.cos(elev) * len, vl = Math.sin(elev) * len;
            double mx = sx + Math.cos(yaw) * hl * 0.5, my = sy + vl * 0.35, mz = sz + Math.sin(yaw) * hl * 0.5;
            double ex = sx + Math.cos(yaw) * hl, ey = sy + vl, ez = sz + Math.sin(yaw) * hl;
            double rm = (r0 + 0.4) * 0.5;
            tubes.add(new double[]{sx, sy, sz, mx, my, mz, r0, rm});
            tubes.add(new double[]{mx, my, mz, ex, ey, ez, rm, 0.4});
            double lr = 3.6 - 1.2 * t;
            blobs.add(new double[]{ex, ey + 0.5, ez, lr, lr * 0.72, lr});
            if (len > 6) {
                blobs.add(new double[]{mx, my + 1.0, mz, 2.0, 1.5, 2.0});
            }
        }
        blobs.add(new double[]{cx, y0 + 92.5, cz, 4.0, 3.0, 4.0});
        blobs.add(new double[]{cx, y0 + 96.5, cz, 2.6, 2.6, 2.6});
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    // ------------------------------------------------------------------ rasterisation into one chunk

    private float noise(int x, int y, int z) {
        long h = hashSeed ^ (x * 0x2545F4914F6CDD1DL) ^ (y * 0x9E3779B97F4A7C15L) ^ (z * 0xBF58476D1CE4E5B9L);
        h = (h ^ (h >>> 31)) * 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }

    public void place(WorldGenLevel level, int minX, int minZ) {
        int maxX = minX + 15, maxZ = minZ + 15;
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // trunk (disc of radius r around the block corner cx, cz)
        int x0 = Math.max(minX, cx - 6), x1 = Math.min(maxX, cx + 6);
        int z0 = Math.max(minZ, cz - 6), z1 = Math.min(maxZ, cz + 6);
        if (x0 <= x1 && z0 <= z1) {
            for (int y = Math.max(minY, y0 - 6); y <= Math.min(maxY, y0 + (int) TRUNK_TOP); y++) {
                double r = trunkRadius(y - y0 + 0.5);
                double r2 = r * r;
                for (int x = x0; x <= x1; x++) {
                    double dx = x + 0.5 - cx;
                    for (int z = z0; z <= z1; z++) {
                        double dz = z + 0.5 - cz;
                        if (dx * dx + dz * dz <= r2) {
                            setWood(level, pos.set(x, y, z), TRUNK);
                        }
                    }
                }
            }
        }

        // branches and roots
        for (double[] t : tubes) {
            double rmax = Math.max(t[6], t[7]);
            int bx0 = Math.max(minX, (int) Math.floor(Math.min(t[0], t[3]) - rmax));
            int bx1 = Math.min(maxX, (int) Math.floor(Math.max(t[0], t[3]) + rmax));
            int bz0 = Math.max(minZ, (int) Math.floor(Math.min(t[2], t[5]) - rmax));
            int bz1 = Math.min(maxZ, (int) Math.floor(Math.max(t[2], t[5]) + rmax));
            if (bx0 > bx1 || bz0 > bz1) {
                continue;
            }
            int by0 = Math.max(minY, (int) Math.floor(Math.min(t[1], t[4]) - rmax));
            int by1 = Math.min(maxY, (int) Math.floor(Math.max(t[1], t[4]) + rmax));
            double vx = t[3] - t[0], vy = t[4] - t[1], vz = t[5] - t[2];
            double vv = vx * vx + vy * vy + vz * vz;
            for (int x = bx0; x <= bx1; x++) {
                for (int y = by0; y <= by1; y++) {
                    for (int z = bz0; z <= bz1; z++) {
                        double px = x + 0.5 - t[0], py = y + 0.5 - t[1], pz = z + 0.5 - t[2];
                        double s = vv < 1.0E-9 ? 0 : Math.max(0, Math.min(1, (px * vx + py * vy + pz * vz) / vv));
                        double qx = px - vx * s, qy = py - vy * s, qz = pz - vz * s;
                        double r = t[6] + (t[7] - t[6]) * s;
                        if (qx * qx + qy * qy + qz * qz <= r * r) {
                            setWood(level, pos.set(x, y, z), WOOD);
                        }
                    }
                }
            }
        }

        // foliage
        for (double[] b : blobs) {
            int bx0 = Math.max(minX, (int) Math.floor(b[0] - b[3]));
            int bx1 = Math.min(maxX, (int) Math.floor(b[0] + b[3]));
            int bz0 = Math.max(minZ, (int) Math.floor(b[2] - b[5]));
            int bz1 = Math.min(maxZ, (int) Math.floor(b[2] + b[5]));
            if (bx0 > bx1 || bz0 > bz1) {
                continue;
            }
            int by0 = Math.max(minY, (int) Math.floor(b[1] - b[4]));
            int by1 = Math.min(maxY, (int) Math.floor(b[1] + b[4]));
            for (int x = bx0; x <= bx1; x++) {
                for (int y = by0; y <= by1; y++) {
                    for (int z = bz0; z <= bz1; z++) {
                        double dx = (x + 0.5 - b[0]) / b[3], dy = (y + 0.5 - b[1]) / b[4], dz = (z + 0.5 - b[2]) / b[5];
                        double q = dx * dx + dy * dy + dz * dz;
                        if (q <= 1.0 - 0.35 * noise(x, y, z)) {
                            pos.set(x, y, z);
                            BlockState st = level.getBlockState(pos);
                            if (st.isAir() || (st.canBeReplaced() && st.getFluidState().isEmpty())) {
                                level.setBlock(pos, LEAVES, 2);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void setWood(WorldGenLevel level, BlockPos pos, BlockState state) {
        BlockState st = level.getBlockState(pos);
        if (!st.is(Blocks.BEDROCK) && !st.is(Blocks.SPRUCE_LOG)) {
            level.setBlock(pos, state, 2);
        }
    }
}
