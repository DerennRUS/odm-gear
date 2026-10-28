package com.derenn.odmgear.client;

import com.derenn.odmgear.OdmConfig;
import com.derenn.odmgear.OdmMod;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Player movement while using ODM gear, ported from AoTTG2 Human.cs (FixedUpdate, FixedUpdateLaunch,
 * FixedUpdatePivot, Dash). Everything runs on the client for the local player: the result is written
 * into the player's delta movement, so vanilla collision code still moves the player.
 *
 * Units: positions in blocks, velocities in blocks/second, constants are AoTTG values multiplied by worldScale.
 */
public final class OdmPhysics {
    // --- AoTTG2 constants (units / seconds) ---
    /** BaseCharacter.Gravity = 20. Halved while pulled by a hook that is above the player. */
    static final double GRAVITY = 40.0;
    /** Vanilla Minecraft gravity, 0.08 blocks/tick^2 = 32 blocks/s^2. */
    static final double VANILLA_GRAVITY_BLOCKS = 32.0;
    /** Without gas, gravity grows by this much per second (AoTTG units/s^2 per s) up to vanilla gravity. */
    static final double GRAVITY_RAMP = 10.0;
    /** Gas + W: extra vertical thrust by camera pitch, up to +25 looking straight up / +10 looking straight down. */
    /** Shift + W with a hook attached: upward thrust by camera pitch, 0..35 looking up, 0..10 looking down. */
    static final double PITCH_LIFT_UP = 35.0;
    static final double PITCH_LIFT_DOWN = 10.0;
    /** On attach the rope snaps in: +50 toward the hook until 10% of the initial rope length is reeled in. */
    static final double SNAP_ACCEL = 50.0;
    static final double SNAP_FRACTION = 0.10;
    /** Gas held: constant upward thrust; +5 more while a hook is attached. */
    static final double GAS_LIFT = 10.0;
    static final double GAS_LIFT_HOOKED = 5.0;
    /** Rigidbody mass = 0.5 - (acceleration - 100) * 0.001 with the default acceleration stat 115. */
    static final double MASS = 0.485;
    /** Rope pull force per hook: 10 when both hooks pull, 20 when only one pulls (ForceMode.Force). */
    static final double PULL_FORCE_BOTH = 10.0;
    static final double PULL_FORCE_SINGLE = 20.0;
    /** Braking acceleration factor when flying away from a pulling hook without gas. */
    static final double AWAY_BRAKE = 2.0;
    /** Gas without hooks: direction * acceleration stat / 5 (115 / 5), ForceMode.Acceleration. */
    static final double GAS_ACCEL = 23.0;
    /** Pivot (gas + hook) adds 0.1 speed every 0.02 s fixed step. */
    static final double PIVOT_SPEED_GAIN_PER_STEP = 0.1;
    /** RotateTowards limit used in FixedUpdatePivot (radians, ~88.2 degrees). */
    static final double PIVOT_ANGLE = 1.53938;
    /** Dash: instant 40 speed change, then for 0.5 s speed above the pre-dash speed decays (-v * 1.7). */
    static final double DASH_SPEED = 40.0;
    static final double DASH_TIME = 0.5;
    static final double DASH_DECAY = 1.7;
    /** AoTTG jump: +8 vertical velocity. */
    /** Gas-jump height on the ground, in blocks (about a vanilla jump). */
    static final double JUMP_HEIGHT = 1.3;
    /** Run speed stat 110 / 10. Landing faster than RunSpeed*sqrt(1.5) slides; slide ends below RunSpeed*1.2. */
    static final double RUN_SPEED = 11.0;
    static final double FIXED_STEP = 0.02;
    /** Speed limit (AoTTG units/s). */
    static final double MAX_SPEED = 125.0;
    /** Without gas the speed slowly fades by this much per second (AoTTG units/s^2). */
    static final double COAST_DECAY = 20.0;
    /** Gas acceleration (AoTTG units/s^2) depending on current speed: 10 up to 50, 5 up to 75, 3 up to 100, 1 up to 125. */
    static double gasAccel(double speedUnits) {
        if (speedUnits < 50) return 10.0;
        if (speedUnits < 75) return 5.0;
        if (speedUnits < 100) return 3.0;
        if (speedUnits < MAX_SPEED) return 1.0;
        return 0.0;
    }

