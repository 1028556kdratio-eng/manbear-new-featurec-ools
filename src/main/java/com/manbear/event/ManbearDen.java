package com.manbear.event;

import com.manbear.ManbearConfig;
import com.manbear.data.ManbearData;
import com.manbear.data.PlayerProfile;
import com.manbear.entity.CorpseEntity;
import com.manbear.entity.ManbearEntity;
import com.manbear.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/** Persistent one-per-world Manbear cave den, generated near a learned home area. */
public final class ManbearDen {
    private ManbearDen() { }

    private record Home(UUID owner, BlockPos base, PlayerProfile profile) { }

    public static void tick(MinecraftServer server, long now) {
        if (server == null || !ManbearConfig.DEN_ENABLED.get()) return;
        ManbearData data = ManbearData.get(server);

        if (data.denCooldownUntil() > 0L) {
            if (now < data.denCooldownUntil()) return;
            data.completeDenCooldown(); // fills the responsible player's rage to 100 once
        }

        if (data.hasDen()) {
            if (now % 100L == 0L) {
                // Older versions sampled the top of a tree canopy as the "surface", leaving the den floating
                // in the forest. Repair those saved dens once their region is loaded, then generate a proper one.
                if (repairMisplacedDen(server, data)) return;
                inspectDen(server, now, data);
            }
            return;
        }
        if (now % 100L != 0L) return;

        Home home = findHome(server, data);
        if (home == null) return;
        if (!data.denUnlocked() && home.profile().basePresenceTicks < ManbearConfig.DEN_HOME_TIME_MINUTES.get() * 1200) return;

        ServerLevel level = server.overworld();
        BlockPos generated = tryGenerate(level, home.base(), level.random);
        if (generated == null) return;
        int surfaceY = generated.getY();
        BlockPos center = new BlockPos(generated.getX(), surfaceY - 9, generated.getZ());
        BlockPos entrance = new BlockPos(generated.getX() + 18, surfaceY - 2, generated.getZ());
        build(level, center, surfaceY, level.random);
        data.setDen(level.dimension().location().toString(), center, entrance, home.owner());
    }

