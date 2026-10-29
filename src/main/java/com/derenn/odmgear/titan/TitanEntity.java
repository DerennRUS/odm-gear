package com.derenn.odmgear.titan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AoTTG2-style titan. Appearance and size are rolled on spawn; behaviour follows AoTTG2 BasicTitan /
 * BaseTitanAIController (attack ranges and timings from TitanAIInfo.json and BasicTitan.cs), adapted to blocks:
 * titans sit at night, chase players by day, smash wooden-tool blocks in their way and walk around the rest.
 */
public class TitanEntity extends Monster {
    /** Titan heights in blocks and their spawn chances. */
    public static final float[] SIZES = {10f, 18f, 25f};
    public static final float[] SIZE_CHANCES = {0.25f, 0.60f, 0.15f};
    /** Hit damage in hearts by size. */
    public static final float[] DAMAGE_HEARTS = {4f, 8f, 11f};
    public static final int[] HEADS = {0, 1, 3, 4};
    public static final String[] HAIRS = {"armin", "boy1", "boy2", "boy3", "eren", "girl2", "jean", "levi", "marco", "mikasa", "mike", "petra"};
    public static final int[][] HAIR_COLORS = {
            {35, 18, 11}, {61, 36, 20}, {90, 56, 37}, {204, 153, 102}, {230, 190, 138}, {255, 204, 71}, {153, 101, 21},
            {151, 80, 45}, {208, 135, 54}, {36, 28, 17}, {154, 51, 0}, {86, 79, 82}, {70, 71, 71}, {94, 8, 8},
            {192, 185, 175}, {154, 158, 159}, {40, 40, 40}, {130, 50, 50}};
    public static final int EYE_TEXTURES = 11;

    // --- behaviour constants ---
    static final double DETECT_RANGE = 30.0;
    static final double LOSE_RANGE = 60.0;
    /** Walking speed in titan heights per second (run.walk played at ~1.5x). */
    static final double WALK_SPEED = 0.32;
    /** Model units the body advances per run.walk cycle (41 frames). */
    static final float WALK_STRIDE = 1.24f;
    /** AoTTG2 Default titan: CloseAttackRange 12, ChaseAngleMinRange 50 (local units). */
    static final float CLOSE_ATTACK_RANGE = 12f;
    static final float CHASE_ANGLE_MIN_RANGE = 50f;
    static final float TURN_ANGLE = 45f;
    static final float TURN_SPEED = 1.3f;
    static final float ATTACK_PAUSE = 0.5f;
    static final float TURN_PAUSE = 0.8f;
    static final float ARM_DISABLE_TIME = 12f;
    static final float CRIPPLE_TIME = 8f;
    /** Below this fraction of the height obstacles are kicked, above it they are punched. */
    static final float FOOT_ZONE = 0.38f;
    static final float BLEND_TICKS = 4f;
    static final float TURN_RATE = 90f;

