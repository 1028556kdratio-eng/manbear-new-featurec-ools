package com.manbear.data;

import com.manbear.ManbearConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * What the Manbear has learned about one player. Every trait is a 0..1 moving average,
 * saved with the world, so he remembers you between sessions.
 */
public class PlayerProfile {
    /** how often you look at him when you could see him */
    public float vigilance = 0.5F;
    /** how often you run away when chased */
    public float panic = 0.5F;
    /** how often you fight back */
    public float aggression = 0.0F;
    /** how much you stand still / hole up */
    public float camping = 0.0F;
    public float sneakiness = 0.0F;
    public float sprintiness = 0.3F;
    /** His aggression towards this player, 0..100. Fed by tree cutting, killing harmless animals, hitting him, ... */
    public float rage = 0F;

    public int hits;
    public int trapsSprung;
    public int deaths;
    public int chases;

    private final Map<Long, Integer> heat = new HashMap<>();
    private final Map<Long, Long> heatPos = new HashMap<>();   // cell -> last block position seen there
    public BlockPos base = null;
    /** home raid: set while you are away, carried out when you come back within range */
    public boolean raidPending;
    public long raidCooldownUntil;
    public int awayTicks;
    /** a decaying counter of time spent close to the learned base; used to unlock the den */
    public int basePresenceTicks;

    // transient sampling state
    private Vec3 areaAnchor;
    private int areaTicks;
    private Vec3 lastPos;
    private Vec3 heading = Vec3.ZERO;
    private Vec3 velocity = Vec3.ZERO;

    public Vec3 heading() { return heading; }
    /** blocks per sample (10 ticks) */
    public Vec3 velocity() { return velocity; }

    public static float ema(float old, float sample, float alpha) {
        return old + (sample - old) * alpha;
    }

    public void addRage(float v) {
        if (!ManbearConfig.RAGE_ENABLED.get()) return;
        rage = Math.max(0F, Math.min(100F, rage + v));
    }

    public float rageFactor() { return ManbearConfig.RAGE_ENABLED.get() ? rage / 100F : 0F; }

    public void onHitManbear() {
        hits++;
        aggression = Math.min(1F, aggression + 0.2F);
        addRage(ManbearConfig.RAGE_HIT.get().floatValue());
    }

    /** Called every 10 ticks for each survival player. */
    public void sample(ServerPlayer p, long gameTime, Entity manbear) {
        Vec3 pos = p.position();
        if (lastPos != null) {
            Vec3 d = pos.subtract(lastPos);
            velocity = new Vec3(d.x, 0, d.z);
            camping = ema(camping, d.lengthSqr() < 0.04 ? 1F : 0F, 0.004F);
            if (velocity.lengthSqr() > 0.01) heading = velocity.normalize();
        }
        lastPos = pos;

        sprintiness = ema(sprintiness, p.isSprinting() ? 1F : 0F, 0.004F);
        sneakiness = ema(sneakiness, p.isShiftKeyDown() ? 1F : 0F, 0.004F);
        aggression = Math.max(0F, aggression - 0.0004F);

        if (manbear != null && manbear.level() == p.level() && manbear.distanceTo(p) < 64 && p.hasLineOfSight(manbear)) {
            Vec3 look = p.getViewVector(1F);
            Vec3 to = manbear.getEyePosition().subtract(p.getEyePosition()).normalize();
            vigilance = ema(vigilance, look.dot(to) > 0.8 ? 1F : 0F, 0.04F);
        }

        if (gameTime % 100 == 0) addHeat(p);
        if (base != null) {
            double bdx = pos.x - (base.getX() + 0.5);
            double bdz = pos.z - (base.getZ() + 0.5);
            if (bdx * bdx + bdz * bdz <= 32.0 * 32.0) basePresenceTicks = Math.min(3_000_000, basePresenceTicks + 10);
            else basePresenceTicks = Math.max(0, basePresenceTicks - 20);
        }

        // rage cools off, but staying in one place for ages feeds it
        if (ManbearConfig.RAGE_ENABLED.get()) {
            rage = Math.max(0F, rage - ManbearConfig.RAGE_DECAY_PER_MINUTE.get().floatValue() / 120F);
            if (areaAnchor == null || pos.distanceToSqr(areaAnchor) > 24 * 24) {
                areaAnchor = pos;
                areaTicks = 0;
            } else {
                areaTicks += 10;
                if (areaTicks > 6000) addRage(ManbearConfig.RAGE_CAMP_PER_MINUTE.get().floatValue() / 120F);
            }
        }
    }

