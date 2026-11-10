package com.huyue.villagewar;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

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

    /** 首都中心（地表），会顺带把区块加载出来。 */
    public BlockPos capital(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + offsetX;
        int z = spawn.getZ() + offsetZ;
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }
}