    private static final EntityDataAccessor<Integer> SIZE = SynchedEntityData.defineId(TitanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> LOOKS = SynchedEntityData.defineId(TitanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> ANIM_SPEED = SynchedEntityData.defineId(TitanEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> ANIM = SynchedEntityData.defineId(TitanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ANIM_SEQ = SynchedEntityData.defineId(TitanEntity.class, EntityDataSerializers.INT);
    /** bit0 left arm cut, bit1 right arm cut, bit2 holding with the left hand. */
    private static final EntityDataAccessor<Integer> FLAGS = SynchedEntityData.defineId(TitanEntity.class, EntityDataSerializers.INT);
    /** Entity id of the grabbed player, -1 if none. */
    private static final EntityDataAccessor<Integer> HELD = SynchedEntityData.defineId(TitanEntity.class, EntityDataSerializers.INT);

    // ---------------------------------------------------------------- animation playback (both sides)
    private int localAnimStart;
    private int lastAnim = -1;
    private int lastSeq = -1;
    private float lastSpeed = 1f;
    private int blendPrev = -1;
    private float blendPrevFrame;
    private float blendPrevSpeed;
    private int blendStart;
    private float[] blendPose;
    public float walkPhase, walkPhasePrev;
    public final TitanGeometry.Frame geometryFrame = new TitanGeometry.Frame();

    // ---------------------------------------------------------------- server AI
    enum S { IDLE, WANDER, CHASE, WAIT_ATTACK, PAUSE, TURN, ATTACK, EAT, SIT_DOWN, SIT_IDLE, SIT_UP, SIT_FALL, SIT_CRIPPLE, BLIND, SIT_BLIND, DEAD }

    public static final int STRIKER = 0, GRABBER = 1;

    private S state = S.IDLE;
    private float stateTime;
    private float stateElapsed;
    private int archetype;
    private boolean natural;
    @Nullable
    private Player target;
    private float focusTimer;
    private float chaseTimer;
    private float moveAngle;
    private float waitAttackTime;
    private boolean moving;
    private float heading;
    private float wanderHeading;
    private int navTimer;
    private int detourTicks;
    private float detourHeading;
    private int detourSign = 1;
    private float turnFrom, turnTo, turnDuration;
    private float armLTime, armRTime;
    private int regenTimer;
    private boolean bladeHurt;
    private double movedSq;
    private int dieTicks = 60;

    private final List<Window> windows = new ArrayList<>();
    private boolean grabAttack;
    private int breakZone; // 0 none, 1 hand, 2 foot
    private final List<Float> breakTimes = new ArrayList<>();
    private float killAt;

    /** A hand/foot hitbox active between start and end seconds of the current attack. */
    private static final class Window {
        final int part;
        final float start, end;
        boolean done;

        Window(int part, float start, float length) {
            this.part = part;
            this.start = start;
            this.end = start + Math.max(length, 0.06f);
        }
    }

    public TitanEntity(EntityType<? extends TitanEntity> type, Level level) {
        super(type, level);
        this.xpReward = 50;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.ATTACK_DAMAGE, 8.0);
    }

    @Override
    protected void registerGoals() {
        // behaviour is driven by customServerAiStep
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(SIZE, 0);
        entityData.define(LOOKS, 0);
        entityData.define(ANIM_SPEED, 1f);
        entityData.define(ANIM, 0);
        entityData.define(ANIM_SEQ, 0);
        entityData.define(FLAGS, 0);
        entityData.define(HELD, -1);
    }

    // ================================================================ appearance

    /** Looks packed into one int: body(3 bits) head(2) hair(4) hairColor(5) eye(4) gray(7). */
    public static int packLooks(int body, int head, int hair, int hairColor, int eye, int gray) {
        return body | head << 3 | hair << 5 | hairColor << 9 | eye << 14 | gray << 18;
    }

    public int body() { return Math.min(4, entityData.get(LOOKS) & 7); }
    public int headIndex() { return entityData.get(LOOKS) >> 3 & 3; }
    public int head() { return HEADS[headIndex()]; }
    public String hair() { return HAIRS[Math.min(HAIRS.length - 1, entityData.get(LOOKS) >> 5 & 15)]; }
    public int[] hairColor() { return HAIR_COLORS[Math.min(HAIR_COLORS.length - 1, entityData.get(LOOKS) >> 9 & 31)]; }
    public int eye() { return entityData.get(LOOKS) >> 14 & 15; }
    /** Skin brightness 0.7 .. 1.0 (AoTTG2 tints the body gray in this range). */
    public float gray() { return 0.7f + 0.3f * ((entityData.get(LOOKS) >> 18 & 127) / 127f); }

    public int sizeIndex() { return Math.max(0, Math.min(SIZES.length - 1, entityData.get(SIZE))); }
    public float heightBlocks() { return SIZES[sizeIndex()]; }
    /** Blocks per model unit. */
    public float modelScale() { return heightBlocks() / TitanModelData.get().height; }

    public void setSizeIndex(int i) {
        entityData.set(SIZE, i);
        refreshDimensions();
        setMaxUpStep(heightBlocks() / 4f);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(heightBlocks() * 2.0);
        setHealth(getMaxHealth());
    }

    public void randomizeLooks(RandomSource r) {
        int body = r.nextInt(5);
        int head = r.nextInt(HEADS.length);
        int hair = r.nextInt(HAIRS.length);
        int color = r.nextInt(HAIR_COLORS.length);
        int eye = r.nextInt(EYE_TEXTURES);
        if (eye == 7) {
            eye = 0; // AoTTG2 skips atlas cell 7
        }
        int gray = r.nextInt(128);
        entityData.set(LOOKS, packLooks(body, head, hair, color, eye, gray));
    }

    public static int rollSize(RandomSource r) {
        float x = r.nextFloat();
        float acc = 0;
        for (int i = 0; i < SIZES.length; i++) {
            acc += SIZE_CHANCES[i];
            if (x < acc) {
                return i;
            }
        }
        return SIZES.length - 1;
    }

    public void setNatural(boolean natural) {
        this.natural = natural;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (SIZE.equals(key)) {
            refreshDimensions();
            setMaxUpStep(heightBlocks() / 4f);
        }
        if (level().isClientSide && ANIM_SEQ.equals(key)) {
            onAnimChanged();
        }
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        float h = heightBlocks();
        return EntityDimensions.scalable(h * 0.3f, h);
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        float h = heightBlocks();
        return getBoundingBox().inflate(h * 0.6f, h * 0.2f, h * 0.6f);
    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, reason, data, tag);
        randomizeLooks(level.getRandom());
        setSizeIndex(rollSize(level.getRandom()));
        archetype = level.getRandom().nextBoolean() ? STRIKER : GRABBER;
        return result;
    }

    // ================================================================ animation

    public int anim() { return entityData.get(ANIM); }
    public float animSpeedSynced() { return entityData.get(ANIM_SPEED); }

    /** Plays a looping/idle animation; does nothing if it is already playing. */
    public void playAnim(String name) {
        int a = TitanModelData.get().anim(name);
        if (a != anim()) {
            startAnim(a, 1f);
        }
    }

    /** Starts an animation from the beginning (attacks may repeat the same animation). */
    public void playAnimRestart(String name, float speed) {
        startAnim(TitanModelData.get().anim(name), speed);
    }

    private void startAnim(int a, float speed) {
        entityData.set(ANIM_SPEED, speed);
        entityData.set(ANIM, a);
        entityData.set(ANIM_SEQ, entityData.get(ANIM_SEQ) + 1);
        onAnimChanged();
    }

    /** Remember the previous animation for cross-fading and restart the local animation clock. */
    private void onAnimChanged() {
        int a = anim();
        int seq = entityData.get(ANIM_SEQ);
        if (a == lastAnim && seq == lastSeq) {
            return;
        }
        if (lastAnim >= 0) {
            blendPrev = lastAnim;
            blendPrevFrame = frameOf(lastAnim, localAnimStart, lastSpeed, 0f);
            blendPrevSpeed = isWalk(lastAnim) ? 0f : lastSpeed;
            blendStart = tickCount;
        }
        lastAnim = a;
        lastSeq = seq;
        lastSpeed = animSpeedSynced();
        localAnimStart = tickCount;
    }

    private static boolean isWalk(int anim) {
        return TitanModelData.get().animNames[anim].startsWith("run.");
    }

    public boolean isLooping(int anim) {
        String n = TitanModelData.get().animNames[anim];
        return n.equals("idle") || n.startsWith("run.") || n.equals("sit_idle");
    }

    private float frameOf(int anim, int start, float speed, float partialTick) {
        if (isWalk(anim)) {
            return Mth.lerp(partialTick, walkPhasePrev, walkPhase);
        }
        return (tickCount - start + partialTick) * speed;
    }

    /** Current animation frame (fractional). The walk cycle follows the distance actually travelled. */
    public float animFrame(float partialTick) {
        return frameOf(anim(), localAnimStart, animSpeedSynced(), partialTick);
    }

    /** Skeleton pose (3x4 skin matrices) including cross-fade and cut-off arms. */
    public void computePose(float partialTick, float[] out) {
        TitanModelData d = TitanModelData.get();
        int a = anim();
        d.pose(a, animFrame(partialTick), isLooping(a), out);
        if (blendPrev >= 0) {
            float t = (tickCount - blendStart + partialTick) / BLEND_TICKS;
            if (t < 1f && t >= 0f) {
                if (blendPose == null) {
                    blendPose = new float[out.length];
                }
                float f = blendPrevFrame + (tickCount - blendStart + partialTick) * blendPrevSpeed;
                d.pose(blendPrev, f, isLooping(blendPrev), blendPose);
                TitanModelData.blend(blendPose, out, t, out);
            } else if (t >= 1f) {
                blendPrev = -1;
            }
        }
        if (armDisabled(true)) {
            d.collapseArm(out, true);
        }
        if (armDisabled(false)) {
            d.collapseArm(out, false);
        }
    }

    // ================================================================ synced state helpers

    public boolean armDisabled(boolean left) {
        return (entityData.get(FLAGS) & (left ? 1 : 2)) != 0;
    }

    private void setFlag(int bit, boolean on) {
        int f = entityData.get(FLAGS);
        entityData.set(FLAGS, on ? f | bit : f & ~bit);
    }

    public int heldEntityId() {
        return entityData.get(HELD);
    }

    public boolean holdLeft() {
        return (entityData.get(FLAGS) & 4) != 0;
    }

    // ================================================================ tick

    @Override
    public void tick() {
        super.tick();
        walkPhasePrev = walkPhase;
        double dx = getX() - xo, dz = getZ() - zo;
        double disp = Math.sqrt(dx * dx + dz * dz);
        walkPhase += (float) (disp * 41.0 / (WALK_STRIDE * modelScale()));
        if (walkPhase > 41f * 1000f) {
            walkPhase -= 41f * 1000f;
            walkPhasePrev -= 41f * 1000f;
        }
    }

    @Override
    protected float tickHeadTurn(float yBodyRotTarget, float animStep) {
        yBodyRot = getYRot();
        yHeadRot = getYRot();
        return animStep;
    }

    @Override
    public void travel(Vec3 input) {
        if (!isControlledByLocalInstance()) {
            super.travel(input);
            return;
        }
        double speed = 0;
        if (moving && !isDeadOrDying()) {
            speed = WALK_SPEED * heightBlocks() / 20.0;
            float diff = Math.abs(Mth.wrapDegrees(heading - getYRot()));
            if (diff > 60f) {
                speed *= 0.3;
            }
            if (isInWater()) {
                speed *= 0.5; // titans can't swim: they walk on the bottom at half speed
            }
        }
        double yaw = getYRot() * Mth.DEG_TO_RAD;
        Vec3 dm = getDeltaMovement();
        setDeltaMovement(-Math.sin(yaw) * speed, dm.y, Math.cos(yaw) * speed);
        double bx = getX(), bz = getZ();
        move(MoverType.SELF, getDeltaMovement());
        movedSq = (getX() - bx) * (getX() - bx) + (getZ() - bz) * (getZ() - bz);
        Vec3 after = getDeltaMovement();
        setDeltaMovement(after.x, (after.y - 0.08) * 0.98, after.z);
        calculateEntityAnimation(false);
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (isDeadOrDying()) {
            return;
        }
        float dt = 0.05f;
        stateTime -= dt;
        stateElapsed += dt;
        updateArms(dt);
        if (++regenTimer >= 60) {
            regenTimer = 0;
            if (getHealth() < getMaxHealth()) {
                heal(2f); // 1 heart every 3 seconds
            }
        }
        updateHeld();
        boolean night = isNight();
        if (night) {
            target = null;
        } else {
            updateTarget(dt);
        }
        moving = false;

        switch (state) {
            case IDLE -> {
                if (night) {
                    sitDown();
                } else if (target != null) {
                    startChase();
                } else if (stateTime <= 0) {
                    setState(S.WANDER, 3f + random.nextFloat() * 5f);
                    wanderHeading = random.nextFloat() * 360f;
                }
            }
            case WANDER -> {
                if (night) {
                    sitDown();
                } else if (target != null) {
                    startChase();
                } else if (stateTime <= 0) {
                    setState(S.IDLE, 2f + random.nextFloat() * 3f);
                } else {
                    steer(wanderHeading, false);
                }
            }
            case CHASE -> tickChase(night);
            case WAIT_ATTACK -> tickWaitAttack(night);
            case PAUSE -> {
                if (stateTime <= 0) {
                    setState(S.IDLE, 0f);
                }
            }
            case TURN -> {
                float t = Mth.clamp(stateElapsed / turnDuration, 0f, 1f);
                setYawAll(turnFrom + Mth.wrapDegrees(turnTo - turnFrom) * t);
                if (stateTime <= 0) {
                    pause(TURN_PAUSE);
                }
            }
            case ATTACK -> tickAttack();
            case EAT -> tickEat();
            case SIT_DOWN -> {
                if (stateTime <= 0) {
                    setState(S.SIT_IDLE, 0f);
                    playAnim("sit_idle");
                }
            }
            case SIT_IDLE -> {
                if (!night && stateTime <= 0) {
                    action(S.SIT_UP, "sit_getup");
                }
            }
            case SIT_UP, BLIND -> {
                if (stateTime <= 0) {
                    pause(0.3f);
                }
            }
            case SIT_FALL -> {
                if (stateTime <= 0) {
                    setState(S.SIT_CRIPPLE, CRIPPLE_TIME);
                    playAnim("sit_idle");
                }
            }
            case SIT_CRIPPLE -> {
                if (stateTime <= 0) {
                    action(S.SIT_UP, "sit_getup");
                }
            }
            case SIT_BLIND -> {
                if (stateTime <= 0) {
                    setState(S.SIT_IDLE, 0.3f);
                    playAnim("sit_idle");
                }
            }
            default -> {
            }
        }

        if (state == S.IDLE || state == S.WANDER || state == S.CHASE || state == S.WAIT_ATTACK || state == S.PAUSE) {
            playAnim(moving && movedSq > 1.0E-5 ? "run.walk" : "idle");
        }
    }

    private boolean isNight() {
        long t = level().getDayTime() % 24000L;
        return t >= 13000L && t < 23000L;
    }

    // ---------------------------------------------------------------- states

    private void setState(S s, float time) {
        if (s != S.EAT && s != S.ATTACK) {
            release();
        }
        state = s;
        stateTime = time;
        stateElapsed = 0;
    }

    /** AoTTG StateAction: switch state and play an animation for its full length. */
    private void action(S s, String anim) {
        setState(s, animLength(anim, 1f));
        playAnimRestart(anim, 1f);
    }

    private float animLength(String anim, float speed) {
        TitanModelData d = TitanModelData.get();
        return d.length(d.anim(anim)) / 20f / speed;
    }

    private void pause(float time) {
        setState(S.PAUSE, time);
    }

    private void sitDown() {
        action(S.SIT_DOWN, "sit_down");
    }

    private void startChase() {
        setState(S.CHASE, 0f);
        chaseTimer = 0;
    }

    private void setYawAll(float yaw) {
        setYRot(yaw);
        yBodyRot = yaw;
        yHeadRot = yaw;
    }

    private void updateTarget(float dt) {
        if (target != null && (!target.isAlive() || target.isSpectator() || target.level() != level()
                || horizontalDistance(target) > LOSE_RANGE)) {
            target = null;
        }
        focusTimer -= dt;
        if (focusTimer <= 0 || target == null) {
            focusTimer = 1f;
            Player best = null;
            double bestD = DETECT_RANGE;
            for (Player p : level().players()) {
                if (!p.isAlive() || p.isSpectator()) {
                    continue;
                }
                double d = distanceTo(p);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            if (best != null) {
                target = best;
            }
        }
    }

    private double horizontalDistance(Entity e) {
        double dx = e.getX() - getX(), dz = e.getZ() - getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private float yawTo(Entity e) {
        return (float) (Mth.atan2(-(e.getX() - getX()), e.getZ() - getZ()) * Mth.RAD_TO_DEG);
    }

    /** Local units -> blocks. */
    private double localToBlocks(double local) {
        return local / TitanGeometry.LOCAL_PER_MODEL * modelScale();
    }

    private void tickChase(boolean night) {
        if (night) {
            sitDown();
            return;
        }
        if (target == null) {
            setState(S.IDLE, 1f);
            return;
        }
        double dist = horizontalDistance(target);
        if (dist <= localToBlocks(CLOSE_ATTACK_RANGE)) {
            setState(S.WAIT_ATTACK, 0.5f + random.nextFloat() * 0.1f);
            waitAttackTime = 0;
            return;
        }
        chaseTimer -= 0.05f;
        if (chaseTimer <= 0) {
            moveAngle = dist > localToBlocks(CHASE_ANGLE_MIN_RANGE) ? random.nextFloat() * 90f - 45f : 0f;
            chaseTimer = 0.75f + random.nextFloat() * 1.25f;
        }
        steer(yawTo(target) + moveAngle, true);
    }

    private void tickWaitAttack(boolean night) {
        if (night) {
            sitDown();
            return;
        }
        if (target == null) {
            setState(S.IDLE, 1f);
            return;
        }
        if (stateTime > 0) {
            return;
        }
        waitAttackTime += 0.05f;
        double dist = horizontalDistance(target);
        if (dist > localToBlocks(CLOSE_ATTACK_RANGE)) {
            startChase();
            return;
        }
        String attack = canAttack() ? chooseAttack(target) : null;
        if (attack != null) {
            startAttack(attack, target);
        } else if (Math.abs(Mth.wrapDegrees(yawTo(target) - getYRot())) > TURN_ANGLE) {
            startTurn(yawTo(target));
        } else if (waitAttackTime > 2f) {
            steer(yawTo(target), true);
        }
    }

    private void startTurn(float toYaw) {
        float delta = Mth.wrapDegrees(toYaw - getYRot());
        String anim = delta > 0 ? "turnaround.R" : "turnaround.L";
        turnFrom = getYRot();
        turnTo = toYaw;
        turnDuration = animLength(anim, TURN_SPEED);
        setState(S.TURN, turnDuration);
        playAnimRestart(anim, TURN_SPEED);
    }

    public boolean canAttack() {
        return !armDisabled(true) && !armDisabled(false);
    }

    // ---------------------------------------------------------------- movement and obstacles

    private static final int FOOT = 1, HAND = 2, BLOCKED = 4;

    /** Walk towards a heading, breaking wooden-tool blocks in the way and walking around everything else. */
    private void steer(float desired, boolean chasing) {
        if (--navTimer <= 0) {
            navTimer = 4;
            navigate(desired);
            if (state == S.ATTACK) {
                return;
            }
        }
        float diff = Mth.wrapDegrees(heading - getYRot());
        float step = TURN_RATE * 0.05f;
        setYawAll(getYRot() + Mth.clamp(diff, -step, step));
        moving = true;
    }

    private boolean blocked(int probe) {
        return (probe & BLOCKED) != 0 || (!canBreak() && (probe & (HAND | FOOT)) != 0);
    }

    private boolean canBreak() {
        return canAttack() && level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }

    private void navigate(float desired) {
        int probe;
        if (detourTicks > 0) {
            detourTicks--;
            probe = probe(detourHeading);
            if (blocked(probe)) {
                pickDetour(desired);
                probe = probe(heading);
            } else {
                heading = detourHeading;
            }
            if (detourTicks == 0 && !blocked(probe(desired))) {
                heading = desired;
                probe = probe(heading);
            }
        } else {
            probe = probe(desired);
            if (blocked(probe)) {
                pickDetour(desired);
                probe = probe(heading);
            } else {
                heading = desired;
            }
        }
        if ((probe & (HAND | FOOT)) != 0 && !blocked(probe) && Math.abs(Mth.wrapDegrees(heading - getYRot())) < 25f) {
            startBreak((probe & HAND) != 0);
        }
    }

    private void pickDetour(float desired) {
        for (int k = 1; k <= 6; k++) {
            for (int side = 0; side < 2; side++) {
                int sign = side == 0 ? detourSign : -detourSign;
                float h = desired + sign * k * 30f;
                if (!blocked(probe(h))) {
                    detourSign = sign;
                    detourHeading = h;
                    heading = h;
                    detourTicks = 10;
                    return;
                }
            }
        }
        detourHeading = desired + 180f;
        heading = detourHeading;
        detourTicks = 5;
    }

    /** Collects block columns of an oriented box in front of the titan. */
    private Set<Long> frontColumns(float headingDeg, double from, double to, double halfWidth) {
        double rad = headingDeg * Mth.DEG_TO_RAD;
        double fx = -Math.sin(rad), fz = Math.cos(rad);
        double sx = -fz, sz = fx;
        Set<Long> cols = new HashSet<>();
        for (double d = from; d <= to + 1.0E-6; d += 0.5) {
            for (double l = -halfWidth; l <= halfWidth + 1.0E-6; l += 0.5) {
                int bx = Mth.floor(getX() + fx * d + sx * l);
                int bz = Mth.floor(getZ() + fz * d + sz * l);
                cols.add(BlockPos.asLong(bx, 0, bz));
            }
        }
        return cols;
    }

    private double probeDepth() {
        return Math.max(1.5, 0.12 * heightBlocks());
    }

    /** What is in the way in a direction: FOOT/HAND = breakable blocks at that height, BLOCKED = unbreakable. */
    private int probe(float headingDeg) {
        float h = heightBlocks();
        double halfW = getBbWidth() * 0.5;
        double feet = getY();
        double stepTop = feet + maxUpStep() + 0.01;
        double footTop = feet + FOOT_ZONE * h;
        int y0 = Mth.floor(feet);
        int y1 = Mth.floor(feet + h);
        int result = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long c : frontColumns(headingDeg, halfW + 0.25, halfW + probeDepth(), halfW)) {
            int x = BlockPos.getX(c), z = BlockPos.getZ(c);
            for (int y = y0; y <= y1; y++) {
                pos.set(x, y, z);
                BlockState st = level().getBlockState(pos);
                if (st.isAir()) {
                    continue;
                }
                VoxelShape shape = st.getCollisionShape(level(), pos);
                if (shape.isEmpty() || y + shape.max(Direction.Axis.Y) <= stepTop) {
                    continue;
                }
                int cls = classify(pos, st);
                if (cls == 2) {
                    return result | BLOCKED;
                }
                if (cls == 1) {
                    result |= y + 0.5 < footTop ? FOOT : HAND;
                }
            }
        }
        return result;
    }

    /** 0 = passable, 1 = breakable (wooden-tool block), 2 = unbreakable (stone of any kind, thick trunks...). */
    private int classify(BlockPos pos, BlockState st) {
        if (st.isAir() || st.getCollisionShape(level(), pos).isEmpty()) {
            return 0;
        }
        if (st.getDestroySpeed(level(), pos) < 0 || st.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return 2;
        }
        if (!(st.is(BlockTags.MINEABLE_WITH_AXE) || st.is(BlockTags.MINEABLE_WITH_SHOVEL) || st.is(BlockTags.MINEABLE_WITH_HOE))) {
            return 2;
        }
        if (st.is(BlockTags.LOGS) && thickTrunk(pos)) {
            return 2;
        }
        return 1;
    }

    /** A log that is part of a 2x2 (or thicker) trunk. */
    private boolean thickTrunk(BlockPos pos) {
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                boolean all = true;
                for (int i = 0; i < 2 && all; i++) {
                    for (int j = 0; j < 2 && all; j++) {
                        all = level().getBlockState(pos.offset(dx + i, 0, dz + j)).is(BlockTags.LOGS);
                    }
                }
                if (all) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Punch (hands, above FOOT_ZONE) or kick (feet, from step height up to FOOT_ZONE) to clear the way. */
    private void startBreak(boolean hands) {
        windows.clear();
        breakTimes.clear();
        grabAttack = false;
        String anim;
        if (hands) {
            anim = "attack.comboPunch";
            float len = animLength(anim, 1f);
            windows.add(new Window(TitanGeometry.HAND_R, 0.28f * len, 0.14f));
            windows.add(new Window(TitanGeometry.HAND_L, 0.63f * len, 0.14f));
            breakTimes.add(0.28f * len);
            breakTimes.add(0.63f * len);
            breakZone = 1;
        } else {
            anim = "attack.kick";
            float len = animLength(anim, 1f);
            windows.add(new Window(TitanGeometry.FOOT_L, 0.38f * len, 0.25f));
            breakTimes.add(0.38f * len);
            breakZone = 2;
        }
        action(S.ATTACK, anim);
        playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 2f, 0.5f);
    }

    private void breakBlocks(int zone) {
        float h = heightBlocks();
        double halfW = getBbWidth() * 0.5;
        double feet = getY();
        double stepTop = feet + maxUpStep() + 0.01;
        double footTop = feet + FOOT_ZONE * h;
        int y0 = Mth.floor(feet);
        int y1 = Mth.floor(feet + h + 1);
        int broken = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long c : frontColumns(getYRot(), halfW - 0.5, halfW + probeDepth() + 1.0, halfW + 0.5)) {
            int x = BlockPos.getX(c), z = BlockPos.getZ(c);
            for (int y = y0; y <= y1; y++) {
                pos.set(x, y, z);
                BlockState st = level().getBlockState(pos);
                if (st.isAir()) {
                    continue;
                }
                VoxelShape shape = st.getCollisionShape(level(), pos);
                if (shape.isEmpty() || y + shape.max(Direction.Axis.Y) <= stepTop) {
                    continue;
                }
                boolean inFoot = y + 0.5 < footTop;
                if ((zone == 1) == inFoot) {
                    continue;
                }
                if (classify(pos, st) == 1 && broken < 600) {
                    level().destroyBlock(pos.immutable(), false, this);
                    broken++;
                }
            }
        }
        if (broken > 0) {
            playSound(SoundEvents.GENERIC_EXPLODE, 1.5f, 0.6f);
        }
    }

    // ---------------------------------------------------------------- attacks

    /** AoTTG2 AttackRanges (titan-local units: x right, y up, z forward). */
    private static final String[] ATTACKS = {"PunchCombo", "Slam", "Swing", "Slap", "SlapFace", "SlapBack", "Grab"};
    private static final float[][][] RANGES = {
            {{-5, 5, -3, 15, 0, 16}},
            {{-5, 5, -3, 7, 0, 14}},
            {{-6, 6, -3, 7, 3, 14}},
            {{-5, 5, 4, 20, 1, 16}},
            {{-4, 4, 10, 18, 0, 4}},
            {{-4, 4, 9, 15, -4, 0}},
            {{-4, 4, -2, 20, -4, 4}, {-6, 6, -2, 15, 2, 8}, {-6, 6, 4, 15, 8, 16}, {-6, 6, -2, 5, -8, -2}, {-5, 5, 20, 26, -2, 6}},
    };

    /** Target position in AoTTG titan-local units (x = titan's right). */
    private double[] localOf(Entity e) {
        double dx = e.getX() - getX(), dy = e.getY() + e.getBbHeight() * 0.5 - getY(), dz = e.getZ() - getZ();
        double yaw = getYRot() * Mth.DEG_TO_RAD;
        double c = Math.cos(yaw), s = Math.sin(yaw);
        double k = TitanGeometry.LOCAL_PER_MODEL / modelScale();
        double mx = (dx * c + dz * s) * k, mz = (-dx * s + dz * c) * k;
        return new double[]{-mx, dy * k, mz};
    }

    /** AoTTG GetNearestHumanAngles: horizontal angle (+ = titan's right) and elevation, seen from the neck. */
    private double[] anglesTo(Entity e) {
        double[] l = localOf(e);
        double ny = TitanGeometry.NECK[1] * TitanGeometry.LOCAL_PER_MODEL;
        double tx = l[0], ty = l[1] - ny, tz = l[2];
        double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        double angleY = len < 1.0E-6 ? 0 : Math.asin(ty / len) * Mth.RAD_TO_DEG;
        double angleX = Mth.atan2(tx, tz) * Mth.RAD_TO_DEG;
        return new double[]{angleX, angleY};
    }

    @Nullable
    private String chooseAttack(Player p) {
        double[] l = localOf(p);
        float total = 0;
        float[] w = new float[ATTACKS.length];
        for (int i = 0; i < ATTACKS.length; i++) {
            for (float[] r : RANGES[i]) {
                if (l[0] >= r[0] && l[0] <= r[1] && l[1] >= r[2] && l[1] <= r[3] && l[2] >= r[4] && l[2] <= r[5]) {
                    boolean grab = ATTACKS[i].equals("Grab");
                    w[i] = archetype == GRABBER ? (grab ? 1f : 0.05f) : (grab ? 0.15f : 1f);
                    total += w[i];
                    break;
                }
            }
        }
        if (total <= 0) {
            return null;
        }
        float x = random.nextFloat() * total;
        for (int i = 0; i < ATTACKS.length; i++) {
            if (w[i] > 0 && x < w[i]) {
                return ATTACKS[i];
            }
            x -= w[i];
        }
        return null;
    }

    private void startAttack(String attack, Player p) {
        windows.clear();
        breakTimes.clear();
        breakZone = 0;
        grabAttack = false;
        double[] ang = anglesTo(p);
        double angleX = ang[0], angleY = ang[1];
        String anim;
        final int L = TitanGeometry.HAND_L, R = TitanGeometry.HAND_R;
        switch (attack) {
            case "PunchCombo" -> {
                anim = "attack.combo";
                float len = animLength(anim, 1f);
                windows.add(new Window(R, 0.11f * len, 0.14f));
                windows.add(new Window(L, 0.26f * len, 0.14f));
                windows.add(new Window(L, 0.495f * len, 0.15f));
                windows.add(new Window(R, 0.495f * len, 0.15f));
            }
            case "Slam" -> {
                anim = "attack.front.ground";
                float len = animLength(anim, 1f);
                windows.add(new Window(L, 0.42f * len, 0.15f));
                windows.add(new Window(R, 0.42f * len, 0.15f));
            }
            case "Swing" -> {
                boolean left = angleX > 0;
                anim = left ? "attack.swing.l" : "attack.swing.r";
                windows.add(new Window(left ? L : R, 0.41f * animLength(anim, 1f), 0.13f));
            }
            case "Slap" -> {
                boolean left = angleX < 0;
                String side = left ? "l" : "r";
                anim = angleY > 45 ? "attack.anti.AE.high." + side : angleY > -10 ? "attack.anti.AE." + side : "attack.anti.AE.low." + side;
                windows.add(new Window(left ? L : R, 0.335f * animLength(anim, 1f), 0.25f));
            }
            case "SlapFace" -> {
                anim = "attack.slap.face";
                windows.add(new Window(R, 0.64f * animLength(anim, 1f), 0.3f));
            }
            case "SlapBack" -> {
                anim = "attack.slap.back";
                windows.add(new Window(R, 0.65f * animLength(anim, 1f), 0.1f));
            }
            default -> {
                anim = grabAnim(p, angleX);
                grabAttack = true;
            }
        }
        action(S.ATTACK, anim);
        playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 2f, 0.5f);
    }

    /** AoTTG2 BasicTitan.AttackGrab: animation + hand hitbox (delay, length) from the target's local position. */
    private String grabAnim(Player p, double angleX) {
        double[] l = localOf(p);
        double y = l[1], z = Math.abs(l[2]);
        String choice;
        if (z <= 4) {
            choice = y < 2 ? "Core" : y < 11 ? "Stomach" : y < 20 ? "Head" : "High";
        } else if (z <= 8) {
            choice = y < 5 ? "Ground" : y <= 11 ? "Air" : y < 20 ? "Head" : "High";
        } else {
            choice = y < 5 ? "Ground" : y < 20 ? "AirFar" : "High";
        }
        final int L = TitanGeometry.HAND_L, R = TitanGeometry.HAND_R;
        boolean right = angleX > 0;
        switch (choice) {
            case "Core" -> {
                windows.add(new Window(right ? R : L, 0.65f, 0.23f));
                return right ? "grab.core.R" : "grab.core.L";
            }
            case "Stomach", "Air", "AirFar" -> {
                if (right && angleX > 90) {
                    windows.add(new Window(R, 0.88f, 0.36f));
                    return "grab.back.r";
                }
                if (!right && angleX < -90) {
                    windows.add(new Window(L, 0.88f, 0.36f));
                    return "grab.back.l";
                }
                if (choice.equals("Stomach")) {
                    windows.add(new Window(right ? R : L, 0.71f, 0.36f));
                    return right ? "grab.stomach.r" : "grab.stomach.l";
                }
                if (choice.equals("Air")) {
                    windows.add(new Window(right ? R : L, 0.4f, 0.3f));
                    return right ? "grab.air.short.r" : "grab.air.short.l";
                }
                windows.add(new Window(right ? R : L, 0.76f, 0.27f));
                return right ? "grab.air.far.r" : "grab.air.far.l";
            }
            case "Head" -> {
                if (right) {
                    if (angleX > 90) {
                        windows.add(new Window(L, 0.96f, 0.13f));
                        return "grab.head.back.r";
                    }
                    windows.add(new Window(R, 1.03f, 0.5f));
                    return "grab.head.front.r";
                }
                if (angleX < -90) {
                    windows.add(new Window(R, 0.96f, 0.13f));
                    return "grab.head.back.l";
                }
                windows.add(new Window(L, 1.03f, 0.5f));
                return "grab.head.front.1";
            }
            case "Ground" -> {
                windows.add(new Window(right ? R : L, 0.91f, 0.35f));
                if (right) {
                    return angleX > 90 ? "grab.ground.back.r" : "grab.ground.front.r";
                }
                return angleX < -90 ? "grab.ground.back.l" : "grab.ground.front.l";
            }
            default -> {
                windows.add(new Window(right ? R : L, 0.88f, 0.43f));
                return right ? "grab.high.r" : "grab.high.l";
            }
        }
    }

    private void tickAttack() {
        float t = stateElapsed;
        for (int i = 0; i < breakTimes.size(); i++) {
            if (breakTimes.get(i) >= 0 && t >= breakTimes.get(i)) {
                breakTimes.set(i, -1f);
                breakBlocks(breakZone);
            }
        }
        TitanGeometry.Frame f = null;
        for (Window w : windows) {
            if (w.done || t < w.start || t > w.end) {
                continue;
            }
            if (f == null) {
                f = TitanGeometry.frame(this, 1f, false);
            }
            Vec3 c = TitanGeometry.hitboxCenter(f, w.part);
            double r = TitanGeometry.hitboxRadius(f, w.part);
            for (Player p : level().players()) {
                if (!p.isAlive() || p.isSpectator() || p.getId() == heldEntityId() || !sphereHits(c, r, p.getBoundingBox())) {
                    continue;
                }
                if (grabAttack) {
                    if (heldEntityId() < 0 && !isHeldByAnyTitan(p)) {
                        grab(p, w.part == TitanGeometry.HAND_L);
                        w.done = true;
                    }
                } else {
                    float dmg = DAMAGE_HEARTS[sizeIndex()] * 2f;
                    if (p.hurt(damageSources().mobAttack(this), dmg)) {
                        Vec3 push = p.position().subtract(position()).multiply(1, 0, 1).normalize();
                        p.push(push.x * 1.2, 0.4, push.z * 1.2);
                        p.hurtMarked = true;
                    }
                    w.done = true;
                }
            }
        }
        if (stateTime <= 0) {
            if (heldEntityId() >= 0) {
                setState(S.EAT, 0f);
                String anim = holdLeft() ? "eat.l" : "eat.r";
                float len = animLength(anim, 1f);
                stateTime = len;
                killAt = len - 4.72f;
                playAnimRestart(anim, 1f);
            } else {
                pause(ATTACK_PAUSE);
            }
        }
    }

    private static boolean sphereHits(Vec3 c, double r, AABB bb) {
        double x = Mth.clamp(c.x, bb.minX, bb.maxX) - c.x;
        double y = Mth.clamp(c.y, bb.minY, bb.maxY) - c.y;
        double z = Mth.clamp(c.z, bb.minZ, bb.maxZ) - c.z;
        return x * x + y * y + z * z <= r * r;
    }

    private boolean isHeldByAnyTitan(Player p) {
        for (TitanEntity t : level().getEntitiesOfClass(TitanEntity.class, p.getBoundingBox().inflate(64))) {
            if (t.heldEntityId() == p.getId()) {
                return true;
            }
        }
        return false;
    }

    private void grab(Player p, boolean left) {
        entityData.set(HELD, p.getId());
        setFlag(4, left);
        playSound(SoundEvents.PLAYER_HURT, 1f, 0.6f);
    }

    private void release() {
        if (heldEntityId() >= 0) {
            entityData.set(HELD, -1);
        }
    }

    private void updateHeld() {
        int id = heldEntityId();
        if (id < 0) {
            return;
        }
        Entity e = level().getEntity(id);
        if (!(e instanceof Player p) || !p.isAlive() || p.isSpectator() || p.distanceTo(this) > heightBlocks() * 2 + 10) {
            release();
            return;
        }
        p.fallDistance = 0;
    }

    private void tickEat() {
        if (heldEntityId() < 0 && stateElapsed < killAt) {
            pause(1f); // the victim got free
            return;
        }
        Entity e = level().getEntity(heldEntityId());
        if (e instanceof Player p && stateElapsed >= killAt) {
            p.hurt(damageSources().mobAttack(this), 1000f);
            playSound(SoundEvents.PLAYER_HURT, 1.5f, 0.5f);
            release();
        }
        if (stateTime <= 0) {
            pause(ATTACK_PAUSE);
        }
    }

    // ---------------------------------------------------------------- damage from blades

    /** Called on the server when a player's blade hits one of the hurtboxes. */
    public void onBladeHit(ServerPlayer p, TitanGeometry.Part part, float speedUnits) {
        if (isDeadOrDying()) {
            return;
        }
        boolean sleeping = isNight() && (state == S.SIT_DOWN || state == S.SIT_IDLE);
        TitanGeometry.Frame f = TitanGeometry.frame(this, 1f, false);
        level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1f, 1.3f);
        switch (part) {
            case NAPE -> {
                double weapon = p.getAttributeValue(Attributes.ATTACK_DAMAGE);
                float dmg = (float) (weapon * speedUnits / 20.0);
                particles(new DustParticleOptions(new Vector3f(0.7f, 0.05f, 0.05f), 3f), TitanGeometry.napePoint(f), 30, heightBlocks() * 0.04);
                if (dmg > 0) {
                    bladeHurt = true;
                    invulnerableTime = 0;
                    hurt(damageSources().playerAttack(p), dmg);
                    bladeHurt = false;
                }
            }
            case EYES -> {
                if (!sleeping) {
                    blind();
                }
            }
            case LEG_L, LEG_R -> {
                if (!sleeping) {
                    cripple();
                }
            }
            case FOREARM_L -> disableArm(true, f);
            case FOREARM_R -> disableArm(false, f);
        }
    }

