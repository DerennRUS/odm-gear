package com.derenn.odmgear.titan;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * World-space geometry of a posed titan: skinned mesh (for hooks), hurtboxes (nape, eyes, forearms, legs),
 * hand/foot hitboxes and grab sockets. Hurtbox data comes from AoTTG2 BasicTitan0.prefab
 * (see tools/titan/hurtboxes.py), converted into rest model space: model = (60.2 - ux, uy + 8.1, uz) / 6.375.
 */
public final class TitanGeometry {
    /** AoTTG titan-local units per model unit (the titan is ~18 local units tall). */
    public static final float LOCAL_PER_MODEL = 6.375f;

    public enum Part { NAPE, EYES, FOREARM_L, FOREARM_R, LEG_L, LEG_R }

    private record Shape(Part part, String bone, float x0, float y0, float z0, float x1, float y1, float z1, float r, boolean box) {
        static Shape capsule(Part p, String bone, float x0, float y0, float z0, float x1, float y1, float z1, float r) {
            return new Shape(p, bone, x0, y0, z0, x1, y1, z1, r, false);
        }

        /** Box given by centre and half extents. */
        static Shape box(Part p, String bone, float cx, float cy, float cz, float hx, float hy, float hz) {
            return new Shape(p, bone, cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz, 0, true);
        }
    }

    /** Order = priority when two hurtboxes are hit at the same distance. */
    private static final Shape[] SHAPES = {
            // NapeHurtbox: capsule r 0.0709, height 0.156 (x15) on the neck, behind it
            Shape.capsule(Part.NAPE, "neck", 0f, 2.058f, -0.162f, 0f, 2.092f, -0.158f, 0.1668f),
            // EyesHurtbox: box 2.72 x 1.14 x 1 on the head
            Shape.box(Part.EYES, "head", 0.0035f, 2.4038f, 0.4285f, 0.213f, 0.089f, 0.0785f),
            // ForearmL/RHurtbox: spheres r 0.07 (x15) at the elbow end of the forearm
            Shape.capsule(Part.FOREARM_L, "forearm.L", 0.795f, 1.585f, -0.0104f, 0.795f, 1.585f, -0.0104f, 0.165f),
            Shape.capsule(Part.FOREARM_R, "forearm.R", -0.795f, 1.585f, -0.0104f, -0.795f, 1.585f, -0.0104f, 0.165f),
            // LegL/RHurtbox: boxes 0.1 x 0.1 x 0.04 (x15) behind the shins
            Shape.box(Part.LEG_L, "shin.L", 0.2888f, 0.3169f, -0.1596f, 0.1176f, 0.1176f, 0.047f),
            Shape.box(Part.LEG_R, "shin.R", -0.2893f, 0.3182f, -0.1578f, 0.1176f, 0.1176f, 0.047f),
    };

    /** Hand / foot hitboxes (HandHitboxRadius 0.18, FootHitboxRadius 0.12 in AoTTG2, scaled x15). */
    public static final float HAND_RADIUS = 0.18f * 15f / LOCAL_PER_MODEL;
    public static final float FOOT_RADIUS = 0.12f * 15f / LOCAL_PER_MODEL;
    public static final int HAND_L = 0, HAND_R = 1, FOOT_L = 2, FOOT_R = 3;
    private static final String[] HIT_BONES = {"hand.L.001", "hand.R.001", "foot.L", "foot.R"};
    private static final float[][] HIT_POINTS = {
            {1.3227f, 1.2788f, 0.0183f}, {-1.3227f, 1.2788f, 0.0183f}, {0.2934f, 0.098f, -0.0089f}, {-0.2934f, 0.098f, -0.0089f}};
    /** GrabL/RSocket on the hands. */
    private static final float[][] GRAB_POINTS = {{1.2889f, 1.2534f, 0.1214f}, {-1.2889f, 1.2534f, 0.1214f}};
    /** Neck point used for AoTTG "angles to target". */
    public static final float[] NECK = {0f, 1.96f, 0f};

    private TitanGeometry() {
    }

