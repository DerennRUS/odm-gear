package com.derenn.odmgear.client;

import com.derenn.odmgear.titan.TitanEntity;
import com.derenn.odmgear.titan.TitanGeometry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Client-side lookups against titan meshes (hooks, aiming). */
public final class TitanPick {
    private static final List<TitanEntity> titans = new ArrayList<>();
    private static long cachedTime = Long.MIN_VALUE;
    private static Level cachedLevel;

    private TitanPick() {
    }

    public record Hit(TitanEntity titan, TitanGeometry.MeshHit mesh) {
        public double t() {
            return mesh.t();
        }

        public Vec3 point() {
            return mesh.point();
        }
    }

    public static List<TitanEntity> titans(Level level) {
        if (!(level instanceof ClientLevel cl)) {
            return List.of();
        }
        if (cl != cachedLevel || cl.getGameTime() != cachedTime) {
            cachedLevel = cl;
            cachedTime = cl.getGameTime();
            titans.clear();
            for (Entity e : cl.entitiesForRendering()) {
                if (e instanceof TitanEntity t && t.isAlive()) {
                    titans.add(t);
                }
            }
        }
        return titans;
    }

    public static AABB reachBox(TitanEntity t) {
        float h = t.heightBlocks();
        return t.getBoundingBox().inflate(h * 0.65, h * 0.15, h * 0.65);
    }

    /** First titan mesh hit along a->b (t = 0..1 along the segment). */
    public static Hit raycast(Level level, Vec3 a, Vec3 b) {
        Hit best = null;
        for (TitanEntity t : titans(level)) {
            AABB box = reachBox(t);
            if (!box.contains(a) && box.clip(a, b).isEmpty()) {
                continue;
            }
            TitanGeometry.MeshHit h = TitanGeometry.raycastMesh(t, a, b, 1f);
            if (h != null && (best == null || h.t() < best.t())) {
                best = new Hit(t, h);
            }
        }
        return best;
    }
}
