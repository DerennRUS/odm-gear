package com.derenn.odmgear.titan;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * AoTTG2-style titan. Appearance (body, head, hair, hair colour, eyes, skin tone) and size are rolled on spawn,
 * the current animation is decided on the server and synced as (animation, start tick).
 */
public class TitanEntity extends Monster {
    /** Titan heights in blocks and their spawn chances. */
    public static final float[] SIZES = {10f, 15f, 25f, 40f};
    public static final float[] SIZE_CHANCES = {0.20f, 0.30f, 0.40f, 0.10f};
    public static final int[] HEADS = {0, 1, 3, 4};
    public static final String[] HAIRS = {"armin", "boy1", "boy2", "boy3", "eren", "girl2", "jean", "levi", "marco", "mikasa", "mike", "petra"};
    public static final int[][] HAIR_COLORS = {
            {35, 18, 11}, {61, 36, 20}, {90, 56, 37}, {204, 153, 102}, {230, 190, 138}, {255, 204, 71}, {153, 101, 21},
            {151, 80, 45}, {208, 135, 54}, {36, 28, 17}, {154, 51, 0}, {86, 79, 82}, {70, 71, 71}, {94, 8, 8},
            {192, 185, 175}, {154, 158, 159}, {40, 40, 40}, {130, 50, 50}};
    public static final int EYE_TEXTURES = 11;

    private static final EntityDataAccessor<Integer> SIZE = SynchedEntityData.defineId(TitanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> LOOKS = SynchedEntityData.defineId(TitanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ANIM = SynchedEntityData.defineId(TitanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ANIM_START = SynchedEntityData.defineId(TitanEntity.class, EntityDataSerializers.INT);

    // client-side animation blending
    public int clientPrevAnim = -1;
    public float clientPrevAnimFrame;
    public int clientBlendStart;
    private int clientLastAnim = -1;
    private int clientLastStart;

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
        // temporary wandering behaviour until the AoTTG2 AI is in place
        goalSelector.addGoal(5, new net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(6, new net.minecraft.world.entity.ai.goal.LookAtPlayerGoal(this, net.minecraft.world.entity.player.Player.class, 30f));
        goalSelector.addGoal(7, new net.minecraft.world.entity.ai.goal.RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(SIZE, 0);
        entityData.define(LOOKS, 0);
        entityData.define(ANIM, 0);
        entityData.define(ANIM_START, 0);
    }

    // ------------------------------------------------------------------ appearance

    /** Looks packed into one int: body(3 bits) head(2) hair(4) hairColor(5) eye(4) gray(7). */
    public static int packLooks(int body, int head, int hair, int hairColor, int eye, int gray) {
        return body | head << 3 | hair << 5 | hairColor << 9 | eye << 14 | gray << 18;
    }

    public int body() { return entityData.get(LOOKS) & 7; }
    public int headIndex() { return entityData.get(LOOKS) >> 3 & 3; }
    public int head() { return HEADS[headIndex()]; }
    public String hair() { return HAIRS[Math.min(HAIRS.length - 1, entityData.get(LOOKS) >> 5 & 15)]; }
    public int[] hairColor() { return HAIR_COLORS[Math.min(HAIR_COLORS.length - 1, entityData.get(LOOKS) >> 9 & 31)]; }
    public int eye() { return entityData.get(LOOKS) >> 14 & 15; }
    /** Skin brightness 0.7 .. 1.0 (AoTTG2 tints the body gray in this range). */
    public float gray() { return 0.7f + 0.3f * ((entityData.get(LOOKS) >> 18 & 127) / 127f); }

    public int sizeIndex() { return Math.max(0, Math.min(SIZES.length - 1, entityData.get(SIZE))); }
    public float heightBlocks() { return SIZES[sizeIndex()]; }

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

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (SIZE.equals(key)) {
            refreshDimensions();
            setMaxUpStep(heightBlocks() / 4f);
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
        return getBoundingBox().inflate(h * 0.5f, h * 0.2f, h * 0.5f);
    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, reason, data, tag);
        randomizeLooks(level.getRandom());
        setSizeIndex(rollSize(level.getRandom()));
        return result;
    }

    // ------------------------------------------------------------------ animation

    public int anim() { return entityData.get(ANIM); }
    public int animStart() { return entityData.get(ANIM_START); }

    public void playAnim(String name) {
        int a = TitanModelData.get().anim(name);
        if (a != anim()) {
            entityData.set(ANIM, a);
            entityData.set(ANIM_START, tickCount);
        }
    }

    public boolean isLooping(int anim) {
        String n = TitanModelData.get().animNames[anim];
        return n.equals("idle") || n.startsWith("run.") || n.equals("sit_idle");
    }

    /** Current animation frame (fractional) for rendering / hitboxes. Walk/run cycles follow the actual speed. */
    public float animFrame(float partialTick) {
        return (tickCount - animStart() + partialTick) * animSpeed();
    }

    public float animSpeed() {
        String n = TitanModelData.get().animNames[anim()];
        if (n.startsWith("run.")) {
            // one walk cycle (41 frames) should move the titan about 1.1 of its height
            double speed = Math.sqrt(getDeltaMovement().x * getDeltaMovement().x + getDeltaMovement().z * getDeltaMovement().z) * 20.0;
            float cycleBlocks = heightBlocks() * 1.1f;
            float framesPerSecond = (float) (speed / cycleBlocks * 41.0);
            return Math.max(0.3f, Math.min(2.5f, framesPerSecond / 20f));
        }
        return 1f;
    }

    /** Client: remember the previous animation so the renderer can cross-fade. */
    public void updateClientBlend(float partialTick) {
        int a = anim();
        int s = animStart();
        if (clientLastAnim == -1) {
            clientLastAnim = a;
            clientLastStart = s;
            return;
        }
        if (a != clientLastAnim || s != clientLastStart) {
            clientPrevAnim = clientLastAnim;
            clientPrevAnimFrame = (tickCount - clientLastStart + partialTick);
            clientBlendStart = tickCount;
            clientLastAnim = a;
            clientLastStart = s;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            double horiz = getDeltaMovement().horizontalDistanceSqr();
            if (horiz > 1.0E-4) {
                playAnim("run.walk");
            } else {
                playAnim("idle");
            }
        }
    }

    // ------------------------------------------------------------------ save / load

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("TitanSize", sizeIndex());
        tag.putInt("TitanLooks", entityData.get(LOOKS));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(LOOKS, tag.getInt("TitanLooks"));
        entityData.set(SIZE, tag.getInt("TitanSize"));
        refreshDimensions();
        setMaxUpStep(heightBlocks() / 4f);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }
}
