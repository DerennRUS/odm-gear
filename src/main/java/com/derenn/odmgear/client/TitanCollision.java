package com.derenn.odmgear.client;

import com.derenn.odmgear.titan.TitanEntity;
import com.derenn.odmgear.titan.TitanGeometry;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The player bumps into titan bodies (capsules from AoTTG2's titan colliders) and slides along them.
 * The player is a vertical capsule from feet+0.3 to feet+1.5 with radius 0.3.
 */
public final class TitanCollision {
    static final double PLAYER_R = 0.3;
    static final double STEP = 0.3;

    private TitanCollision() {
    }

    private static List<double[]> capsulesNear(LocalPlayer p, AABB area) {
        List<double[]> out = new ArrayList<>();
        for (TitanEntity t : TitanPick.titans(p.level())) {
            if (t.getId() == -1 || !TitanPick.reachBox(t).intersects(area)) {
                continue;
            }
            out.addAll(TitanGeometry.bodyCapsules(t, 1f));
        }
        return out;
    }

    /**
     * Moves the player's feet from 'from' toward 'to' in small steps; on contact the player is pushed out of the
     * body and the remaining motion slides along it. Returns the reachable end position and the normal of the last
     * contact (or null) so the caller can drop the velocity component into the body.
     */
    public static Vec3[] sweep(LocalPlayer p, Vec3 from, Vec3 to) {
        double h = p.getBbHeight();
        AABB area = new AABB(from, to).inflate(2 + h);
        List<double[]> caps = capsulesNear(p, area);
        if (caps.isEmpty()) {
            return new Vec3[]{to, null};
        }
        Vec3 move = to.subtract(from);
        int steps = Math.max(1, (int) Math.ceil(move.length() / STEP));
        Vec3 step = move.scale(1.0 / steps);
        Vec3 cur = from;
        Vec3 normal = null;
        for (int i = 0; i < steps; i++) {
            cur = cur.add(step);
            for (int iter = 0; iter < 3; iter++) {
                Vec3 n = pushOut(cur, h, caps);
                if (n == null) {
                    break;
                }
                cur = cur.add(n);
                Vec3 dir = n.normalize();
                normal = dir;
                double into = step.dot(dir);
                if (into < 0) {
                    step = step.subtract(dir.scale(into));
                }
            }
        }
        return new Vec3[]{cur, normal};
    }

    /** Push vector that moves a player standing at 'feet' out of all capsules, or null if not touching. */
    static Vec3 pushOut(Vec3 feet, double height, List<double[]> caps) {
        double ax = feet.x, ay = feet.y + PLAYER_R, az = feet.z;
        double by = feet.y + Math.max(PLAYER_R, height - PLAYER_R);
        Vec3 total = null;
        for (double[] c : caps) {
            double[] st = closest(ax, ay, az, ax, by, az, c[0], c[1], c[2], c[3], c[4], c[5]);
            double px = ax, py = ay + (by - ay) * st[0], pz = az;
            double qx = c[0] + (c[3] - c[0]) * st[1], qy = c[1] + (c[4] - c[1]) * st[1], qz = c[2] + (c[5] - c[2]) * st[1];
            double dx = px - qx, dy = py - qy, dz = pz - qz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double r = PLAYER_R + c[6];
            if (dist >= r) {
                continue;
            }
            Vec3 n = dist > 1.0E-6 ? new Vec3(dx / dist, dy / dist, dz / dist) : new Vec3(0, 1, 0);
            Vec3 push = n.scale(r - dist + 1.0E-3);
            total = total == null ? push : total.add(push);
        }
        return total;
    }

    /** Parameters (s, t) of the closest points between segments p0-p1 and q0-q1. */
    static double[] closest(double p0x, double p0y, double p0z, double p1x, double p1y, double p1z,
                            double q0x, double q0y, double q0z, double q1x, double q1y, double q1z) {
        double d1x = p1x - p0x, d1y = p1y - p0y, d1z = p1z - p0z;
        double d2x = q1x - q0x, d2y = q1y - q0y, d2z = q1z - q0z;
        double rx = p0x - q0x, ry = p0y - q0y, rz = p0z - q0z;
        double a = d1x * d1x + d1y * d1y + d1z * d1z;
        double e = d2x * d2x + d2y * d2y + d2z * d2z;
        double f = d2x * rx + d2y * ry + d2z * rz;
        double s, t;
        if (a < 1.0E-12 && e < 1.0E-12) {
            return new double[]{0, 0};
        }
        if (a < 1.0E-12) {
            s = 0;
            t = clamp(f / e);
        } else {
            double c = d1x * rx + d1y * ry + d1z * rz;
            if (e < 1.0E-12) {
                t = 0;
                s = clamp(-c / a);
            } else {
                double b = d1x * d2x + d1y * d2y + d1z * d2z;
                double den = a * e - b * b;
                s = den > 1.0E-12 ? clamp((b * f - c * e) / den) : 0;
                t = (b * s + f) / e;
                if (t < 0) {
                    t = 0;
                    s = clamp(-c / a);
                } else if (t > 1) {
                    t = 1;
                    s = clamp((b - c) / a);
                }
            }
        }
        return new double[]{s, t};
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** Walking (vanilla movement): if a titan body overlaps the player, push the player out of it. */
    public static void depenetrate(LocalPlayer p) {
        AABB area = p.getBoundingBox().inflate(2);
        List<double[]> caps = capsulesNear(p, area);
        if (caps.isEmpty()) {
            return;
        }
        Vec3 n = pushOut(p.position(), p.getBbHeight(), caps);
        if (n == null) {
            return;
        }
        p.setPos(p.position().add(n));
        Vec3 dir = n.normalize();
        Vec3 v = p.getDeltaMovement();
        double into = v.dot(dir);
        if (into < 0) {
            p.setDeltaMovement(v.subtract(dir.scale(into)));
        }
    }
}