    /** One evaluated pose of a titan. */
    public static final class Frame {
        int tick = Integer.MIN_VALUE;
        float partial = -1f;
        boolean meshValid;
        public float[] pose;
        float[] verts;
        int[] tris;
        int[] vertBone;
        String meshKey;
        public double x, y, z;
        public float cos, sin, scale;
    }

    /**
     * Solid body colliders (AoTTG2 BasicTitan0.prefab, layer 12): capsules on the torso, neck, head, arms and legs,
     * spheres on the hands. arm: 0 = always, 1 = left forearm/hand, 2 = right forearm/hand (gone while cut off).
     */
    private record Collider(String bone, float x0, float y0, float z0, float x1, float y1, float z1, float r, int arm) {
    }

    private static final Collider[] COLLIDERS = {
            new Collider("Controller.Body", 0f, 1.057f, -0.0062f, 0f, 1.2711f, -0.0062f, 0.4506f, 0),
            new Collider("chest", 0f, 1.4261f, 0.0211f, 0f, 1.658f, 0.006f, 0.3982f, 0),
            new Collider("neck", 0f, 2.0244f, 0.0024f, 0f, 2.0813f, 0.008f, 0.2128f, 0),
            new Collider("head", 0f, 2.4176f, 0.0647f, 0f, 2.4754f, 0.0704f, 0.3390f, 0),
            new Collider("upper_arm.L", 0.7589f, 1.5802f, -0.0063f, 0.3956f, 1.7515f, -0.0063f, 0.1301f, 0),
            new Collider("upper_arm.R", -0.7589f, 1.5802f, -0.0063f, -0.3956f, 1.7515f, -0.0063f, 0.1301f, 0),
            new Collider("forearm.L", 1.1186f, 1.4109f, 0.0094f, 0.7763f, 1.5758f, 0.0094f, 0.1153f, 1),
            new Collider("forearm.R", -1.1186f, 1.4109f, 0.0094f, -0.7763f, 1.5758f, 0.0094f, 0.1153f, 2),
            new Collider("hand.L.001", 1.3227f, 1.2787f, 0.0183f, 1.3227f, 1.2787f, 0.0183f, 0.1750f, 1),
            new Collider("hand.R.001", -1.3227f, 1.2787f, 0.0183f, -1.3227f, 1.2787f, 0.0183f, 0.1750f, 2),
            new Collider("thigh.L", 0.2983f, 0.4724f, -0.022f, 0.2852f, 0.726f, -0.022f, 0.1988f, 0),
            new Collider("thigh.R", -0.2779f, 0.4715f, -0.0242f, -0.2501f, 0.724f, -0.0242f, 0.1988f, 0),
            new Collider("shin.L", 0.2931f, 0.1453f, -0.022f, 0.2936f, 0.3535f, -0.022f, 0.1380f, 0),
            new Collider("shin.R", -0.2931f, 0.1453f, -0.022f, -0.2936f, 0.3535f, -0.022f, 0.1380f, 0),
    };

    /** World-space body capsules {x0, y0, z0, x1, y1, z1, radius} of a titan in its current pose. */
    public static java.util.List<double[]> bodyCapsules(TitanEntity e, float partial) {
        Frame f = frame(e, partial, false);
        java.util.List<double[]> out = new java.util.ArrayList<>(COLLIDERS.length);
        for (Collider c : COLLIDERS) {
            if ((c.arm == 1 && e.armDisabled(true)) || (c.arm == 2 && e.armDisabled(false))) {
                continue;
            }
            Vec3 a = bonePoint(f, c.bone, c.x0, c.y0, c.z0);
            Vec3 b = bonePoint(f, c.bone, c.x1, c.y1, c.z1);
            out.add(new double[]{a.x, a.y, a.z, b.x, b.y, b.z, c.r * f.scale});
        }
        return out;
    }

    // ------------------------------------------------------------------ frames and transforms