    private static Home findHome(MinecraftServer server, ManbearData data) {
        // Once the den has existed, rebuild around its original owner's learned home after the lockout.
        if (data.denUnlocked() && data.denOwner() != null) {
            PlayerProfile profile = data.profile(data.denOwner());
            if (profile.base != null) return new Home(data.denOwner(), profile.base, profile);
        }
        Home best = null;
        int bestPresence = -1;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator() || player.isCreative() || !player.isAlive() || player.serverLevel() != server.overworld()) continue;
            PlayerProfile profile = data.profile(player.getUUID());
            if (profile.base == null) continue;
            if (profile.basePresenceTicks > bestPresence) {
                bestPresence = profile.basePresenceTicks;
                best = new Home(player.getUUID(), profile.base, profile);
            }
        }
        return best;
    }

    /** Returns the chosen base-relative X/Z at its surface Y, or null until a safe loaded site is available. */
    private static BlockPos tryGenerate(ServerLevel level, BlockPos base, RandomSource random) {
        // Keep the full den footprint inside the configured base-distance band:
        // the structure extends roughly 20 blocks beyond its centre in either direction.
        int min = ManbearConfig.DEN_MIN_BASE_DISTANCE.get() + 25;
        int max = ManbearConfig.DEN_MAX_BASE_DISTANCE.get() - 20;
        if (max < min) return null;
        for (int attempt = 0; attempt < 100; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = min + random.nextDouble() * (max - min);
            int x = base.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = base.getZ() + (int) Math.round(Math.sin(angle) * radius);
            BlockPos column = new BlockPos(x, base.getY(), z);
            BlockPos minPos = new BlockPos(x - 20, Math.max(level.getMinBuildHeight() + 8, base.getY() - 30), z - 20);
            BlockPos maxPos = new BlockPos(x + 20, Math.min(level.getMaxBuildHeight() - 2, base.getY() + 20), z + 20);
            if (!level.getWorldBorder().isWithinBounds(column) || !level.hasChunksAt(minPos, maxPos)) continue;

            // MOTION_BLOCKING_NO_LEAVES still treats tree trunks as terrain. Scanning down from WORLD_SURFACE
            // and explicitly skipping leaves/logs finds the actual ground below forest canopies.
            int surface = findNaturalSurface(level, x, z);
            if (surface == Integer.MIN_VALUE || surface < level.getMinBuildHeight() + 30
                    || surface > level.getMaxBuildHeight() - 20) continue;
            BlockPos ground = new BlockPos(x, surface - 1, z);
            if (!level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) continue;
            if (!level.getFluidState(ground).isEmpty()) continue;

            double baseDistance = Math.sqrt(base.distSqr(new BlockPos(x, base.getY(), z)));
            if (baseDistance < ManbearConfig.DEN_MIN_BASE_DISTANCE.get() + 20.0
                    || baseDistance > ManbearConfig.DEN_MAX_BASE_DISTANCE.get() - 20.0) continue;
            if (!dryCaveVolume(level, x, z, surface)) continue;
            if (!hasBuriedChamberRoof(level, x, z, surface)) continue;
            int entranceSurface = findNaturalSurface(level, x + 18, z);
            if (entranceSurface == Integer.MIN_VALUE || Math.abs(entranceSurface - surface) > 2) continue;
            return new BlockPos(x, surface, z);
        }
        return null;
    }

    /** Returns the first air block above natural ground, ignoring foliage and trunks; MIN_VALUE means no ground found. */
    private static int findNaturalSurface(ServerLevel level, int x, int z) {
        int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), level.getMaxBuildHeight() - 1);
        int minY = Math.max(level.getMinBuildHeight() + 20, top - 128);
        for (int y = top - 1; y >= minY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            var state = level.getBlockState(pos);
            if (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
                    || isDenDecoration(state) || !level.getFluidState(pos).isEmpty()) continue;
            if (!state.canOcclude() || !state.isFaceSturdy(level, pos, Direction.UP)) continue;
            return y + 1;
        }
        return Integer.MIN_VALUE;
    }

    /** Blocks used as den decorations; ignore these when reconstructing the real terrain surface on legacy saves. */
    private static boolean isDenDecoration(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.BONE_BLOCK) || state.is(Blocks.COBWEB)
                || state.is(Blocks.CHAIN) || state.is(Blocks.SOUL_LANTERN);
    }

    /** Require a solid underground roof above the main room, rejecting hillsides and open-air/canopy sites. */
    private static boolean hasBuriedChamberRoof(ServerLevel level, int cx, int cz, int surface) {
        int roofY = surface - 7;
        for (int dx = -6; dx <= 6; dx += 3) {
            for (int dz = -6; dz <= 6; dz += 3) {
                BlockPos roof = new BlockPos(cx + dx, roofY, cz + dz);
                var state = level.getBlockState(roof);
                if (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
                        || !level.getFluidState(roof).isEmpty()
                        || !state.isFaceSturdy(level, roof, Direction.DOWN)) return false;
            }
        }
        return true;
    }

    /** Detect and migrate dens saved by the old tree-canopy heightmap bug. */
    private static boolean repairMisplacedDen(MinecraftServer server, ManbearData data) {
        BlockPos center = data.denCenter();
        if (center == null) return false;
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                new net.minecraft.resources.ResourceLocation(data.denDimension())));
        if (level == null) return false;

        int savedSurface = center.getY() + 9;
        int actualSurface = findNaturalSurface(level, center.getX(), center.getZ());
        if (actualSurface == Integer.MIN_VALUE || Math.abs(actualSurface - savedSurface) <= 4) return false;

        BlockPos from = new BlockPos(center.getX() - 20, savedSurface - 14, center.getZ() - 20);
        BlockPos to = new BlockPos(center.getX() + 20, savedSurface + 2, center.getZ() + 20);
        if (!level.hasChunksAt(from, to)) return false; // wait until cleanup can be done without loading/generating chunks

        // Remove den decorations and permanent remains that were stranded above the real ground by older versions.
        for (int x = from.getX(); x <= to.getX(); x++) {
            for (int z = from.getZ(); z <= to.getZ(); z++) {
                int localSurface = findNaturalSurface(level, x, z);
                if (localSurface == Integer.MIN_VALUE) continue;
                for (int y = Math.max(from.getY(), localSurface); y <= to.getY(); y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    var state = level.getBlockState(p);
                    if (isDenDecoration(state)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        net.minecraft.world.phys.AABB oldArea = new net.minecraft.world.phys.AABB(
                from.getX(), from.getY(), from.getZ(), to.getX() + 1.0, to.getY() + 1.0, to.getZ() + 1.0);
        for (CorpseEntity corpse : level.getEntitiesOfClass(CorpseEntity.class, oldArea)) {
            if (corpse.isPermanent()) {
                int groundSurface = findNaturalSurface(level, corpse.blockPosition().getX(), corpse.blockPosition().getZ());
                if (groundSurface != Integer.MIN_VALUE && corpse.getY() >= groundSurface) corpse.discard();
            }
        }
        data.relocateDenForRegeneration();
        return true;
    }

    private static boolean dryCaveVolume(ServerLevel level, int cx, int cz, int surface) {
        // The chamber and the descending entrance may not intersect water or lava.
        for (int x = cx - 8; x <= cx + 20; x++) {
            for (int z = cz - 8; z <= cz + 8; z++) {
                for (int y = surface - 14; y <= surface + 1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!level.getFluidState(p).isEmpty()) return false;
                }
            }
        }
        for (int x = cx - 15; x <= cx + 8; x++) {
            for (int z = cz - 15; z <= cz + 8; z++) {
                for (int y = surface - 13; y <= surface - 5; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!level.getFluidState(p).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    private static void build(ServerLevel level, BlockPos center, int surface, RandomSource random) {
        int cx = center.getX(), cz = center.getZ();
        // Large, roughly rounded main chamber. Leave its floor, roof and walls intact.
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                double ellipse = (dx * dx) / 58.0 + (dz * dz) / 58.0;
                if (ellipse > 1.18) continue;
                for (int dy = -11; dy <= -6; dy++) carve(level, new BlockPos(cx + dx, surface + dy, cz + dz));
            }
        }

        // Main sloping entrance tunnel (to the east) from the chamber to the surface.
        for (int i = 5; i <= 19; i++) {
            int floorY = surface - 12 + Math.round((i - 5) * 10.0F / 14.0F);
            for (int side = -1; side <= 1; side++) {
                for (int up = 1; up <= 3; up++) carve(level, new BlockPos(cx + i, floorY + up, cz + side));
            }
        }
        // Two branching side tunnels so the den has more than one passage.
        for (int z = 6; z >= -13; z--) {
            for (int xOff = -2; xOff <= 0; xOff++) {
                for (int up = -1; up <= 3; up++) carve(level, new BlockPos(cx + xOff, surface - 10 + up, cz + z));
            }
        }
        for (int x = -6; x >= -13; x--) {
            for (int zOff = -4; zOff <= -2; zOff++) {
                for (int up = -1; up <= 3; up++) carve(level, new BlockPos(cx + x, surface - 10 + up, cz + zOff));
            }
        }
        // A small feeding alcove at the end of the western branch.
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (dx * dx + dz * dz > 6) continue;
            for (int dy = -10; dy <= -7; dy++) carve(level, new BlockPos(cx - 14 + dx, surface + dy, cz - 3 + dz));
        }

        // Scatter a few rough stone patches and bone piles around the room's floor.
        BlockPos floorCenter = new BlockPos(cx, surface - 12, cz);
        int[][] stones = {{-5,-4},{-4,3},{2,5},{5,1},{-2,5},{4,-4},{-5,2}};
        for (int[] o : stones) {
            BlockPos p = floorCenter.offset(o[0], 0, o[1]);
            if (random.nextBoolean()) level.setBlock(p, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
            else level.setBlock(p, Blocks.COBBLESTONE.defaultBlockState(), 2);
        }
        level.setBlock(floorCenter.offset(-2, 1, -2), Blocks.BONE_BLOCK.defaultBlockState(), 2);
        level.setBlock(floorCenter.offset(-1, 1, -2), Blocks.BONE_BLOCK.defaultBlockState(), 2);
        level.setBlock(floorCenter.offset(-2, 1, -1), Blocks.BONE_BLOCK.defaultBlockState(), 2);
        for (BlockPos p : List.of(
                new BlockPos(cx - 5, surface - 8, cz - 5), new BlockPos(cx + 5, surface - 8, cz - 5),
                new BlockPos(cx - 5, surface - 8, cz + 5), new BlockPos(cx + 5, surface - 8, cz + 5))) {
            if (level.getBlockState(p).isAir()) level.setBlock(p, Blocks.COBWEB.defaultBlockState(), 2);
        }
        // A few dim soul lanterns, hanging from the roof.
        for (int[] o : new int[][]{{-4,-4},{4,-4},{-4,4},{4,4}}) {
            BlockPos chain = new BlockPos(cx + o[0], surface - 6, cz + o[1]);
            BlockPos lantern = chain.below();
            level.setBlock(chain, Blocks.CHAIN.defaultBlockState(), 2);
            level.setBlock(lantern, Blocks.SOUL_LANTERN.defaultBlockState(), 2);
        }

        // Permanent animal remains: the cave is a den, not a temporary evidence event.
        String[] kinds = {"minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken", "minecraft:rabbit"};
        for (int i = 0; i < 9; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = 1.5 + random.nextDouble() * 4.2;
            double x = cx + 0.5 + Math.cos(angle) * radius;
            double z = cz + 0.5 + Math.sin(angle) * radius;
            BlockPos feet = new BlockPos((int)Math.floor(x), surface - 11, (int)Math.floor(z));
            if (!level.getBlockState(feet).isAir()) feet = feet.above();
            CorpseEntity corpse = ModEntities.CORPSE.get().create(level);
            if (corpse == null) continue;
            corpse.setKind(kinds[random.nextInt(kinds.length)]);
            corpse.setPermanent(true);
            corpse.moveTo(x, feet.getY(), z, random.nextFloat() * 360.0F, 0.0F);
            level.addFreshEntity(corpse);
        }
    }

    private static void carve(ServerLevel level, BlockPos p) {
        if (p.getY() <= level.getMinBuildHeight() + 4 || p.getY() >= level.getMaxBuildHeight() - 1) return;
        if (!level.hasChunkAt(p)) return;
        if (!level.getFluidState(p).isEmpty()) return;
        if (level.getBlockState(p).is(Blocks.BEDROCK)) return;
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
    }

    public static boolean contains(ServerLevel level, BlockPos pos, ManbearData data) {
        if (!data.hasDen() || !data.denDimension().equals(level.dimension().location().toString())) return false;
        BlockPos center = data.denCenter();
        if (center == null) return false;
        int cx = center.getX(), cz = center.getZ();
        int surface = center.getY() + 9;
        int dx = pos.getX() - cx, dz = pos.getZ() - cz;
        int ry = pos.getY() - surface;
        // Main room including floor/roof/walls.
        if (Math.abs(dx) <= 8 && Math.abs(dz) <= 8 && ry >= -13 && ry <= -5) return true;
        // Sloped east entrance tunnel, including its floor and ceiling.
        if (dx >= 5 && dx <= 19 && Math.abs(dz) <= 2) {
            int floorY = -12 + Math.round((dx - 5) * 10.0F / 14.0F);
            if (ry >= floorY - 1 && ry <= floorY + 4) return true;
        }
        // North and west branch tunnels and the feeding alcove.
        if (dx >= -4 && dx <= 0 && dz >= -13 && dz <= 7 && ry >= -12 && ry <= -6) return true;
        if (dx >= -13 && dx <= -5 && dz >= -4 && dz <= -2 && ry >= -12 && ry <= -6) return true;
        return dx >= -17 && dx <= -11 && dz >= -6 && dz <= 0 && ry >= -11 && ry <= -6;
    }

    public static void onBlockBroken(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (player == null || player.getServer() == null) return;
        ManbearData data = ManbearData.get(player.getServer());
        if (contains(level, pos, data)) destroy(player.getServer(), level, player.getUUID(), "a block was broken inside the den");
    }

    public static void onBlockPlaced(ServerLevel level, BlockPos pos, UUID actor) {
        MinecraftServer server = level.getServer();
        if (server == null) return;
        ManbearData data = ManbearData.get(server);
        if (contains(level, pos, data)) destroy(server, level, actor, "a block was placed inside the den");
    }

    private static void inspectDen(MinecraftServer server, long now, ManbearData data) {
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                new net.minecraft.resources.ResourceLocation(data.denDimension())));
        BlockPos center = data.denCenter();
        if (level == null || center == null) return;
        int surface = center.getY() + 9;
        BlockPos from = new BlockPos(center.getX() - 20, surface - 14, center.getZ() - 20);
        BlockPos to = new BlockPos(center.getX() + 20, surface + 2, center.getZ() + 20);
        if (!level.hasChunksAt(from, to)) return; // inspect when the whole den region is loaded

        for (int x = from.getX(); x <= to.getX(); x++) {
            for (int y = from.getY(); y <= to.getY(); y++) {
                for (int z = from.getZ(); z <= to.getZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!contains(level, p, data)) continue;
                    var state = level.getBlockState(p);
                    if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || level.getFluidState(p).is(FluidTags.LAVA)) {
                        destroy(server, level, null, "fire reached the den");
                        return;
                    }
                }
            }
        }

        AABB area = new AABB(from.getX(), from.getY(), from.getZ(), to.getX() + 1.0, to.getY() + 1.0, to.getZ() + 1.0);
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, area,
                mob -> !(mob instanceof ManbearEntity) && mob.isAlive() && contains(level, mob.blockPosition(), data));
        if (mobs.size() >= ManbearConfig.DEN_MOB_DESTROY_THRESHOLD.get()) {
            destroy(server, level, null, "the den was filled with mobs");
        }
    }

    private static void destroy(MinecraftServer server, ServerLevel level, UUID actor, String reason) {
        ManbearData data = ManbearData.get(server);
        if (!data.hasDen()) return;
        long cooldown = ManbearConfig.DEN_DESTROYED_COOLDOWN_MINUTES.get() * 1200L;
        data.destroyDen(server.overworld().getGameTime() + cooldown, actor);
        ManbearEntity active = ServerEvents.findActive(server);
        if (active != null && active.level() instanceof ServerLevel activeLevel) active.ai().beginLeaving(activeLevel);
        // No public announcement: discovery of the consequences is part of the encounter.
    }
}
