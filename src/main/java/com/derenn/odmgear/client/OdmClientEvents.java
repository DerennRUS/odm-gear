package com.derenn.odmgear.client;

import com.derenn.odmgear.OdmMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = OdmMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OdmClientEvents {
    private static final OdmPhysics ODM = OdmPhysics.INSTANCE;
    private static final long DOUBLE_TAP_NS = 200_000_000L;
    private static final long[] lastTap = new long[4];

    private OdmClientEvents() {
    }

    private static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.screen == null && ODM.isUsable(mc.player);
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.CLIENT) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (event.player != mc.player || mc.player == null) {
            return;
        }
        if (TitanClient.applyHeld(mc.player)) {
            // grabbed by a titan: no gear, no movement
            ODM.vel = Vec3.ZERO;
            ODM.reset(mc.player);
            mc.player.setDeltaMovement(Vec3.ZERO);
            return;
        }
        ODM.tick(mc.player, mc);
    }

    /** Swallow vanilla actions that share a key with ODM controls (Q drop, E inventory, F swap...). */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        KeyMapping[] vanilla = {mc.options.keyDrop, mc.options.keyInventory, mc.options.keySwapOffhand, mc.options.keyPickItem};
        for (KeyMapping v : vanilla) {
            for (KeyMapping mine : OdmKeys.ACTION_KEYS) {
                if (OdmKeys.sameKey(mine, v)) {
                    while (v.consumeClick()) {
                        // discard
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !ODM.isUsable(mc.player)) {
            return;
        }
        Input input = event.getInput();
        if (OdmKeys.sameKey(OdmKeys.GAS, mc.options.keyShift)) {
            input.shiftKeyDown = false;
        }
        if (ODM.physicsActive) {
            // no vanilla air control: AoTTG only steers with gas
            input.leftImpulse = 0;
            input.forwardImpulse = 0;
            input.jumping = false;
        }
    }

    // ------------------------------------------------------------------ input

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        int key = event.getKey();
        int scan = event.getScanCode();
        if (OdmKeys.TOGGLE.matches(key, scan) && OdmMod.hasGear(mc.player)) {
            ODM.toggle(mc.player);
            return;
        }
        if (!ODM.isUsable(mc.player)) {
            return;
        }
        if (OdmKeys.HOOK_LEFT.matches(key, scan)) ODM.pressedLeft = true;
        if (OdmKeys.HOOK_RIGHT.matches(key, scan)) ODM.pressedRight = true;
        if (OdmKeys.HOOK_BOTH.matches(key, scan)) ODM.pressedBoth = true;
        if (OdmKeys.GAS.matches(key, scan)) { ODM.pressedGas = true; ODM.gasEdge = true; }
        if (OdmKeys.REEL_IN.matches(key, scan)) ODM.onScroll(-1);
        if (OdmKeys.REEL_OUT.matches(key, scan)) ODM.onScroll(1);

        // Dash: dash key + direction, or double tap of a direction (AoTTG DashDoubleTap)
        LocalPlayer p = mc.player;
        KeyMapping[] dirs = {mc.options.keyUp, mc.options.keyDown, mc.options.keyRight, mc.options.keyLeft};
        double[][] fr = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        if (OdmKeys.DASH.matches(key, scan)) {
            for (int i = 0; i < 4; i++) {
                if (dirs[i].isDown() || dirs[i].matches(key, scan)) {
                    ODM.pendingDash = dashDir(p, fr[i]);
                    break;
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            if (dirs[i].matches(key, scan)) {
                long now = System.nanoTime();
                if (OdmKeys.held(OdmKeys.DASH)) {
                    ODM.pendingDash = dashDir(p, fr[i]);
                } else if (now - lastTap[i] < DOUBLE_TAP_NS && !p.onGround()) {
                    ODM.pendingDash = dashDir(p, fr[i]);
                    lastTap[i] = 0;
                    continue;
                }
                lastTap[i] = now;
            }
        }
    }

    private static Vec3 dashDir(LocalPlayer p, double[] fr) {
        return OdmPhysics.forwardVector(p.getYRot()).scale(fr[0])
                .add(OdmPhysics.rightVector(p.getYRot()).scale(fr[1])).normalize();
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!active() || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        int b = event.getButton();
        boolean used = false;
        if (OdmKeys.HOOK_LEFT.matchesMouse(b)) { ODM.pressedLeft = true; used = true; }
        if (OdmKeys.HOOK_RIGHT.matchesMouse(b)) { ODM.pressedRight = true; used = true; }
        if (OdmKeys.HOOK_BOTH.matchesMouse(b)) { ODM.pressedBoth = true; used = true; }
        if (OdmKeys.GAS.matchesMouse(b)) { ODM.pressedGas = true; ODM.gasEdge = true; used = true; }
        if (OdmKeys.REEL_IN.matchesMouse(b)) { ODM.onScroll(-1); used = true; }
        if (OdmKeys.REEL_OUT.matchesMouse(b)) { ODM.onScroll(1); used = true; }
        if (used) {
            event.setCanceled(true); // don't also attack / use items
        }
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (!active()) {
            return;
        }
        ODM.onScroll(event.getScrollDelta());
        event.setCanceled(true); // the wheel reels instead of switching hotbar slots
    }

    // ------------------------------------------------------------------ rendering

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        RopeRenderer.render(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick());
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.options.hideGui) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        if (ODM.hasGearButDisabled(p)) {
            g.drawCenteredString(mc.font, "ODM OFF", cx, cy + 10, 0xAAAAAA);
            return;
        }
        if (!ODM.isUsable(p)) {
            return;
        }
        // hook range indicators left/right of the crosshair
        g.drawString(mc.font, "<", cx - 14, cy - 4, ODM.leftInRange ? 0x55FF55 : 0xFF5555, true);
        g.drawString(mc.font, ">", cx + 10, cy - 4, ODM.rightInRange ? 0x55FF55 : 0xFF5555, true);
        // distance to the block under the crosshair (blocks); red when out of hook range
        double d = ODM.aimDistance;
        double range = Hook.FLIGHT_SPEED * Hook.MAX_FLIGHT_TIME * OdmPhysics.scale();
        String text = d < 0 ? "???" : String.valueOf((int) d);
        int color = d < 0 || d > range ? 0xFF6060 : 0xFFFFFF;
        g.drawCenteredString(mc.font, text, cx, cy + 10, color);
        if (TitanClient.isCharging()) {
            g.drawCenteredString(mc.font, "\u2694", cx, cy + 20, 0xFFD040); // blades ready
        }
    }
}