    /** How fast reel in/out blends in (1/s), so reeling turns your momentum instead of snapping it. */
    static final double REEL_RESPONSE = 14.0;

    static final int SUBSTEPS = 5;
    static final double TICK = 0.05;
    static final double DT = TICK / SUBSTEPS;

    public static final OdmPhysics INSTANCE = new OdmPhysics();

    public final Hook leftHook = new Hook(true);
    public final Hook rightHook = new Hook(false);

    public boolean enabled = true;
    /** True while our physics drives the player (in the air, or sliding after landing). */
    public boolean physicsActive;
    public boolean sliding;
    public Vec3 vel = Vec3.ZERO;
    private Vec3 lastPos = Vec3.ZERO;
    private Vec3 lastDelta = Vec3.ZERO;

    private double dashTimeLeft;
    private double originalDashSpeed;
    private int reelInSteps;
    private double reelSmooth;
    /** Extra gravity built up while gas is released (AoTTG units/s^2). */
    private double gravityExtra;
    private double reelOutTime;
    public boolean gasInUse;

    // Input collected by OdmClientEvents between ticks
    boolean pressedLeft, pressedRight, pressedBoth, pressedGas;
    /** Gas key went down since last tick: on the ground this is AoTTG's jump. */
    boolean gasEdge;
    Vec3 pendingDash;

    // HUD info
    public boolean leftInRange, rightInRange;
    /** Distance in blocks from the player to the block under the crosshair, or -1 if nothing is hit. */
    public double aimDistance = -1;

    private OdmPhysics() {
    }

    public static double scale() {
        return OdmConfig.scale();
    }

    public boolean isUsable(LocalPlayer p) {
        return enabled && p.isAlive() && !p.isSpectator() && !p.getAbilities().flying && !p.isPassenger()
                && !p.isFallFlying() && OdmMod.hasGear(p);
    }

    public boolean hasGearButDisabled(LocalPlayer p) {
        return !enabled && OdmMod.hasGear(p);
    }

    public void reset(LocalPlayer p) {
        if (physicsActive) {
            p.setDeltaMovement(vel.scale(TICK));
        }
        physicsActive = false;
        sliding = false;
        leftHook.release();
        rightHook.release();
        dashTimeLeft = 0;
        gasInUse = false;
    }

    public void onScroll(double delta) {
        if (delta < 0) {
            // AoTTG: each wheel-down notch reels in for one fixed step (0.02 s)
            // each wheel notch reels in for ~0.15 s so the smoothed reel has time to act
            reelInSteps = Math.min(reelInSteps + (int) Math.round(0.15 / DT), SUBSTEPS * 8);
        } else if (delta > 0) {
            // AoTTG: wheel up = reel out, smoothed for 0.2 s
            reelOutTime = 0.2;
        }
    }

