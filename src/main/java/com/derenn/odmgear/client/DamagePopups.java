package com.derenn.odmgear.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Floating damage numbers (hearts x 100), Minecraft font with a black outline: yellow below 1000, red from 1000. */
public final class DamagePopups {
    static final int LIFE_TICKS = 30;

    private static final class Popup {
        final Vec3 pos;
        final String text;
        final int color;
        final long start;

        Popup(Vec3 pos, String text, int color, long start) {
            this.pos = pos;
            this.text = text;
            this.color = color;
            this.start = start;
        }
    }

    private static final List<Popup> popups = new ArrayList<>();

    /** AoTTG-style numbers at the top centre of the screen: newest at the bottom slot, older ones move up, 1.5x smaller. */
    private static final class HudEntry {
        final String text;
        final int color;
        final long born;
        float slot;

        HudEntry(String text, int color, long born) {
            this.text = text;
            this.color = color;
            this.born = born;
        }
    }

    static final long HUD_LIFE_MS = 3000;
    static final float HUD_SCALE = 2.0f;
    static final float HUD_SHRINK = 1.5f;
    private static final List<HudEntry> hud = new ArrayList<>();
    private static long lastHudFrame;

    private DamagePopups() {
    }

    public static void add(Vec3 pos, int value) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        int color = value >= 1000 ? 0xFF3030 : 0xFFE030;
        popups.add(new Popup(pos, String.valueOf(value), color, mc.level.getGameTime()));
        HudEntry e = new HudEntry(String.valueOf(value), color, System.currentTimeMillis());
        e.slot = -0.6f; // slides into the main slot
        hud.add(0, e);
        if (hud.size() > 6) {
            hud.remove(hud.size() - 1);
        }
    }

    public static void renderHud(net.minecraft.client.gui.GuiGraphics g, int screenWidth, int screenHeight) {
        long now = System.currentTimeMillis();
        float dt = lastHudFrame == 0 ? 0f : Math.min(0.1f, (now - lastHudFrame) / 1000f);
        lastHudFrame = now;
        hud.removeIf(e -> now - e.born > HUD_LIFE_MS);
        if (hud.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        MultiBufferSource.BufferSource buffers = g.bufferSource();
        float cx = screenWidth / 2f;
        float baseY = screenHeight * 0.18f; // bottom edge of the newest number
        float y = baseY;
        for (int i = 0; i < hud.size(); i++) {
            HudEntry e = hud.get(i);
            e.slot += (i - e.slot) * Math.min(1f, dt * 14f);
            float scale = HUD_SCALE / (float) Math.pow(HUD_SHRINK, Math.max(0f, e.slot));
            float age = now - e.born;
            float alpha = age > HUD_LIFE_MS - 300 ? Math.max(0f, (HUD_LIFE_MS - age) / 300f) : 1f;
            if (e.slot < 0) {
                alpha *= 1f + e.slot / 0.6f * 0.8f;
            }
            FormattedCharSequence seq = Component.literal(e.text).getVisualOrderText();
            float w = font.width(seq);
            float h = font.lineHeight;
            float top = y - h * scale;
            g.pose().pushPose();
            g.pose().translate(cx, top, 0);
            g.pose().scale(scale, scale, 1f);
            int bgAlpha = (int) (0x90 * alpha) << 24;
            g.fill((int) Math.floor(-w / 2f - 4), -3, (int) Math.ceil(w / 2f + 4), (int) h + 2, bgAlpha);
            int a = Math.max(4, (int) (255 * alpha)) << 24;
            font.drawInBatch8xOutline(seq, -w / 2f, 0, (e.color & 0xFFFFFF) | a, a, g.pose().last().pose(), buffers, 0xF000F0);
            g.pose().popPose();
            g.flush();
            y = top - 4 * scale / HUD_SCALE * 2f;
        }
    }

    public static void render(PoseStack ps, Camera camera, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || popups.isEmpty()) {
            return;
        }
        long now = mc.level.getGameTime();
        Vec3 cam = camera.getPosition();
        Font font = mc.font;
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Iterator<Popup> it = popups.iterator();
        while (it.hasNext()) {
            Popup p = it.next();
            float age = now - p.start + partialTick;
            if (age > LIFE_TICKS || age < -5) {
                it.remove();
                continue;
            }
            Vec3 at = p.pos.add(0, age * 0.05, 0);
            double dist = at.distanceTo(cam);
            // grow with distance so the number stays readable; pop in a bit at the start
            float pop = age < 3 ? 1.6f - 0.2f * age : 1f;
            float scale = (float) (0.02 * Math.max(1.0, dist / 6.0)) * pop;
            ps.pushPose();
            ps.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
            ps.mulPose(camera.rotation());
            ps.scale(-scale, -scale, scale);
            Matrix4f m = ps.last().pose();
            FormattedCharSequence seq = Component.literal(p.text).getVisualOrderText();
            float x = -font.width(seq) / 2f;
            font.drawInBatch8xOutline(seq, x, 0, p.color, 0xFF000000, m, buffers, 0xF000F0);
            ps.popPose();
        }
        buffers.endBatch();
    }
}