    public static Frame frame(TitanEntity e, float partial, boolean mesh) {
        Frame f = e.geometryFrame;
        TitanModelData d = TitanModelData.get();
        if (f.pose == null) {
            f.pose = new float[d.boneCount * 12];
        }
        if (f.tick != e.tickCount || f.partial != partial) {
            f.tick = e.tickCount;
            f.partial = partial;
            f.meshValid = false;
            e.computePose(partial, f.pose);
            f.x = Mth.lerp(partial, e.xo, e.getX());
            f.y = Mth.lerp(partial, e.yo, e.getY());
            f.z = Mth.lerp(partial, e.zo, e.getZ());
            float yaw = Mth.rotLerp(partial, e.yBodyRotO, e.yBodyRot) * Mth.DEG_TO_RAD;
            f.cos = Mth.cos(yaw);
            f.sin = Mth.sin(yaw);
            f.scale = e.heightBlocks() / d.height;
        }
        if (mesh && !f.meshValid) {
            buildMesh(e, d, f);
            f.meshValid = true;
        }
        return f;
    }

    private static void buildMesh(TitanEntity e, TitanModelData d, Frame f) {
        TitanModelData.Mesh body = d.meshes.get("body_" + e.body());
        TitanModelData.Mesh head = d.meshes.get("head_" + e.head());
        String key = body.name + "|" + head.name;
        int nb = body.vertexCount();
        int n = nb + head.vertexCount();
        if (!key.equals(f.meshKey)) {
            f.meshKey = key;
            f.verts = new float[n * 3];
            f.tris = new int[body.triVerts.length + head.triVerts.length];
            for (int i = 0; i < body.triVerts.length; i++) {
                f.tris[i] = body.triVerts[i];
            }
            for (int i = 0; i < head.triVerts.length; i++) {
                f.tris[body.triVerts.length + i] = nb + head.triVerts[i];
            }
            f.vertBone = new int[n];
            for (int v = 0; v < n; v++) {
                TitanModelData.Mesh m = v < nb ? body : head;
                int vi = v < nb ? v : v - nb;
                int best = m.bones[vi][0];
                float bw = -1;
                for (int k = 0; k < m.bones[vi].length; k++) {
                    if (m.weights[vi][k] > bw) {
                        bw = m.weights[vi][k];
                        best = m.bones[vi][k];
                    }
                }
                f.vertBone[v] = best;
            }
        }
        float[] tmp = new float[nb * 3];
        d.skin(body, f.pose, tmp);
        System.arraycopy(tmp, 0, f.verts, 0, nb * 3);
        float[] tmp2 = new float[head.vertexCount() * 3];
        d.skin(head, f.pose, tmp2);
        System.arraycopy(tmp2, 0, f.verts, nb * 3, tmp2.length);
    }

    public static Vec3 toWorld(Frame f, double mx, double my, double mz) {
        double x = mx * f.scale, y = my * f.scale, z = mz * f.scale;
        return new Vec3(f.x + x * f.cos - z * f.sin, f.y + y, f.z + x * f.sin + z * f.cos);
    }

    /** World point to model space (x, y, z). */
    public static double[] toModel(Frame f, Vec3 w) {
        double dx = w.x - f.x, dy = w.y - f.y, dz = w.z - f.z;
        return new double[]{(dx * f.cos + dz * f.sin) / f.scale, dy / f.scale, (-dx * f.sin + dz * f.cos) / f.scale};
    }

    /** A rest-pose model point carried by a bone, in world space. */
    public static Vec3 bonePoint(Frame f, String bone, float rx, float ry, float rz) {
        int b = TitanModelData.get().boneIndex.get(bone);
        float[] o = new float[3];
        TitanModelData.transform(f.pose, b, rx, ry, rz, o);
        return toWorld(f, o[0], o[1], o[2]);
    }

    public static Vec3 hitboxCenter(Frame f, int which) {
        float[] p = HIT_POINTS[which];
        return bonePoint(f, HIT_BONES[which], p[0], p[1], p[2]);
    }

    public static double hitboxRadius(Frame f, int which) {
        return (which <= HAND_R ? HAND_RADIUS : FOOT_RADIUS) * f.scale;
    }

    public static Vec3 grabSocket(Frame f, boolean left) {
        float[] p = GRAB_POINTS[left ? 0 : 1];
        return bonePoint(f, left ? "hand.L" : "hand.R", p[0], p[1], p[2]);
    }