    /** Called once per client tick at the end of the player tick. */
    public void tick(LocalPlayer p, Minecraft mc) {
        if (!isUsable(p)) {
            if (physicsActive || leftHook.state != Hook.State.IDLE || rightHook.state != Hook.State.IDLE) {
                reset(p);
            }
            clearPressed();
            return;
        }
        double s = scale();
        boolean screen = mc.screen != null;

        // ---------- input ----------
        boolean leftKey = !screen && (OdmKeys.held(OdmKeys.HOOK_LEFT) || pressedLeft);
        boolean rightKey = !screen && (OdmKeys.held(OdmKeys.HOOK_RIGHT) || pressedRight);
        boolean bothKey = !screen && (OdmKeys.held(OdmKeys.HOOK_BOTH) || pressedBoth);
        boolean gas = !screen && (OdmKeys.held(OdmKeys.GAS) || pressedGas);
        boolean reelInKey = !screen && OdmKeys.held(OdmKeys.REEL_IN);
        boolean reelOutKey = !screen && OdmKeys.held(OdmKeys.REEL_OUT);
        Vec3 dashDir = screen ? null : pendingDash;
        boolean jumpPressed = !screen && gasEdge;
        clearPressed();
        Vec3 inputDir = screen ? null : inputDirection(mc, p);
        boolean wHeld = !screen && mc.options.keyUp.isDown();

        Vec3 pos = p.position();
        // velocity the player actually had over the last tick (works for vanilla movement too)
        Vec3 measuredVel = pos.subtract(lastPos).scale(1.0 / TICK);
        if (measuredVel.length() > 200) {
            measuredVel = p.getDeltaMovement().scale(1.0 / TICK);
        }
        leftHook.prevPos = leftHook.pos;
        rightHook.prevPos = rightHook.pos;

        // ---------- correct our velocity for collisions that happened during vanilla move ----------
        if (physicsActive) {
            Vec3 actual = pos.subtract(lastPos);
            if (actual.subtract(lastDelta).length() > lastDelta.length() + 2.0) {
                // teleported or pushed by something else: hand control back
                physicsActive = false;
                sliding = false;
            } else {
                double vx = blocked(lastDelta.x, actual.x) ? 0 : vel.x;
                double vy = blocked(lastDelta.y, actual.y) ? 0 : vel.y;
                double vz = blocked(lastDelta.z, actual.z) ? 0 : vel.z;
                vel = new Vec3(vx, vy, vz);
            }
        }

        // ---------- hooks: firing ----------
        boolean hasHook = leftHook.state != Hook.State.IDLE || rightHook.state != Hook.State.IDLE;
        leftHook.keyHeld = leftKey || (bothKey && (leftHook.isHooked() || !hasHook));
        rightHook.keyHeld = rightKey || (bothKey && (rightHook.isHooked() || !hasHook));
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.getPosition();
        Vec3 look = new Vec3(cam.getLookVector());
        Vec3 aim = aimPoint(p, camPos, look, s);
        Vec3 right = rightVector(p.getYRot());
        Vec3 playerVel = physicsActive ? vel : p.getDeltaMovement().scale(1.0 / TICK);
        fireIfNeeded(p, leftHook, bothKey && !leftKey, aim, camPos, right, playerVel, s);
        fireIfNeeded(p, rightHook, bothKey && !rightKey, aim, camPos, right, playerVel, s);
        updateRangeHud(p, aim, camPos, right, s);
        BlockHitResult aimHit = p.level().clip(new ClipContext(camPos, camPos.add(look.scale(Math.min(1000 * s, 512))),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        aimDistance = aimHit.getType() == HitResult.Type.BLOCK ? aimHit.getLocation().distanceTo(pos) : -1;

        // ---------- ground / air state ----------
        boolean fluid = p.isInWater() || p.isInLava() || p.onClimbable();
        boolean onGround = p.onGround();
        Vec3 center = pos.add(0, p.getBbHeight() * 0.5, 0);
        if (fluid) {
            if (physicsActive) {
                p.setDeltaMovement(vel.scale(TICK));
            }
            physicsActive = false;
            sliding = false;
        } else if (onGround) {
            if (physicsActive && !sliding) {
                // JustGrounded
                double h = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
                if (h > RUN_SPEED * s * Math.sqrt(1.5) && (anyHooked() || inputDir != null)) {
                    sliding = true;
                    vel = new Vec3(vel.x, 0, vel.z);
                } else {
                    physicsActive = false;
                    p.setDeltaMovement(vel.x * TICK, 0, vel.z * TICK);
                }
            }
            if (sliding) {
                double h = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
                if (h < RUN_SPEED * s * 1.2 && !anyPulling()) {
                    sliding = false;
                    physicsActive = false;
                    p.setDeltaMovement(vel.x * TICK, 0, vel.z * TICK);
                }
            }
            if (!physicsActive && anyPulling() && liftsOff(center, s)) {
                physicsActive = true;
                sliding = false;
                vel = new Vec3(measuredVel.x, 0, measuredVel.z);
            }
            // AoTTG: the gas key is also jump while standing on the ground
            if (jumpPressed) {
                Vec3 base = physicsActive ? vel : measuredVel;
                vel = new Vec3(base.x, Math.sqrt(2 * GRAVITY * s * JUMP_HEIGHT), base.z);
                physicsActive = true;
                sliding = false;
            }
        } else if (!physicsActive) {
            // Plain walking/falling stays vanilla. ODM physics takes over only once the gear is used in the air.
            boolean engaged = leftHook.isVisible() || rightHook.isVisible() || gas || dashDir != null;
            if (engaged) {
                physicsActive = true;
                sliding = false;
                vel = measuredVel;
            }
        } else {
            sliding = false;
        }

        // ---------- dash ----------
        if (dashDir != null && physicsActive && !sliding && dashTimeLeft <= 0) {
            originalDashSpeed = vel.length();
            vel = vel.add(dashDir.scale(DASH_SPEED * s));
            dashTimeLeft = DASH_TIME;
            playSound(p, SoundEvents.FIRECHARGE_USE, 0.6f, 1.6f);
            puff(p, 8);
        }

        // ---------- simulate 5 substeps ----------
        Vec3 simPos = pos;
        gasInUse = false;
        for (int i = 0; i < SUBSTEPS; i++) {
            Vec3 c = simPos.add(0, p.getBbHeight() * 0.5, 0);
            if (leftHook.step(p.level(), p, DT)) {
                onHookAttached(p, leftHook);
            }
            if (rightHook.step(p.level(), p, DT)) {
                onHookAttached(p, rightHook);
            }

            double reelAxis = reelInSteps > 0 || reelInKey ? -1 : (reelOutTime > 0 || reelOutKey ? 1 : 0);
            reelSmooth = reelAxis; // instant response, as in AoTTG

            if (physicsActive) {
                Vec3 acc = Vec3.ZERO;
                boolean bothLaunched = leftHook.launch && rightHook.launch;
                boolean pivotL = false;
                boolean pivotR = false;
                for (Hook h : new Hook[]{leftHook, rightHook}) {
                    if (!h.isPulling()) {
                        continue;
                    }
                    Vec3 dir = h.pos.subtract(c);
                    if (dir.lengthSqr() < 1.0E-6) {
                        continue;
                    }
                    dir = dir.normalize();
                    boolean away = vel.dot(dir) < 0;
                    if (away && gas) {
                        if (h.left) {
                            pivotL = true;
                        } else {
                            pivotR = true;
                        }
                    } else {
                        double f = bothLaunched ? PULL_FORCE_BOTH : PULL_FORCE_SINGLE;
                        acc = acc.add(dir.scale(f / MASS * s));
                        if (away) {
                            acc = acc.add(vel.scale(-AWAY_BRAKE));
                        }
                    }
                }
                boolean pivot = pivotL || pivotR;
                acc = acc.add(snapAccel(c, s));

                // free gas (no pivot): accelerate toward the pressed WASD direction (relative to the camera);
                // with no direction keys it just boosts along the current horizontal motion
                if (!pivot && gas && !sliding && dashTimeLeft <= DASH_TIME - 0.25) {
                    Vec3 d = inputDir;
                    if (d == null) {
                        Vec3 h = new Vec3(vel.x, 0, vel.z);
                        d = h.lengthSqr() > 1.0E-4 ? h.normalize() : null;
                    }
                    if (d != null) {
                        double speedU = vel.length() / s;
                        double base = gasAccel(speedU);
                        // left / right / back thrust is stronger: + half of the current speed per second
                        double side = base + 0.5 * speedU;
                        if (inputDir != null) {
                            Vec3 fwd = forwardVector(p.getYRot());
                            Vec3 rgt = rightVector(p.getYRot());
                            double fc = d.dot(fwd);
                            double rc = d.dot(rgt);
                            acc = acc.add(fwd.scale(fc * (fc > 0 ? base : side) * s));
                            acc = acc.add(rgt.scale(rc * side * s));
                        } else {
                            acc = acc.add(d.scale(base * s));
                        }
                    }
                    gasInUse = true;
                }
                // vertical gas thrust: lift while gas is held, and W pushes along the camera pitch
                if (gas && !sliding) {
                    double up = GAS_LIFT + (anyHooked() ? GAS_LIFT_HOOKED : 0);
                    if (wHeld && anyHooked()) {
                        float pitch = p.getXRot(); // -90 = straight up, +90 = straight down
                        up += (pitch < 0 ? PITCH_LIFT_UP : PITCH_LIFT_DOWN) * (Math.abs(pitch) / 90.0);
                    }
                    acc = acc.add(0, up * s, 0);
                }
                // dash slowdown back to the pre-dash speed
                if (dashTimeLeft > 0 && vel.length() > originalDashSpeed) {
                    acc = acc.add(vel.scale(-DASH_DECAY));
                }
                // pivot: velocity becomes (almost) tangential to the rope and keeps growing
                if (pivot) {
                    Vec3 target = pivotL && pivotR ? leftHook.pos.add(rightHook.pos).scale(0.5)
                            : (pivotL ? leftHook.pos : rightHook.pos);
                    // on the rope gas adds half of the free-flight acceleration
                    double newSpeed = vel.length() + gasAccel(vel.length() / s) * 0.5 * s * DT;
                    double reel = Mth.clamp(reelSmooth, -0.8, 0.8) + 1.0;
                    Vec3 toHook = target.subtract(c);
                    if (toHook.lengthSqr() > 1.0E-6) {
                        Vec3 cur = vel.lengthSqr() > 1.0E-8 ? vel.normalize() : toHook.normalize();
                        Vec3 d = rotateTowards(toHook.normalize(), cur, PIVOT_ANGLE * reel);
                        vel = d.scale(newSpeed);
                    }
                    gasInUse = true;
                }
                // gravity (halved while a pulling hook is above us)
                boolean lower = (leftHook.isPulling() && leftHook.pos.y > c.y) || (rightHook.isPulling() && rightHook.pos.y > c.y);
                // gas held: base gravity; released: gravity ramps up toward vanilla Minecraft gravity
                if (gas) {
                    gravityExtra = 0;
                } else {
                    double maxExtra = Math.max(0, VANILLA_GRAVITY_BLOCKS / s - GRAVITY);
                    gravityExtra = Math.min(maxExtra, gravityExtra + GRAVITY_RAMP * DT);
                }
                acc = acc.add(0, -(GRAVITY + gravityExtra) * s * (lower ? 0.5 : 1.0), 0);

                vel = vel.add(acc.scale(DT));
                // coasting: speed fades slowly while gas is not used; hard speed limit
                double speed = vel.length();
                if (!gas && !sliding && speed > 1.0E-6) {
                    double ns = Math.max(0, speed - COAST_DECAY * s * DT);
                    vel = vel.scale(ns / speed);
                    speed = ns;
                }
                if (speed > MAX_SPEED * s) {
                    vel = vel.scale(MAX_SPEED * s / speed);
                }
                if (sliding) {
                    double k = Math.pow(0.99, DT / FIXED_STEP);
                    vel = new Vec3(vel.x * k, vel.y, vel.z * k);
                }
                simPos = simPos.add(vel.scale(DT));
            }

            // hook release: after 0.3 s attached, a released key detaches the hook
            for (Hook h : new Hook[]{leftHook, rightHook}) {
                if (h.launch) {
                    h.launchTime += DT;
                    if (!h.keyHeld && h.launchTime > Hook.MIN_LAUNCH_TIME) {
                        h.release();
                    }
                }
            }

            if (reelInSteps > 0) {
                reelInSteps--;
            }
            if (reelOutTime > 0) {
                reelOutTime -= DT;
            }
            if (dashTimeLeft > 0) {
                dashTimeLeft -= DT;
            }
        }

        if (physicsActive) {
            Vec3 delta = simPos.subtract(pos);
            p.setDeltaMovement(delta);
            p.fallDistance = 0;
            lastDelta = delta;
            if (gasInUse) {
                puff(p, 1);
            }
        }
        lastPos = pos;
    }

    private static boolean blocked(double intended, double actual) {
        return Math.abs(intended) > 1.0E-5 && Math.abs(actual - intended) > 1.0E-3;
    }

    private boolean anyHooked() {
        return leftHook.isHooked() || rightHook.isHooked();
    }

    private boolean anyPulling() {
        return leftHook.isPulling() || rightHook.isPulling();
    }

    /** Rope snap after attaching: pulls toward the hook until the rope got 10% shorter. */
    private Vec3 snapAccel(Vec3 c, double s) {
        Vec3 total = Vec3.ZERO;
        for (Hook h : new Hook[]{leftHook, rightHook}) {
            if (!h.snapping) {
                continue;
            }
            if (!h.isHooked()) {
                h.snapping = false;
                continue;
            }
            Vec3 d = h.pos.subtract(c);
            double dist = d.length();
            if (dist <= h.snapTarget || dist < 1.0E-6) {
                h.snapping = false;
                continue;
            }
            total = total.add(d.scale(SNAP_ACCEL * s / dist));
        }
        return total;
    }

    /** On the ground AoTTG cancels horizontal motion; we leave the ground once the rope's upward pull beats gravity. */
    private boolean liftsOff(Vec3 c, double s) {
        boolean both = leftHook.launch && rightHook.launch;
        double ay = 0;
        boolean lower = false;
        for (Hook h : new Hook[]{leftHook, rightHook}) {
            if (!h.isPulling()) {
                continue;
            }
            Vec3 dir = h.pos.subtract(c);
            if (dir.lengthSqr() < 1.0E-6) {
                continue;
            }
            dir = dir.normalize();
            ay += dir.y * (both ? PULL_FORCE_BOTH : PULL_FORCE_SINGLE) / MASS * s;
            if (h.pos.y > c.y) {
                lower = true;
            }
        }
        ay += snapAccel(c, s).y;
        return ay > GRAVITY * s * (lower ? 0.5 : 1.0);
    }

    private void fireIfNeeded(LocalPlayer p, Hook h, boolean bothOffset, Vec3 aim, Vec3 camPos, Vec3 right, Vec3 playerVel, double s) {
        if (!h.keyHeld || h.state != Hook.State.IDLE) {
            return;
        }
        Vec3 target = aim;
        if (bothOffset) {
            double dist = camPos.distanceTo(target);
            double offset = dist <= 50 * s ? dist * 0.05 : dist * 0.3;
            target = target.add(right.scale(h.left ? -offset : offset));
        }
        h.fire(anchor(p.position(), p.getBbHeight(), p.getYRot(), h.left), target, playerVel, s);
        playSound(p, SoundEvents.CROSSBOW_SHOOT, 0.5f, 1.4f);
    }

    private void onHookAttached(LocalPlayer p, Hook h) {
        double len = h.pos.distanceTo(p.position().add(0, p.getBbHeight() * 0.5, 0));
        h.snapTarget = len * (1.0 - SNAP_FRACTION);
        h.snapping = true;
        p.level().playLocalSound(h.pos.x, h.pos.y, h.pos.z, SoundEvents.CHAIN_HIT, SoundSource.PLAYERS, 0.8f, 1.2f, false);
    }

    private void updateRangeHud(LocalPlayer p, Vec3 aim, Vec3 camPos, Vec3 right, double s) {
        double range = (Hook.FLIGHT_SPEED * Hook.MAX_FLIGHT_TIME) * s;
        Vec3 c = p.position().add(0, p.getBbHeight() * 0.5, 0);
        double dist = camPos.distanceTo(aim);
        double offset = dist <= 50 * s ? dist * 0.05 : dist * 0.3;
        leftInRange = inRange(p, c, aim.add(right.scale(-offset)), range);
        rightInRange = inRange(p, c, aim.add(right.scale(offset)), range);
    }

    private static boolean inRange(LocalPlayer p, Vec3 from, Vec3 target, double range) {
        Vec3 dir = target.subtract(from);
        if (dir.lengthSqr() < 1.0E-6) {
            return false;
        }
        Vec3 to = from.add(dir.normalize().scale(range));
        return p.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.BLOCK;
    }

    private static Vec3 aimPoint(LocalPlayer p, Vec3 camPos, Vec3 look, double s) {
        double max = Math.min(1000 * s, 512);
        Vec3 end = camPos.add(look.scale(max));
        BlockHitResult hit = p.level().clip(new ClipContext(camPos, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : end;
    }

    /** Where the rope leaves the body: waist height, a little to the side. */
    public static Vec3 anchor(Vec3 feet, double height, float yaw, boolean left) {
        Vec3 r = rightVector(yaw).scale(left ? -0.35 : 0.35);
        return feet.add(r.x, height * 0.5, r.z);
    }

    public static Vec3 forwardVector(float yaw) {
        float rad = yaw * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(rad), 0, Mth.cos(rad));
    }

    public static Vec3 rightVector(float yaw) {
        float rad = yaw * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.cos(rad), 0, -Mth.sin(rad));
    }

    /** Horizontal WASD direction relative to the camera (AoTTG GetTargetDirection), or null. */
    static Vec3 inputDirection(Minecraft mc, LocalPlayer p) {
        double f = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double r = (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0);
        if (f == 0 && r == 0) {
            return null;
        }
        Vec3 d = forwardVector(p.getYRot()).scale(f).add(rightVector(p.getYRot()).scale(r));
        return d.normalize();
    }

    /** Unity Vector3.RotateTowards for unit vectors. */
    static Vec3 rotateTowards(Vec3 from, Vec3 to, double maxRad) {
        double dot = Mth.clamp(from.dot(to), -1.0, 1.0);
        double angle = Math.acos(dot);
        if (angle <= maxRad || angle < 1.0E-6) {
            return to;
        }
        double sin = Math.sin(angle);
        if (sin < 1.0E-6) {
            Vec3 axis = from.cross(new Vec3(0, 1, 0));
            if (axis.lengthSqr() < 1.0E-6) {
                axis = from.cross(new Vec3(1, 0, 0));
            }
            axis = axis.normalize();
            return from.scale(Math.cos(maxRad)).add(axis.scale(Math.sin(maxRad)));
        }
        double t = maxRad / angle;
        return from.scale(Math.sin((1 - t) * angle) / sin).add(to.scale(Math.sin(t * angle) / sin)).normalize();
    }

    private static void playSound(LocalPlayer p, SoundEvent sound, float volume, float pitch) {
        p.level().playLocalSound(p.getX(), p.getY(), p.getZ(), sound, SoundSource.PLAYERS, volume, pitch, false);
    }

    private void puff(LocalPlayer p, int count) {
        for (int i = 0; i < count; i++) {
            p.level().addParticle(ParticleTypes.CLOUD,
                    p.getX() + (p.getRandom().nextDouble() - 0.5) * 0.4,
                    p.getY() + 0.8,
                    p.getZ() + (p.getRandom().nextDouble() - 0.5) * 0.4,
                    0, 0, 0);
        }
    }

    private void clearPressed() {
        pressedLeft = false;
        pressedRight = false;
        pressedBoth = false;
        pressedGas = false;
        gasEdge = false;
        pendingDash = null;
    }

    public void toggle(LocalPlayer p) {
        enabled = !enabled;
        if (!enabled) {
            reset(p);
        }
        p.displayClientMessage(Component.translatable(enabled ? "msg.odmgear.on" : "msg.odmgear.off"), true);
    }

    /** Speed in AoTTG units/s, for the speedometer. */
    public double displaySpeed(LocalPlayer p) {
        Vec3 v = physicsActive ? vel : p.getDeltaMovement().scale(1.0 / TICK);
        return v.length() / scale();
    }
}