    private void blind() {
        if (state == S.BLIND || state == S.SIT_BLIND || state == S.DEAD) {
            return;
        }
        if (state == S.SIT_CRIPPLE || state == S.SIT_IDLE) {
            action(S.SIT_BLIND, "sit_hit_eye");
        } else {
            action(S.BLIND, "hit.eye");
        }
    }

    private void cripple() {
        if (state == S.SIT_CRIPPLE || state == S.SIT_FALL || state == S.DEAD) {
            return;
        }
        action(S.SIT_FALL, "sit_hunt_down");
    }

    private void disableArm(boolean left, TitanGeometry.Frame f) {
        if (state == S.ATTACK || armDisabled(left)) {
            return; // AoTTG2: arms can't be cut mid-attack
        }
        Vec3 at = TitanGeometry.forearmPoint(f, left);
        setFlag(left ? 1 : 2, true);
        if (left) {
            armLTime = ARM_DISABLE_TIME;
        } else {
            armRTime = ARM_DISABLE_TIME;
        }
        particles(new DustParticleOptions(new Vector3f(0.7f, 0.05f, 0.05f), 3f), at, 40, heightBlocks() * 0.05);
        if (heldEntityId() >= 0 && holdLeft() == left) {
            release();
            pause(0.5f);
        }
    }