    private void addHeat(ServerPlayer p) {
        long key = (((long) (p.getBlockX() >> 3)) << 32) | ((p.getBlockZ() >> 3) & 0xFFFFFFFFL);
        heat.merge(key, 1, Integer::sum);
        heatPos.put(key, p.blockPosition().asLong());
        if (heat.size() > 64) {
            long minKey = key;
            int min = Integer.MAX_VALUE;
            for (Map.Entry<Long, Integer> e : heat.entrySet()) {
                if (e.getValue() < min) { min = e.getValue(); minKey = e.getKey(); }
            }
            heat.remove(minKey);
            heatPos.remove(minKey);
        }
        int best = 0;
        long bestKey = key;
        for (Map.Entry<Long, Integer> e : heat.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); bestKey = e.getKey(); }
        }
        Long pos = heatPos.get(bestKey);
        if (best >= 6 && pos != null) base = BlockPos.of(pos);
    }

    /** Your most visited places, most visited first (needs roughly 20 s spent in a spot to count). */
    public java.util.List<BlockPos> hotspots() {
        java.util.List<Map.Entry<Long, Integer>> list = new java.util.ArrayList<>(heat.entrySet());
        list.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (Map.Entry<Long, Integer> e : list) {
            Long pos = heatPos.get(e.getKey());
            if (pos != null && e.getValue() >= 4) out.add(BlockPos.of(pos));
        }
        return out;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putFloat("vig", vigilance); t.putFloat("pan", panic); t.putFloat("agg", aggression);
        t.putFloat("camp", camping); t.putFloat("sneak", sneakiness);
        t.putFloat("sprint", sprintiness);
        t.putFloat("rage", rage);
        t.putInt("hits", hits); t.putInt("traps", trapsSprung); t.putInt("deaths", deaths); t.putInt("chases", chases);
        if (base != null) t.putLong("base", base.asLong());
        t.putBoolean("raid", raidPending); t.putLong("raidCd", raidCooldownUntil);
        t.putInt("basePresence", basePresenceTicks);
        ListTag list = new ListTag();
        for (Map.Entry<Long, Integer> e : heat.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("k", e.getKey()); c.putInt("v", e.getValue());
            Long hp = heatPos.get(e.getKey());
            if (hp != null) c.putLong("p", hp);
            list.add(c);
        }
        t.put("heat", list);
        return t;
    }

    public static PlayerProfile load(CompoundTag t) {
        PlayerProfile p = new PlayerProfile();
        p.vigilance = t.getFloat("vig"); p.panic = t.getFloat("pan"); p.aggression = t.getFloat("agg");
        p.camping = t.getFloat("camp"); p.sneakiness = t.getFloat("sneak");
        p.sprintiness = t.getFloat("sprint");
        p.rage = t.getFloat("rage");
        p.hits = t.getInt("hits"); p.trapsSprung = t.getInt("traps"); p.deaths = t.getInt("deaths"); p.chases = t.getInt("chases");
        if (t.contains("base")) p.base = BlockPos.of(t.getLong("base"));
        p.raidPending = t.getBoolean("raid"); p.raidCooldownUntil = t.getLong("raidCd");
        p.basePresenceTicks = t.getInt("basePresence");
        ListTag list = t.getList("heat", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            p.heat.put(c.getLong("k"), c.getInt("v"));
            if (c.contains("p")) p.heatPos.put(c.getLong("k"), c.getLong("p"));
        }
        return p;
    }

    public String describe() {
        return String.format("RAGE=%.1f vigilance=%.2f panic=%.2f aggression=%.2f camping=%.2f sneaky=%.2f sprint=%.2f | hits=%d traps=%d chases=%d deaths=%d",
                rage, vigilance, panic, aggression, camping, sneakiness, sprintiness, hits, trapsSprung, chases, deaths);
    }
}
