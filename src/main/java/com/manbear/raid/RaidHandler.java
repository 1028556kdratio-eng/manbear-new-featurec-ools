package com.manbear.raid;

import com.manbear.ai.CoverFinder;
import com.manbear.block.FootprintBlock;
import com.manbear.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.common.Tags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Something rummaged through your house": a door left open, chests broken with their loot spilled on the floor,
 * windows and a few wall blocks smashed, giant footprints on the floor inside.
 * BEDS ARE NEVER TOUCHED (nor anything right next to one).
 */
public final class RaidHandler {
    private static final int RADIUS = 18;

    private RaidHandler() { }

    /** @return a short summary, or null if there was no house to rummage through */
    public static String rummage(ServerLevel level, BlockPos center, RandomSource rnd) {
        List<BlockPos> doors = new ArrayList<>();
        List<BlockPos> containers = new ArrayList<>();
        List<BlockPos> glass = new ArrayList<>();
        List<BlockPos> beds = new ArrayList<>();
        List<BlockPos> walls = new ArrayList<>();

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dy = -4; dy <= 8; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState st = level.getBlockState(pos);
                    if (st.isAir()) continue;
                    if (st.is(BlockTags.BEDS)) {
                        beds.add(pos);
                    } else if (st.getBlock() instanceof DoorBlock && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                        doors.add(pos);
                    } else if (st.is(Blocks.CHEST) || st.is(Blocks.TRAPPED_CHEST) || st.is(Blocks.BARREL)) {
                        containers.add(pos);
                    } else if (st.is(Tags.Blocks.GLASS) || st.is(Tags.Blocks.GLASS_PANES)) {
                        glass.add(pos);
                    } else if (isWallBlock(level, pos, st)) {
                        walls.add(pos);
                    }
                }
            }
        }
        if (doors.isEmpty() && containers.isEmpty()) return null;   // nothing that looks like a house

        // never go anywhere near a bed
        Set<BlockPos> safe = new HashSet<>();
        for (BlockPos b : beds) {
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) safe.add(b.offset(x, y, z));
        }

        int nDoors = 0;
        int nChests = 0;
        int nGlass = 0;
        int nBlocks = 0;
        int nPrints = 0;

        // --- doors: swing open (the one closest to the middle first)
        doors.sort((a, b) -> Double.compare(a.distSqr(center), b.distSqr(center)));
        BlockPos mainDoor = doors.isEmpty() ? null : doors.get(0);
        for (BlockPos d : doors) {
            if (nDoors >= 2) break;
            BlockState st = level.getBlockState(d);
            if (st.getBlock() instanceof DoorBlock door && !st.getValue(DoorBlock.OPEN)) {
                door.setOpen(null, level, st, d, true);
                nDoors++;
            }
        }

        // --- chests / barrels: loot spilled all over the floor (set not to despawn), container destroyed
        Collections.shuffle(containers, new java.util.Random(rnd.nextLong()));
        int wantChests = containers.isEmpty() ? 0 : Math.max(1, (int) Math.ceil(containers.size() * 0.6));
        List<BlockPos> broken = new ArrayList<>();
        for (BlockPos c : containers) {
            if (nChests >= wantChests) break;
            if (safe.contains(c)) continue;
            spill(level, c, rnd);
            level.destroyBlock(c, false);
            broken.add(c);
            nChests++;
        }

        // --- windows
        for (BlockPos g : glass) {
            if (nGlass >= 14) break;
            if (safe.contains(g) || rnd.nextFloat() > 0.55F) continue;
            level.destroyBlock(g, false);
            nGlass++;
        }

        // --- a few wall blocks knocked out
        Collections.shuffle(walls, new java.util.Random(rnd.nextLong()));
        int wantBlocks = 3 + rnd.nextInt(5);
        for (BlockPos w : walls) {
            if (nBlocks >= wantBlocks) break;
            if (safe.contains(w)) continue;
            level.destroyBlock(w, false);
            nBlocks++;
        }

        // --- giant footprints inside: from the door in, then around the broken chests
        BlockPos start = mainDoor == null ? null : insideNeighbor(level, mainDoor);
        if (start == null && !broken.isEmpty()) start = insideNeighbor(level, broken.get(0));
        if (start != null) nPrints += trail(level, start, 12, rnd, safe);
        for (BlockPos c : broken) {
            BlockPos in = insideNeighbor(level, c);
            if (in != null) nPrints += trail(level, in, 3, rnd, safe);
        }
        return "doors=" + nDoors + " chests=" + nChests + " windows=" + nGlass + " blocks=" + nBlocks + " footprints=" + nPrints;
    }

    private static boolean indoor(ServerLevel level, BlockPos p) {
        return CoverFinder.standable(level, p, 2) && !level.canSeeSky(p);
    }

    /** a solid, cheap, non-fancy block with indoor air right beside it (so the damage is visible from inside) */
    private static boolean isWallBlock(ServerLevel level, BlockPos pos, BlockState st) {
        if (st.hasBlockEntity() || st.is(BlockTags.BEDS) || st.is(BlockTags.DOORS) || !st.isSolidRender(level, pos)) return false;
        float h = st.getDestroySpeed(level, pos);
        if (h < 0 || h > 6.0F) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (indoor(level, pos.relative(d))) return true;
        }
        return false;
    }

    private static BlockPos insideNeighbor(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            if (indoor(level, n)) return n;
        }
        return null;
    }

    /** dumps the contents on the floor; items never despawn, so they are still there when you get home */
    private static void spill(ServerLevel level, BlockPos pos, RandomSource rnd) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof Container container)) return;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack st = container.removeItemNoUpdate(i);
            if (st.isEmpty()) continue;
            ItemEntity ie = new ItemEntity(level, pos.getX() + 0.5 + (rnd.nextDouble() - 0.5) * 0.6, pos.getY() + 0.6,
                    pos.getZ() + 0.5 + (rnd.nextDouble() - 0.5) * 0.6, st);
            ie.setDeltaMovement((rnd.nextDouble() - 0.5) * 0.35, 0.2 + rnd.nextDouble() * 0.15, (rnd.nextDouble() - 0.5) * 0.35);
            ie.setUnlimitedLifetime();
            level.addFreshEntity(ie);
        }
    }

    /** a wandering line of giant prints over indoor floor */
    private static int trail(ServerLevel level, BlockPos start, int steps, RandomSource rnd, Set<BlockPos> safe) {
        int placed = 0;
        BlockPos cur = start;
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(rnd);
        for (int i = 0; i < steps; i++) {
            if (!safe.contains(cur) && level.getBlockState(cur).isAir()) {
                BlockState print = ModBlocks.FOOTPRINT.get().defaultBlockState().setValue(FootprintBlock.FACING, dir);
                if (print.canSurvive(level, cur)) {
                    level.setBlock(cur, print, 3);
                    placed++;
                }
            }
            Direction next = rnd.nextFloat() < 0.7F ? dir : Direction.Plane.HORIZONTAL.getRandomDirection(rnd);
            BlockPos n = cur.relative(next);
            if (!indoor(level, n)) {
                boolean moved = false;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    if (indoor(level, cur.relative(d))) { next = d; n = cur.relative(d); moved = true; break; }
                }
                if (!moved) break;
            }
            dir = next;
            cur = n;
        }
        return placed;
    }
}
