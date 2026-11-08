package com.huyue.villagewar;

import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * M0 的核心：村庄会自己长。
 *
 * <p>每 10 秒扫一次玩家周围的村民：够 3 只就算一个村子，按村民数量攒成长点；
 * 攒够就在村子边上盖东西。现在有三种：小屋（带门和床）、长屋（两间房两张床）、农田。</p>
 *
 * <p>刻意做得很土：不碰原版村庄数据、不改村民 AI，先验证「世界自己在动」这件事。</p>
 */
public final class VillageGrowth {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int CHECK_INTERVAL_TICKS = 200; // 10 秒
    private static final int SAVE_INTERVAL_TICKS = 1200; // 1 分钟
    private static final int GROWTH_COST = 60;
    private static final int RADIUS = 48;
    private static final int MIN_VILLAGERS = 3;

    private static int tickCounter = 0;
    private static boolean loaded = false;

    private VillageGrowth() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!loaded) {
            GrowthStore.load(server);
            loaded = true;
        }

        tickCounter++;
        if (tickCounter % CHECK_INTERVAL_TICKS != 0) {
            if (tickCounter % SAVE_INTERVAL_TICKS == 0) {
                GrowthStore.saveIfDirty(server);
            }
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            if (level.players().isEmpty()) {
                continue;
            }
            for (ServerPlayer player : level.players()) {
                growAround(level, player);
            }
        }
        GrowthStore.saveIfDirty(server);
    }

    private static void growAround(ServerLevel level, ServerPlayer player) {
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class,
                player.getBoundingBox().inflate(RADIUS), Villager::isAlive);
        if (villagers.size() < MIN_VILLAGERS) {
            return;
        }

        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        for (Villager villager : villagers) {
            sx += villager.getX();
            sy += villager.getY();
            sz += villager.getZ();
        }
        BlockPos center = BlockPos.containing(sx / villagers.size(), sy / villagers.size(), sz / villagers.size());
        String key = keyOf(level, center);
        GrowthStore.Entry entry = GrowthStore.get(key);
        entry.lastX = center.getX();
        entry.lastZ = center.getZ();
        entry.dimension = level.dimension().location().toString();
        entry.points += Math.max(1, villagers.size() / 2);
        GrowthStore.markDirty();

        if (entry.points < GROWTH_COST) {
            return;
        }
        String built = tryBuild(level, center, entry.houses);
        if (built != null) {
            entry.points -= GROWTH_COST;
            entry.houses++;
            GrowthStore.markDirty();
            LOGGER.info("[villagewar] {} 在 {} 建了{}（第 {} 个）", key, center, built, entry.houses);
            announce(level, center, built, entry.houses);
        }
    }

    /** 村子按「维度 + 所在区块」归并，避免玩家走两步就多出一个新村。 */
    private static String keyOf(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "@" + (pos.getX() >> 4) + "," + (pos.getZ() >> 4);
    }

    private static void announce(ServerLevel level, BlockPos center, String what, int houses) {
        Component message = Component.literal("附近村子新添了" + what + "（第 " + houses + " 座）");
        for (ServerPlayer nearby : level.players()) {
            if (nearby.distanceToSqr(center.getX(), center.getY(), center.getZ()) < 96.0 * 96.0) {
                nearby.displayClientMessage(message, false);
            }
        }
        level.playSound(null, center, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.6F, 1.4F);
    }

    // ── 建造 ────────────────────────────────────────────────────────────────

    /**
     * 按已建数量轮换建筑类型：小屋 → 农田 → 长屋 → 小屋……
     * 加一点随机，避免每个村子长得一模一样。
     */
    private static String tryBuild(ServerLevel level, BlockPos center, int houses) {
        int base = (houses + level.getRandom().nextInt(2)) % 3;
        for (int i = 0; i < 3; i++) {
            int type = (base + i) % 3;
            BlockPos spot = findSpot(level, center, type);
            if (spot == null) {
                continue;
            }
            switch (type) {
                case 0 -> {
                    placeHut(level, spot);
                    return "一间小屋";
                }
                case 1 -> {
                    placeFarm(level, spot);
                    return "一块农田";
                }
                default -> {
                    placeLongHouse(level, spot);
                    return "一座长屋";
                }
            }
        }
        return null;
    }

    /** 在村子周围找一个能放下的位置；三种建筑尺寸不同。 */
    private static BlockPos findSpot(ServerLevel level, BlockPos center, int type) {
        int halfW = type == 2 ? 3 : (type == 1 ? 3 : 2);
        int halfD = type == 2 ? 3 : 2;
        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < 40; attempt++) {
            int dx = random.nextInt(45) - 22;
            int dz = random.nextInt(45) - 22;
            if (Math.abs(dx) < halfW + 2 && Math.abs(dz) < halfD + 2) {
                continue; // 别盖在村子正中间
            }
            int x = center.getX() + dx;
            int z = center.getZ() + dz;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos base = new BlockPos(x, y, z);
            if (areaIsClear(level, base, halfW, halfD, false)) {
                return base;
            }
        }
        return null;
    }

    /**
     * 地基范围：脚下必须是实心方块，上面 4 层必须是空气。
     * allowGround=true 用于农田（脚下本来就该是泥土/草方块，上面要有空间）。
     */
    private static boolean areaIsClear(Level level, BlockPos base, int halfW, int halfD, boolean allowGround) {
        for (int dx = -halfW; dx <= halfW; dx++) {
            for (int dz = -halfD; dz <= halfD; dz++) {
                BlockPos ground = base.offset(dx, -1, dz);
                if (!level.getBlockState(ground).canOcclude()) {
                    return false;
                }
                for (int dy = 0; dy <= 3; dy++) {
                    if (!level.getBlockState(base.offset(dx, dy, dz)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void placeHut(Level level, BlockPos base) {
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();

        // 地基
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.COBBLESTONE.defaultBlockState());
            }
        }

        // 墙：四角原木，中间木板；南面留门洞
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (dz == 2 && dx == 0 && dy <= 1) {
                        continue; // 门洞，稍后放门
                    }
                    level.setBlockAndUpdate(pos, corner ? log : planks);
                }
            }
        }

        // 窗
        level.setBlockAndUpdate(base.offset(-2, 1, 0), glass);
        level.setBlockAndUpdate(base.offset(2, 1, 0), glass);

        // 屋顶（两级，看着像个屋顶）
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(base.offset(dx, 3, dz), planks);
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlockAndUpdate(base.offset(dx, 4, dz), planks);
            }
        }

        placeDoor(level, base.offset(0, 0, 2), Direction.SOUTH);

        // 室内：床（脚 + 头）、工作台、木桶、灯笼
        placeBed(level, base.offset(-1, 0, 1), Direction.NORTH);
        level.setBlockAndUpdate(base.offset(1, 0, -1), Blocks.CRAFTING_TABLE.defaultBlockState());
        level.setBlockAndUpdate(base.offset(1, 0, 1), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(base.offset(1, 1, 1), Blocks.LANTERN.defaultBlockState());
    }

    /** 7×5 的长屋：两张床，两端各一个工作方块。 */
    private static void placeLongHouse(Level level, BlockPos base) {
        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();

        int halfW = 3;
        int halfD = 2;

        // 地基
        for (int dx = -halfW; dx <= halfW; dx++) {
            for (int dz = -halfD; dz <= halfD; dz++) {
                level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.COBBLESTONE.defaultBlockState());
            }
        }

        // 墙
        for (int dx = -halfW; dx <= halfW; dx++) {
            for (int dz = -halfD; dz <= halfD; dz++) {
                boolean edge = Math.abs(dx) == halfW || Math.abs(dz) == halfD;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(dx) == halfW && Math.abs(dz) == halfD;
                for (int dy = 0; dy <= 2; dy++) {
                    if (dz == halfD && Math.abs(dx) <= 1 && dy <= 1) {
                        continue; // 中间两格宽的门洞
                    }
                    level.setBlockAndUpdate(base.offset(dx, dy, dz), corner ? log : planks);
                }
            }
        }

        // 窗
        for (int dz = -1; dz <= 1; dz++) {
            level.setBlockAndUpdate(base.offset(-halfW, 1, dz), glass);
            level.setBlockAndUpdate(base.offset(halfW, 1, dz), glass);
        }

        // 屋顶
        for (int dx = -halfW; dx <= halfW; dx++) {
            for (int dz = -halfD; dz <= halfD; dz++) {
                level.setBlockAndUpdate(base.offset(dx, 3, dz), planks);
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlockAndUpdate(base.offset(dx, 4, dz), planks);
            }
        }

        // 两扇门（门洞宽 3 格，中间放一扇即可）
        placeDoor(level, base.offset(0, 0, halfD), Direction.SOUTH);

        // 两张床分列左右
        placeBed(level, base.offset(-2, 0, 0), Direction.NORTH);
        placeBed(level, base.offset(2, 0, 0), Direction.NORTH);

        level.setBlockAndUpdate(base.offset(-1, 0, -1), Blocks.CRAFTING_TABLE.defaultBlockState());
        level.setBlockAndUpdate(base.offset(1, 0, -1), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(base.offset(0, 1, 0), Blocks.LANTERN.defaultBlockState());
    }

    /** 7×7 农田：中间水源，四周耕地 + 成熟小麦，角落堆肥桶。 */
    private static void placeFarm(Level level, BlockPos base) {
        // 先把地面挖下去一格
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos p = base.offset(dx, -1, dz);
                if (dx == 0 && dz == 0) {
                    level.setBlockAndUpdate(p, Blocks.WATER.defaultBlockState());
                } else {
                    level.setBlockAndUpdate(p, Blocks.FARMLAND.defaultBlockState());
                    BlockState wheat = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
                    level.setBlockAndUpdate(p.above(), wheat);
                }
            }
        }
        level.setBlockAndUpdate(base.offset(-3, -1, -3), Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(base.offset(3, -1, 3), Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(base.offset(0, 2, 0), Blocks.LANTERN.defaultBlockState());
    }

    // ── 小构件 ──────────────────────────────────────────────────────────────

    private static void placeDoor(Level level, BlockPos pos, Direction facing) {
        level.setBlockAndUpdate(pos, Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        level.setBlockAndUpdate(pos.above(), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** 床：脚在 footPos，头朝 facing 方向放一格。 */
    private static void placeBed(Level level, BlockPos footPos, Direction facing) {
        level.setBlockAndUpdate(footPos, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, facing)
                .setValue(BedBlock.PART, BedPart.FOOT));
        level.setBlockAndUpdate(footPos.relative(facing), Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, facing)
                .setValue(BedBlock.PART, BedPart.HEAD));
    }
}
