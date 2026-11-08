package com.huyue.villagewar;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * M0 的核心：村庄会自己长。
 *
 * <p>每 10 秒扫一次玩家周围的村民：够 3 只就算一个村子，按村民数量攒成长点；
 * 攒够 60 点就在村子边上盖一间 5×5 的小屋，并在附近玩家的聊天栏提示一句。</p>
 *
 * <p>刻意做得很土：不碰原版村庄数据、不改村民 AI，先验证「世界自己在动」这件事。
 * 下一步才把「工坊标记 / 农田标记」那套循环接进来。</p>
 */
public final class VillageGrowth {
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
        if (buildHut(level, center)) {
            entry.points -= GROWTH_COST;
            entry.houses++;
            GrowthStore.markDirty();
            announce(level, center, entry.houses);
        }
    }

    /** 村子按「维度 + 所在区块」归并，避免玩家走两步就多出一个新村。 */
    private static String keyOf(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "@" + (pos.getX() >> 4) + "," + (pos.getZ() >> 4);
    }

    private static void announce(ServerLevel level, BlockPos center, int houses) {
        Component message = Component.literal("附近村庄扩建了一间房（共 " + houses + " 间）");
        for (ServerPlayer nearby : level.players()) {
            if (nearby.distanceToSqr(center.getX(), center.getY(), center.getZ()) < 96.0 * 96.0) {
                nearby.displayClientMessage(message, false);
            }
        }
        level.playSound(null, center, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.6F, 1.4F);
    }

    private static boolean buildHut(ServerLevel level, BlockPos center) {
        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < 32; attempt++) {
            int dx = random.nextInt(41) - 20;
            int dz = random.nextInt(41) - 20;
            if (Math.abs(dx) < 7 && Math.abs(dz) < 7) {
                continue; // 别盖在村子正中间
            }
            int x = center.getX() + dx;
            int z = center.getZ() + dz;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos base = new BlockPos(x, y, z);
            if (!areaIsClear(level, base)) {
                continue;
            }
            placeHut(level, base);
            return true;
        }
        return false;
    }

    /** 5×5 的地基范围：脚下必须是实心方块，上面 4 层必须是空气。 */
    private static boolean areaIsClear(Level level, BlockPos base) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos ground = base.offset(dx, -1, dz);
                BlockState below = level.getBlockState(ground);
                if (!below.canOcclude()) {
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

        // 墙：四角原木，中间木板，南面留门
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
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                        continue;
                    }
                    level.setBlockAndUpdate(pos, corner ? log : planks);
                }
            }
        }

        // 窗
        level.setBlockAndUpdate(base.offset(-2, 1, 0), glass);
        level.setBlockAndUpdate(base.offset(2, 1, 0), glass);

        // 屋顶
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(base.offset(dx, 3, dz), planks);
            }
        }

        // 室内：工作台、木桶、火把 —— 让她看得出这是「工坊」
        level.setBlockAndUpdate(base.offset(0, 0, -1), Blocks.CRAFTING_TABLE.defaultBlockState());
        level.setBlockAndUpdate(base.offset(-1, 0, -1), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(base.offset(1, 0, -1), Blocks.TORCH.defaultBlockState());
    }
}
