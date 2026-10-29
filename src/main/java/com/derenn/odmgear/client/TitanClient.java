package com.derenn.odmgear.client;

import com.derenn.odmgear.OdmMod;
import com.derenn.odmgear.net.OdmNetwork;
import com.derenn.odmgear.titan.TitanEntity;
import com.derenn.odmgear.titan.TitanGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;

/**
 * Blades (client side). With ODM gear, AoTTG style: holding LMB readies the slash, releasing it strikes.
 * The strike is a sphere around the player swept along the flight path for a short window, so a nape you
 * fly past is cut even if the camera isn't on it; only a cone straight behind the camera is excluded.
 * Without the gear a plain click strikes immediately with the same zone.
 */
@Mod.EventBusSubscriber(modid = OdmMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TitanClient {
    /** Radius of the blade zone around the player, in blocks. */
    static final double RADIUS = 2.0;
    /** Dead zone straight behind the camera: 90 degrees wide (the zone covers the other 270). */
    static final double REAR_CONE_DEGREES = 90.0;
    static final double MIN_DOT = Math.cos(Math.toRadians(180.0 - REAR_CONE_DEGREES / 2.0));
    /** The slash stays active this many ticks after the strike (0.3 s). */
    static final int SLASH_TICKS = 6;

    private static boolean charging;
    /** Wind-up animation: 0 = rest, 1 = blade fully drawn back. */
    private static float windUp, windUpPrev;
    /** Ticks to draw the blade back fully. */
    static final float WIND_UP_TICKS = 4f;
    private static boolean attackWasDown;
    private static int slashTicks;
    private static final Set<Integer> hitThisSlash = new HashSet<>();
    private static Vec3 prevCenter;
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
        charging = false;
        return true;
    }

    /** True while the ODM blade controls replace vanilla left click. */
    private static boolean bladeMode(LocalPlayer p) {
        return OdmPhysics.INSTANCE.isUsable(p);
    }

    public static boolean isCharging() {
        return charging;
    }

    public static float windUp(float partialTick) {
        return windUpPrev + (windUp - windUpPrev) * partialTick;
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
        if (bladeMode(p)) {
            // the press only readies the blades; the strike happens on release (see onClientTick)
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        boolean onTitan = mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof TitanEntity;
        boolean hit = strike(p);
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
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            prevCenter = null;
            prevPos = null;
            charging = false;
            return;
        }
        Vec3 pos = p.position();
        if (prevPos != null) {
            measuredSpeed = pos.distanceTo(prevPos) / 0.05;
        }

        boolean down = mc.screen == null && OdmKeys.held(mc.options.keyAttack);
        if (bladeMode(p) && holder(p) == null) {
            if (down && !attackWasDown) {
                charging = true;
            } else if (!down && attackWasDown && charging) {
                charging = false;
                releaseStrike(mc, p);
            }
        } else {
            charging = false;
        }
        attackWasDown = down;
        windUpPrev = windUp;
        windUp = charging ? Math.min(1f, windUp + 1f / WIND_UP_TICKS) : 0f;

        if (slashTicks > 0) {
            slashTicks--;
            sweep(p);
        }
        prevCenter = center(p);
        prevPos = pos;
    }

    private static Vec3 center(LocalPlayer p) {
        return p.position().add(0, p.getBbHeight() * 0.5, 0);
    }

    private static void releaseStrike(Minecraft mc, LocalPlayer p) {
        p.swing(InteractionHand.MAIN_HAND);
        // ordinary mobs under the crosshair still get a normal hit
        if (mc.hitResult instanceof EntityHitResult ehr && !(ehr.getEntity() instanceof TitanEntity) && mc.gameMode != null) {
            Entity target = ehr.getEntity();
            mc.gameMode.attack(p, target);
        }
        strike(p);
    }

    /** Starts a slash window and checks it immediately. */
    private static boolean strike(LocalPlayer p) {
        slashTicks = SLASH_TICKS;
        hitThisSlash.clear();
        return sweep(p);
    }

    /** Meaty slash: crit crack + heavy hit + wet cut; bigger hits sound lower and get an extra thump. */
    private static void playNapeHitSound(int shown) {
        net.minecraft.client.sounds.SoundManager sm = Minecraft.getInstance().getSoundManager();
        boolean big = shown >= 1000;
        sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT, big ? 0.75f : 1.0f, 1.0f));
        sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_STRONG, big ? 0.6f : 0.85f, 1.0f));
        sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.HONEY_BLOCK_BREAK, 0.7f, 0.9f));
        if (big) {
            sm.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE, 1.6f, 0.35f));
        }
    }

    /** Current speed in AoTTG units per second. */
    static float speedUnits() {
        OdmPhysics odm = OdmPhysics.INSTANCE;
        double blocks = odm.physicsActive ? odm.vel.length() : measuredSpeed;
        return (float) (blocks / OdmPhysics.scale());
    }

    /** Swept sphere from last tick's position to now; each titan can be cut once per slash. */
    private static boolean sweep(LocalPlayer p) {
        Vec3 now = center(p);
        Vec3 from = prevCenter == null || prevCenter.distanceToSqr(now) > 400 ? now : prevCenter;
        Vec3 look = p.getViewVector(1f);
        boolean any = false;
        for (TitanEntity t : TitanPick.titans(p.level())) {
            if (hitThisSlash.contains(t.getId()) || t.distanceToSqr(p) > Math.pow(t.heightBlocks() * 1.5 + 12, 2)) {
                continue;
            }
            TitanGeometry.Part part = TitanGeometry.zoneHit(t, from, now, RADIUS, look, MIN_DOT, 1f);
            if (part != null) {
                hitThisSlash.add(t.getId());
                float speed = speedUnits();
                OdmNetwork.CHANNEL.sendToServer(new OdmNetwork.TitanHit(t.getId(), part.ordinal(), speed));
                if (part == TitanGeometry.Part.NAPE) {
                    // same formula as the server: weapon damage x speed / 20 (health points);
                    // shown as hearts x 100 / 2 x 15 (= hearts x 750)
                    double dmg = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) * speed / 20.0;
                    int shown = (int) Math.round(dmg / 2.0 * 100.0 / 2.0 * 15.0);
                    DamagePopups.add(TitanGeometry.napePoint(TitanGeometry.frame(t, 1f, false)), shown);
                    playNapeHitSound(shown);
                }
                any = true;
            }
        }
        return any;
    }
}
