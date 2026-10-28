package com.manbear.ai;

import com.manbear.ManbearConfig;
import com.manbear.data.ManbearData;
import com.manbear.data.PlayerProfile;
import com.manbear.data.Pit;
import com.manbear.entity.CompanionBird;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.ManbearMode;
import com.manbear.entity.Stance;
import com.manbear.registry.ModBlocks;
import com.manbear.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.tags.FluidTags;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * The decision maker. He is always in exactly one mode, but can switch at any moment:
 * stalk -> peek -> fake chase -> real chase and back, with weights shaped by what he has
 * learned about the player (see PlayerProfile).
 */
public class ManbearBrain {
    private enum Lunge { NONE, WINDUP, AIR, RECOVER }

    private final ManbearEntity m;
    private final BlockBreaker breaker = new BlockBreaker();

    private ManbearMode mode = ManbearMode.STALK;
    private int modeTime;
    private int modeDuration = 600;

    private ServerPlayer target;
    private int targetLock;
    private int ticksAlive;
    private int noTargetTicks;
    private int lastChaseEnd;

    // awareness
    private double dist;
    private boolean los;
    private boolean looking;
    private int lookedTicks;

    // learned traits (0..1)
    private PlayerProfile prof;
    private float vig = 0.5F, panic = 0.5F, aggr = 0F, camp = 0F, sprint = 0.3F;

    // movement
    private int pathCd;
    private boolean navFail;
    private int navFailCount;
    private Vec3 lastPos = Vec3.ZERO;
    private int stuckTicks;
    private int stanceLock;
    private int unreachableTicks;

    // stalk
    private int orbitDir = 1;
    private int orbitFlip = 200;
    private double stalkRadius = 18;
    private int freezeTicks;

    // hide / peek
    private CoverFinder.Cover cover;
    private boolean atHide;
    private int waitTicks;
    private int peekPhase;
    private int peekTimer;
    private int peeksLeft;

    // fake chase
    private int fakePhase;
    private double fakeStop;
    private int fakeFreeze;
    private int fakeHitsLanded;
    private int fakeHitsTarget = 2;

    // chase unpredictability: one possible silent pause, rush burst and teleport per encounter
    private boolean chasePauseAbilityArmed;
    private int chasePauseTicks;
    private double chasePauseStartDistance;
    private double chasePauseClosestDistance;
    private int rushBurstTicks;
    private int fakePauseExtensionTicks;
    private boolean chaseTeleportAbilityArmed;

    // persistent den-follow event
    private boolean returningToDen;
    private BlockPos returnDenEntrance;
    private double returnStartDistance;
    private int returnTicks;

    // chase
    private int chaseTicks;
    private int lungeCd;
    private int meleeCd;
    private int pendingMelee;
    private int hitsLanded;
    private Lunge lunge = Lunge.NONE;
    private int lungeTimer;
    private boolean lungeHit;
    private boolean ambushing;

    // traps
    private BlockPos trapSite;
    private int trapPhase;
    private int trapTimer;
    private int trapCooldown = 700;

    // ambience
    private int ambientCd = 200;
    private int coverFailCd;
    private int mimicCd = 300;
    private int leavingTicks;
    private int peekSide;
    private boolean started;
    private float noise;
    private int roarsUsed;
    private int roarCd;
    private int stunTicks;
    private ServerPlayer stunTarget;
    private int breathCd = 100;
    private BlockPos lastHide;
    private int trickCd = 400;
    private final java.util.List<Pending> pending = new java.util.ArrayList<>();

    private record Pending(int at, SoundEvent ev, SoundSource src, double x, double y, double z, float vol, float pitch) { }
    private int snoreCd = 60;
    private int rushTicks;
    private LivingEntity carried;     // the kidnapped pet riding on his back
    private LivingEntity petTarget;
    private int petPhase;
    private int petTimer;
    private BlockPos dropSpot;
    private int manStunTicks;     // HE is stunned (axe hit)
    private int manStunCd;
    private int distractTicks;
    private int distractCd;
    private Entity distractAnimal;
    private float rageF;          // his rage towards the current target, 0..1
    private boolean ducking;
    private int voiceCd = 600;
    private int pathTrigger = 8;
    private double fogDist = -1;
    private boolean caveFleeing;
    private boolean ambushOnly;
    private boolean lethalGrab;     // the intimidation grab: no escape
    private int unstickStep;
    private int lurkSeen;
    // grab
    private ServerPlayer held;
    private float grabProgress;
    private int grabTicks;
    private int grabCd;
    private boolean pounce;
    private boolean lureStarted;
    private boolean lureDone;
    private int lurePhase;
    private int lureTimer;
    private int lureStare;
    private UUID animalId;
    private SoundEvent lureCall = SoundEvents.WOLF_WHINE;
    private boolean lureTrapVariant;
    private BlockPos baitSpot;
    private int lastChaseTick = -100000;
    // pit lure ("he waits behind you; you notice him; he runs off; you follow; you fall")
    private int pitPhase;                 // 0 behind you, 1 running off, 2 waiting beyond the pit
    private Pit pit;
    private BlockPos pitFar;
    private int pitTimer;
    private int pitWait;
    private boolean pitChase;             // you are in the hole: he attacks down into it from the edge
    // kidnapping ("he sneaks up on you, carries you to the nearest cave")
    private static final int KID_SNEAK = 0, KID_BACKOFF = 1, KID_CARRY = 2, KID_FADE = 3, KID_DEN_GUARD = 4, KID_DEN_SLEEP = 5;
    private int kidPhase;
    private int kidSeen;
    private int kidAttempts;
    private int kidTimer;
    private int kidCount;                 // space-bar presses so far
    private int kidPresses = 50;          // presses needed (50-100 depending on rage)
    private int kidMinTicks;              // the escape window: he never gets there before you could have mashed free
    private int kidFadeTimer;
    private int kidStuck;
    private BlockPos kidCave;
    private boolean kidnapToDen;
    private int denGuardWaitTicks;
    private double kidSpeed = 1.6;
    // continuous chase voice
    private int roarLoopCd;
    // hallucination
    private boolean hallSpawned;
    private java.util.UUID doppelId;
    private int hallTimer;
    private int hallGone;
    private boolean hallTreeVariant;
    // evidence: did you ever notice him, and where has he been
    private boolean noticed;
    private int noticeTicks;
    private boolean leaveFromWatch;
    private boolean noEvidence;
    private boolean destroyed;
    private final java.util.List<Vec3> trail = new java.util.ArrayList<>();
    private Vec3 lastTrail;
    private ManbearMode prevMode = ManbearMode.STALK;
    // fake death
    private int chaseHits;                // your hits in this chase
    private int fakeDeathAt = 7;          // ...WEAPON hits after which he plays dead (rolled 5-10 for every chase)
    private boolean hitWithWeapon;        // was the hit being processed made with a weapon?
    private int deadTicks;
    private int deadAway;

    public ManbearBrain(ManbearEntity m) { this.m = m; }

    private RandomSource rnd() { return m.getRandom(); }

    /** real chases only happen on "chaser" spawns, and never in the first few seconds */
    private boolean canChase() {
        // an angry Manbear will chase even if this spawn was not "a chaser", and needs less time to work up to it
        return (m.isChaser() || rageF >= 0.55F)
                && ticksAlive >= (int) (ManbearConfig.GRACE_SECONDS.get() * 20 * (1F - 0.7F * rageF));
    }

    // an angry Manbear is BOLDER: he works closer to you
    /**
     * His time is up, whatever he is doing (chase, grab, sleeping...). He only disappears while nobody can see him;
     * if someone can, he bolts for cover at full speed and is removed the instant he is out of sight.
     */
    private void expire(ServerLevel level) {
        if (!visibleToAnyone(level)) {
            m.discard();
            return;
        }
        if (!m.leaving) {
            caveFleeing = true;
            beginLeaving(level);
        }
    }

