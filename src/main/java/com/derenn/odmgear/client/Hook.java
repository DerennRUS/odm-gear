package com.derenn.odmgear.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * One grappling hook. Ported from AoTTG2 Hook.cs / HookUseable.cs.
 * All speeds are in blocks per second (AoTTG units * worldScale).
 */
public final class Hook {
    public enum State { IDLE, FLYING, HOOKED, RETRACT }

    /** Hook projectile speed: baseVelocity 3 units per 0.02 s fixed step = 150 units/s. */
    public static final double FLIGHT_SPEED = 150.0;
    /** Hook gives up after 0.8 s of flight. */
    public static final double MAX_FLIGHT_TIME = 0.8;
    /** Missed hook retracts for 0.5 s before it can be fired again. */
    public static final double RETRACT_TIME = 0.5;
    /** After attaching, a hook stays at least this long even if the key was released. */
    public static final double MIN_LAUNCH_TIME = 0.3;

    public final boolean left;
    public State state = State.IDLE;
    public Vec3 pos = Vec3.ZERO;
    public Vec3 prevPos = Vec3.ZERO;
    private Vec3 baseVel = Vec3.ZERO;
    private Vec3 relVel = Vec3.ZERO;
    private double life;
    private BlockPos hookedBlock;

    /** "_launchLeft/_launchRight" in AoTTG: true while the rope is pulling. */
    public boolean launch;
    public double launchTime;
    /** Whether the hook key is currently held (HookUseable.IsActive). */
    public boolean keyHeld;

    public Hook(boolean left) {
        this.left = left;
    }

    public boolean isHooked() {
        return state == State.HOOKED;
    }

    public boolean isPulling() {
        return launch && state == State.HOOKED;
    }

    public boolean isVisible() {
        return state == State.FLYING || state == State.HOOKED;
    }

    public void fire(Vec3 anchor, Vec3 target, Vec3 playerVel, double scale) {
        Vec3 dir = target.subtract(anchor);
        if (dir.lengthSqr() < 1.0E-8) {
            return;
        }
        dir = dir.normalize();
        baseVel = dir.scale(FLIGHT_SPEED * scale);
        // Player velocity is added only if it points within 90 degrees of the shot.
        double along = playerVel.dot(dir);
        relVel = along > 0 ? dir.scale(along) : Vec3.ZERO;
        pos = anchor;
        prevPos = anchor;
        life = 0;
        state = State.FLYING;
        launch = false;
    }

    /** Returns true at the moment the hook attaches. */
    public boolean step(Level level, Entity owner, double dt) {
        if (state == State.FLYING) {
            Vec3 next = pos.add(baseVel.add(relVel).scale(dt));
            BlockHitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
            if (hit.getType() == HitResult.Type.BLOCK) {
                pos = hit.getLocation();
                hookedBlock = hit.getBlockPos();
                state = State.HOOKED;
                launch = true;
                launchTime = 0;
                return true;
            }
            pos = next;
            life += dt;
            if (life > MAX_FLIGHT_TIME) {
                state = State.RETRACT;
                life = 0;
            }
        } else if (state == State.RETRACT) {
            life += dt;
            if (life > RETRACT_TIME) {
                state = State.IDLE;
            }
        } else if (state == State.HOOKED && hookedBlock != null && level.getBlockState(hookedBlock).isAir()) {
            release();
        }
        return false;
    }

    public void release() {
        state = State.IDLE;
        launch = false;
        hookedBlock = null;
    }
}
