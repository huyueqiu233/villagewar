package com.huyue.villagewar;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 势力定义（M3 前置）：现在先两个。
 *
 * <p>首都坐标 = 世界出生点 + 固定偏移，两个势力隔 2400 格，
 * 玩家选完势力直接出生在自己首都附近。</p>
 */
public enum Faction {
    CRIMSON("crimson", "赤潮", ChatFormatting.RED, 0xC0392B, 1200, 0),
    AZURE("azure", "苍蓝", ChatFormatting.AQUA, 0x2E86DE, -1200, 0);

    public final String id;
    public final String display;
    public final ChatFormatting color;
    public final int rgb;
    public final int offsetX;
    public final int offsetZ;
    /** 主势力范围半径（单人的时候每个势力就一个圈子）。 */
    public static final int TERRITORY_RADIUS = 320;

    Faction(String id, String display, ChatFormatting color, int rgb, int offsetX, int offsetZ) {
        this.id = id;
        this.display = display;
        this.color = color;
        this.rgb = rgb;
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
    }

    public static Faction byId(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        for (Faction f : values()) {
            if (f.id.equals(s) || f.display.equals(raw)) {
                return f;
            }
        }
        return null;
    }

    /** 这个坐标归哪个势力（主势力范围内最近的；都不在范围里就是 null）。 */
    public static Faction ownerOf(ServerLevel level, BlockPos pos) {
        Faction best = null;
        double bestSq = (double) TERRITORY_RADIUS * TERRITORY_RADIUS;
        for (Faction faction : values()) {
            BlockPos capital = faction.capital(level);
            double dx = capital.getX() - pos.getX();
            double dz = capital.getZ() - pos.getZ();
            double sq = dx * dx + dz * dz;
            if (sq <= bestSq) {
                bestSq = sq;
                best = faction;
            }
        }
        return best;
    }

    /** 首都坐标缓存：ownerOf 会被高频调用，不能每次都去扫地表。 */
    private static final Map<Faction, BlockPos> CAPITAL_CACHE = new EnumMap<>(Faction.class);

    public static void clearCapitalCache() {
        CAPITAL_CACHE.clear();
    }

    /**
     * 首都中心（地表）。
     *
     * <p>固定偏移点可能落在海里——在海上盖首都会变成一座孤岛，村民无处工作、村子也长不起来。
     * 所以从偏移点向外一圈圈找**陆地**（非水、非海洋/河流/沙滩生态）。</p>
     */
    public BlockPos capital(ServerLevel level) {
        BlockPos cached = CAPITAL_CACHE.get(this);
        if (cached != null) {
            return cached;
        }
        BlockPos spawn = level.getSharedSpawnPos();
        int baseX = spawn.getX() + offsetX;
        int baseZ = spawn.getZ() + offsetZ;
        BlockPos found = findLand(level, baseX, baseZ);
        if (found == null) {
            level.getChunk(baseX >> 4, baseZ >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, baseX, baseZ);
            found = new BlockPos(baseX, y, baseZ);
        }
        CAPITAL_CACHE.put(this, found);
        return found;
    }

    private static BlockPos findLand(ServerLevel level, int cx, int cz) {
        for (int r = 0; r <= 512; r += 24) {
            for (int dx = -r; dx <= r; dx += 24) {
                for (int dz = -r; dz <= r; dz += 24) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = cx + dx;
                    int z = cz + dz;
                    level.getChunk(x >> 4, z >> 4);
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (y <= level.getMinBuildHeight() + 4) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState below = level.getBlockState(pos.below());
                    if (!below.getFluidState().isEmpty()) {
                        continue;
                    }
                    var biome = level.getBiome(pos);
                    if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH)) {
                        continue;
                    }
                    return pos;
                }
            }
        }
        return null;
    }
}