    private void updateArms(float dt) {
        for (int side = 0; side < 2; side++) {
            boolean left = side == 0;
            if (!armDisabled(left)) {
                continue;
            }
            float t = left ? (armLTime -= dt) : (armRTime -= dt);
            if (t <= 0) {
                setFlag(left ? 1 : 2, false);
            } else if (ARM_DISABLE_TIME - t > 2.5f && tickCount % 3 == 0) {
                Vec3 at = TitanGeometry.forearmPoint(TitanGeometry.frame(this, 1f, false), left);
                particles(ParticleTypes.CLOUD, at, 3, heightBlocks() * 0.03);
            }
        }
    }

    private void particles(ParticleOptions type, Vec3 at, int count, double spread) {
        if (level() instanceof ServerLevel sl) {
            sl.sendParticles(type, at.x, at.y, at.z, count, spread, spread, spread, 0.02);
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (bladeHurt || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return super.hurt(source, amount);
        }
        return false; // only the nape can be damaged
    }

    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        return true;
    }

    @Override
    public void die(DamageSource source) {
        boolean sitting = state == S.SIT_IDLE || state == S.SIT_CRIPPLE || state == S.SIT_FALL || state == S.SIT_DOWN || state == S.SIT_BLIND;
        super.die(source);
        release();
        state = S.DEAD;
        String anim = sitting ? "sit_die" : "die.back";
        dieTicks = TitanModelData.get().length(TitanModelData.get().anim(anim)) + 30;
        playAnimRestart(anim, 1f);
    }