    private boolean visibleToAnyone(ServerLevel level) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || !p.isAlive() || p.distanceToSqr(m) > 200.0 * 200.0) continue;
            Vec3 eye = p.getEyePosition();
            Vec3 to = m.getEyePosition().subtract(eye).normalize();
            if (p.getLookAngle().dot(to) < 0.1) continue;      // not looking his way
            if (CoverFinder.lineClear(level, eye, m.getEyePosition(), p)
                    || CoverFinder.lineClear(level, eye, m.position().add(0, 0.5, 0), p)) return true;
        }
        return false;
    }

    private int hideMin() { return (int) (ManbearConfig.STALK_MIN_DISTANCE.get() * (1F - 0.45F * rageF)); }
    private int hideMax() { return Math.max(hideMin() + 6, (int) ((ManbearConfig.STALK_MAX_DISTANCE.get() + 4) * (1F - 0.45F * rageF))); }

    /** being near him while he stalks, hides or peeks enrages him */
    private void closeRage() {
        if (prof == null || !(mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK)) return;
        float add = ManbearConfig.RAGE_CLOSE_PER_SECOND.get().floatValue();
        if (dist < 6) prof.addRage(add * 2F);
        else if (dist < 12) prof.addRage(add);
    }

    public ManbearMode mode() { return mode; }

    public String debugString() {
        return "chaser=" + m.isChaser() + " mode=" + mode + " t=" + modeTime + "/" + modeDuration + " dist=" + (int) dist
                + " looking=" + looking + " vig=" + String.format("%.2f", vig) + " panic=" + String.format("%.2f", panic)
                + " trapCd=" + trapCooldown + " target=" + (target == null ? "none" : target.getGameProfile().getName());
    }

    // ================================================================= main tick
    public void tick(ServerLevel level) {
        ticksAlive++;
        modeTime++;
        if (trapCooldown > 0) trapCooldown--;
        if (lungeCd > 0) lungeCd--;
        if (meleeCd > 0) meleeCd--;
        if (grabCd > 0) grabCd--;
        if (manStunCd > 0) manStunCd--;
        if (distractCd > 0) distractCd--;
        if (stanceLock > 0) stanceLock--;
        if (ambientCd > 0) ambientCd--;
        if (coverFailCd > 0) coverFailCd--;
        if (targetLock > 0) targetLock--;
        if (roarCd > 0) roarCd--;
        if (!started) {
            started = true;
            if (m.isSleeper()) setMode(level, ManbearMode.SLEEP);
            else if (m.isBehind()) setMode(level, ManbearMode.BEHIND);
            else if (m.isPathBlocker()) setMode(level, ManbearMode.PATH);
            else if (m.isFogger()) setMode(level, ManbearMode.FOG);
            else if (m.isCaver()) setMode(level, ManbearMode.CAVE);
            else if (m.isLurker()) setMode(level, ManbearMode.LURKER);
            else if (m.isPetnapper()) setMode(level, ManbearMode.PETNAP);
            else if (m.isFlash()) setMode(level, ManbearMode.FLASH);
            else if (m.isIntimidator()) setMode(level, ManbearMode.INTIMIDATE);
            else if (m.isPitLurer()) setMode(level, ManbearMode.PITLURE);
            else if (m.isKidnapper()) setMode(level, ManbearMode.KIDNAP);
            else if (m.isHallucinator()) setMode(level, ManbearMode.HALLUCINATE);
        }
        tickStun();

        if (mimicCd > 0) mimicCd--;
        // he never lingers: after his lifespan he fades away (after a chase in progress, at most 40 s later)
        if (mode == ManbearMode.FLASH && m.lifeTicks >= 24) { m.discard(); return; }
        if (m.lifeTicks > m.lifespan() && !(mode == ManbearMode.KIDNAP && kidPhase >= KID_CARRY)) expire(level);   // never mid-kidnapping
        if (m.leaving) leavingTicks++;

        resolveTarget(level);
        if (mode == ManbearMode.SLEEP) { tickSleep(level); return; }
        if (target == null) {
            if (returningToDen) {
                m.setFaceTarget(null, 0F);
                tickReturnToDen(level);
                return;
            }
            m.setFaceTarget(null, 0F);
            m.getNavigation().stop();
            m.setClimbing(false);
            if (m.leaving || ++noTargetTicks > 1200) m.discard();
            return;
        }
        noTargetTicks = 0;
        if (ticksAlive % 20 == 1 || prof == null) refreshTraits(level);
        sense();
        if (looking) {
            if (++noticeTicks >= 6) noticed = true;      // you really looked at him
            if (noticeTicks == 6 && isWatch(mode) && !returningToDen && rnd().nextFloat() < ManbearConfig.DEN_RETURN_CHANCE.get()) {
                startReturnToDen(level);
            }
        } else noticeTicks = 0;
        if (ticksAlive % 10 == 0 && m.onGround()) {                                       // remember the way he walked (for footprints)
            Vec3 here = m.position();
            if (lastTrail == null || here.distanceToSqr(lastTrail) > 2.5) {
                trail.add(here);
                lastTrail = here;
                if (trail.size() > 14) trail.remove(0);
            }
        }
        if (ticksAlive % 20 == 0) closeRage();
        if (!lureStarted && m.isLurer() && mode == ManbearMode.STALK) {
            lureStarted = true;
            setMode(level, ManbearMode.LURE);
        }
        m.setFaceTarget(target, mode.isChaseLike() ? 38F : 16F);
        if (mode == ManbearMode.CAVE && !caveFleeing) m.setFaceTarget(null, 0F);   // sits with his back to you
        // running off (pit lure) or carrying you away: he looks where he is going, not at you
        if ((mode == ManbearMode.KIDNAP && kidPhase >= KID_CARRY) || (mode == ManbearMode.PITLURE && pitPhase == 1)) m.setFaceTarget(null, 0F);
        if (mode == ManbearMode.PLAYDEAD) m.setFaceTarget(null, 0F);                     // lies where he fell
        updateStance(level);
        if (returningToDen) { tickReturnToDen(level); return; }
        if (mode == ManbearMode.PLAYDEAD) { tickPlayDead(level); return; }
        if ((mode == ManbearMode.FAKE_CHASE || mode == ManbearMode.CHASE) && chasePauseTicks <= 0) tryStartChasePause(level);
        if (chasePauseTicks > 0) { tickChasePause(level); return; }
        ambient();
        chaseVoice();
        mimicAnimal();
        breathe();
        tickTricks(level);
        voiceMimic();

        if (manStunTicks > 0) {   // axe stun: he can do nothing
            manStunTicks--;
            m.getNavigation().stop();
            m.setClimbing(false);
            lunge = Lunge.NONE;
            pounce = false;
            pendingMelee = 0;
            return;
        }
        switch (mode) {
            case STALK -> tickStalk(level);
            case HIDE -> tickHide(level);
            case PEEK -> tickPeek(level);
            case FAKE_CHASE -> tickFake(level);
            case CHASE, AMBUSH -> tickChase(level);
            case LAY_TRAP -> tickTrap(level);
            case RETREAT -> tickRetreat(level);
            case BEHIND -> tickBehind(level);
            case LURE -> tickLure(level);
            case PATH -> tickPath(level);
            case FOG -> tickFog(level);
            case CAVE -> tickCave(level);
            case GRAB -> tickGrab(level);
            case LURKER -> tickLurker(level);
            case PETNAP -> tickPetnap(level);
            case INTIMIDATE -> tickIntimidate(level);
            case PITLURE -> tickPitLure(level);
            case KIDNAP -> tickKidnap(level);
            case HALLUCINATE -> tickHallucinate(level);
            case FLASH -> { m.getNavigation().stop(); if (m.lifeTicks >= 24) m.discard(); }
            default -> { }
        }

        updateClimb();
        spontaneous(level);
        if (modeTime >= modeDuration) onModeExpired(level);
    }

    // ================================================================= target / senses / traits
    private void resolveTarget(ServerLevel level) {
        if (target != null && !validTarget(target)) target = null;
        if (target == null || (targetLock <= 0 && ticksAlive % 100 == 0)) {
            ServerPlayer best = null;
            double bd = Double.MAX_VALUE;
            for (ServerPlayer p : level.players()) {
                if (!validTarget(p)) continue;
                double d = p.distanceToSqr(m);
                if (p == target) d -= 24 * 24;
                if (d < bd) { bd = d; best = p; }
            }
            target = best;
        }
    }

    private boolean validTarget(ServerPlayer p) {
        return p.isAlive() && !p.isSpectator() && !p.isCreative() && p.level() == m.level() && m.distanceToSqr(p) < 110 * 110;
    }

    private void sense() {
        dist = m.distanceTo(target);
        los = m.hasLineOfSight(target);
        Vec3 look = target.getViewVector(1F);
        Vec3 to = m.getEyePosition().subtract(target.getEyePosition()).normalize();
        looking = los && dist < 70 && look.dot(to) > 0.72;
        lookedTicks = looking ? lookedTicks + 1 : Math.max(0, lookedTicks - 2);
    }

    private void refreshTraits(ServerLevel level) {
        ManbearData data = ManbearData.get(level.getServer());
        prof = data.profile(target.getUUID());
        rageF = prof.rageFactor();
        if (ManbearConfig.ADAPTATION.get()) {
            vig = prof.vigilance; panic = prof.panic; aggr = prof.aggression;
            camp = prof.camping; sprint = prof.sprintiness;
        } else {
            vig = 0.5F; panic = 0.5F; aggr = 0F; camp = 0F; sprint = 0.3F;
        }
        if (ticksAlive % 600 == 1) data.setDirty();
    }

    // ================================================================= mode switching
    private void setMode(ServerLevel level, ManbearMode nm) {
        if (nm == mode && modeTime < 5) return;
        exitMode(level);
        prevMode = mode;
        mode = nm;
        if (!isWatch(nm) && nm != ManbearMode.RETREAT) noticed = true;     // anything but stalking/hiding/peeking: you know about him
        if (nm != ManbearMode.CHASE && nm != ManbearMode.AMBUSH && nm != ManbearMode.GRAB) pitChase = false;
        modeTime = 0;
        m.setSyncedMode(nm);
        navFail = false;
        navFailCount = 0;
        unreachableTicks = 0;
        stuckTicks = 0;
        pathCd = 0;
        m.getNavigation().stop();
        enterMode(level);
    }

    private void exitMode(ServerLevel level) {
        breaker.cancel(level, m);
        m.setPeekSide(0);
        if (ducking) { ducking = false; m.setDucking(false); }
        lunge = Lunge.NONE;
        pounce = false;
        if (mode == ManbearMode.GRAB) { restoreXpBar(); held = null; lethalGrab = false; }
        if (mode == ManbearMode.PETNAP) dropPet(level);
        if (mode == ManbearMode.KIDNAP) { restoreXpBar(); held = null; }
        if (mode == ManbearMode.PITLURE && pit != null) {
            if (!pit.sprung()) PitTraps.removeAndFill(level, pit);   // the lure failed: put the ground back
            pit = null;
        }
        if (mode == ManbearMode.LURE) {
            if (lurePhase >= 2) releaseLureAnimal(level);
            else discardLureAnimal(level);
        }
        if (mode.isChaseLike()) ambushing = false;
    }

    private void enterMode(ServerLevel level) {
        RandomSource r = rnd();
        switch (mode) {
            case STALK -> {
                modeDuration = (int) ((160 + r.nextInt(240)) * (1F - 0.5F * rageF));   // angrier = more restless
                int lo = (int) (ManbearConfig.STALK_MIN_DISTANCE.get() * (1F - 0.45F * rageF));
                int hi = Math.max(lo + 1, (int) (ManbearConfig.STALK_MAX_DISTANCE.get() * (1F - 0.45F * rageF)));
                stalkRadius = Mth.clamp(lo + r.nextInt(hi - lo) - 4 * (1 - vig) + 4 * vig - 3 * camp, lo - 4, hi + 6);
                orbitDir = r.nextBoolean() ? 1 : -1;
                orbitFlip = 120 + r.nextInt(240);
                freezeTicks = 0;
            }
            case HIDE -> {
                modeDuration = 700;
                cover = coverFailCd > 0 ? null : CoverFinder.findCover(level, m, target, hideMin(), hideMax(), lastHide);
                if (cover == null) { coverFailCd = 200; setMode(level, ManbearMode.STALK); return; }
                lastHide = cover.hide();
                atHide = false;
                waitTicks = 80 + r.nextInt(140);
            }
            case PEEK -> {
                modeDuration = 900;
                if (cover == null || cover.peek() == null || cover.duck()) cover = coverFailCd > 0 ? null : CoverFinder.findCover(level, m, target, hideMin(), hideMax());
                if (cover == null || cover.peek() == null || cover.duck()) { coverFailCd = 200; setMode(level, ManbearMode.STALK); return; }
                peekPhase = 0;
                peeksLeft = 1 + r.nextInt(3);
            }
            case FAKE_CHASE -> {
                modeDuration = 420;
                fakePhase = 0;
                fakeStop = 3.5;
                fakeHitsLanded = 0;
                fakePauseExtensionTicks = 0;
                int lo = ManbearConfig.FAKE_CHASE_HITS_MIN.get();
                int hi = Math.max(lo, ManbearConfig.FAKE_CHASE_HITS_MAX.get());
                fakeHitsTarget = lo + r.nextInt(hi - lo + 1);
                pendingMelee = 0;
                meleeCd = 0;
                rushBurstTicks = 0;
                chasePauseTicks = 0;
                chasePauseAbilityArmed = r.nextFloat() < ManbearConfig.CHASE_STARE_ABILITY_CHANCE.get();
                chaseTeleportAbilityArmed = r.nextFloat() < ManbearConfig.CHASE_TELEPORT_ABILITY_CHANCE.get();
                if (r.nextFloat() < 0.55F) m.playBear(ModSounds.ROAR.get(), 3.5F, 0.85F + r.nextFloat() * 0.2F);
            }
            case CHASE, AMBUSH -> {
                float panicScale = 0.8F + 0.6F * panic;
                modeDuration = (int) ((400 + r.nextInt(700)) * panicScale);
                if (mode == ManbearMode.AMBUSH) modeDuration = 700;
                chaseTicks = 0;
                if (prevMode != ManbearMode.CHASE && prevMode != ManbearMode.AMBUSH && prevMode != ManbearMode.GRAB) {
                    // a new chase: a new number of hits he will take before he plays dead
                    int lo = ManbearConfig.FAKE_DEATH_MIN_HITS.get();
                    int hi = Math.max(lo, ManbearConfig.FAKE_DEATH_MAX_HITS.get());
                    chaseHits = 0;
                    fakeDeathAt = lo + r.nextInt(hi - lo + 1);
                }
                if (ticksAlive - lastChaseTick > 600) roarsUsed = 0;   // a new chase only after 30 s of calm
                roarCd = Math.max(roarCd, 100);
                hitsLanded = 0;
                lunge = Lunge.NONE;
                pendingMelee = 0;
                lungeCd = ambushing ? 0 : 30 + r.nextInt(30);
                freezeTicks = 0;
                rushBurstTicks = 0;
                chasePauseTicks = 0;
                chasePauseAbilityArmed = mode == ManbearMode.CHASE && r.nextFloat() < ManbearConfig.CHASE_STARE_ABILITY_CHANCE.get();
                chaseTeleportAbilityArmed = mode == ManbearMode.CHASE && r.nextFloat() < ManbearConfig.CHASE_TELEPORT_ABILITY_CHANCE.get();
                if (prof != null) prof.chases++;
                // unpredictable start: sometimes he roars first, sometimes he just stares for a breath, sometimes silence
                float roll = r.nextFloat();
                if (ambushing || roll < 0.45F) m.playBear(ModSounds.ROAR.get(), 4.5F, 0.8F + r.nextFloat() * 0.2F);
                if (!ambushing && roll > 0.7F) freezeTicks = 10 + r.nextInt(14);
            }
            case LAY_TRAP -> {
                modeDuration = 900;
                trapSite = null;
                if (trapCooldown <= 0 && ManbearConfig.MAX_TRAPS.get() > 0) {
                    trapSite = CoverFinder.findTrapSite(level, target, prof, r);
                }
                if (trapSite == null) { trapCooldown = 300; setMode(level, ManbearMode.STALK); return; }
                trapPhase = 0;
            }
            case LURE -> {
                modeDuration = 1800;
                lurePhase = 0;
                lureTimer = 0;
                lureStare = 0;
                lureTrapVariant = r.nextFloat() < ManbearConfig.LURE_TRAP_CHANCE.get().floatValue();
                cover = coverFailCd > 0 ? null : CoverFinder.findCover(level, m, target, hideMin(), hideMax());
                if (cover == null) { lureDone = true; setMode(level, ManbearMode.STALK); return; }
            }
            case PATH -> {
                modeDuration = 1_000_000;
                pathTrigger = 4 + r.nextInt(9);   // he lunges when you get this close (4-12 blocks)
            }
            case FOG -> {
                modeDuration = 1_000_000;
                fogDist = -1;
            }
            case LURKER -> {
                modeDuration = 1_000_000;
                lurkSeen = 0;
            }
            case PETNAP -> {
                modeDuration = 1_000_000;
                lurkSeen = 0;
                petPhase = 0;
                petTimer = 0;
                petTarget = null;
                dropSpot = null;
            }
            case FLASH -> modeDuration = 1_000_000;
            case INTIMIDATE -> modeDuration = 1_000_000;
            case PITLURE -> {
                modeDuration = 1_000_000;
                pitPhase = 0;
                pit = null;
                pitTimer = 0;
                pitWait = 0;
            }
            case KIDNAP -> {
                modeDuration = 1_000_000;
                kidPhase = KID_SNEAK;
                kidSeen = 0;
                kidTimer = 0;
                kidStuck = 0;
                kidAttempts++;
                // A generated den is a valid kidnapping destination even when there is no natural cave nearby.
                ManbearData denData = ManbearData.get(level.getServer());
                boolean hasDenDestination = denData.hasDen()
                        && denData.denDimension().equals(level.dimension().location().toString());
                if (!hasDenDestination && CaveFinder.find(level, m.blockPosition(), ManbearConfig.KIDNAP_SEARCH_RADIUS.get(), 1, r).isEmpty()) {
                    kidAttempts = 99;
                    setMode(level, ManbearMode.STALK);
                    return;
                }
            }
            case GRAB -> {
                modeDuration = 20 * 12;
                m.getNavigation().stop();
                m.setClimbing(false);
            }
            case CAVE -> {
                modeDuration = 1_000_000;
                caveFleeing = false;
                m.setStance(Stance.SIT);
            }
            case BEHIND -> modeDuration = 1_000_000;
            case SLEEP -> {
                modeDuration = 1_000_000;
                m.setStance(Stance.SLEEP);
                m.setFaceTarget(null, 0F);
            }
            case HALLUCINATE -> {
                modeDuration = 1_000_000;
                hallSpawned = false;
                hallTimer = 0;
                hallGone = 0;
                hallTreeVariant = false;
                doppelId = null;
            }
            case PLAYDEAD -> {
                modeDuration = 1_000_000;
                deadTicks = 0;
                deadAway = 0;
                m.setStance(Stance.SLEEP);          // lies down exactly like a sleeper
                m.setClimbing(false);
            }
            case RETREAT -> modeDuration = m.leaving ? 1_000_000 : 100 + r.nextInt(120);
        }
    }

    private void onModeExpired(ServerLevel level) {
        if (m.leaving) { modeTime = 0; return; }
        switch (mode) {
            case CHASE, AMBUSH -> endChase(level);
            case FAKE_CHASE -> vanishFast(level);
            case GRAB -> endGrab(level, false);
            case RETREAT -> setMode(level, rnd().nextFloat() < 0.55F ? ManbearMode.HIDE : ManbearMode.STALK);
            default -> setMode(level, pickNextMode(false));
        }
    }

    private ManbearMode pickNextMode(boolean spontaneous) {
        boolean canChase = canChase();
        double tension = Mth.clamp(0.8 + (ticksAlive - lastChaseEnd) / 2400.0, 0.8, 2.0);
        double[] w = new double[ManbearMode.values().length];
        w[ManbearMode.STALK.ordinal()] = spontaneous ? 0 : 1.0;
        w[ManbearMode.HIDE.ordinal()] = coverFailCd > 0 ? 0 : 2.8 + 2.2 * vig;
        w[ManbearMode.PEEK.ordinal()] = coverFailCd > 0 ? 0 : 1.6 + 1.6 * vig;
        w[ManbearMode.FAKE_CHASE.ordinal()] = ((m.isFaker() || rageF > 0.4F) && ticksAlive > 200) ? 0.7 + 1.8 * vig * (1 - aggr) + 0.8 * panic : 0;
        w[ManbearMode.CHASE.ordinal()] = canChase ? (0.5 + 2.2 * panic + 1.2 * (1 - vig) + 1.2 * camp + 0.8 * sprint) * tension * (1.0 + 3.0 * rageF) : 0;
        w[ManbearMode.LAY_TRAP.ordinal()] = ((m.isTrapper() || rageF > 0.5F) && trapCooldown <= 0 && ManbearConfig.MAX_TRAPS.get() > 0) ? 0.6 + 2.0 * aggr + 1.0 * sprint + 1.0 * camp : 0;
        w[ManbearMode.KIDNAP.ordinal()] = (m.isKidnapper() && kidAttempts < 2 && ticksAlive > 200 && !m.leaving) ? 1.2 + 2.0 * rageF : 0;
        w[ManbearMode.AMBUSH.ordinal()] = 0;
        w[ManbearMode.RETREAT.ordinal()] = 0;
        w[ManbearMode.SLEEP.ordinal()] = 0;
        w[mode.ordinal()] = 0;
        double total = 0;
        for (double d : w) total += d;
        if (total <= 0) return ManbearMode.STALK;
        double pick = rnd().nextDouble() * total;
        for (int i = 0; i < w.length; i++) {
            pick -= w[i];
            if (pick <= 0) return ManbearMode.byId(i);
        }
        return ManbearMode.STALK;
    }

    /** once a second: random, unpredictable switches */
    private void spontaneous(ServerLevel level) {
        if (m.leaving || ticksAlive % 20 != 0) return;
        boolean calm = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK || mode == ManbearMode.LAY_TRAP;
        if (!calm) return;
        boolean canChase = canChase();

        // you wandered too close while he was lurking: pounce
        if (dist < 8.5 + 6.0 * rageF && modeTime > 60 && canChase && rnd().nextFloat() < 0.22F + 0.5F * rageF) {
            setMode(level, ManbearMode.CHASE);
            return;
        }
        // stared at for too long: duck out of sight
        if (mode == ManbearMode.STALK && lookedTicks > 40 && vig > 0.25F && rageF < 0.6F && coverFailCd <= 0) {
            setMode(level, ManbearMode.HIDE);
            return;
        }
        // out in the open he gets nervous quickly: duck behind the next tree
        if (mode == ManbearMode.STALK && coverFailCd <= 0 && rnd().nextFloat() < 0.10F) {
            setMode(level, ManbearMode.HIDE);
            return;
        }
        float p = (0.04F + 0.05F * (1F - vig)) * (1F + 2F * rageF);   // angry = erratic: switches behaviour far more often
        if (rnd().nextFloat() < p) setMode(level, pickNextMode(true));
    }

    // ================================================================= events
    /**
     * Every hit on him comes through here. Only WEAPON hits count toward the fake death: a sword, axe or trident in the
     * hand (or any item whose attack damage bonus is 3 or more, modded weapons included), or an arrow / thrown trident.
     * Fists, tools, snowballs and eggs do not.
     */
    public void onDamaged(ServerLevel level, DamageSource source) {
        Entity attacker = source.getEntity();
        Entity direct = source.getDirectEntity();
        hitWithWeapon = direct instanceof net.minecraft.world.entity.projectile.AbstractArrow
                || (attacker instanceof ServerPlayer p && direct == attacker && isWeapon(p.getMainHandItem()));
        if (direct instanceof net.minecraft.world.entity.projectile.Projectile && attacker instanceof ServerPlayer thrower) {
            onProjectile(level, thrower);       // arrows, snowballs, eggs, tridents...
        } else {
            onHurt(level, attacker);
        }
        hitWithWeapon = false;
    }

    private static boolean isWeapon(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) return false;
        net.minecraft.world.item.Item item = stack.getItem();
        if (item instanceof net.minecraft.world.item.SwordItem || item instanceof AxeItem || item instanceof net.minecraft.world.item.TridentItem) return true;
        double bonus = 0;       // modded weapons: a real attack damage bonus (swords 3-7, axes 6-9; pickaxes 1, shovels 1.5, hoes 0)
        for (net.minecraft.world.entity.ai.attributes.AttributeModifier mod
                : stack.getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
            if (mod.getOperation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION) bonus += mod.getAmount();
        }
        return bonus >= 3.0;
    }

    public void onHurt(ServerLevel level, Entity attacker) {
        if (!(attacker instanceof ServerPlayer sp)) return;
        ManbearData data = ManbearData.get(level.getServer());
        data.profile(sp.getUUID()).onHitManbear();
        data.setDirty();
        target = sp;
        targetLock = 400;
        noticed = true;
        if (mode == ManbearMode.SLEEP) { wake(level); return; }
        if (ManbearConfig.FAKE_DEATH.get() && hitWithWeapon && mode.isChaseLike() && !ambushOnly && !m.leaving && ++chaseHits >= fakeDeathAt) {
            startPlayDead(level, sp);       // you hurt him enough: he drops like a stone
            return;
        }
        if (manStunCd == 0 && !m.leaving && (mode == ManbearMode.CHASE || mode == ManbearMode.AMBUSH || (mode == ManbearMode.GRAB && !lethalGrab)
                || (mode == ManbearMode.KIDNAP && kidPhase == KID_CARRY))
                && sp.getMainHandItem().getItem() instanceof AxeItem axe && axe.getTier().getLevel() >= 2) {
            stunHim(level, sp);       // iron, diamond or netherite axe
            return;
        }
        if (mode == ManbearMode.GRAB) return;   // too busy holding you
        if (mode == ManbearMode.KIDNAP && kidPhase >= KID_CARRY) return;
        if (mode == ManbearMode.CAVE) { caveFleeing = true; beginLeaving(level); return; }
        if (mode == ManbearMode.FOG) { beginLeaving(level); return; }
        if (mode == ManbearMode.PATH) { pounceChase(level, false, 8.0F); return; }
        if (m.leaving) return;
        if (m.getHealth() < m.getMaxHealth() * 0.3F) { beginLeaving(level); return; }
        if (!mode.isChaseLike()) {
            float r = rnd().nextFloat();
            if (r < 0.35F) setMode(level, ManbearMode.CHASE);
            else if (r < 0.85F) setMode(level, ManbearMode.RETREAT);
            else if (trapCooldown <= 0) setMode(level, ManbearMode.LAY_TRAP);
        }
    }

    public void beginLeaving(ServerLevel level) {
        returningToDen = false;
        returnDenEntrance = null;
        chasePauseTicks = 0;
        rushBurstTicks = 0;
        if (!m.leaving) leaveFromWatch = isWatch(mode);
        m.leaving = true;
        setMode(level, ManbearMode.RETREAT);
        modeDuration = 1_000_000;
    }

    /** a trap he laid has just been sprung */
    public void ambush(ServerPlayer p, BlockPos pos) {
        if (m.leaving || !(m.level() instanceof ServerLevel level)) return;
        if (mode == ManbearMode.KIDNAP && kidPhase >= KID_CARRY) return;
        target = p;
        targetLock = 800;
        ambushing = true;
        ambushOnly = true;      // a trap ambush is a single lunge, not a chase
        setMode(level, ManbearMode.AMBUSH);
        refreshTraits(level);
    }

    public void forceMode(ServerLevel level, ManbearMode mm) {
        ambushing = false;
        modeTime = 100;
        setMode(level, mm);
    }

    // ================================================================= movement helpers
    private boolean canFit(ServerLevel level, Stance s) {
        return m.fitsIgnoringLeaves(s.dims.makeBoundingBox(m.position()));   // leaves do not count: he walks through them
    }

    /** a point about 1.5 blocks along the way he is going (next path node, else toward you) */
    private Vec3 aheadPoint() {
        Vec3 dir = null;
        Path p = m.getNavigation().getPath();
        if (p != null && !p.isDone()) {
            dir = Vec3.atBottomCenterOf(p.getNextNodePos()).subtract(m.position());
        } else if (target != null) {
            dir = target.position().subtract(m.position());
        }
        if (dir == null) return null;
        dir = new Vec3(dir.x, 0, dir.z);
        if (dir.lengthSqr() < 1.0E-4) return null;
        return m.position().add(dir.normalize().scale(1.5));
    }

    /** deep enough water that he swims instead of wading */
    private boolean swimming() {
        return m.isInWater() && m.getFluidHeight(FluidTags.WATER) > 1.2D;
    }

    /** any real water (rivers are often only 1 block deep) */
    private boolean inWater() {
        return m.isInWater() && m.getFluidHeight(FluidTags.WATER) > 0.4D;
    }

    /** not lava / not a long drop of lava straight ahead */
    private boolean safeToCharge(ServerLevel level) {
        Vec3 d = target.position().subtract(m.position());
        Vec3 h = new Vec3(d.x, 0, d.z);
        if (h.lengthSqr() < 1.0E-4) return false;
        h = h.normalize();
        for (int i = 1; i <= 3; i++) {
            BlockPos ahead = BlockPos.containing(m.getX() + h.x * i, m.getY(), m.getZ() + h.z * i);
            if (level.getFluidState(ahead).is(FluidTags.LAVA) || level.getFluidState(ahead.below()).is(FluidTags.LAVA)) return false;
        }
        return true;
    }

    private void updateStance(ServerLevel level) {
        if (ticksAlive % 3 != 0) return;
        Stance cur = m.getStance();
        if (mode == ManbearMode.PLAYDEAD) {
            if (cur != Stance.SLEEP) m.setStance(Stance.SLEEP);
            return;
        }
        if (swimming()) {
            if (cur != Stance.SWIM) m.setStance(Stance.SWIM);
            return;
        }
        if (mode == ManbearMode.CAVE && !caveFleeing) {
            if (cur != Stance.SIT) m.setStance(Stance.SIT);
            return;
        }
        if (ducking && mode == ManbearMode.HIDE) {
            if (cur != Stance.DUCK) m.setStance(Stance.DUCK);
            return;
        }
        Stance want;
        if (mode.isChaseLike() || mode == ManbearMode.FAKE_CHASE || mode == ManbearMode.BEHIND
                || mode == ManbearMode.PATH || mode == ManbearMode.FOG || mode == ManbearMode.FLASH
                || (mode == ManbearMode.PETNAP && petPhase >= 1) || mode == ManbearMode.INTIMIDATE || (mode == ManbearMode.RETREAT && m.leaving)
                || (mode == ManbearMode.LURE && lurePhase >= 3) || mode == ManbearMode.PITLURE || mode == ManbearMode.HALLUCINATE
                || (mode == ManbearMode.KIDNAP && kidPhase >= KID_CARRY)) want = Stance.STAND;
        else want = dist < 60 ? Stance.CROUCH : Stance.STAND;
        if (stanceLock > 0 && want.ordinal() < cur.ordinal() && cur != Stance.SWIM) want = cur;
        // shrink to whatever fits here AND a step ahead: low doorways, tunnels, branches
        // Crawl before ducking: if a tunnel is too short for a standing/crouched body, he drops to the crawl animation.
        Stance[] ladder = {Stance.STAND, Stance.CROUCH, Stance.CRAWL, Stance.DUCK};
        int idx = 0;
        while (idx < ladder.length - 1 && ladder[idx] != want) idx++;
        Vec3 ahead = aheadPoint();
        while (idx < ladder.length - 1 && (!canFit(level, ladder[idx])
                || (ahead != null && !m.fitsIgnoringLeaves(ladder[idx].dims.makeBoundingBox(ahead))))) idx++;
        want = ladder[idx];
        if (want != cur) m.setStance(want);
    }

    /** Paths to the goal; if the current body is too big for the route, crouches or crawls to fit. */
    private boolean moveToSmart(ServerLevel level, double x, double y, double z, double speed) {
        PathNavigation nav = m.getNavigation();
        Path best = nav.createPath(x, y, z, 1);
        if (best != null && best.canReach()) {
            nav.moveTo(best, speed);
            return true;
        }
        Stance cur = m.getStance();
        for (Stance s : Stance.values()) {
            if (s.ordinal() >= Stance.SLEEP.ordinal() || s.ordinal() <= cur.ordinal() || !canFit(level, s)) continue;
            m.setStance(s);
            Path p = nav.createPath(x, y, z, 1);
            if (p != null && p.canReach()) {
                nav.moveTo(p, speed);
                stanceLock = 60;
                return true;
            }
        }
        m.setStance(cur);
        if (best != null) nav.moveTo(best, speed);
        return false;
    }

    /** In deep water ground pathing is useless, so he just swims straight at the goal. */
    private void swimToward(double x, double y, double z, double speed) {
        Vec3 to = new Vec3(x - m.getX(), y - m.getY(), z - m.getZ());
        double len = to.length();
        if (len < 0.4) return;
        Vec3 dir = to.scale(1.0 / len);
        if (!swimming()) {   // wading in a shallow river: push along the bottom, do not float up
            dir = new Vec3(dir.x, Mth.clamp(dir.y, -0.2, 0.35), dir.z);
            dir = dir.normalize();
        }
        double perTick = m.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) * speed * 2.1 * ManbearConfig.SWIM_SPEED_FACTOR.get();
        Vec3 v = m.getDeltaMovement();
        m.impulse(v.scale(0.4).add(dir.scale(perTick * 0.6)));
    }

    private void navTo(ServerLevel level, double x, double y, double z, double speed, int every) {
        if (inWater()) {
            m.getNavigation().stop();
            navFail = false;
            swimToward(x, y, z, speed);
            return;
        }
        if (pathCd > 0 && !m.getNavigation().isDone()) {
            pathCd--;
            m.getNavigation().setSpeedModifier(speed);
            return;
        }
        pathCd = every;
        navFail = !moveToSmart(level, x, y, z, speed);
        navFailCount = navFail ? navFailCount + 1 : 0;
    }

    private boolean reached(BlockPos p, double sqr) {
        return m.position().distanceToSqr(Vec3.atBottomCenterOf(p)) < sqr;
    }

    /** the player's current top speed as a speed modifier for us (1.0 = walk, 1.3 = sprint) */
    private double refSpeedMod() {
        double base = m.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
        double ps = target.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (!target.isSprinting()) ps *= 1.3D;
        return Mth.clamp(ps / base, 1.0D, 1.9D);
    }

    /** Modifier corresponding to the player's sprint pace, regardless of whether they are sprinting right now. */
    private double sprintSpeedMod() {
        double base = m.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
        double playerSprintSpeed = target.getAttributeValue(Attributes.MOVEMENT_SPEED) * 1.3D;
        return Mth.clamp(playerSprintSpeed / base, 1.0D, 3.0D);
    }

    private void updateClimb() {
        boolean chasing = mode.isChaseLike() || mode == ManbearMode.FAKE_CHASE;
        boolean want = m.horizontalCollision
                && ((chasing && (target.getY() > m.getY() + 0.8 || stuckTicks > 8)) || target.getY() > m.getY() + 2.5);
        m.setClimbing(want && lunge == Lunge.NONE);
    }

    // ================================================================= STALK
    private void tickStalk(ServerLevel level) {
        if (--orbitFlip <= 0) {
            orbitDir = -orbitDir;
            orbitFlip = 120 + rnd().nextInt(240);
        }
        // freeze like a statue while you stare at him
        if (looking && dist < 60 && rageF < 0.6F) freezeTicks = Math.max(freezeTicks, 10);   // a furious one no longer freezes when watched
        if (freezeTicks > 0) {
            freezeTicks--;
            m.getNavigation().stop();
            return;
        }
        Vec3 p = target.position();
        Vec3 me = m.position();
        Vec3 r = new Vec3(me.x - p.x, 0, me.z - p.z);
        double rl = r.length();
        if (rl < 0.01) { r = new Vec3(1, 0, 0); rl = 1; }
        Vec3 u = r.scale(1.0 / rl);
        Vec3 t = new Vec3(-u.z, 0, u.x).scale(orbitDir);
        double radial = Mth.clamp(stalkRadius - rl, -6, 6);
        Vec3 goal = p.add(u.scale(rl + radial)).add(t.scale(4.0));
        BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, target.getBlockY(), 3);
        if (g == null) {
            orbitDir = -orbitDir;
            if (++navFailCount > 3 && coverFailCd <= 0) setMode(level, ManbearMode.HIDE);
            return;
        }
        boolean crouched = m.getStance() != Stance.STAND;
        double speed = crouched ? 0.62 : 0.85;
        if (!looking && rl > stalkRadius + 3) speed = 0.95; // closes in while you look away
        navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, speed, 15);
    }

    // ================================================================= HIDE
    private void tickHide(ServerLevel level) {
        if (cover == null) { setMode(level, ManbearMode.STALK); return; }
        if (!atHide) {
            if (reached(cover.hide(), 1.6)) {
                atHide = true;
                m.getNavigation().stop();
                ducking = cover.duck();            // low wall or bush: squat down (and his eyes go dark)
                m.setDucking(ducking);
            } else {
                Vec3 h = Vec3.atBottomCenterOf(cover.hide());
                navTo(level, h.x, h.y, h.z, looking ? 1.15 : 0.9, 10);
                // inside a bush: walk straight in, he pushes through leaves
                if (cover.inside() && (navFail || m.distanceToSqr(h.x, h.y, h.z) < 36)) {
                    m.getMoveControl().setWantedPosition(h.x, h.y, h.z, 0.9);
                    navFailCount = 0;
                }
                if (navFailCount > 4) { cover = null; coverFailCd = 200; setMode(level, ManbearMode.STALK); }
                return;
            }
        }
        m.getNavigation().stop();
        if (--waitTicks <= 0) {
            float pick = rnd().nextFloat();
            if (cover.peek() != null && !cover.duck() && pick < 0.55F) setMode(level, ManbearMode.PEEK);
            else if (pick < 0.88F) setMode(level, ManbearMode.HIDE);   // hop to a different tree
            else setMode(level, ManbearMode.STALK);
        }
    }

    // ================================================================= PEEK
    /** +1 = his left, -1 = his right, depending on which side the open view is */
    private int openSide() {
        BlockPos peek = cover.peek();
        if (peek == null) return rnd().nextBoolean() ? 1 : -1;
        double dx = peek.getX() - cover.hide().getX();
        double dz = peek.getZ() - cover.hide().getZ();
        float yaw = m.getYRot() * ((float) Math.PI / 180F);
        double left = dx * Mth.cos(yaw) + dz * Mth.sin(yaw);
        return left >= 0 ? 1 : -1;
    }

    private void tickPeek(ServerLevel level) {
        if (cover == null || cover.peek() == null || cover.duck()) { setMode(level, ManbearMode.STALK); return; }
        Vec3 h = Vec3.atBottomCenterOf(cover.hide());
        switch (peekPhase) {
            case 0 -> { // get behind the tree
                if (reached(cover.hide(), 1.6)) { peekPhase = 1; m.getNavigation().stop(); peekTimer = 20 + rnd().nextInt(30); }
                else navTo(level, h.x, h.y, h.z, 0.95, 10);
            }
            case 1 -> { // stand hidden for a beat
                m.getNavigation().stop();
                if (--peekTimer <= 0) {
                    peekSide = openSide() * cover.lean();
                    m.setPeekSide(peekSide);   // peek_left / peek_right
                    peekPhase = 2;
                    // he holds the lean for a good while, then simply moves on (leaving PEEK clears the pose)
                    peekTimer = Math.max(120, (int) (200 + rnd().nextInt(160) - 100 * vig));
                }
            }
            default -> { // leaning out, staring, motionless
                m.getNavigation().stop();
                m.setPeekSide(peekSide);
                if (--peekTimer <= 0) {
                    setMode(level, rnd().nextFloat() < 0.78F ? ManbearMode.HIDE : ManbearMode.STALK);
                }
            }
        }
        if (navFailCount > 5) { cover = null; coverFailCd = 200; setMode(level, ManbearMode.STALK); }
    }

    // ================================================================= FAKE CHASE
    /** Fake chases now land a small number of real hits before he turns and leaves. */
    private void tickFake(ServerLevel level) {
        if (fakePhase == 1) {
            m.getNavigation().stop();
            if (--fakeFreeze <= 0) vanishFast(level);
            return;
        }

        if (pendingMelee > 0 && --pendingMelee == 0) {
            if (m.distanceTo(target) < 5.3 && m.doHurtTarget(target)) {
                fakeHitsLanded++;
                if (fakeHitsLanded >= fakeHitsTarget) {
                    fakePhase = 1;
                    fakeFreeze = 24 + rnd().nextInt(25);
                    m.getNavigation().stop();
                    return;
                }
            }
        }

        if (tryChaseTeleport(level)) return;
        if (breaker.active()) {
            m.getNavigation().stop();
            if (!breaker.tick(level, m)) { stuckTicks = 0; pathCd = 0; }
            return;
        }

        if (dist < 4.0 && meleeCd == 0 && pendingMelee == 0) {
            m.triggerAttack(rnd().nextInt(3));
            meleeCd = 22;
            pendingMelee = 5;
            m.getNavigation().stop();
            return;
        }

        double speed = refSpeedMod() * ManbearConfig.FAKE_SPEED_MULT.get();
        if (rushBurstTicks > 0) {
            rushBurstTicks--;
            speed = sprintSpeedMod() * ManbearConfig.CHASE_BURST_SPEED_MULT.get();
        }
        navTo(level, target.getX(), target.getY(), target.getZ(), speed, 4);
        tunnel(level, speed);
        // If the player completely outruns the encounter, he gives up rather than stretching a fake chase forever.
        if (modeTime > 400 + fakePauseExtensionTicks && fakeHitsLanded == 0) vanishFast(level);
    }

    /** Arm the sudden silent pause only once per chase, on a second boundary for readable timing. */
    private void tryStartChasePause(ServerLevel level) {
        if (!chasePauseAbilityArmed || ticksAlive % 20 != 0 || modeTime < 30 || dist < 8.0 || dist > 48.0
                || !m.onGround() || breaker.active() || lunge != Lunge.NONE || pendingMelee > 0) return;
        if (mode == ManbearMode.FAKE_CHASE && fakePhase != 0) return;
        if (rnd().nextFloat() >= 0.12F) return;
        int min = ManbearConfig.CHASE_STARE_MIN_SECONDS.get();
        int max = Math.max(min, ManbearConfig.CHASE_STARE_MAX_SECONDS.get());
        chasePauseTicks = (min + rnd().nextInt(max - min + 1)) * 20;
        chasePauseStartDistance = dist;
        chasePauseClosestDistance = dist;
        // A pause is a separate beat, not time taken away from the chase itself.
        modeDuration += chasePauseTicks;
        if (mode == ManbearMode.FAKE_CHASE) fakePauseExtensionTicks += chasePauseTicks;
        chasePauseAbilityArmed = false;
        m.getNavigation().stop();
        m.setDeltaMovement(Vec3.ZERO);
    }

    /** No footsteps, growls, mimicry or movement during this stare. If he sees you close the gap, he rushes. */
    private void tickChasePause(ServerLevel level) {
        m.getNavigation().stop();
        m.setClimbing(false);
        m.setDeltaMovement(Vec3.ZERO);
        if (target != null) {
            m.setFaceTarget(target, 45F);
            chasePauseClosestDistance = Math.min(chasePauseClosestDistance, m.distanceTo(target));
        }
        chasePauseTicks--;
        if (chasePauseTicks <= 0) {
            // Remember whether the player ever stepped closer during the stare, even if they backed away again.
            if (target != null && chasePauseClosestDistance < chasePauseStartDistance - 0.5) {
                rushBurstTicks = ManbearConfig.CHASE_BURST_SECONDS.get() * 20;
            }
        }
    }

    /** A single teleport during some chases places him ahead of a sprinting player, often between them and home. */
    private boolean tryChaseTeleport(ServerLevel level) {
        if (!chaseTeleportAbilityArmed || target == null || dist < 10.0 || dist > 48.0 || ticksAlive % 20 != 0) return false;
        if (!target.isSprinting() && (prof == null || prof.velocity().x * prof.velocity().x + prof.velocity().z * prof.velocity().z < 0.035)) return false;
        if (rnd().nextFloat() >= 0.12F) return false;

        Vec3 motion = prof != null ? prof.velocity() : target.getDeltaMovement();
        Vec3 dir = new Vec3(motion.x, 0, motion.z);
        if (dir.lengthSqr() < 1.0E-4) {
            Vec3 look = target.getLookAngle();
            dir = new Vec3(look.x, 0, look.z);
        }
        if (dir.lengthSqr() < 1.0E-4) return false;
        dir = dir.normalize();

        // If his learned base lies along the player's movement, ambush the route back to safety.
        if (prof != null && prof.base != null) {
            Vec3 toBase = new Vec3(prof.base.getX() + 0.5 - target.getX(), 0, prof.base.getZ() + 0.5 - target.getZ());
            if (toBase.lengthSqr() > 100.0 && toBase.lengthSqr() < 200.0 * 200.0) {
                Vec3 baseDir = toBase.normalize();
                if (baseDir.dot(dir) > 0.50) dir = baseDir;
            }
        }
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        for (int attempt = 0; attempt < 8; attempt++) {
            double ahead = 8.0 + rnd().nextInt(8);
            double sidestep = rnd().nextInt(7) - 3;
            double x = target.getX() + dir.x * ahead + side.x * sidestep;
            double z = target.getZ() + dir.z * ahead + side.z * sidestep;
            BlockPos ground = CoverFinder.groundNear(level, x, z, target.getBlockY(), 6);
            if (ground == null || !level.getFluidState(ground).isEmpty()) continue;
            double d2 = target.distanceToSqr(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
            if (d2 < 64.0 || d2 > 34.0 * 34.0) continue;
            AABB box = m.getDimensions(m.getPose()).makeBoundingBox(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
            if (!m.fitsIgnoringLeaves(box)) continue;
            m.getNavigation().stop();
            m.setDeltaMovement(Vec3.ZERO);
            m.setPos(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
            m.setFaceTarget(target, 45F);
            chaseTeleportAbilityArmed = false;
            return true;
        }
        return false;
    }

    private boolean startReturnToDen(ServerLevel level) {
        if (m.leaving || target == null) return false;
        ManbearData data = ManbearData.get(level.getServer());
        if (!data.hasDen() || !data.denDimension().equals(level.dimension().location().toString())) return false;
        BlockPos entrance = data.denEntrance();
        if (entrance == null || !level.hasChunkAt(entrance)) return false;
        returningToDen = true;
        returnDenEntrance = entrance.immutable();
        returnStartDistance = dist;
        returnTicks = 0;
        setMode(level, ManbearMode.RETREAT);
        return true;
    }

    /** He walks into his den. A player who closes the gap noisily reveals that they are following. */
    private void tickReturnToDen(ServerLevel level) {
        if (!returningToDen || returnDenEntrance == null) return;
        ManbearData data = ManbearData.get(level.getServer());
        if (!data.hasDen() || !data.denDimension().equals(level.dimension().location().toString())) {
            returningToDen = false;
            returnDenEntrance = null;
            if (!m.leaving) setMode(level, ManbearMode.STALK);
            return;
        }
        returnTicks++;
        m.setClimbing(false);
        m.setFaceTarget(null, 0F);
        m.getNavigation().setSpeedModifier(1.0);

        if (target != null) {
            double currentDist = m.distanceTo(target);
            boolean closedGap = currentDist < returnStartDistance - 2.5;
            boolean runningAfter = target.isSprinting() && currentDist < 34.0;
            boolean tooClose = currentDist < 10.0;
            boolean noticedFollowing = target.isShiftKeyDown() && looking && currentDist < 18.0;
            if (tooClose || runningAfter || (closedGap && !target.isShiftKeyDown() && currentDist < 30.0) || noticedFollowing) {
                returningToDen = false;
                returnDenEntrance = null;
                targetLock = 800;
                setMode(level, ManbearMode.CHASE);
                return;
            }
        }

        Vec3 entrance = Vec3.atBottomCenterOf(returnDenEntrance);
        if (m.position().distanceToSqr(entrance) < 9.0) {
            // He reached the mouth of the den and vanishes inside. His den remains in the world.
            returningToDen = false;
            m.discard();
            return;
        }
        navTo(level, returnDenEntrance.getX() + 0.5, returnDenEntrance.getY(), returnDenEntrance.getZ() + 0.5, 1.05, 8);
        if (navFail || returnTicks % 80 == 0) m.getMoveControl().setWantedPosition(
                returnDenEntrance.getX() + 0.5, returnDenEntrance.getY(), returnDenEntrance.getZ() + 0.5, 1.05);
        if (returnTicks > 2400) {
            returningToDen = false;
            returnDenEntrance = null;
            setMode(level, ManbearMode.STALK);
        }
    }

    /** he bolts away from you and despawns the moment he is out of sight */
    private void vanishFast(ServerLevel level) {
        caveFleeing = true;
        beginLeaving(level);
    }

    /** wedged on something: try, in turn, a jump, a step left, a step right, a fresh path, backing off and running at it again */
    private void unstick(ServerLevel level, double speed) {
        unstickStep++;
        Vec3 to = target.position().subtract(m.position());
        Vec3 h = new Vec3(to.x, 0, to.z);
        h = h.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : h.normalize();
        Vec3 side = new Vec3(-h.z, 0, h.x);
        switch (unstickStep % 5) {
            case 0 -> m.getJumpControl().jump();
            case 1 -> m.getMoveControl().setWantedPosition(m.getX() + side.x * 3, m.getY(), m.getZ() + side.z * 3, speed);
            case 2 -> m.getMoveControl().setWantedPosition(m.getX() - side.x * 3, m.getY(), m.getZ() - side.z * 3, speed);
            case 3 -> { m.getNavigation().stop(); pathCd = 0; }
            default -> m.getMoveControl().setWantedPosition(m.getX() - h.x * 2, m.getY(), m.getZ() - h.z * 2, speed);
        }
        stuckTicks = 5;   // look again very soon
    }

    /** stuck or no path: tear through whatever is in the way (or wade the river) straight toward you */
    private void tunnel(ServerLevel level, double speed) {
        Vec3 cur = m.position();
        if (cur.distanceToSqr(lastPos) < 0.0016) stuckTicks++; else stuckTicks = 0;
        lastPos = cur;
        if ((navFail || stuckTicks > 12) && dist > 2.4) {
            BlockPos b = BlockBreaker.canGrief(level) ? breaker.findAlongRay(level, m, target) : null;
            if (b != null) {
                if (BlockBreaker.inReach(m, b)) {
                    breaker.start(level, b);
                    m.getNavigation().stop();
                    stuckTicks = 0;
                } else {
                    m.getMoveControl().setWantedPosition(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, speed);
                }
            } else if (stuckTicks > 10) {
                unstick(level, speed);
            } else if (safeToCharge(level)) {
                m.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), speed);
            }
        }
    }

    // ================================================================= CHASE
    private void tickChase(ServerLevel level) {
        chaseTicks++;
        lastChaseTick = ticksAlive;
        if (ambushOnly && chaseTicks > 500) { vanish(level); return; }   // never got him
        if (ticksAlive % 20 == 0) samplePanic();

        if (pendingMelee > 0 && --pendingMelee == 0) {
            if ((m.distanceTo(target) < 5.3 || (pitChase && reachDist() < 4.8)) && m.doHurtTarget(target)) {
                hitsLanded++;
                if (ambushOnly) { vanish(level); return; }
            }
        }
        if (freezeTicks > 0) {
            freezeTicks--;
            m.getNavigation().stop();
            return;
        }
        // an animal near his path distracts him for a couple of seconds while he kills it
        if (distractTicks > 0) { tickDistract(level); return; }
        if (distractCd == 0 && mode == ManbearMode.CHASE && lunge == Lunge.NONE && dist > 5 && chaseTicks % 10 == 0) {
            Animal near = nearbyAnimal(level);
            if (near != null) { startDistract(level, near); return; }
        }
        // stunning roar: only when you have opened up a gap, at most N times per chase; he keeps running meanwhile
        if (!ambushOnly && lunge == Lunge.NONE && roarCd == 0 && roarsUsed < ManbearConfig.ROARS_PER_CHASE.get() && chaseTicks > 80
                && los && dist >= ManbearConfig.ROAR_MIN_DISTANCE.get() && dist <= ManbearConfig.ROAR_MAX_DISTANCE.get()) {
            startRoar(level);
        }
        if (lunge != Lunge.NONE) { tickLunge(level); return; }

        if (breaker.active()) {
            m.getNavigation().stop();
            if (!breaker.tick(level, m)) { stuckTicks = 0; pathCd = 0; }
            return;
        }
        if (tryChaseTeleport(level)) return;

        double speed = refSpeedMod() * ManbearConfig.CHASE_SPEED_MULT.get();   // as fast as a fake chase
        if (mode == ManbearMode.AMBUSH && chaseTicks < 100) speed *= 1.2;
        if (m.getRoarTicks() > 0) speed *= 1.12;   // catching up while you are frozen
        if (rushTicks > 0) {                         // the "behind you" jumpscare charge
            rushTicks--;
            if (dist > 3.5) speed = refSpeedMod() * ManbearConfig.BEHIND_RUSH_MULTIPLIER.get();
            else rushTicks = 0;
        }
        if (rushBurstTicks > 0) {
            rushBurstTicks--;
            speed = sprintSpeedMod() * ManbearConfig.CHASE_BURST_SPEED_MULT.get();
        }

        // melee
        if (reachDist() < 4.0 && meleeCd == 0 && pendingMelee == 0) {
            if (canGrab()) { startGrab(level); return; }
            m.triggerAttack(rnd().nextInt(3));
            meleeCd = 24;
            pendingMelee = 5;
            m.getNavigation().stop();
            return;
        }
        // lunge
        if (lungeCd == 0 && dist >= 4.4 && dist <= 9.5 && los && m.onGround() && !targetInPit()) {
            lunge = Lunge.WINDUP;
            lungeTimer = 7;
            lungeHit = false;
            m.getNavigation().stop();
            m.triggerAttack(rnd().nextInt(3));
            m.playBear(ModSounds.SNARL.get(), 3.5F, 0.75F + rnd().nextFloat() * 0.2F);
            return;
        }

        navTo(level, target.getX(), target.getY(), target.getZ(), speed, 6);

        // Path to you is blocked (you are in a house / bunker) or he is stuck: tunnel straight toward you.
        Vec3 cur = m.position();
        if (cur.distanceToSqr(lastPos) < 0.0016) stuckTicks++; else stuckTicks = 0;
        lastPos = cur;
        if ((navFail || stuckTicks > 12) && dist > 2.4 && !(targetInPit() && reachDist() < 5.0)) {   // at the edge of the pit he reaches down, he does not dig
            BlockPos b = BlockBreaker.canGrief(level) ? breaker.findAlongRay(level, m, target) : null;
            if (b != null) {
                if (BlockBreaker.inReach(m, b)) {
                    breaker.start(level, b);
                    m.getNavigation().stop();
                    stuckTicks = 0;
                } else {
                    // walk right up to the wall first (overrides the partial path this tick)
                    m.getMoveControl().setWantedPosition(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, speed);
                }
                unreachableTicks = Math.max(0, unreachableTicks - 3);
            } else if (stuckTicks > 10) {
                unreachableTicks += 1;
                unstick(level, speed);
            } else if (safeToCharge(level)) {
                // nothing to break: a river or a gap. Go straight at you - he wades and swims across.
                m.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), speed);
            }
        }
        if (!navFail) unreachableTicks = Math.max(0, unreachableTicks - 2);
        if (unreachableTicks > 160) {
            setMode(level, trapCooldown <= 0 ? ManbearMode.LAY_TRAP : ManbearMode.STALK);
            return;
        }

        if (hitsLanded >= (aggr > 0.5F ? 2 : 4) || dist > 85) endChase(level);
    }

    private void tickLunge(ServerLevel level) {
        lungeTimer--;
        switch (lunge) {
            case WINDUP -> {
                m.getNavigation().stop();
                if (lungeTimer <= 0) {
                    Vec3 aim = target.position();
                    if (pounce && prof != null) aim = aim.add(prof.velocity().scale(0.9)); // lead a moving target
                    Vec3 d = aim.subtract(m.position());
                    Vec3 h = new Vec3(d.x, 0, d.z);
                    double hl = h.length();
                    h = hl < 1.0E-4 ? new Vec3(0, 0, 1) : h.scale(1.0 / hl);
                    if (pounce) {
                        // ballistic leap: reaches the target in about 9 ticks (under half a second)
                        double v = Mth.clamp(hl / 6.2, 1.0, 3.2);
                        m.impulse(new Vec3(h.x * v, 0.42, h.z * v));
                        lungeTimer = 24;
                    } else {
                        m.impulse(new Vec3(h.x * 0.95, 0.38, h.z * 0.95));
                        lungeTimer = 16;
                    }
                    lunge = Lunge.AIR;
                }
            }
            case AIR -> {
                if (!lungeHit && m.getBoundingBox().inflate(pounce ? 1.3 : 0.8, pounce ? 0.8 : 0.4, pounce ? 1.3 : 0.8).intersects(target.getBoundingBox())) {
                    lungeHit = true;
                    if (m.doHurtTarget(target)) {
                        hitsLanded++;
                        if (ambushOnly) { vanish(level); return; }   // lunged, hurt you, gone
                        if (canGrab()) { startGrab(level); return; }  // caught you mid-leap: grab
                    }
                }
                if (lungeTimer <= 0 || (lungeTimer < (pounce ? 18 : 11) && m.onGround())) {
                    lunge = Lunge.RECOVER;
                    lungeTimer = pounce ? 6 : 14;
                }
            }
            case RECOVER -> {
                m.getNavigation().stop();
                if (lungeTimer <= 0) {
                    lunge = Lunge.NONE;
                    pounce = false;
                    lungeCd = ManbearConfig.LUNGE_COOLDOWN_TICKS.get() + rnd().nextInt(30) - (aggr > 0.5F ? 20 : 0);
                    if (lungeHit && (aggr > 0.5F || rnd().nextFloat() < 0.2F)) endChase(level);
                }
            }
            default -> { }
        }
    }

    private void samplePanic() {
        if (prof == null) return;
        Vec3 away = target.position().subtract(m.position());
        away = new Vec3(away.x, 0, away.z);
        if (away.lengthSqr() < 0.01) return;
        away = away.normalize();
        boolean fleeing = prof.velocity().dot(away) > 0.5;
        if (fleeing) prof.panic = PlayerProfile.ema(prof.panic, 1F, 0.08F);
        else if (dist < 10) prof.panic = PlayerProfile.ema(prof.panic, 0F, 0.08F);
    }

    private void endChase(ServerLevel level) {
        lastChaseEnd = ticksAlive;
        float r = rnd().nextFloat();
        setMode(level, r < 0.6F ? ManbearMode.RETREAT : r < 0.85F ? ManbearMode.HIDE : ManbearMode.STALK);
    }

    // ================================================================= LAY TRAP
    private void tickTrap(ServerLevel level) {
        if (trapSite == null) { setMode(level, ManbearMode.STALK); return; }
        if (trapPhase == 0) {
            Vec3 s = Vec3.atBottomCenterOf(trapSite);
            navTo(level, s.x, s.y, s.z, 0.8, 15);
            if (dist < 10 && los) { // caught red-handed
                boolean canChase = canChase();
                setMode(level, canChase && rnd().nextFloat() < 0.5F ? ManbearMode.CHASE : ManbearMode.RETREAT);
                return;
            }
            if (reached(trapSite, 5.0)) {
                trapPhase = 1;
                trapTimer = 35;
                m.getNavigation().stop();
                m.triggerAttack(2);
            }
        } else {
            m.getNavigation().stop();
            if (--trapTimer <= 0) {
                placeTrap(level);
                trapCooldown = 900 + rnd().nextInt(1800);
                setMode(level, rnd().nextFloat() < 0.6F ? ManbearMode.HIDE : ManbearMode.STALK);
            }
        }
    }

    private void placeTrap(ServerLevel level) {
        placeTrapAt(level, trapSite);
    }

    boolean placeTrapAt(ServerLevel level, BlockPos site) {
        BlockState st = level.getBlockState(site);
        if (!(st.isAir() || st.canBeReplaced())) return false;
        BlockState trap = ModBlocks.BEAR_TRAP.get().defaultBlockState()
                .setValue(com.manbear.block.BearTrapBlock.HUNTER_SET, true);
        if (!trap.canSurvive(level, site)) return false;
        level.setBlock(site, trap, 3);
        ManbearData data = ManbearData.get(level.getServer());
        String dim = level.dimension().location().toString();
        for (ManbearData.Trap old : data.addTrap(new ManbearData.Trap(dim, site.asLong()), Math.max(ManbearConfig.MAX_TRAPS.get(), 4))) {
            ServerLevel l = level.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(old.dim())));
            BlockPos p = BlockPos.of(old.pos());
            if (l != null && l.hasChunkAt(p) && l.getBlockState(p).is(ModBlocks.BEAR_TRAP.get())) l.removeBlock(p, false);
        }
        return true;
    }

    // ================================================================= RETREAT
    private void tickRetreat(ServerLevel level) {
        Vec3 me = m.position();
        Vec3 away = new Vec3(me.x - target.getX(), 0, me.z - target.getZ());
        away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
        Vec3 goal = me.add(away.scale(18));
        BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, m.getBlockY(), 3);
        if (caveFleeing || g == null) {
            BlockPos alt = fleeSpot(level);
            if (alt != null) g = alt;
        }
        double fleeSpeed = caveFleeing ? Math.max(2.2, refSpeedMod() * 2.4) : (m.leaving ? 1.1 : 0.9);
        if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, fleeSpeed, caveFleeing ? 8 : 15);
        else m.getNavigation().stop();
        if (caveFleeing) m.setFaceTarget(null, 0F);   // runs forward, away from you

        if (m.leaving) {
            // only ever vanishes while nobody can see him (failsafe: far away for a full minute)
            if (!visibleToAnyone(level) || (leavingTicks > 1200 && dist > 40)) m.discard();
            return;
        }
        if ((!los && dist > 30) || dist > 46) {
            setMode(level, rnd().nextFloat() < 0.55F && coverFailCd <= 0 ? ManbearMode.HIDE : ManbearMode.STALK);
        }
    }

    // ================================================================= ANIMAL LURE
    private PathfinderMob lureAnimal(ServerLevel level) {
        if (animalId == null) return null;
        return level.getEntity(animalId) instanceof PathfinderMob a && a.isAlive() ? a : null;
    }

    /** nobody saw it yet: just remove it */
    private void discardLureAnimal(ServerLevel level) {
        PathfinderMob a = lureAnimal(level);
        if (a != null) a.discard();
        animalId = null;
    }

    /** it was seen: it simply becomes an ordinary animal that is terrified of him */
    private void releaseLureAnimal(ServerLevel level) {
        PathfinderMob a = lureAnimal(level);
        if (a != null) {
            a.removeTag("manbear_run");
            a.removeTag("manbear_lure");
            a.getPersistentData().remove("lure_x");
            a.getPersistentData().remove("lure_y");
            a.getPersistentData().remove("lure_z");
        }
        animalId = null;
    }

    private void abortLure(ServerLevel level) {
        lureDone = true;
        setMode(level, ManbearMode.STALK);   // exitMode removes / releases the animal
    }

    /** puts a random animal between you and him, off to the side where you are likely to notice it */
    private boolean spawnLureAnimal(ServerLevel level) {
        Vec3 toBear = new Vec3(m.getX() - target.getX(), 0, m.getZ() - target.getZ());
        double len = toBear.length();
        if (len < 12) return false;
        toBear = toBear.scale(1.0 / len);
        Vec3 side = new Vec3(-toBear.z, 0, toBear.x);
        EntityType<?>[] types = {EntityType.WOLF, EntityType.PIG, EntityType.SHEEP, EntityType.COW, EntityType.VILLAGER};
        for (int i = 0; i < 20; i++) {
            double fwd = 14 + rnd().nextInt(8);
            double lat = rnd().nextInt(13) - 6;
            Vec3 p = target.position().add(toBear.scale(fwd)).add(side.scale(lat));
            BlockPos g = CoverFinder.groundNear(level, p.x, p.z, target.getBlockY(), 2);
            if (g == null || g.distSqr(m.blockPosition()) < 10 * 10) continue;
            Entity e = types[rnd().nextInt(types.length)].create(level);
            if (!(e instanceof PathfinderMob a)) return false;
            a.moveTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, rnd().nextFloat() * 360F, 0F);
            a.addTag("manbear_lure");
            a.goalSelector.addGoal(0, new LureWolfGoal(a));
            if (a instanceof Wolf) lureCall = SoundEvents.WOLF_WHINE;
            else if (a instanceof Pig) lureCall = SoundEvents.PIG_AMBIENT;
            else if (a instanceof Sheep) lureCall = SoundEvents.SHEEP_AMBIENT;
            else if (a instanceof Cow) lureCall = SoundEvents.COW_AMBIENT;
            else if (a instanceof Villager) lureCall = SoundEvents.VILLAGER_AMBIENT;
            level.addFreshEntity(a);
            animalId = a.getUUID();
            return true;
        }
        return false;
    }

    /** trap variant: a bait spot a few blocks in front of his hiding place, ringed with traps on your side */
    private boolean setupBait(ServerLevel level) {
        Vec3 hide = Vec3.atBottomCenterOf(cover.hide());
        Vec3 toPlayer = new Vec3(target.getX() - hide.x, 0, target.getZ() - hide.z);
        double len = toPlayer.length();
        if (len < 14) return false;
        toPlayer = toPlayer.scale(1.0 / len);
        Vec3 side = new Vec3(-toPlayer.z, 0, toPlayer.x);
        BlockPos bait = CoverFinder.groundNear(level, hide.x + toPlayer.x * 7, hide.z + toPlayer.z * 7, cover.hide().getY(), 2);
        if (bait == null) return false;
        int placed = 0;
        for (int k = -2; k <= 2; k += 2) {
            double tx = bait.getX() + 0.5 + toPlayer.x * 3 + side.x * k;
            double tz = bait.getZ() + 0.5 + toPlayer.z * 3 + side.z * k;
            BlockPos g = CoverFinder.groundNear(level, tx, tz, bait.getY(), 1);
            if (g != null && placeTrapAt(level, g)) placed++;
        }
        if (placed == 0) return false;
        baitSpot = bait;
        return true;
    }

    private boolean playerSeesAnimal(PathfinderMob a) {
        double d = target.distanceTo(a);
        if (d > 48 || d < 6 || !target.hasLineOfSight(a)) return false;
        Vec3 look = target.getViewVector(1F);
        Vec3 to = a.getEyePosition().subtract(target.getEyePosition()).normalize();
        return look.dot(to) > 0.65;
    }

    /**
     * Animal shows up -> you see it -> it bolts.
     *  - 70%: it runs to a bait spot ringed with his bear traps and waits there, calling you in.
     *  - 30%: it runs straight to him, he lets out a long roar and a chase (or fake chase) starts.
     */
    private void tickLure(ServerLevel level) {
        if (lurePhase > 0) m.getNavigation().stop();   // phase 0 is walking to the hiding spot
        switch (lurePhase) {
            case 0 -> { // slip into hiding first
                Vec3 h = Vec3.atBottomCenterOf(cover.hide());
                if (reached(cover.hide(), 1.6) || modeTime > 500) {
                    m.getNavigation().stop();
                    if (!spawnLureAnimal(level)) { abortLure(level); return; }
                    if (lureTrapVariant && !setupBait(level)) lureTrapVariant = false;   // no room for traps: go to him instead
                    lurePhase = 1;
                    lureTimer = 0;
                } else {
                    navTo(level, h.x, h.y, h.z, 1.0, 10);
                }
            }
            case 1 -> { // wait for you to notice the animal
                PathfinderMob a = lureAnimal(level);
                if (a == null) { abortLure(level); return; }
                lureTimer++;
                if (lureTimer % 100 == 40 && lureStare == 0) {
                    level.playSound(null, a.getX(), a.getY(), a.getZ(), lureCall, SoundSource.NEUTRAL, 2.5F, 0.9F);
                }
                if (lureStare == 0 && playerSeesAnimal(a)) lureStare = 28;
                if (lureStare > 0) {   // it stares at you for a moment...
                    a.getNavigation().stop();
                    a.getLookControl().setLookAt(target, 30.0F, 30.0F);
                    if (--lureStare == 0) {   // ...then bolts
                        if (lureTrapVariant && baitSpot != null) {
                            a.getPersistentData().putDouble("lure_x", baitSpot.getX() + 0.5);
                            a.getPersistentData().putDouble("lure_y", baitSpot.getY());
                            a.getPersistentData().putDouble("lure_z", baitSpot.getZ() + 0.5);
                        }
                        a.addTag("manbear_run");
                        lurePhase = 2;
                        lureTimer = 0;
                    }
                } else if (lureTimer > 1100) {
                    abortLure(level);
                }
            }
            case 2 -> { // it runs
                PathfinderMob a = lureAnimal(level);
                lureTimer++;
                if (a == null) { abortLure(level); return; }
                if (lureTrapVariant && baitSpot != null) {
                    if (a.distanceToSqr(baitSpot.getX() + 0.5, baitSpot.getY(), baitSpot.getZ() + 0.5) < 9 || lureTimer > 500) {
                        a.removeTag("manbear_run");        // it stops at the bait spot and calls to you
                        lurePhase = 4;
                        lureTimer = 0;
                    }
                } else if (a.distanceTo(m) < 4.5 || lureTimer > 500) {
                    a.removeTag("manbear_run");
                    a.removeTag("manbear_lure");   // from now on it is just an animal scared of him
                    lurePhase = 3;
                    lureTimer = 0;
                    m.setRoarTicks(80);
                    m.playBear(ModSounds.ROAR.get(), 9.0F, 0.65F);
                    m.playBear(ModSounds.ROAR_DEEP.get(), 9.0F, 0.6F);
                }
            }
            case 4 -> { // bait: it waits among the traps; if you step on one, the usual ambush starts
                PathfinderMob a = lureAnimal(level);
                lureTimer++;
                if (a == null) { lureDone = true; setMode(level, ManbearMode.HIDE); return; }
                a.getNavigation().stop();
                a.getLookControl().setLookAt(target, 30.0F, 30.0F);
                if (lureTimer % 90 == 30) level.playSound(null, a.getX(), a.getY(), a.getZ(), lureCall, SoundSource.NEUTRAL, 2.0F, 0.9F);
                if (lureTimer > 900) { lureDone = true; setMode(level, ManbearMode.HIDE); }
            }
            default -> { // phase 3: the long roar, then it starts
                lureTimer++;
                if (lureTimer == 30) m.playBear(ModSounds.ROAR_DEEP.get(), 9.0F, 0.55F);
                if (lureTimer == 55) m.playBear(ModSounds.ROAR.get(), 9.0F, 0.6F);
                if (lureTimer >= 80) {
                    lureDone = true;
                    if (m.isChaser()) {
                        ambushing = true;
                        setMode(level, ManbearMode.CHASE);
                    } else {
                        setMode(level, ManbearMode.FAKE_CHASE);
                    }
                }
            }
        }
    }

    // ================================================================= BEHIND YOU
    /** He stands behind you, silent and still, until you turn around - then charges and roars. */
    private void tickBehind(ServerLevel level) {
        Vec3 look = target.getLookAngle();
        Vec3 lookH = new Vec3(look.x, 0, look.z);
        lookH = lookH.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : lookH.normalize();
        Vec3 toEye = m.getEyePosition().subtract(target.getEyePosition()).normalize();
        boolean seen = los && dist < 45 && look.dot(toEye) > 0.5;
        if (seen && modeTime > 30) { jumpscare(level); return; }

        // follow you at a distance so he stays at your back; when you stand still he stands still
        if (dist > 17 || dist < 5.5) {
            Vec3 goal = target.position().subtract(lookH.scale(11));
            BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, target.getBlockY(), 5);
            if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, dist > 17 ? Math.max(0.9, refSpeedMod() * 1.05) : 0.7, 8);
        } else {
            m.getNavigation().stop();
        }
    }

    private void jumpscare(ServerLevel level) {
        pounceChase(level, true, 10.0F);
    }

    /** a very fast leap at the player that turns into a chase (behind-you jumpscare, path blocker, ...) */
    private void pounceChase(ServerLevel level, boolean rush, float roarVol) {
        m.setChaser(true);
        m.extendLife(2400);
        ambushing = true;
        rushTicks = rush ? 50 : 0;
        m.setRoarTicks(40);
        m.playBear(ModSounds.ROAR.get(), roarVol, 0.65F);
        m.playBear(ModSounds.ROAR_DEEP.get(), roarVol, 0.6F);
        if (!ManbearConfig.SOUNDS_FROM_BODY.get()) {      // the old 'right in the player's ears' copy (off by default: it came from nowhere)
            level.playSound(null, target.getX(), target.getY(), target.getZ(), ModSounds.ROAR_DEEP.get(), SoundSource.HOSTILE, 4.0F, 0.8F);
            level.playSound(null, target.getX(), target.getY(), target.getZ(), ModSounds.ROAR.get(), SoundSource.HOSTILE, 4.0F, 0.7F);
        }
        setMode(level, ManbearMode.CHASE);
        refreshTraits(level);
        // leap at the player right now (setMode cleared the lunge state, so set it afterwards)
        pounce = true;
        lunge = Lunge.WINDUP;
        lungeTimer = 4;
        lungeHit = false;
        m.getNavigation().stop();
        m.triggerLeap();
    }

    private void vanish(ServerLevel level) {
        level.sendParticles(ParticleTypes.LARGE_SMOKE, m.getX(), m.getY() + 2.0, m.getZ(), 30, 0.6, 1.5, 0.6, 0.02);
        m.discard();
    }

    // ================================================================= GRAB
    private boolean canGrab() {
        return mode == ManbearMode.CHASE && !ambushOnly && grabCd == 0 && target != null && target.isAlive()
                && !target.isPassenger() && rnd().nextFloat() < ManbearConfig.GRAB_CHANCE.get().floatValue();
    }

    private void startGrab(ServerLevel level) {
        held = target;
        grabProgress = 0F;
        grabTicks = 0;
        m.playBear(ModSounds.SNARL.get(), 5.0F, 0.6F);
        setMode(level, ManbearMode.GRAB);
    }

    /** one space-bar press from the grabbed player */
    public void struggle(ServerPlayer p) {
        if (mode == ManbearMode.GRAB && p == held && !lethalGrab) grabProgress += ManbearConfig.STRUGGLE_PER_PRESS.get().floatValue();
        else if (mode == ManbearMode.KIDNAP && kidPhase == KID_CARRY && p == held) {
            kidCount++;                                              // exactly N presses, no drain
            grabProgress = Math.min(1F, kidCount / (float) Math.max(1, kidPresses));
        }
    }

    /** He holds you in front of his chest, hurts you every second, and you mash space (shown on the exp bar) to break free. */
    private void tickGrab(ServerLevel level) {
        m.getNavigation().stop();
        if (held == null || !held.isAlive() || held.isDeadOrDying() || held.level() != m.level() || m.distanceToSqr(held) > 30 * 30) {
            endGrab(level, false);
            return;
        }
        grabTicks++;
        float yaw = m.getYRot() * ((float) Math.PI / 180F);
        Vec3 fwd = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3 hold = m.position().add(fwd.scale(1.6)).add(0, 1.2, 0);
        if (!level.noCollision(held, held.getBoundingBox().move(hold.subtract(held.position())))) {
            hold = m.position().add(fwd.scale(0.9)).add(0, 0.2, 0);   // not enough room to lift you here
        }
        if (ticksAlive % 2 == 0) {
            Vec3 d = m.getEyePosition().subtract(hold.add(0, 1.6, 0));
            double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
            float ly = (float) (Mth.atan2(d.z, d.x) * (180D / Math.PI)) - 90.0F;
            float lp = (float) -(Mth.atan2(d.y, horiz) * (180D / Math.PI));
            held.setYRot(ly);
            held.setXRot(lp);
            held.connection.teleport(hold.x, hold.y, hold.z, ly, lp);
        }
        held.setDeltaMovement(Vec3.ZERO);
        held.fallDistance = 0F;

        grabProgress = Math.max(0F, grabProgress - 0.004F);   // the grip tightens if you stop
        if (lethalGrab) grabProgress = 0F;                    // intimidation: nothing you do fills the bar
        if (grabTicks % 20 == 0 && !lethalGrab) {
            held.hurt(level.damageSources().mobAttack(m), ManbearConfig.GRAB_DAMAGE.get().floatValue());
            m.triggerAttack(2);
        }
        if (ticksAlive % 2 == 0) {
            held.connection.send(new ClientboundSetExperiencePacket(Mth.clamp(grabProgress, 0F, 1F), 0, 0));   // the exp bar is the escape bar
        }
        if (lethalGrab) {
            if (ticksAlive % 10 == 0) held.displayClientMessage(Component.literal("..."), true);
            if (grabTicks >= 70) killHeld(level);
            return;
        }
        if (ticksAlive % 10 == 0) held.displayClientMessage(Component.literal("MASH SPACE!"), true);
        if (grabProgress >= 1F) endGrab(level, true);
    }

    /** intimidation: he reached you. There is no escape. */
    private void startLethalGrab(ServerLevel level) {
        held = target;
        grabProgress = 0F;
        grabTicks = 0;
        lethalGrab = true;
        m.playBear(ModSounds.HUFF.get(), 1.2F, 0.7F);   // still no roar: just a breath
        setMode(level, ManbearMode.GRAB);
    }

    private void killHeld(ServerLevel level) {
        ServerPlayer p = held;
        restoreXpBar();
        held = null;
        lethalGrab = false;
        if (p != null && p.isAlive()) {
            if (ManbearConfig.INTIMIDATE_LETHAL.get()) {
                // bypasses invulnerability and totems
                p.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
            } else {
                float dmg = Math.max(0F, p.getHealth() - 2F);
                if (dmg > 0F) p.hurt(level.damageSources().mobAttack(m), dmg);
            }
        }
        vanish(level);
    }

    /** He walks slowly (a player's walking pace) straight at you and does not care whether you look at him. No footsteps, only soft breathing. */
    private void tickIntimidate(ServerLevel level) {
        if (breaker.active()) {
            m.getNavigation().stop();
            if (!breaker.tick(level, m)) { stuckTicks = 0; pathCd = 0; }
            return;
        }
        navTo(level, target.getX(), target.getY(), target.getZ(), 1.0, 10);
        tunnel(level, 1.0);
        if (dist < 3.0 && target.isAlive() && !target.isPassenger()) startLethalGrab(level);
    }

    private void restoreXpBar() {
        if (held != null && held.connection != null) {
            held.connection.send(new ClientboundSetExperiencePacket(held.experienceProgress, held.totalExperience, held.experienceLevel));
        }
    }

    private void endGrab(ServerLevel level, boolean escaped) {
        restoreXpBar();
        ServerPlayer p = held;
        ServerPlayer p0 = held;
        held = null;
        grabCd = escaped ? 300 : 200;
        if (escaped && p0 != null && level.getServer() != null) {
            ManbearData.get(level.getServer()).profile(p0.getUUID()).addRage(ManbearConfig.RAGE_ESCAPE.get().floatValue());
        }
        if (p != null && p.isAlive()) {
            Vec3 away = p.position().subtract(m.position());
            away = new Vec3(away.x, 0, away.z);
            away = away.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : away.normalize();
            p.setDeltaMovement(away.x * 0.9, 0.45, away.z * 0.9);   // flung clear
            p.hurtMarked = true;
            p.fallDistance = 0F;
        }
        ambushing = false;
        setMode(level, ManbearMode.CHASE);
        if (escaped) freezeTicks = 30;     // he is dazed for a moment when you break free
    }

    /** called when he is removed: never leave a player's exp bar stuck on the escape bar */
    public void cleanup() {
        if (doppelId != null && m.level() instanceof ServerLevel dl) discardDoppel(dl);
        if (mode == ManbearMode.KIDNAP && kidPhase == KID_FADE && held != null && m.level() instanceof ServerLevel sl) {
            kidnapTeleport(sl, false); // he was removed mid-fade: preserve the old safe fallback without a guard entity
        }
        restoreXpBar();
        held = null;
        if (carried != null && m.level() instanceof ServerLevel sl) dropPet(sl);   // never lose a pet when he despawns
    }

    // ================================================================= STUN / DISTRACTION
    private void stunHim(ServerLevel level, ServerPlayer by) {
        if (mode == ManbearMode.GRAB) endGrab(level, true);   // the hit knocks his grip loose
        else if (mode == ManbearMode.KIDNAP && kidPhase == KID_CARRY) endKidnap(level, true);
        manStunTicks = ManbearConfig.STUN_TICKS.get();
        manStunCd = manStunTicks + 100;
        m.getNavigation().stop();
        m.setRoarTicks(Math.min(manStunTicks, 30));
        level.sendParticles(ParticleTypes.CRIT, m.getX(), m.getY() + 2.0, m.getZ(), 25, 0.6, 1.2, 0.6, 0.2);
        level.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.SHIELD_BREAK, SoundSource.HOSTILE, 1.5F, 0.7F);
        ManbearData.get(level.getServer()).profile(by.getUUID()).addRage(ManbearConfig.RAGE_STUN.get().floatValue());
    }

    private Animal nearbyAnimal(ServerLevel level) {
        Animal best = null;
        double bd = Double.MAX_VALUE;
        for (Animal a : level.getEntitiesOfClass(Animal.class, m.getBoundingBox().inflate(7, 3, 7),
                x -> x.isAlive() && !(x instanceof CompanionBird) && !x.getTags().contains("manbear_lure") && !x.getTags().contains(PetKidnap.CAPTIVE)
                        && !(x instanceof OwnableEntity oe && oe.getOwnerUUID() != null))) {
            double d = a.distanceToSqr(m);
            if (d < bd) { bd = d; best = a; }
        }
        return best;
    }

    private void startDistract(ServerLevel level, Animal a) {
        distractAnimal = a;
        distractTicks = ManbearConfig.DISTRACT_TICKS.get();
        distractCd = distractTicks + 160;
        m.getNavigation().stop();
        m.playBear(ModSounds.SNARL.get(), 3.0F, 0.8F);
        if (prof != null && target != null) prof.addRage(ManbearConfig.RAGE_STUN.get().floatValue());   // you used an animal to slow him down
    }

    /** he runs to the animal, kills it, and goes straight back to the chase */
    private void tickDistract(ServerLevel level) {
        Entity a = distractAnimal;
        if (a == null || !a.isAlive()) { distractTicks = 0; distractAnimal = null; return; }
        distractTicks--;
        m.setFaceTarget(a, 40F);
        if (m.distanceTo(a) > 3.2) {
            navTo(level, a.getX(), a.getY(), a.getZ(), refSpeedMod() * 2.5, 4);
        } else {
            m.getNavigation().stop();
            if (distractTicks % 14 == 0) m.triggerAttack(rnd().nextInt(3));
            if (a instanceof LivingEntity le && distractTicks <= ManbearConfig.DISTRACT_TICKS.get() - 10) {
                le.hurt(level.damageSources().mobAttack(m), 1000F);
            }
        }
        if (distractTicks <= 0) distractAnimal = null;
    }

    // ================================================================= PET KIDNAPPING
    private LivingEntity findPet(ServerLevel level) {
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : PetKidnap.petsOf(level, target, 40)) {
            double d = e.distanceToSqr(m);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    /** sneaks up behind you; once your back has been turned long enough he dashes in, grabs your pet and carries it off to a trap */
    private void tickPetnap(ServerLevel level) {
        switch (petPhase) {
            case 0 -> {
                Vec3 look = target.getLookAngle();
                Vec3 lookH = new Vec3(look.x, 0, look.z);
                lookH = lookH.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : lookH.normalize();
                Vec3 toEye = m.getEyePosition().subtract(target.getEyePosition()).normalize();
                boolean seen = los && dist < 45 && look.dot(toEye) > 0.55;
                lurkSeen = seen ? lurkSeen + 1 : 0;
                petTarget = findPet(level);
                if ((lurkSeen >= 12 && modeTime > 40) || petTarget == null) {   // spotted, or nothing left to take
                    caveFleeing = true;
                    beginLeaving(level);
                    return;
                }
                petTimer = lurkSeen == 0 ? petTimer + 1 : 0;      // how long your back has been turned
                if (modeTime > 60 && petTimer >= 40 && m.distanceTo(petTarget) < 30) {
                    petPhase = 1;
                    petTimer = 0;
                    return;
                }
                // meanwhile stay at your back, like the lurker
                Vec3 toMe = new Vec3(m.getX() - target.getX(), 0, m.getZ() - target.getZ());
                double h = toMe.length();
                Vec3 toMeN = h < 1.0E-4 ? lookH.scale(-1) : toMe.scale(1.0 / h);
                if (dist > 11 || dist < 4 || lookH.dot(toMeN) > -0.5) {
                    Vec3 goal = target.position().subtract(lookH.scale(7.5));
                    BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, target.getBlockY(), 3);
                    if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, Math.max(1.2, refSpeedMod() * 1.6), 4);
                } else {
                    m.getNavigation().stop();
                }
            }
            case 1 -> {   // the dash
                if (petTarget == null || !petTarget.isAlive() || petTarget.isPassenger()) { petTarget = findPet(level); }
                if (petTarget == null || ++petTimer > 300) { caveFleeing = true; beginLeaving(level); return; }
                navTo(level, petTarget.getX(), petTarget.getY(), petTarget.getZ(), refSpeedMod() * 2.5, 4);
                if (m.distanceTo(petTarget) < 3.6) snatchPet(level, petTarget);
            }
            case 2 -> {   // running off with it
                if (carried == null || !carried.isAlive()) { caveFleeing = true; beginLeaving(level); return; }
                if (dropSpot == null) dropSpot = chooseDropSpot(level);
                petTimer++;
                navTo(level, dropSpot.getX() + 0.5, dropSpot.getY(), dropSpot.getZ() + 0.5, refSpeedMod() * 2.2, 6);
                if (reached(dropSpot, 9.0) || petTimer > 500) {
                    dropPet(level);
                    petPhase = 3;
                }
            }
            default -> { caveFleeing = true; beginLeaving(level); }
        }
    }

    private void snatchPet(ServerLevel level, LivingEntity pet) {
        PetKidnap.capture(pet);
        pet.startRiding(m, true);
        carried = pet;
        m.triggerAttack(1);
        m.playBear(ModSounds.SNARL.get(), 3.5F, 0.8F);
        level.playSound(null, pet.getX(), pet.getY(), pet.getZ(), SoundEvents.WOLF_WHINE, SoundSource.NEUTRAL, 1.6F, 1.0F);
        dropSpot = chooseDropSpot(level);
        petPhase = 2;
        petTimer = 0;
    }

    /** somewhere 20-50 blocks from you, preferably out of your line of sight */
    private BlockPos chooseDropSpot(ServerLevel level) {
        BlockPos any = null;
        for (int i = 0; i < 40; i++) {
            double ang = rnd().nextDouble() * Math.PI * 2;
            double d = 20 + rnd().nextInt(31);
            BlockPos g = CoverFinder.groundNear(level, target.getX() + Math.cos(ang) * d, target.getZ() + Math.sin(ang) * d, target.getBlockY(), 2);
            if (g == null) continue;
            if (any == null) any = g;
            if (!CoverFinder.lineClear(level, target.getEyePosition(), Vec3.atBottomCenterOf(g).add(0, 1, 0), target)) return g;
        }
        return any != null ? any : m.blockPosition();
    }

    /** lets the pet down where he stands and clamps a closed bear trap around it */
    private void dropPet(ServerLevel level) {
        if (carried != null) {
            LivingEntity pet = carried;
            carried = null;
            if (pet.isAlive()) {
                pet.stopRiding();
                BlockPos at = m.blockPosition();
                pet.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, pet.getYRot(), 0F);
                pet.fallDistance = 0F;
                PetKidnap.capture(pet);
                PetKidnap.trap(level, pet);
            }
        }
    }

    /** he was told his name: a chase, a fake chase or he gives up (separate slices of one roll) */
    public void saidName(ServerPlayer p, float roll) {
        if (m.leaving || !(m.level() instanceof ServerLevel level)) return;
        if (mode == ManbearMode.FLASH || mode == ManbearMode.PETNAP) return;
        if (mode == ManbearMode.KIDNAP && kidPhase >= KID_CARRY) return;
        float c = ManbearConfig.SAY_CHASE_CHANCE.get().floatValue();
        float f = ManbearConfig.SAY_FAKE_CHANCE.get().floatValue();
        float d = ManbearConfig.SAY_DESPAWN_CHANCE.get().floatValue();
        if (roll < c) {
            target = p;
            targetLock = 600;
            ambushing = false;
            m.setChaser(true);
            setMode(level, ManbearMode.CHASE);
        } else if (roll < c + f) {
            target = p;
            targetLock = 600;
            setMode(level, ManbearMode.FAKE_CHASE);
        } else if (roll < c + f + d) {
            caveFleeing = true;
            beginLeaving(level);
        }
    }

    // ================================================================= LURKER
    /** Right behind you. If you turn, he scrambles to stay at your back; if you actually spot him he bolts and despawns out of sight. */
    private void tickLurker(ServerLevel level) {
        Vec3 look = target.getLookAngle();
        Vec3 lookH = new Vec3(look.x, 0, look.z);
        lookH = lookH.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : lookH.normalize();
        Vec3 toEye = m.getEyePosition().subtract(target.getEyePosition()).normalize();
        boolean seen = los && dist < 45 && look.dot(toEye) > 0.55;
        lurkSeen = seen ? lurkSeen + 1 : 0;
        if (lurkSeen >= 12 && modeTime > 40) {   // you looked right at him: gone
            caveFleeing = true;
            beginLeaving(level);
            return;
        }
        Vec3 toMe = new Vec3(m.getX() - target.getX(), 0, m.getZ() - target.getZ());
        double h = toMe.length();
        Vec3 toMeN = h < 1.0E-4 ? lookH.scale(-1) : toMe.scale(1.0 / h);
        double facing = lookH.dot(toMeN);              // -1 = exactly behind you
        double want = 6.5;
        if (dist > 9.5 || dist < 3.5 || facing > -0.6) {
            Vec3 goal = target.position().subtract(lookH.scale(want));
            BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, target.getBlockY(), 3);
            if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, Math.max(1.2, refSpeedMod() * 1.6), 4);
        } else {
            m.getNavigation().stop();
        }
    }

    // ================================================================= PATH BLOCKER / FOG WATCHER / CAVE
    /** He waits where you go most often. Come within a random 4-12 blocks and he lunges and chases. */
    private void tickPath(ServerLevel level) {
        m.getNavigation().stop();
        if (dist <= pathTrigger && (los || dist < 7)) pounceChase(level, false, 8.0F);
    }

    /** Stands at the very edge of your view in the fog and only watches. He keeps that distance and slips away if you rush him. */
    private void tickFog(ServerLevel level) {
        if (fogDist < 0) fogDist = dist;
        if (dist < fogDist * 0.45) { beginLeaving(level); return; }
        if (dist < fogDist * 0.85) {
            Vec3 me = m.position();
            Vec3 away = new Vec3(me.x - target.getX(), 0, me.z - target.getZ());
            away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
            BlockPos g = CoverFinder.groundNear(level, me.x + away.x * 14, me.z + away.z * 14, m.getBlockY(), 5);
            if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, 0.9, 15);
            else m.getNavigation().stop();
        } else {
            m.getNavigation().stop();
        }
    }

    /** Sits in the blood with his back to you. If you come close he bolts, and vanishes once out of sight. */
    private void tickCave(ServerLevel level) {
        m.getNavigation().stop();
        if (dist < ManbearConfig.CAVE_FLEE_DISTANCE.get() && (los || dist < 6)) {
            caveFleeing = true;
            m.playBear(ModSounds.GROWL.get(), 3.0F, 0.8F);   // startled growl
            beginLeaving(level);
            return;
        }
        if (m.lifeTicks > m.lifespan() && ticksAlive % 20 == 0 && (!los || dist > 40)) m.discard();
    }

    private BlockPos fleeSpot(ServerLevel level) {
        BlockPos best = null;
        double bestScore = -1;
        for (int i = 0; i < 20; i++) {
            double ang = rnd().nextDouble() * Math.PI * 2;
            double d = 10 + rnd().nextInt(14);
            BlockPos g = CoverFinder.groundNear(level, m.getX() + Math.cos(ang) * d, m.getZ() + Math.sin(ang) * d, m.getBlockY(), 3);
            if (g == null) continue;
            double score = g.distSqr(target.blockPosition());
            if (CoverFinder.lineClear(level, target.getEyePosition(), Vec3.atBottomCenterOf(g).add(0, 2, 0), target)) score *= 0.5;
            if (score > bestScore) { bestScore = score; best = g; }
        }
        return best;
    }

    // ================================================================= STUN ROAR
    private void startRoar(ServerLevel level) {
        roarsUsed++;
        roarCd = 160;
        stunTicks = ManbearConfig.ROAR_STUN_TICKS.get();
        stunTarget = target;
        m.setRoarTicks(Math.max(30, stunTicks));
        m.playBear(ModSounds.ROAR.get(), 6.0F, 0.7F);
        m.playBear(ModSounds.ROAR_DEEP.get(), 6.0F, 0.8F);
        if (!ManbearConfig.SOUNDS_FROM_BODY.get()) level.playSound(null, target.getX(), target.getY(), target.getZ(), ModSounds.ROAR_DEEP.get(), SoundSource.HOSTILE, 1.8F, 0.9F);
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, stunTicks + 6, 6, false, false, false));
        target.addEffect(new MobEffectInstance(MobEffects.JUMP, stunTicks + 6, 128, false, false, false));
    }

    /** freezes the player and forces their camera onto him */
    private void tickStun() {
        if (stunTicks <= 0) return;
        if (stunTarget == null || !stunTarget.isAlive() || stunTarget.hasDisconnected()) { stunTicks = 0; return; }
        stunTicks--;
        if (stunTicks % 2 == 0) {
            Vec3 d = m.getEyePosition().subtract(stunTarget.getEyePosition());
            double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
            float yaw = (float) (Mth.atan2(d.z, d.x) * (180D / Math.PI)) - 90.0F;
            float pitch = (float) -(Mth.atan2(d.y, horiz) * (180D / Math.PI));
            stunTarget.setYRot(yaw);
            stunTarget.setXRot(pitch);
            stunTarget.connection.teleport(stunTarget.getX(), stunTarget.getY(), stunTarget.getZ(), yaw, pitch);
        }
    }

    // ================================================================= SLEEP
    public void addNoise(float amount) {
        if (mode == ManbearMode.SLEEP) noise += amount;
    }

    private void tickSleep(ServerLevel level) {
        m.getNavigation().stop();
        m.setFaceTarget(null, 0F);
        m.setClimbing(false);
        noise = Math.max(0F, noise - 0.025F);   // calms down 0.5 per second

        if (snoreCd-- <= 0) {
            snoreCd = 80 + rnd().nextInt(100);
            m.playBear(ModSounds.SNORE.get(), 2.2F, 0.9F + rnd().nextFloat() * 0.2F);
        }
        double nearest = Double.MAX_VALUE;
        ServerPlayer nearestPlayer = null;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            double d = p.distanceToSqr(m);
            if (d < nearest) { nearest = d; nearestPlayer = p; }
        }
        // walking right up to him (not sneaking) is noisy; sneaking past is fine
        if (nearestPlayer != null && nearest < 4.0 * 4.0 && !nearestPlayer.isShiftKeyDown()) noise += 0.04F;

        if (noise >= ManbearConfig.SLEEP_NOISE_THRESHOLD.get()) { wake(level); return; }

        // sleepers fade away eventually, but only when nobody is watching
        if (m.lifeTicks > m.lifespan() && ticksAlive % 20 == 0
                && (nearestPlayer == null || nearest > 40 * 40 || !m.hasLineOfSight(nearestPlayer))) {
            m.discard();
        }
    }

    /** woken: roars and immediately chases whoever is closest */
    private void wake(ServerLevel level) {
        noise = 0;
        m.setChaser(true);          // allowed to chase this time
        m.extendLife(2400);
        ambushing = true;           // roar + instant lunge readiness
        resolveTarget(level);
        if (target == null) { m.discard(); return; }
        m.playBear(ModSounds.ROAR_DEEP.get(), 6.0F, 0.75F);
        setMode(level, ManbearMode.CHASE);
        refreshTraits(level);
    }

    /**
     * While he stalks he sometimes makes the sounds of someone else in your base: a chest or door opening
     * (and closing), a torch fizzling out, a low grumble. They come from random spots around you.
     */
    private void tickTricks(ServerLevel level) {
        for (java.util.Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (ticksAlive >= p.at()) {
                level.playSound(null, p.x(), p.y(), p.z(), p.ev(), p.src(), p.vol(), p.pitch());
                it.remove();
            }
        }
        if (trickCd-- > 0 || !ManbearConfig.AMBIENT_TRICKS.get()) return;
        int min = ManbearConfig.TRICK_MIN_INTERVAL_TICKS.get();
        int max = Math.max(min + 1, ManbearConfig.TRICK_MAX_INTERVAL_TICKS.get());
        trickCd = (int) ((min + rnd().nextInt(max - min)) * (1F - 0.5F * rageF));
        boolean stealthy = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK;
        if (!stealthy) return;

        // somewhere 7-18 blocks from you, often behind you
        Vec3 look = target.getLookAngle();
        double base = rnd().nextFloat() < 0.5F ? Math.atan2(-look.z, -look.x) : rnd().nextDouble() * Math.PI * 2;
        double ang = base + (rnd().nextDouble() - 0.5) * 1.2;
        double d = 7 + rnd().nextDouble() * 11;
        double x = target.getX() + Math.cos(ang) * d;
        double z = target.getZ() + Math.sin(ang) * d;
        double y = target.getY();
        float gain = 1F;
        if (ManbearConfig.SOUNDS_FROM_BODY.get()) {        // it is HIM making them: from where he stands (louder, he is usually far off)
            x = m.getX();
            y = m.getY() + 1.0;
            z = m.getZ();
            gain = 2.2F;
        }
        float pitch = 0.9F + rnd().nextFloat() * 0.2F;
        int pick = rnd().nextInt(10);
        if (pick <= 1) {                       // chest creaks open, then shuts
            level.playSound(null, x, y, z, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1.3F * gain, pitch);
            pending.add(new Pending(ticksAlive + 30 + rnd().nextInt(30), SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, x, y, z, 1.3F * gain, pitch));
        } else if (pick <= 3) {                // a door
            boolean iron = rnd().nextFloat() < 0.25F;
            level.playSound(null, x, y, z, iron ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1.4F * gain, pitch);
            pending.add(new Pending(ticksAlive + 25 + rnd().nextInt(40), iron ? SoundEvents.IRON_DOOR_CLOSE : SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, x, y, z, 1.4F * gain, pitch));
        } else if (pick <= 5) {                // a torch burning out
            level.playSound(null, x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.8F * gain, 1.3F + rnd().nextFloat() * 0.3F);
        } else if (pick == 6) {                // barrel
            level.playSound(null, x, y, z, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 1.3F * gain, pitch);
            pending.add(new Pending(ticksAlive + 35 + rnd().nextInt(30), SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, x, y, z, 1.3F * gain, pitch));
        } else {                               // low grumbling, from him
            m.playBear(rnd().nextBoolean() ? ModSounds.GROWL.get() : ModSounds.HUFF.get(), 1.4F + rnd().nextFloat(), 0.55F + rnd().nextFloat() * 0.15F);
        }
    }

    /** every 30 s while stalking / hiding / peeking / chasing: a 1-in-5 chance he plays back a phrase of YOUR voice */
    private void voiceMimic() {
        if (voiceCd-- > 0) return;
        voiceCd = ManbearConfig.VOICE_INTERVAL_TICKS.get();
        if (!ManbearConfig.VOICE_MIMIC.get() || target == null) return;
        boolean ok = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK
                || mode == ManbearMode.CHASE || mode == ManbearMode.AMBUSH;
        if (!ok || rnd().nextFloat() >= ManbearConfig.VOICE_CHANCE.get().floatValue() * (1F + rageF)) return;
        ServerPlayer who = target;
        double vx = m.getX();
        double vy = m.getY() + m.getBbHeight() * 0.7;
        double vz = m.getZ();
        // multiplayer: very often use SOMEONE ELSE's voice so you think your friend is out there
        byte[] other = ManbearConfig.VOICE_SHARING.get() && rnd().nextFloat() < 0.7F
                ? com.manbear.voice.VoiceBank.randomFromOthers(who.getUUID(), new java.util.Random(rnd().nextLong())) : null;
        if (other != null) {
            com.manbear.net.ManbearNet.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> who),
                    new com.manbear.net.RemoteVoicePacket(vx, vy, vz, other));
        } else {
            com.manbear.net.ManbearNet.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> who),
                    new com.manbear.net.VoiceMimicPacket(vx, vy, vz));
        }
    }

    /** closer = heavier breathing */
    private void breathe() {
        if (breathCd-- > 0) return;
        breathCd = 50 + rnd().nextInt(60);
        boolean stealthy = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK
                || mode == ManbearMode.LAY_TRAP || mode == ManbearMode.BEHIND || mode == ManbearMode.LURKER
                || mode == ManbearMode.INTIMIDATE || mode == ManbearMode.PITLURE || mode == ManbearMode.HALLUCINATE || (mode == ManbearMode.KIDNAP && kidPhase < KID_CARRY);
        boolean soft = mode == ManbearMode.INTIMIDATE;   // the intimidator only ever breathes softly
        double range = soft ? 40.0 : 30.0;
        if (!stealthy || dist >= range) return;
        float c = 1F - (float) (dist / range);
        float vol = soft ? 0.25F + 0.9F * c : 0.35F + 2.8F * c * c;
        m.playBear(ModSounds.HUFF.get(), vol, soft ? 0.7F : 0.8F + rnd().nextFloat() * 0.2F);
    }

    // ================================================================= ambience
    /** distorted pig / cow / sheep noises, from him or from behind you */
    private void mimicAnimal() {
        if (mimicCd > 0 || !ManbearConfig.MIMIC_ANIMAL_SOUNDS.get()) return;
        mimicCd = 450 + rnd().nextInt(900);
        boolean stealthy = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK
                || mode == ManbearMode.LAY_TRAP || mode == ManbearMode.RETREAT;
        if (!stealthy) return;
        SoundEvent ev;
        int pick = rnd().nextInt(3);
        if (pick == 0) ev = ModSounds.MIMIC_PIG.get();
        else if (pick == 1) ev = ModSounds.MIMIC_COW.get();
        else ev = ModSounds.MIMIC_SHEEP.get();
        double x = m.getX();
        double y = m.getY();
        double z = m.getZ();
        if (!ManbearConfig.SOUNDS_FROM_BODY.get() && rnd().nextFloat() < 0.3F) { // from behind the player (old behaviour)
            Vec3 look = target.getLookAngle();
            x = target.getX() - look.x * 18;
            y = target.getY();
            z = target.getZ() - look.z * 18;
        }
        m.level().playSound(null, x, y, z, ev, SoundSource.NEUTRAL, 2.2F, 1.0F);
    }

    private void ambient() {
        if (ambientCd > 0) return;
        RandomSource r = rnd();
        ambientCd = 300 + r.nextInt(600);
        switch (mode) {
            case STALK -> {
                if (r.nextFloat() < 0.7F) m.playBear(ModSounds.GROWL.get(), 3.0F, 0.8F + r.nextFloat() * 0.3F);
                else m.playBear(ModSounds.HUFF.get(), 1.6F, 0.9F);
                if (r.nextFloat() < 0.05F) m.playBear(ModSounds.ROAR.get(), 5.5F, 0.8F);
            }
            case HIDE, PEEK -> { if (dist < 22) m.playBear(ModSounds.HUFF.get(), 1.1F, 0.9F + r.nextFloat() * 0.2F); }
            case CHASE, AMBUSH -> m.playBear(ModSounds.GROWL.get(), 2.5F, 0.9F);
            case RETREAT -> m.playBear(ModSounds.GROWL.get(), 1.5F, 0.7F);
            default -> { }
        }
    }

    /** he is gone for good (despawned, killed, /remove): an unsprung pit is filled back in */
    public void onDestroyed(Entity.RemovalReason reason) {
        if (destroyed) return;          // remove() can run more than once in the same tick: only act the first time
        destroyed = true;
        if (pit != null && !pit.sprung() && m.level() instanceof ServerLevel sl) PitTraps.removeAndFill(sl, pit);
        pit = null;
        // he vanished while stalking / hiding / peeking and you never noticed him: he leaves something behind
        boolean watching = isWatch(mode) || (mode == ManbearMode.RETREAT && leaveFromWatch);
        if (reason == Entity.RemovalReason.DISCARDED && ManbearConfig.EVIDENCE_ENABLED.get() && !noEvidence && !noticed && watching
                && m.level() instanceof ServerLevel sl) {
            Vec3 away = target != null ? new Vec3(m.getX() - target.getX(), 0, m.getZ() - target.getZ()) : new Vec3(1, 0, 0);
            Evidence.leave(sl, this, m.position(), away, new java.util.ArrayList<>(trail));
        }
    }

    /** test command */
    public boolean debugEvidence(ServerLevel level, String kind, Vec3 at, Vec3 away) {
        return Evidence.leaveAs(kind, level, this, at, away, new java.util.ArrayList<>(trail));
    }

    /** removed on purpose (command): no evidence */
    public void suppressEvidence() { noEvidence = true; }

    private static boolean isWatch(ManbearMode md) {
        return md == ManbearMode.STALK || md == ManbearMode.HIDE || md == ManbearMode.PEEK;
    }

    // ================================================================= PIT LURE
    /** you are in (or at) the hole: he reaches down into it from the edge instead of trying to walk in */
    private boolean targetInPit() {
        return pitChase && target != null && target.getY() < m.getY() - 1.5;
    }

    /** distance used for his melee: straight down into a pit only counts sideways */
    private double reachDist() {
        if (targetInPit()) {
            double dx = target.getX() - m.getX();
            double dz = target.getZ() - m.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }
        return dist;
    }

    /**
     * 0: he stands silent behind you, waiting for you to notice him.
     * 1: you did - he digs his pit and runs off past it.
     * 2: he waits on the far side, always keeping the pit between you and him. If you walk to him you step on the cover,
     *    it breaks, and the chase starts. If you do not follow (or walk around it) he leaves.
     */
    private void tickPitLure(ServerLevel level) {
        if (pitPhase == 0) {
            Vec3 look = target.getLookAngle();
            Vec3 lookH = new Vec3(look.x, 0, look.z);
            lookH = lookH.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : lookH.normalize();
            Vec3 toEye = m.getEyePosition().subtract(target.getEyePosition()).normalize();
            boolean seen = los && dist < 45 && look.dot(toEye) > 0.5;
            if (seen && modeTime > 30) { pitNoticed(level); return; }
            if (modeTime > ManbearConfig.PIT_NOTICE_SECONDS.get() * 20) { vanishFast(level); return; }   // never noticed him
            // stay at your back; if you stand still, so does he
            if (dist > 17 || dist < 5.5) {
                Vec3 goal = target.position().subtract(lookH.scale(11));
                BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, target.getBlockY(), 5);
                if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, dist > 17 ? Math.max(0.9, refSpeedMod() * 1.05) : 0.7, 8);
            } else {
                m.getNavigation().stop();
            }
            return;
        }
        if (pit == null) { vanishFast(level); return; }
        if (pit.sprung()) { startPitChase(level); return; }            // somebody stepped on the cover

        if (pitPhase == 1) {
            pitTimer++;
            Vec3 f = Vec3.atBottomCenterOf(pitFar);
            navTo(level, f.x, f.y, f.z, Math.max(1.3, refSpeedMod() * 1.5), 6);
            if (reached(pitFar, 9.0) || pitTimer > 400 || navFailCount > 8) {
                pitPhase = 2;
                pitWait = 0;
                m.getNavigation().stop();
            }
            return;
        }

        pitWait++;
        double dFar = Math.hypot(target.getX() - (pitFar.getX() + 0.5), target.getZ() - (pitFar.getZ() + 0.5));
        if (dFar < 5.5                                                     // you got to him without falling: you went around
                || dFar > 80                                               // you wandered off
                || (pitWait > ManbearConfig.PIT_FOLLOW_SECONDS.get() * 20 && dFar > 30)) {   // you are not following
            vanishFast(level);
            return;
        }
        // keep the pit exactly on the line between you and him, whichever way you approach
        if (pitWait % 20 == 0) {
            Vec3 c = Vec3.atBottomCenterOf(pit.top);
            double dx = c.x - target.getX();
            double dz = c.z - target.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 6) {
                double wx = c.x + dx / len * 8;
                double wz = c.z + dz / len * 8;
                if (Math.hypot(wx - (pitFar.getX() + 0.5), wz - (pitFar.getZ() + 0.5)) > 4.5) {
                    BlockPos g = CoverFinder.groundNear(level, wx, wz, pit.top.getY() + 1, 5);
                    if (g != null) pitFar = g;
                }
            }
        }
        if (reached(pitFar, 4.0)) {
            m.getNavigation().stop();
        } else {
            Vec3 f = Vec3.atBottomCenterOf(pitFar);
            navTo(level, f.x, f.y, f.z, 0.9, 10);
        }
    }

    /** you turned around and saw him: dig the pit beyond him and run for the far side of it */
    private void pitNoticed(ServerLevel level) {
        pit = PitTraps.plan(level, target, m.position());
        if (pit == null) { vanishFast(level); return; }              // no suitable ground: he just bolts
        Vec3 c = Vec3.atBottomCenterOf(pit.top);
        double dx = c.x - target.getX();
        double dz = c.z - target.getZ();
        double len = Math.max(1.0E-3, Math.sqrt(dx * dx + dz * dz));
        pitFar = CoverFinder.groundNear(level, c.x + dx / len * 8, c.z + dz / len * 8, pit.top.getY() + 1, 5);
        if (pitFar == null) pitFar = CoverFinder.groundNear(level, c.x + dx / len * 6, c.z + dz / len * 6, pit.top.getY() + 1, 3);
        if (pitFar == null) { vanishFast(level); return; }            // exitMode fills the pit again
        m.playBear(ModSounds.HUFF.get(), 2.0F, 0.7F);
        pitPhase = 1;
        pitTimer = 0;
        pitWait = 0;
        navFailCount = 0;
        pathCd = 0;
    }

    /** you fell in: he roars and the chase begins */
    private void startPitChase(ServerLevel level) {
        ServerPlayer victim = pit.sprungBy == null ? null : level.getServer().getPlayerList().getPlayer(pit.sprungBy);
        if (victim != null && victim.isAlive() && victim.level() == m.level()) {
            target = victim;
            targetLock = 800;
        }
        pitChase = true;
        m.setChaser(true);
        ambushing = true;             // he roars as it starts and may lunge at once
        setMode(level, ManbearMode.CHASE);
        pit = null;
        refreshTraits(level);
    }

    // ================================================================= KIDNAPPING
    private void tickKidnap(ServerLevel level) {
        switch (kidPhase) {
            case KID_SNEAK -> kidSneak(level);
            case KID_BACKOFF -> kidBackoff(level);
            case KID_CARRY -> kidCarry(level);
            case KID_FADE -> kidFade(level);
            case KID_DEN_GUARD -> tickDenCaptivity(level, false);
            case KID_DEN_SLEEP -> tickDenCaptivity(level, true);
            default -> kidFade(level);
        }
    }

    /** creeps up on you, crouched. Look at him and he backs off into plain stalking. Reach you and he takes you. */
    private void kidSneak(ServerLevel level) {
        Vec3 look = target.getLookAngle();
        Vec3 toEye = m.getEyePosition().subtract(target.getEyePosition()).normalize();
        boolean seen = los && dist < 60 && look.dot(toEye) > 0.6;
        kidSeen = seen ? kidSeen + 1 : 0;
        if (kidSeen >= 8 && modeTime > 20) {            // you spotted him
            kidPhase = KID_BACKOFF;
            kidTimer = 0;
            m.getNavigation().stop();
            m.playBear(ModSounds.HUFF.get(), 2.2F, 0.7F);
            return;
        }
        if (dist < 2.8 && target.isAlive()) { startKidnap(level); return; }
        if (breaker.active()) {
            m.getNavigation().stop();
            if (!breaker.tick(level, m)) { stuckTicks = 0; pathCd = 0; }
            return;
        }
        // slowly: only a little faster than you are walking away from him
        double bps = prof != null ? prof.velocity().length() * 2.0 : 0.0;     // blocks per second
        double speed = Mth.clamp((bps + 1.7) / 4.317, 0.45, 1.5);
        navTo(level, target.getX(), target.getY(), target.getZ(), speed, 8);
        tunnel(level, speed);
    }

    /** walks backwards, still facing you, until he is far enough away to be a stalker again */
    private void kidBackoff(ServerLevel level) {
        kidTimer++;
        Vec3 me = m.position();
        Vec3 away = new Vec3(me.x - target.getX(), 0, me.z - target.getZ());
        away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
        BlockPos g = CoverFinder.groundNear(level, me.x + away.x * 14, me.z + away.z * 14, m.getBlockY(), 3);
        if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, 0.8, 8);
        else m.getNavigation().stop();
        if (dist >= hideMin() + 2 || kidTimer > 160) setMode(level, ManbearMode.STALK);
    }

    private void startKidnap(ServerLevel level) {
        breaker.cancel(level, m);
        target.stopRiding();
        held = target;
        kidCount = 0;
        grabProgress = 0F;
        float rage = prof != null ? prof.rageFactor() : 0F;
        int lo = ManbearConfig.KIDNAP_PRESSES_MIN.get();
        int hi = Math.max(lo, ManbearConfig.KIDNAP_PRESSES_MAX.get());
        kidPresses = lo + Math.round((hi - lo) * rage);              // more rage, more presses
        // enough time to mash that many presses at a hard-but-possible ~6 per second, plus a breath
        kidMinTicks = (int) ((kidPresses / 6.0 + 2.0) * 20);
        kidTimer = 0;
        kidStuck = 0;
        targetLock = 6000;
        chooseKidnapDestination(level);
        kidPhase = KID_CARRY;
        m.triggerAttack(1);
        m.playBear(ModSounds.SNARL.get(), 6.0F, 0.6F);
        held.displayClientMessage(Component.literal("MASH SPACE!"), true);
    }

    /** the nearest cave he can really walk to; if there is none he simply carries you off into the distance */
    private void chooseKidnapDestination(ServerLevel level) {
        kidCave = null;
        kidnapToDen = false;
        kidSpeed = 1.6;
        ManbearData worldData = ManbearData.get(level.getServer());
        if (worldData.hasDen() && worldData.denDimension().equals(level.dimension().location().toString())) {
            // The den is the preferred destination even if its chunk was unloaded; its coordinates are persistent.
            BlockPos entrance = worldData.denEntrance();
            if (entrance != null && level.getWorldBorder().isWithinBounds(entrance)) {
                level.getChunkAt(entrance);
                kidCave = entrance.immutable();
                kidnapToDen = true;
                kidSpeed = 1.55;
                return;
            }
        }
        PathNavigation nav = m.getNavigation();
        int tries = 0;
        for (BlockPos c : CaveFinder.find(level, m.blockPosition(), ManbearConfig.KIDNAP_SEARCH_RADIUS.get(), 10, rnd())) {
            if (++tries > 6) break;
            Path path = nav.createPath(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 1);
            if (path != null && path.canReach()) {
                kidCave = c;
                // far caves: run; near caves: slower, so you are not robbed of your chance
                double secs = Math.max(4.0, kidMinTicks / 20.0 * 0.85);
                kidSpeed = Mth.clamp(path.getNodeCount() / secs / 4.317, 0.9, 2.0);
                return;
            }
        }
        for (int i = 0; i < 20; i++) {
            double ang = rnd().nextDouble() * Math.PI * 2;
            double d = 35 + rnd().nextDouble() * 25;
            BlockPos g = CoverFinder.groundNear(level, m.getX() + Math.cos(ang) * d, m.getZ() + Math.sin(ang) * d, m.getBlockY(), 3);
            if (g != null) { kidCave = g; return; }
        }
    }

    /** holds you in front of his chest, camera on his face. Done every tick: he is running. */
    private void holdInFront(ServerLevel level) {
        float yaw = m.getYRot() * ((float) Math.PI / 180F);
        Vec3 fwd = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3 hold = m.position().add(fwd.scale(1.6)).add(0, 1.2, 0);
        if (!level.noCollision(held, held.getBoundingBox().move(hold.subtract(held.position())))) {
            hold = m.position().add(fwd.scale(0.9)).add(0, 0.2, 0);    // not enough room to lift you here
        }
        Vec3 d = m.getEyePosition().subtract(hold.add(0, 1.6, 0));
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        float ly = (float) (Mth.atan2(d.z, d.x) * (180D / Math.PI)) - 90.0F;
        float lp = (float) -(Mth.atan2(d.y, horiz) * (180D / Math.PI));
        held.setYRot(ly);
        held.setXRot(lp);
        held.connection.teleport(hold.x, hold.y, hold.z, ly, lp);
        held.setDeltaMovement(Vec3.ZERO);
        held.fallDistance = 0F;
    }

    /** you are being carried: mash space (the exp bar is the escape bar) before he has you in the cave */
    private void kidCarry(ServerLevel level) {
        if (held == null || !held.isAlive() || held.isDeadOrDying() || held.hasDisconnected() || held.level() != m.level()) {
            restoreXpBar();
            held = null;
            vanishFast(level);
            return;
        }
        kidTimer++;
        holdInFront(level);
        if (kidTimer % 2 == 0) held.connection.send(new ClientboundSetExperiencePacket(Mth.clamp(grabProgress, 0F, 1F), 0, 0));
        if (kidTimer % 10 == 0) held.displayClientMessage(Component.literal("MASH SPACE!"), true);
        if (kidTimer % 50 == 25) m.playBear(ModSounds.GROWL.get(), 2.5F, 0.7F);
        if (kidCount >= kidPresses) { endKidnap(level, true); return; }

        boolean arrived = false;
        if (kidCave != null) {
            arrived = reached(kidCave, 16.0);
            if (arrived) {
                m.getNavigation().stop();
            } else {
                BlockPos travelGoal = kidCave;
                double remaining = m.position().distanceTo(Vec3.atBottomCenterOf(kidCave));
                // The den can be up to 200 blocks away; walk it in reachable waypoints instead of asking the navigator
                // for a single path beyond its practical search range.
                if (kidnapToDen && remaining > 58.0) {
                    Vec3 toward = Vec3.atBottomCenterOf(kidCave).subtract(m.position()).normalize();
                    Vec3 waypoint = m.position().add(toward.scale(40.0));
                    BlockPos ground = CoverFinder.groundNear(level, waypoint.x, waypoint.z, m.getBlockY(), 10);
                    if (ground != null) travelGoal = ground;
                }
                Vec3 c = Vec3.atBottomCenterOf(travelGoal);
                navTo(level, c.x, c.y, c.z, kidSpeed, 6);
                Vec3 cur = m.position();
                if (cur.distanceToSqr(lastPos) < 0.0016) stuckTicks++; else stuckTicks = 0;
                lastPos = cur;
                if (stuckTicks > 12) { unstick(level, kidSpeed); kidStuck++; }
            }
        } else {
            m.getNavigation().stop();
        }
        // he never finishes before you could have mashed free; after that, the moment he is in the cave (or hopelessly stuck) it is over
        boolean over = arrived || kidCave == null || kidStuck > 15 || navFailCount > 12 || kidTimer > kidMinTicks + 400;
        if (kidTimer >= kidMinTicks && over) beginKidnapFade(level);
    }

    /** you were too slow: the screen goes black */
    private void beginKidnapFade(ServerLevel level) {
        kidPhase = KID_FADE;
        kidFadeTimer = 0;
        m.getNavigation().stop();
        restoreXpBar();
        m.playBear(ModSounds.ROAR_DEEP.get(), 6.0F, 0.6F);
        ServerPlayer who = held;
        com.manbear.net.ManbearNet.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> who),
                new com.manbear.net.BlackoutPacket(30, 90, 50));
    }

    private void kidFade(ServerLevel level) {
        if (held == null) { vanish(level); return; }
        kidFadeTimer++;
        m.getNavigation().stop();
        holdInFront(level);
        if (kidFadeTimer >= 32) {       // fully black by now
            if (!kidnapTeleport(level, true)) vanish(level);
        }
    }

    /**
     * Finishes a kidnapping. When a generated den exists in this dimension, leave the victim in its main chamber
     * and keep the Manbear alive to guard the exit; otherwise preserve the original random-distance destination.
     * @return true when the Manbear remains for the den sequence, false when the old vanish behavior should run
     */
    private boolean kidnapTeleport(ServerLevel level) { return kidnapTeleport(level, true); }

    private boolean kidnapTeleport(ServerLevel level, boolean allowDen) {
        ServerPlayer p = held;
        held = null;
        if (p == null || !p.isAlive() || p.hasDisconnected()) return false;
        ServerLevel pl = p.serverLevel();
        ManbearData data = ManbearData.get(level.getServer());
        if (allowDen && kidnapToDen && data.hasDen()
                && data.denDimension().equals(pl.dimension().location().toString())
                && pl == level) {
            BlockPos roomFeet = data.denCenter().below(2); // the chamber floor is three blocks below the stored centre
            BlockPos entrance = data.denEntrance();
            pl.getChunkAt(roomFeet);
            if (entrance != null && CoverFinder.standable(pl, roomFeet, 2)) {
                p.teleportTo(pl, roomFeet.getX() + 0.5, roomFeet.getY(), roomFeet.getZ() + 0.5, p.getYRot(), 0F);
                p.setDeltaMovement(Vec3.ZERO);
                p.fallDistance = 0F;
                p.setHealth(Math.min(p.getHealth(), 1.0F)); // half a heart
                target = p;
                targetLock = 20 * 300;
                kidPhase = KID_DEN_GUARD;
                kidTimer = 0;
                denGuardWaitTicks = 1200 + rnd().nextInt(2401); // 1–3 minutes
                kidnapToDen = true;
                m.getNavigation().stop();
                m.extendLife(20 * 60 * 10);
                p.displayClientMessage(Component.literal("The Manbear has taken you to its den. Move quietly..."), false);
                return true;
            }
        }

        BlockPos dest = randomFarSpot(pl, p);
        if (dest != null) p.teleportTo(pl, dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, p.getYRot(), 0F);
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0F;
        p.setHealth(Math.min(p.getHealth(), 1.0F));
        return false;
    }

    /** Guards the exit in silence for 1–3 minutes, then returns to the chamber and sleeps. */
    private void tickDenCaptivity(ServerLevel level, boolean headingToSleep) {
        if (target == null || !target.isAlive() || target.hasDisconnected() || target.level() != level) {
            vanishFast(level);
            return;
        }
        ManbearData data = ManbearData.get(level.getServer());
        if (!data.hasDen() || !data.denDimension().equals(level.dimension().location().toString())) {
            setMode(level, ManbearMode.STALK);
            return;
        }
        BlockPos entrance = data.denEntrance();
        BlockPos roomFeet = data.denCenter().below(2);
        if (headingToSleep) {
            if (reached(roomFeet, 3.0) || (m.getNavigation().isDone() && m.position().distanceToSqr(Vec3.atBottomCenterOf(roomFeet)) < 9.0)) {
                noise = 0F;
                m.extendLife(20 * 60 * 30);
                setMode(level, ManbearMode.SLEEP); // the existing vibration/noise listener wakes him into a real chase
                return;
            }
            Vec3 center = Vec3.atBottomCenterOf(roomFeet);
            navTo(level, center.x, center.y, center.z, 0.85, 8);
            return;
        }

        // He must reach the exit first; the timer only starts while he is actually blocking it.
        if (m.position().distanceToSqr(Vec3.atBottomCenterOf(entrance)) > 2.25) {
            Vec3 door = Vec3.atBottomCenterOf(entrance);
            navTo(level, door.x, door.y, door.z, 0.8, 6);
            return;
        }
        m.getNavigation().stop();
        kidTimer++;
        double fromRoom = target.position().distanceTo(Vec3.atBottomCenterOf(roomFeet));
        double toExit = target.position().distanceTo(Vec3.atBottomCenterOf(entrance));
        // The main room is only about 15 blocks across. Leaving it, moving into the exit tunnel, or approaching
        // the guard is treated as an escape attempt even if the player is sneaking.
        if (fromRoom > 11.5 || toExit < 8.0 || target.distanceTo(m) < 4.0) {
            startDenEscapeChase(level);
            return;
        }
        if (kidTimer >= denGuardWaitTicks) {
            kidPhase = KID_DEN_SLEEP;
            kidTimer = 0;
            m.getNavigation().stop();
        }
    }

    private void startDenEscapeChase(ServerLevel level) {
        targetLock = 20 * 40;
        m.setChaser(true);
        if (rnd().nextBoolean()) {
            setMode(level, ManbearMode.FAKE_CHASE);
        } else {
            ambushing = true;
            m.playBear(ModSounds.ROAR_DEEP.get(), 6.0F, 0.75F);
            setMode(level, ManbearMode.CHASE);
            refreshTraits(level);
        }
    }

    private BlockPos randomFarSpot(ServerLevel level, ServerPlayer p) {
        int max = ManbearConfig.KIDNAP_TELEPORT_RADIUS.get();
        int min = Math.min(60, max / 2);
        for (int i = 0; i < 40; i++) {
            double ang = rnd().nextDouble() * Math.PI * 2;
            double d = min + Math.sqrt(rnd().nextDouble()) * (max - min);
            int x = Mth.floor(p.getX() + Math.cos(ang) * d);
            int z = Mth.floor(p.getZ() + Math.sin(ang) * d);
            if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, 64, z))) continue;
            level.getChunk(x >> 4, z >> 4);                           // load / generate the destination
            BlockPos g = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!CoverFinder.standable(level, g, 2)) continue;
            if (!level.getFluidState(g.below()).isEmpty()) continue;
            BlockState below = level.getBlockState(g.below());
            if (below.is(Blocks.MAGMA_BLOCK) || below.is(Blocks.CACTUS)) continue;
            return g;
        }
        return null;
    }

    /** you broke free: you are flung clear, he is furious */
    private void endKidnap(ServerLevel level, boolean escaped) {
        restoreXpBar();
        ServerPlayer p = held;
        held = null;
        grabCd = 300;
        if (escaped && p != null && level.getServer() != null) {
            ManbearData.get(level.getServer()).profile(p.getUUID()).addRage(ManbearConfig.RAGE_ESCAPE.get().floatValue());
        }
        if (p != null && p.isAlive()) {
            Vec3 away = p.position().subtract(m.position());
            away = new Vec3(away.x, 0, away.z);
            away = away.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : away.normalize();
            p.setDeltaMovement(away.x * 0.9, 0.45, away.z * 0.9);
            p.hurtMarked = true;
            p.fallDistance = 0F;
        }
        if (ManbearConfig.KIDNAP_ESCAPE_CHASES.get()) {
            ambushing = false;
            m.setChaser(true);
            setMode(level, ManbearMode.CHASE);
            freezeTicks = 30;                                          // dazed for a moment
        } else {
            vanishFast(level);
        }
    }

    // ================================================================= CONTINUOUS CHASE VOICE
    /**
     * Fake and real chases: he never stops roaring and screaming, and the closer he is the louder it gets
     * (volume ranges from about a quarter of the maximum at 64 blocks to the full value on top of you).
     */
    private void chaseVoice() {
        if (!ManbearConfig.CHASE_VOICE.get()) return;
        boolean on = (mode == ManbearMode.CHASE || mode == ManbearMode.AMBUSH || mode == ManbearMode.FAKE_CHASE)
                && !m.leaving && manStunTicks <= 0 && target != null;
        if (!on) { roarLoopCd = Math.min(roarLoopCd, 6); return; }
        if (--roarLoopCd > 0) return;
        float max = ManbearConfig.CHASE_VOICE_MAX_VOLUME.get().floatValue();
        float close = 1F - Mth.clamp((float) (dist / 64.0), 0F, 1F);   // 0 = far away, 1 = on top of you
        float vol = max * (0.22F + 0.78F * close * close);
        float pitch = 0.75F + rnd().nextFloat() * 0.25F;
        int pick = rnd().nextInt(10);
        if (pick < 4) {
            m.playBear(ModSounds.ROAR.get(), vol, pitch);
        } else if (pick < 7) {
            m.playBear(ModSounds.CHASE_SCREAM.get(), vol, 0.9F + rnd().nextFloat() * 0.3F);
        } else if (pick < 9) {                                          // both at once
            m.playBear(ModSounds.ROAR.get(), vol, pitch);
            m.playBear(ModSounds.CHASE_SCREAM.get(), vol * 0.85F, 0.8F + rnd().nextFloat() * 0.3F);
        } else {
            m.playBear(ModSounds.ROAR_DEEP.get(), vol, pitch - 0.1F);
        }
        roarLoopCd = 14 + rnd().nextInt(14);                            // a new one every 0.7-1.4 s, overlapping the last
    }

    // ================================================================= PROJECTILES
    /**
     * Something thrown or shot hit him. While he stalks, hides, peeks (or sneaks / waits in a lure) he is scared off: he runs
     * and despawns, and his rage rises. Above the rage limit he no longer flees: it only makes him angrier.
     * In any other mode it is an ordinary hit.
     */
    public void onProjectile(ServerLevel level, ServerPlayer sp) {
        boolean passive = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK
                || mode == ManbearMode.PITLURE || mode == ManbearMode.HALLUCINATE || (mode == ManbearMode.KIDNAP && kidPhase < KID_CARRY);
        if (!ManbearConfig.PROJECTILES_SCARE.get() || !passive || m.leaving) { onHurt(level, sp); return; }
        ManbearData data = ManbearData.get(level.getServer());
        PlayerProfile pr = data.profile(sp.getUUID());
        boolean furious = pr.rage > ManbearConfig.PROJECTILE_RAGE_LIMIT.get().floatValue();   // judged before this hit
        pr.addRage(ManbearConfig.RAGE_PROJECTILE.get().floatValue());
        data.setDirty();
        target = sp;
        targetLock = 200;
        noticed = true;
        if (furious) {
            if (m.getHealth() < m.getMaxHealth() * 0.3F) { caveFleeing = true; beginLeaving(level); }
            return;                                                    // he stands his ground: only angrier
        }
        m.playBear(ModSounds.SNARL.get(), 3.0F, 1.1F);
        caveFleeing = true;                                            // runs for cover and vanishes out of sight
        beginLeaving(level);
    }

    // ================================================================= FAKE DEATH
    public boolean isPlayingDead() { return mode == ManbearMode.PLAYDEAD; }

    /** you have hurt him enough: he collapses with a death cry and lies still */
    private void startPlayDead(ServerLevel level, ServerPlayer sp) {
        target = sp;
        targetLock = 1200;
        chaseHits = 0;
        m.playBear(ModSounds.DEATH.get(), 8.0F, 0.85F);
        level.playSound(null, m.getX(), m.getY(), m.getZ(), net.minecraft.sounds.SoundEvents.ANVIL_LAND, net.minecraft.sounds.SoundSource.HOSTILE, 1.5F, 0.5F);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, m.getX(), m.getY() + 0.5, m.getZ(), 30, 1.2, 0.3, 1.2, 0.02);
        setMode(level, ManbearMode.PLAYDEAD);
    }

    /**
     * He is not dead. He lies there (barely breathing, a faint tell) until you let your guard down: you look away for a
     * moment, walk off, or step right up to him. Then he is on his feet and the chase is on again, with a lunge ready.
     */
    private void tickPlayDead(ServerLevel level) {
        deadTicks++;
        m.getNavigation().stop();
        m.setClimbing(false);
        if (deadTicks % 90 == 45) m.playBear(ModSounds.HUFF.get(), 0.6F, 0.55F);
        Vec3 look = target.getLookAngle();
        Vec3 toEye = m.getEyePosition().subtract(target.getEyePosition());
        double len = toEye.length();
        boolean watching = los && dist < 40 && len > 1.0E-3 && look.dot(toEye.scale(1.0 / len)) > 0.35;
        deadAway = watching ? 0 : deadAway + 1;
        boolean guardDown = deadTicks > 20 && (deadAway >= 25 || dist > 20 || (dist < 3.0 && deadTicks > 30));
        if (guardDown || deadTicks > ManbearConfig.FAKE_DEATH_MAX_SECONDS.get() * 20) riseFromDead(level);
    }

    private void riseFromDead(ServerLevel level) {
        ambushing = true;             // roars and may lunge at once
        m.setChaser(true);
        chaseHits = 0;
        m.playBear(ModSounds.ROAR_DEEP.get(), 8.0F, 0.7F);
        setMode(level, ManbearMode.CHASE);
        refreshTraits(level);
    }

    /** hit while "dead": that was a mistake */
    public void onDeadHit(ServerLevel level, Entity attacker) {
        if (!(attacker instanceof ServerPlayer sp) || mode != ManbearMode.PLAYDEAD) return;
        target = sp;
        targetLock = 800;
        riseFromDead(level);
    }

    // ================================================================= HALLUCINATION
    /**
     * He waits motionless among the trees. A copy of YOU (your skin, name, armour, held item; only you can see it) stands in
     * the distance until you look at it, then runs for the woods. If you follow it and get close to him, he does one of:
     * lunge once and vanish / fake chase / flee / real chase (weights in the config: 30 / 40 / 20 / 10).
     */
    private void tickHallucinate(ServerLevel level) {
        hallTimer++;
        m.getNavigation().stop();
        if (!hallSpawned) {
            if (!spawnDoppelganger(level)) { vanishFast(level); return; }
            hallSpawned = true;
        }
        net.minecraft.world.entity.Entity d = doppelId == null ? null : level.getEntity(doppelId);
        hallGone = (d != null && d.isAlive()) ? 0 : hallGone + 1;
        if (hallTreeVariant) {
            // In the tree version, seeing the double make a dash behind the trunk ends the event quietly.
            if (hallGone > 10 || hallTimer > 20 * 90 || dist > 100) setMode(level, ManbearMode.STALK);
            return;
        }
        if (dist < 15 && (los || dist < 9)) { hallucinationOutcome(level); return; }      // you followed yourself to him
        if (hallTimer > 20 * 150 || hallGone > 20 * 45 || dist > 100) vanishFast(level);  // you did not follow
    }

    /** Each hallucination is one of two equally likely forms. If there is no tree nearby, use the normal lead-away form. */
    private boolean spawnDoppelganger(ServerLevel level) {
        hallTreeVariant = rnd().nextBoolean();
        if (hallTreeVariant && spawnTreeDoppelganger(level)) return true;
        hallTreeVariant = false;
        return spawnLeadingDoppelganger(level);
    }

    /** "you", standing between you and the woods where he is, facing you */
    private boolean spawnLeadingDoppelganger(ServerLevel level) {
        Vec3 pp = target.position();
        Vec3 mp = m.position();
        double dx = mp.x - pp.x;
        double dz = mp.z - pp.z;
        double total = Math.sqrt(dx * dx + dz * dz);
        if (total < 24) return false;
        double ux = dx / total;
        double uz = dz / total;
        double[] ahead = {22, 18, 26, 15, 30};
        for (double d : ahead) {
            if (d > total - 14) continue;                                  // keep well clear of him
            BlockPos g = CoverFinder.groundNear(level, pp.x + ux * d, pp.z + uz * d, target.getBlockY(), 3);
            if (g == null || Math.abs(g.getY() - target.getBlockY()) > 6) continue;
            com.manbear.entity.DoppelgangerEntity e = com.manbear.registry.ModEntities.DOPPELGANGER.get().create(level);
            if (e == null) return false;
            BlockPos run = CoverFinder.groundNear(level, mp.x - ux * 7, mp.z - uz * 7, m.getBlockY(), 3);   // a few blocks short of him
            e.copyOf(target);
            e.setDest(run != null ? Vec3.atBottomCenterOf(run) : mp);
            float yaw = (float) (Mth.atan2(pp.z - (g.getZ() + 0.5), pp.x - (g.getX() + 0.5)) * (180D / Math.PI)) - 90.0F;
            e.moveTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, yaw, 0F);
            e.setYHeadRot(yaw);
            e.setYBodyRot(yaw);
            level.addFreshEntity(e);
            doppelId = e.getUUID();
            return true;
        }
        return false;
    }


    /** Place a skin-copy on the player-facing side of a nearby tree; its destination is hidden behind the trunk. */
    private boolean spawnTreeDoppelganger(ServerLevel level) {
        Vec3 playerPos = target.position();
        for (int attempt = 0; attempt < 48; attempt++) {
            double angle = rnd().nextDouble() * Math.PI * 2.0;
            double radius = 10.0 + rnd().nextDouble() * 15.0;
            double tx = playerPos.x + Math.cos(angle) * radius;
            double tz = playerPos.z + Math.sin(angle) * radius;
            BlockPos ground = CoverFinder.groundNear(level, tx, tz, target.getBlockY(), 5);
            if (ground == null || Math.abs(ground.getY() - target.getBlockY()) > 7) continue;

            BlockPos trunk = null;
            outer: for (int ox = -4; ox <= 4; ox++) for (int oz = -4; oz <= 4; oz++) {
                for (int oy = 1; oy <= 11; oy++) {
                    BlockPos check = ground.offset(ox, oy, oz);
                    if (level.getBlockState(check).is(net.minecraft.tags.BlockTags.LOGS)) {
                        trunk = check;
                        break outer;
                    }
                }
            }
            if (trunk == null) continue;
            double dx = playerPos.x - (trunk.getX() + 0.5);
            double dz = playerPos.z - (trunk.getZ() + 0.5);
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.01) continue;
            dx /= len; dz /= len;
            BlockPos start = CoverFinder.groundNear(level, trunk.getX() + 0.5 + dx * 2.3,
                    trunk.getZ() + 0.5 + dz * 2.3, ground.getY(), 3);
            BlockPos hide = CoverFinder.groundNear(level, trunk.getX() + 0.5 - dx * 2.3,
                    trunk.getZ() + 0.5 - dz * 2.3, ground.getY(), 4);
            if (start == null || hide == null || start.distSqr(target.blockPosition()) < 64) continue;

            com.manbear.entity.DoppelgangerEntity e = com.manbear.registry.ModEntities.DOPPELGANGER.get().create(level);
            if (e == null) return false;
            e.copyOf(target);
            e.setTreeAmbush(Vec3.atBottomCenterOf(trunk), Vec3.atBottomCenterOf(hide), 5 + rnd().nextInt(6));
            float yaw = (float) (Mth.atan2(playerPos.z - (start.getZ() + 0.5), playerPos.x - (start.getX() + 0.5)) * (180D / Math.PI)) - 90.0F;
            e.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, yaw, 0F);
            e.setYHeadRot(yaw);
            e.setYBodyRot(yaw);
            level.addFreshEntity(e);
            doppelId = e.getUUID();
            return true;
        }
        return false;
    }

    private void discardDoppel(ServerLevel level) {
        if (doppelId == null) return;
        net.minecraft.world.entity.Entity d = level.getEntity(doppelId);
        if (d != null) d.discard();
        doppelId = null;
    }

    /** you reached him: lunge-and-vanish / fake chase / flee / chase, by weight */
    private void hallucinationOutcome(ServerLevel level) {
        discardDoppel(level);
        int lunge = ManbearConfig.HALLUCINATION_LUNGE.get();
        int fake = ManbearConfig.HALLUCINATION_FAKE_CHASE.get();
        int flee = ManbearConfig.HALLUCINATION_FLEE.get();
        int chase = ManbearConfig.HALLUCINATION_CHASE.get();
        int total = lunge + fake + flee + chase;
        int pick = total <= 0 ? 0 : rnd().nextInt(total);
        int outcome;                       // 0 lunge, 1 fake chase, 2 flee, 3 chase
        if (total <= 0) outcome = 2;
        else if ((pick -= lunge) < 0) outcome = 0;
        else if ((pick -= fake) < 0) outcome = 1;
        else if ((pick -= flee) < 0) outcome = 2;
        else outcome = 3;
        switch (outcome) {
            case 0 -> ambush(target, m.blockPosition());                    // one lunge at you, then he is gone
            case 1 -> {
                targetLock = 600;
                setMode(level, ManbearMode.FAKE_CHASE);                     // a chase that stops short
            }
            case 2 -> {
                m.playBear(ModSounds.SNARL.get(), 3.0F, 1.0F);              // seen, and gone
                caveFleeing = true;
                beginLeaving(level);
            }
            default -> {
                ambushing = true;                                           // a real chase
                m.setChaser(true);
                targetLock = 800;
                setMode(level, ManbearMode.CHASE);
                refreshTraits(level);
            }
        }
    }

}