    public static Vec3 forearmPoint(Frame f, boolean left) {
        return bonePoint(f, left ? "forearm.L" : "forearm.R", left ? 0.795f : -0.795f, 1.585f, -0.0104f);
    }

    public static Vec3 napePoint(Frame f) {
        return bonePoint(f, "neck", 0f, 2.075f, -0.16f);
    }

    // ------------------------------------------------------------------ mesh raycast (hooks)

    public record MeshHit(double t, int tri, float u, float v, Vec3 point) {
    }

    /** First hit of segment a->b with the titan's body/head mesh; t in 0..1 along the segment. */
    public static MeshHit raycastMesh(TitanEntity e, Vec3 a, Vec3 b, float partial) {
        Frame f = frame(e, partial, true);
        double[] pa = toModel(f, a);
        double[] pb = toModel(f, b);
        double dx = pb[0] - pa[0], dy = pb[1] - pa[1], dz = pb[2] - pa[2];
        float[] v = f.verts;
        int[] tr = f.tris;
        double bestT = 2;
        int bestTri = -1;
        double bu = 0, bv = 0;
        for (int t = 0; t + 2 < tr.length; t += 3) {
            int i0 = tr[t] * 3, i1 = tr[t + 1] * 3, i2 = tr[t + 2] * 3;
            double e1x = v[i1] - v[i0], e1y = v[i1 + 1] - v[i0 + 1], e1z = v[i1 + 2] - v[i0 + 2];
            double e2x = v[i2] - v[i0], e2y = v[i2 + 1] - v[i0 + 1], e2z = v[i2 + 2] - v[i0 + 2];
            double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1.0E-12) {
                continue;
            }
            double inv = 1.0 / det;
            double sx = pa[0] - v[i0], sy = pa[1] - v[i0 + 1], sz = pa[2] - v[i0 + 2];
            double u = (sx * px + sy * py + sz * pz) * inv;
            if (u < 0 || u > 1) {
                continue;
            }
            double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
            double w = (dx * qx + dy * qy + dz * qz) * inv;
            if (w < 0 || u + w > 1) {
                continue;
            }
            double tt = (e2x * qx + e2y * qy + e2z * qz) * inv;
            if (tt >= 0 && tt <= 1 && tt < bestT) {
                bestT = tt;
                bestTri = t / 3;
                bu = u;
                bv = w;
            }
        }
        if (bestTri < 0) {
            return null;
        }
        return new MeshHit(bestT, bestTri, (float) bu, (float) bv, a.add(b.subtract(a).scale(bestT)));
    }

    /** Current world position of a point stuck to a mesh triangle. */
    public static Vec3 surfacePoint(TitanEntity e, int tri, float u, float v, float partial) {
        Frame f = frame(e, partial, true);
        if (tri * 3 + 2 >= f.tris.length) {
            return null;
        }
        float[] p = f.verts;
        int i0 = f.tris[tri * 3] * 3, i1 = f.tris[tri * 3 + 1] * 3, i2 = f.tris[tri * 3 + 2] * 3;
        float w = 1 - u - v;
        return toWorld(f, p[i0] * w + p[i1] * u + p[i2] * v, p[i0 + 1] * w + p[i1 + 1] * u + p[i2 + 1] * v,
                p[i0 + 2] * w + p[i1 + 2] * u + p[i2 + 2] * v);
    }

    /** True if the triangle belongs to a forearm/hand that is currently cut off. */
    public static boolean triOnDisabledArm(TitanEntity e, int tri, float partial) {
        Frame f = frame(e, partial, true);
        if (tri * 3 >= f.tris.length) {
            return true;
        }
        int bone = f.vertBone[f.tris[tri * 3]];
        TitanModelData d = TitanModelData.get();
        return (e.armDisabled(true) && d.isArmBone(bone, true)) || (e.armDisabled(false) && d.isArmBone(bone, false));
    }

    // ------------------------------------------------------------------ hurtboxes (blades)

    public record PartHit(Part part, double t) {
    }

    /** Nearest hurtbox hit by segment a->b (world); margin in blocks makes the blade a bit forgiving. */
    public static PartHit hurtboxHit(TitanEntity e, Vec3 a, Vec3 b, double margin, float partial) {
        Frame f = frame(e, partial, false);
        TitanModelData d = TitanModelData.get();
        double[] ma = toModel(f, a);
        double[] mb = toModel(f, b);
        double m = margin / f.scale;
        PartHit best = null;
        for (Shape s : SHAPES) {
            if ((s.part == Part.FOREARM_L && e.armDisabled(true)) || (s.part == Part.FOREARM_R && e.armDisabled(false))) {
                continue;
            }
            int bone = d.boneIndex.get(s.bone);
            double[] ra = toRest(f.pose, bone, ma);
            double[] rb = toRest(f.pose, bone, mb);
            if (ra == null || rb == null) {
                continue;
            }
            double t = s.box ? segmentBox(ra, rb, s, m) : segmentCapsule(ra, rb, s, m);
            if (t >= 0 && (best == null || t < best.t - 1.0E-4)) {
                best = new PartHit(s.part, t);
            }
        }
        return best;
    }

    /**
     * Swept-sphere blade zone: a sphere of the given radius moved along a->b (the player's path this tick).
     * A hurtbox counts if it touches the zone and does not lie inside the rear cone behind the camera
     * (dot(direction to hurtbox, look) >= minDot). Returns the highest-priority part (nape first) or null.
     */
    public static Part zoneHit(TitanEntity e, Vec3 a, Vec3 b, double radius, double below, double above, Vec3 look, double minDot, float partial) {
        Frame f = frame(e, partial, false);
        TitanModelData d = TitanModelData.get();
        double[] ma = toModel(f, a);
        double[] mb = toModel(f, b);
        double m = radius / f.scale;
        for (Shape s : SHAPES) {
            if ((s.part == Part.FOREARM_L && e.armDisabled(true)) || (s.part == Part.FOREARM_R && e.armDisabled(false))) {
                continue;
            }
            int bone = d.boneIndex.get(s.bone);
            double[] ra = toRest(f.pose, bone, ma);
            double[] rb = toRest(f.pose, bone, mb);
            if (ra == null || rb == null) {
                continue;
            }
            double t = s.box ? segmentBoxNear(ra, rb, s, m) : segmentCapsule(ra, rb, s, m);
            if (t < 0) {
                continue;
            }
            Vec3 center = bonePoint(f, s.bone, (s.x0 + s.x1) * 0.5f, (s.y0 + s.y1) * 0.5f, (s.z0 + s.z1) * 0.5f);
            Vec3 at = a.lerp(b, t);
            // the zone is cut flat: only from 'below' under to 'above' over the path (the hurtbox may overlap the cut)
            double vh = (s.box ? (s.y1 - s.y0) * 0.5 : s.r + Math.abs(s.y1 - s.y0) * 0.5) * f.scale;
            if (center.y - vh > at.y + above || center.y + vh < at.y - below) {
                continue;
            }
            Vec3 dir = center.subtract(at);
            double len = dir.length();
            if (len < 1.0E-6 || dir.scale(1.0 / len).dot(look) >= minDot) {
                return s.part;
            }
        }
        return null;
    }

    /** Closest-approach parameter of a segment to a (rest-space) box if within distance m, else -1. */
    private static double segmentBoxNear(double[] a, double[] b, Shape s, double m) {
        double best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i <= 16; i++) {
            double t = i / 16.0;
            double x = a[0] + (b[0] - a[0]) * t, y = a[1] + (b[1] - a[1]) * t, z = a[2] + (b[2] - a[2]) * t;
            double dx = Math.max(0, Math.max(s.x0 - x, x - s.x1));
            double dy = Math.max(0, Math.max(s.y0 - y, y - s.y1));
            double dz = Math.max(0, Math.max(s.z0 - z, z - s.z1));
            double dd = dx * dx + dy * dy + dz * dz;
            if (dd < bestD) {
                bestD = dd;
                best = t;
            }
        }
        return bestD <= m * m ? best : -1;
    }

    /** Inverse of a bone's 3x4 skin matrix applied to a model point. */
    private static double[] toRest(float[] pose, int b, double[] p) {
        int o = b * 12;
        double a = pose[o], bb = pose[o + 1], c = pose[o + 2];
        double d = pose[o + 4], e = pose[o + 5], f = pose[o + 6];
        double g = pose[o + 8], h = pose[o + 9], i = pose[o + 10];
        double det = a * (e * i - f * h) - bb * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1.0E-9) {
            return null;
        }
        double x = p[0] - pose[o + 3], y = p[1] - pose[o + 7], z = p[2] - pose[o + 11];
        double inv = 1.0 / det;
        return new double[]{
                ((e * i - f * h) * x + (c * h - bb * i) * y + (bb * f - c * e) * z) * inv,
                ((f * g - d * i) * x + (a * i - c * g) * y + (c * d - a * f) * z) * inv,
                ((d * h - e * g) * x + (bb * g - a * h) * y + (a * e - bb * d) * z) * inv};
    }

    private static double segmentBox(double[] a, double[] b, Shape s, double m) {
        double[] min = {s.x0 - m, s.y0 - m, s.z0 - m};
        double[] max = {s.x1 + m, s.y1 + m, s.z1 + m};
        double t0 = 0, t1 = 1;
        for (int k = 0; k < 3; k++) {
            double d = b[k] - a[k];
            if (Math.abs(d) < 1.0E-12) {
                if (a[k] < min[k] || a[k] > max[k]) {
                    return -1;
                }
            } else {
                double ta = (min[k] - a[k]) / d, tb = (max[k] - a[k]) / d;
                if (ta > tb) {
                    double tmp = ta;
                    ta = tb;
                    tb = tmp;
                }
                t0 = Math.max(t0, ta);
                t1 = Math.min(t1, tb);
                if (t0 > t1) {
                    return -1;
                }
            }
        }
        return t0;
    }

    /** Segment vs capsule: returns the segment parameter of the closest approach if within radius, else -1. */
    private static double segmentCapsule(double[] p, double[] q, Shape s, double m) {
        double[] c0 = {s.x0, s.y0, s.z0};
        double[] c1 = {s.x1, s.y1, s.z1};
        double r = s.r + m;
        double d1x = q[0] - p[0], d1y = q[1] - p[1], d1z = q[2] - p[2];
        double d2x = c1[0] - c0[0], d2y = c1[1] - c0[1], d2z = c1[2] - c0[2];
        double rx = p[0] - c0[0], ry = p[1] - c0[1], rz = p[2] - c0[2];
        double aa = d1x * d1x + d1y * d1y + d1z * d1z;
        double ee = d2x * d2x + d2y * d2y + d2z * d2z;
        double ff = d2x * rx + d2y * ry + d2z * rz;
        double sP, tC;
        if (aa < 1.0E-12) {
            sP = 0;
            tC = ee < 1.0E-12 ? 0 : Mth.clamp(ff / ee, 0, 1);
        } else {
            double cc = d1x * rx + d1y * ry + d1z * rz;
            if (ee < 1.0E-12) {
                tC = 0;
                sP = Mth.clamp(-cc / aa, 0, 1);
            } else {
                double bb = d1x * d2x + d1y * d2y + d1z * d2z;
                double den = aa * ee - bb * bb;
                sP = den > 1.0E-12 ? Mth.clamp((bb * ff - cc * ee) / den, 0, 1) : 0;
                tC = (bb * sP + ff) / ee;
                if (tC < 0) {
                    tC = 0;
                    sP = Mth.clamp(-cc / aa, 0, 1);
                } else if (tC > 1) {
                    tC = 1;
                    sP = Mth.clamp((bb - cc) / aa, 0, 1);
                }
            }
        }
        double ox = p[0] + d1x * sP - (c0[0] + d2x * tC);
        double oy = p[1] + d1y * sP - (c0[1] + d2y * tC);
        double oz = p[2] + d1z * sP - (c0[2] + d2z * tC);
        return ox * ox + oy * oy + oz * oz <= r * r ? sP : -1;
    }
}