    @Override
    protected void tickDeath() {
        ++deathTime;
        if (deathTime >= dieTicks && !level().isClientSide() && !isRemoved()) {
            particles(ParticleTypes.CAMPFIRE_COSY_SMOKE, position().add(0, heightBlocks() * 0.2, 0), 40, heightBlocks() * 0.2);
            dropExperience();
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** Titans don't shove players out of their bounding box; the body capsules are solid instead (client side). */
    @Override
    protected void pushEntities() {
    }

    @Override
    public boolean canBreatheUnderwater() {
        return true;
    }

    // ================================================================ save / load

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("TitanSize", sizeIndex());
        tag.putInt("TitanLooks", entityData.get(LOOKS));
        tag.putInt("TitanType", archetype);
        tag.putBoolean("TitanNatural", natural);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(LOOKS, tag.getInt("TitanLooks"));
        entityData.set(SIZE, tag.getInt("TitanSize"));
        archetype = tag.getInt("TitanType");
        natural = tag.getBoolean("TitanNatural");
        refreshDimensions();
        setMaxUpStep(heightBlocks() / 4f);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return natural;
    }

    /** Natural titans vanish only when every player is more than 512 blocks away; no vanilla random despawn. */
    static final double DESPAWN_DISTANCE = 512.0;

    @Override
    public void checkDespawn() {
        if (level().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL && shouldDespawnInPeaceful()) {
            discard();
            return;
        }
        noActionTime = 0;
        if (!natural || isPersistenceRequired()) {
            return;
        }
        Player p = level().getNearestPlayer(this, -1.0);
        if (p == null || p.distanceToSqr(this) > DESPAWN_DISTANCE * DESPAWN_DISTANCE) {
            discard();
        }
    }
}
