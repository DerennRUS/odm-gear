package com.derenn.odmgear.client;

import com.derenn.odmgear.OdmMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the ropes as camera-facing ribbons with a little bit of physics:
 * - while a hook flies, the rope follows the path the hook actually travelled and bends toward
 *   where the player is now (same idea as AoTTG2's hook node trail);
 * - once attached, the rope becomes a small verlet chain pinned to the player and the hook, so it
 *   sags, lags behind fast movement and snaps taut.
 */
public final class RopeRenderer {
    private static final int SEGMENTS = 16;
    private static final double WIDTH = 0.065;
    private static final double TRAIL_FALLOFF = 0.75;

    private static final RopeSim LEFT = new RopeSim();
    private static final RopeSim RIGHT = new RopeSim();
    private static long lastFrame = System.nanoTime();

    private RopeRenderer() {
    }

    private static final class RopeSim {
        final Vec3[] p = new Vec3[SEGMENTS + 1];
        final Vec3[] prev = new Vec3[SEGMENTS + 1];
        int attachId = -1;
        boolean ready;
    }

    public static void render(PoseStack ps, Vec3 cam, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        OdmPhysics odm = OdmPhysics.INSTANCE;
        long now = System.nanoTime();
        double frameDt = Math.min((now - lastFrame) / 1.0E9, 0.05);
        lastFrame = now;
        if (p == null) {
            return;
        }
        if (!odm.leftHook.isVisible() && !odm.rightHook.isVisible()) {
            LEFT.ready = false;
            RIGHT.ready = false;
            return;
        }
        Vec3 feet = p.getPosition(partialTick);
        float yaw = p.getViewYRot(partialTick);
        double gravity = OdmPhysics.GRAVITY * OdmPhysics.scale();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (Hook h : new Hook[]{odm.leftHook, odm.rightHook}) {
            RopeSim sim = h.left ? LEFT : RIGHT;
            if (!h.isVisible()) {
                sim.ready = false;
                continue;
            }
            Vec3 anchor = OdmPhysics.anchor(feet, p.getBbHeight(), yaw, h.left);
            List<Vec3> points;
            Vec3 tipDir;
            if (h.state == Hook.State.FLYING) {
                Vec3 tip = h.prevPos.lerp(h.pos, partialTick);
                points = flightCurve(h, anchor, tip);
                tipDir = h.flightDir;
                sim.ready = false;
            } else {
                if (!sim.ready || sim.attachId != h.attachId) {
                    initSim(sim, resample(flightCurve(h, anchor, h.pos), SEGMENTS + 1));
                    sim.attachId = h.attachId;
                    sim.ready = true;
                }
                stepSim(sim, anchor, h.renderPos(partialTick), gravity, frameDt);
                points = List.of(sim.p);
                Vec3 last = sim.p[SEGMENTS].subtract(sim.p[SEGMENTS - 1]);
                tipDir = last.lengthSqr() > 1.0E-8 ? last.normalize() : h.flightDir;
            }
            VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());
            ribbon(vc, ps.last().pose(), points, cam);
            hookHead(mc, ps, buffers, points.get(points.size() - 1), tipDir);
        }
        ps.popPose();
        buffers.endBatch();
    }

    /** Trail of real flight positions; the part near the player is pulled toward the current anchor. */
    private static List<Vec3> flightCurve(Hook h, Vec3 anchor, Vec3 tip) {
        List<Vec3> out = new ArrayList<>();
        List<Vec3> nodes = h.nodes;
        if (nodes.isEmpty()) {
            out.add(anchor);
            out.add(tip);
            return out;
        }
        Vec3 shift = anchor.subtract(nodes.get(0));
        double f = 1.0;
        for (Vec3 n : nodes) {
            out.add(n.add(shift.scale(f)));
            f *= TRAIL_FALLOFF;
        }
        out.add(tip);
        return out;
    }

    private static List<Vec3> resample(List<Vec3> pts, int count) {
        double total = 0;
        double[] acc = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            total += pts.get(i).distanceTo(pts.get(i - 1));
            acc[i] = total;
        }
        List<Vec3> out = new ArrayList<>();
        int j = 1;
        for (int k = 0; k < count; k++) {
            double target = total * k / (count - 1);
            while (j < pts.size() - 1 && acc[j] < target) {
                j++;
            }
            double segLen = acc[j] - acc[j - 1];
            double t = segLen < 1.0E-9 ? 0 : (target - acc[j - 1]) / segLen;
            out.add(pts.get(j - 1).lerp(pts.get(j), Math.max(0, Math.min(1, t))));
        }
        return out;
    }

    private static void initSim(RopeSim sim, List<Vec3> pts) {
        for (int i = 0; i <= SEGMENTS; i++) {
            sim.p[i] = pts.get(i);
            sim.prev[i] = pts.get(i);
        }
    }

    private static void stepSim(RopeSim sim, Vec3 anchor, Vec3 hook, double gravity, double dt) {
        Vec3 g = new Vec3(0, -gravity * 0.25 * dt * dt, 0);
        for (int i = 1; i < SEGMENTS; i++) {
            Vec3 cur = sim.p[i];
            Vec3 vel = cur.subtract(sim.prev[i]).scale(0.86);
            sim.prev[i] = cur;
            sim.p[i] = cur.add(vel).add(g);
        }
        sim.p[0] = anchor;
        sim.p[SEGMENTS] = hook;
        // the rope can't stretch beyond a slightly slack straight line, but it may sag / bunch up
        double rest = anchor.distanceTo(hook) * 1.01 / SEGMENTS;
        for (int it = 0; it < 12; it++) {
            for (int i = 0; i < SEGMENTS; i++) {
                Vec3 a = sim.p[i];
                Vec3 b = sim.p[i + 1];
                Vec3 d = b.subtract(a);
                double len = d.length();
                if (len <= rest || len < 1.0E-9) {
                    continue;
                }
                Vec3 corr = d.scale((len - rest) / len);
                boolean pinA = i == 0;
                boolean pinB = i + 1 == SEGMENTS;
                if (pinA && !pinB) {
                    sim.p[i + 1] = b.subtract(corr);
                } else if (pinB && !pinA) {
                    sim.p[i] = a.add(corr);
                } else if (!pinA) {
                    sim.p[i] = a.add(corr.scale(0.5));
                    sim.p[i + 1] = b.subtract(corr.scale(0.5));
                }
            }
        }
        // stiff cable: the hook end stays nearly straight, bending happens mostly near the player
        for (int i = 1; i < SEGMENTS; i++) {
            double t = (double) i / SEGMENTS;
            Vec3 straight = anchor.lerp(hook, t);
            double w = Math.pow(t, 0.7) * 0.35;
            sim.p[i] = sim.p[i].lerp(straight, w);
        }
    }

    /** Camera-facing strip, every other segment a bit lighter so it reads as a braided cable. */
    private static void ribbon(VertexConsumer vc, Matrix4f pose, List<Vec3> pts, Vec3 cam) {
        double half = WIDTH * 0.5;
        for (int i = 0; i < pts.size() - 1; i++) {
            Vec3 a = pts.get(i);
            Vec3 b = pts.get(i + 1);
            Vec3 d = b.subtract(a);
            if (d.lengthSqr() < 1.0E-10) {
                continue;
            }
            Vec3 toCam = cam.subtract(a.add(b).scale(0.5));
            Vec3 side = d.cross(toCam);
            if (side.lengthSqr() < 1.0E-10) {
                continue;
            }
            side = side.normalize().scale(half);
            int c = (i & 1) == 0 ? 38 : 58;
            quad(vc, pose, a.subtract(side), a.add(side), b.add(side), b.subtract(side), c, c, c + 4);
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int r, int g, int bl) {
        vc.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(r, g, bl, 255).endVertex();
        vc.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(r, g, bl, 255).endVertex();
        vc.vertex(m, (float) c.x, (float) c.y, (float) c.z).color(r, g, bl, 255).endVertex();
        vc.vertex(m, (float) d.x, (float) d.y, (float) d.z).color(r, g, bl, 255).endVertex();
        // back face too, in case the render type culls
        vc.vertex(m, (float) d.x, (float) d.y, (float) d.z).color(r, g, bl, 255).endVertex();
        vc.vertex(m, (float) c.x, (float) c.y, (float) c.z).color(r, g, bl, 255).endVertex();
        vc.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(r, g, bl, 255).endVertex();
        vc.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(r, g, bl, 255).endVertex();
    }

    /** Pixel hook head (an item sprite) pointing along the rope. */
    private static void hookHead(Minecraft mc, PoseStack ps, MultiBufferSource buffers, Vec3 pos, Vec3 dir) {
        if (mc.level == null) {
            return;
        }
        int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(pos));
        ps.pushPose();
        ps.translate(pos.x, pos.y, pos.z);
        Vector3f d = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
        if (d.lengthSquared() > 1.0E-6f) {
            d.normalize();
            ps.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 1, 0), d));
        }
        // spin the sprite around the rope axis so it is never seen edge-on from the side
        ps.mulPose(Axis.YP.rotationDegrees(45));
        ps.scale(0.55f, 0.55f, 0.55f);
        ps.translate(0, -0.35, 0);
        ItemStack stack = new ItemStack(OdmMod.HOOK_TIP.get());
        mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, ps, buffers, mc.level, 0);
        ps.mulPose(Axis.YP.rotationDegrees(90));
        mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, ps, buffers, mc.level, 0);
        ps.popPose();
    }
}
