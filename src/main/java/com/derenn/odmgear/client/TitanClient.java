package com.derenn.odmgear.client;

import com.derenn.odmgear.OdmMod;
import com.derenn.odmgear.net.OdmNetwork;
import com.derenn.odmgear.titan.TitanEntity;
import com.derenn.odmgear.titan.TitanGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Blade hits on titan hurtboxes and being held in a titan's hand (client side). */
@Mod.EventBusSubscriber(modid = OdmMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TitanClient {
    /** Blade reach from the eyes, in blocks. */
    static final double REACH = 4.0;
    /** Extra forgiveness around hurtboxes, in blocks. */
    static final double MARGIN = 0.35;
    /** A slash stays active this many ticks after clicking. */
    static final int SLASH_TICKS = 6;

    private static int slashTicks;
    private static boolean slashHit;
    private static Vec3 prevEye;
    private static Vec3 prevPos;
    private static double measuredSpeed;

    private TitanClient() {
    }

    /** Returns the titan holding this player, or null. */
    public static TitanEntity holder(LocalPlayer p) {
        for (TitanEntity t : TitanPick.titans(p.level())) {
            if (t.heldEntityId() == p.getId()) {
                return t;
            }
        }
        return null;
    }

    /** Pins a held player to the titan's grab socket. Returns true while held. */
    public static boolean applyHeld(LocalPlayer p) {
        TitanEntity t = holder(p);
        if (t == null) {
            return false;
        }
        TitanGeometry.Frame f = TitanGeometry.frame(t, 1f, false);
        Vec3 socket = TitanGeometry.grabSocket(f, t.holdLeft());
        p.setPos(socket.x, socket.y - p.getBbHeight() * 0.5, socket.z);
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
        return true;
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!event.isAttack() || p == null) {
            return;
        }
        if (holder(p) != null) {
            event.setCanceled(true); // AoTTG: no attacking while grabbed
            event.setSwingHand(false);
            return;
        }
        boolean onTitan = mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof TitanEntity;
        slashTicks = SLASH_TICKS;
        slashHit = false;
        boolean hit = trySlash(p);
        if (onTitan || hit) {
            event.setCanceled(true);
            event.setSwingHand(true);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            prevEye = null;
            prevPos = null;
            return;
        }
        Vec3 pos = p.position();
        if (prevPos != null) {
            measuredSpeed = pos.distanceTo(prevPos) / 0.05;
        }
        if (slashTicks > 0) {
            slashTicks--;
            if (!slashHit) {
                trySlash(p);
            }
        }
        prevEye = p.getEyePosition();
        prevPos = pos;
    }

    /** Current speed in AoTTG units per second. */
    static float speedUnits() {
        OdmPhysics odm = OdmPhysics.INSTANCE;
        double blocks = odm.physicsActive ? odm.vel.length() : measuredSpeed;
        return (float) (blocks / OdmPhysics.scale());
    }

    private static boolean trySlash(LocalPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1f);
        Vec3 from = prevEye == null || prevEye.distanceToSqr(eye) > 400 ? eye : prevEye;
        TitanEntity bestTitan = null;
        TitanGeometry.PartHit best = null;
        for (TitanEntity t : TitanPick.titans(p.level())) {
            if (t.distanceToSqr(p) > Math.pow(t.heightBlocks() * 1.5 + 10, 2)) {
                continue;
            }
            // sweep the blade ray between last tick's and this tick's eye position (fast flight)
            for (int k = 0; k <= 3; k++) {
                Vec3 a = from.lerp(eye, k / 3.0);
                TitanGeometry.PartHit h = TitanGeometry.hurtboxHit(t, a, a.add(look.scale(REACH)), MARGIN, 1f);
                if (h != null && (best == null || h.part().ordinal() < best.part().ordinal())) {
                    best = h;
                    bestTitan = t;
                }
            }
        }
        if (best == null) {
            return false;
        }
        slashHit = true;
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        OdmNetwork.CHANNEL.sendToServer(new OdmNetwork.TitanHit(bestTitan.getId(), best.part().ordinal(), speedUnits()));
        return true;
    }
}
