package com.derenn.odmgear.titan;

import com.derenn.odmgear.OdmMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;

/**
 * Natural titan spawning: overworld, daytime only, never closer than 50 blocks to a player
 * and never within 50 blocks of another titan.
 */
public final class TitanSpawner {
    static final double MIN_PLAYER_DIST = 50;
    static final double MIN_TITAN_DIST = 50;
    static final double MAX_DIST = 96;
    /** Chance per player per check (every 5 s). */
    static final float CHANCE = 0.15f;

    private TitanSpawner() {
    }

    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (level.getGameTime() % 100 != 0 || level.getDifficulty() == Difficulty.PEACEFUL
                || !level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
            return;
        }
        long t = level.getDayTime() % 24000L;
        if (t >= 12000L) {
            return; // day only
        }
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && level.random.nextFloat() < CHANCE) {
                trySpawn(level, p);
            }
        }
    }

    private static void trySpawn(ServerLevel level, ServerPlayer near) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double ang = level.random.nextDouble() * Math.PI * 2;
            double dist = MIN_PLAYER_DIST + 2 + level.random.nextDouble() * (MAX_DIST - MIN_PLAYER_DIST - 2);
            int x = Mth.floor(near.getX() + Math.cos(ang) * dist);
            int z = Mth.floor(near.getZ() + Math.sin(ang) * dist);
            BlockPos col = new BlockPos(x, 0, z);
            if (!level.hasChunkAt(col)) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()) {
                continue;
            }
            double cx = x + 0.5, cz = z + 0.5;
            boolean playerNear = false;
            for (ServerPlayer p : level.players()) {
                double dx = p.getX() - cx, dz = p.getZ() - cz;
                if (dx * dx + dz * dz < MIN_PLAYER_DIST * MIN_PLAYER_DIST) {
                    playerNear = true;
                    break;
                }
            }
            if (playerNear) {
                continue;
            }
            boolean titanNear = false;
            for (TitanEntity other : level.getEntitiesOfClass(TitanEntity.class, new AABB(pos).inflate(MIN_TITAN_DIST, 256, MIN_TITAN_DIST))) {
                double dx = other.getX() - cx, dz = other.getZ() - cz;
                if (dx * dx + dz * dz < MIN_TITAN_DIST * MIN_TITAN_DIST) {
                    titanNear = true;
                    break;
                }
            }
            if (titanNear) {
                continue;
            }
            TitanEntity titan = OdmMod.TITAN.get().create(level);
            if (titan == null) {
                return;
            }
            titan.moveTo(cx, y, cz, level.random.nextFloat() * 360f, 0f);
            titan.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null, null);
            titan.setNatural(true);
            if (!level.noCollision(titan)) {
                continue;
            }
            level.addFreshEntity(titan);
            return;
        }
    }
}
