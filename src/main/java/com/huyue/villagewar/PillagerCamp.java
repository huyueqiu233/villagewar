package com.huyue.villagewar;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 掠夺者营地（M3 第二版）：村庄阵营的对手。
 *
 * <p>营地固定在世界出生点南边 1500 格，等级 0~5，每 20 分钟涨一级。
 * 等级越高，来打村庄的波次越狠。营地中心插着一面战旗（核心），
 * 玩家把它打掉 = 端掉营地，等级清零、安静一段时间再慢慢重建。</p>
 */
public final class PillagerCamp {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = CampData.class;

    public static final int OFFSET_Z = 1500;
    public static final int MAX_LEVEL = 5;
    private static final long GROW_INTERVAL_MS = 20 * 60 * 1000L;
    private static final long REBUILD_COOLDOWN_MS = 10 * 60 * 1000L;

    private static CampData data = new CampData();
    private static boolean loaded = false;

    private PillagerCamp() {
    }

    /** 存档内容。 */
    public static final class CampData {
        public int level = 0;
        public long lastGrowAt = 0;
        public long destroyedAt = 0;
        public int coreX = Integer.MIN_VALUE;
        public int coreY = 0;
        public int coreZ = 0;
        public boolean built = false;
    }

    private static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("villagewar_camp.json");
    }

    public static void load(MinecraftServer server) {
        Path p = path(server);
        data = new CampData();
        if (Files.exists(p)) {
            try {
                CampData parsed = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), TYPE);
                if (parsed != null) {
                    data = parsed;
                }
            } catch (Exception ignored) {
            }
        }
        loaded = true;
    }

    public static void save(MinecraftServer server) {
        try {
            Files.writeString(path(server), GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public static int level() {
        return data.level;
    }

    public static BlockPos core() {
        return data.coreX == Integer.MIN_VALUE ? null : new BlockPos(data.coreX, data.coreY, data.coreZ);
    }

    public static BlockPos campPos(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX();
        int z = spawn.getZ() + OFFSET_Z;
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    public static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        ensureBuilt(event.getServer());
    }

    public static void onBlockBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server != null && onCoreBroken(server, event.getPos())) {
            event.getPlayer().displayClientMessage(
                    net.minecraft.network.chat.Component.literal("掠夺者营地被端掉了！他们短时间内不会再重建"), false);
        }
    }

    public static void ensureBuilt(MinecraftServer server) {
        if (!loaded) {
            load(server);
        }
        ServerLevel level = server.overworld();
        if (data.built && core() != null) {
            return;
        }
        BlockPos center = campPos(level);
        build(level, center);
        data.built = true;
        data.coreX = center.getX();
        data.coreY = center.getY() + 2;
        data.coreZ = center.getZ();
        data.lastGrowAt = System.currentTimeMillis();
        save(server);
        LOGGER.info("[villagewar] 掠夺者营地已建成 {}（等级 {}）", center, data.level);
    }

    private static void build(ServerLevel level, BlockPos center) {
        // 清场 + 地基
        for (int dx = -14; dx <= 14; dx++) {
            for (int dz = -14; dz <= 14; dz++) {
                level.setBlockAndUpdate(center.offset(dx, -1, dz), Blocks.COARSE_DIRT.defaultBlockState());
                for (int dy = 0; dy <= 6; dy++) {
                    level.setBlockAndUpdate(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        // 木栅栏围墙
        for (int dx = -14; dx <= 14; dx++) {
            for (int dz = -14; dz <= 14; dz++) {
                boolean edge = Math.abs(dx) == 14 || Math.abs(dz) == 14;
                if (edge && (Math.abs(dx) + Math.abs(dz)) % 7 != 0) {
                    level.setBlockAndUpdate(center.offset(dx, 0, dz), Blocks.DARK_OAK_FENCE.defaultBlockState());
                    level.setBlockAndUpdate(center.offset(dx, 1, dz), Blocks.DARK_OAK_FENCE.defaultBlockState());
                }
            }
        }
        // 四座帐篷（羊毛）
        for (int[] t : new int[][]{{-8, -8}, {8, -8}, {-8, 8}, {8, 8}}) {
            BlockPos base = center.offset(t[0], 0, t[1]);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    level.setBlockAndUpdate(base.offset(dx, 0, dz), Blocks.BLACK_WOOL.defaultBlockState());
                }
            }
            for (int dy = 1; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    level.setBlockAndUpdate(base.offset(dx, dy, -2), Blocks.BLACK_WOOL.defaultBlockState());
                    level.setBlockAndUpdate(base.offset(dx, dy, 2), Blocks.BLACK_WOOL.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(base.above(), Blocks.CAMPFIRE.defaultBlockState());
        }
        // 笼子
        for (int[] c : new int[][]{{-4, 6}, {4, 6}}) {
            BlockPos base = center.offset(c[0], 0, c[1]);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    level.setBlockAndUpdate(base.offset(dx, 0, dz), Blocks.IRON_BARS.defaultBlockState());
                    level.setBlockAndUpdate(base.offset(dx, 1, dz), Blocks.IRON_BARS.defaultBlockState());
                }
            }
        }
        // 中心石台 + 战旗（核心）
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(center.offset(dx, 0, dz), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(center.offset(0, 1, 0), Blocks.STONE_BRICK_WALL.defaultBlockState());
        level.setBlockAndUpdate(center.offset(0, 2, 0), Blocks.MAGENTA_BANNER.defaultBlockState());
        // 周围插火把
        for (int[] p : new int[][]{{-10, 0}, {10, 0}, {0, -10}, {0, 10}}) {
            level.setBlockAndUpdate(center.offset(p[0], 1, p[1]), Blocks.TORCH.defaultBlockState());
        }
    }

    /** 玩家打掉战旗：营地瘫痪。返回是否真的拆掉了。 */
    public static boolean onCoreBroken(MinecraftServer server, BlockPos pos) {
        BlockPos core = core();
        if (core == null || !core.equals(pos)) {
            return false;
        }
        data.level = 0;
        data.built = false;
        data.destroyedAt = System.currentTimeMillis();
        data.coreX = Integer.MIN_VALUE;
        save(server);
        LOGGER.info("[villagewar] 掠夺者营地被端掉 {}", pos);
        return true;
    }

    /** 每 tick 调用；内部自己节流：每 20 分钟涨一级，被拆后 10 分钟内不重建。 */
    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (!data.built) {
            if (now - data.destroyedAt > REBUILD_COOLDOWN_MS) {
                ensureBuilt(server);
            }
            return;
        }
        if (data.level >= MAX_LEVEL) {
            return;
        }
        if (now - data.lastGrowAt >= GROW_INTERVAL_MS) {
            data.level++;
            data.lastGrowAt = now;
            save(server);
            LOGGER.info("[villagewar] 掠夺者营地升到 {} 级", data.level);
            ServerLevel level = server.overworld();
            BlockPos core = core();
            if (core != null) {
                for (var player : level.players()) {
                    if (player.distanceToSqr(core.getX(), core.getY(), core.getZ()) < 128 * 128) {
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal("掠夺者营地变强了（等级 " + data.level + "）"),
                                false);
                    }
                }
            }
        }
    }

    public static String describe() {
        BlockPos core = core();
        String where = core == null ? "已被摧毁" : ("战旗 " + core.getX() + "," + core.getY() + "," + core.getZ());
        return "掠夺者营地：等级 " + data.level + "/" + MAX_LEVEL + "，" + where;
    }
}
