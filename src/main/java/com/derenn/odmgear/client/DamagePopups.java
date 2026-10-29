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

    private DamagePopups() {
    }

    public static void add(Vec3 pos, int value) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        int color = value >= 1000 ? 0xFF3030 : 0xFFE030;
        popups.add(new Popup(pos, String.valueOf(value), color, mc.level.getGameTime()));
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
            float scale = (float) (0.03 * Math.max(1.0, dist / 6.0)) * pop;
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
