package com.manbear.entity;

import com.manbear.ManbearConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * A dead animal the Manbear left behind. It is only a marker that remembers WHICH animal it was; the client draws that
 * animal's real model lying on its side (see CorpseRenderer). It rots away after {@code corpseMinutes}.
 */
public class CorpseEntity extends Entity {
    private static final EntityDataAccessor<String> DATA_KIND = SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.STRING);

    private int age;
    private boolean permanent;

    public CorpseEntity(EntityType<? extends CorpseEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setInvulnerable(true);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_KIND, "minecraft:cow");
    }

    /** registry name of the animal, e.g. "minecraft:pig" */
    public String kind() { return this.entityData.get(DATA_KIND); }
    public void setKind(String id) { this.entityData.set(DATA_KIND, id); }
    public void setPermanent(boolean permanent) { this.permanent = permanent; }
    public boolean isPermanent() { return permanent; }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && !permanent && ++age > ManbearConfig.CORPSE_MINUTES.get() * 1200) this.discard();
    }

    @Override
    public boolean isPickable() { return false; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Kind")) setKind(tag.getString("Kind"));
        age = tag.getInt("Age");
        permanent = tag.getBoolean("Permanent");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("Kind", kind());
        tag.putInt("Age", age);
        tag.putBoolean("Permanent", permanent);
    }
}
