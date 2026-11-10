package com.huyue.villagewar;

import java.util.Random;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import org.slf4j.Logger;

/**
 * 首都：每个势力在固定坐标先搭一座能认出来的据点。
 *
 * <p>不依赖世界生成器，直接在地表上平地起房子——两次开服不会重复盖（用标记方块判断）。</p>
 */
public final class CapitalBuilder {
    private static final Logger LOGGER = LogUtils.getLogger();

    private CapitalBuilder() {
    }

    /** 已经盖过就跳过；返回是否新盖。 */
    public static boolean ensureBuilt(ServerLevel level, Faction faction) {
        BlockPos center = faction.capital(level);
        // 标记：首都中心放一块势力色羊毛；有就说明盖过
        BlockState mark = faction == Faction.CRIMSON
                ? Blocks.RED_WOOL.defaultBlockState()
                : Blocks.LIGHT_BLUE_WOOL.defaultBlockState();
        BlockPos markPos = center.below();
        if (level.getBlockState(markPos).is(mark.getBlock())) {
            return false;
        }
        build(level, center, faction);
        LOGGER.info("[villagewar] {} 首都已建成 {}", faction.display, center);
        return true;
    }

    private static void build(ServerLevel level, BlockPos center, Faction faction) {
        Random rnd = new Random(center.getX() * 31L + center.getZ());
        // 1) 平地：21x21 的石砖地基
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                BlockPos p = center.offset(dx, -1, dz);
                level.setBlockAndUpdate(p, Blocks.STONE_BRICKS.defaultBlockState());
                for (int dy = 0; dy <= 5; dy++) {
                    level.setBlockAndUpdate(p.above(dy), Blocks.AIR.defaultBlockState());
                }
            }
        }
        // 2) 大厅 15x11：石砖墙 + 云杉台阶屋顶
        int hx = 7;
        int hz = 5;
        for (int dx = -hx; dx <= hx; dx++) {
            for (int dz = -hz; dz <= hz; dz++) {
                BlockPos floor = center.offset(dx, 0, dz);
                boolean edgeX = Math.abs(dx) == hx;
                boolean edgeZ = Math.abs(dz) == hz;
                if (edgeX || edgeZ) {
                    for (int y = 1; y <= 4; y++) {
                        level.setBlockAndUpdate(floor.above(y), Blocks.STONE_BRICKS.defaultBlockState());
                    }
                    if (rnd.nextInt(5) == 0) {
                        level.setBlockAndUpdate(floor.above(5), Blocks.SPRUCE_STAIRS.defaultBlockState());
                    }
                } else {
                    level.setBlockAndUpdate(floor, Blocks.SPRUCE_PLANKS.defaultBlockState());
                }
            }
        }
        // 3) 门（东面开 1x2）+ 门口灯笼
        level.setBlockAndUpdate(center.offset(hx, 1, 0), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(center.offset(hx, 2, 0), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(center.offset(hx + 1, 3, 0), Blocks.LANTERN.defaultBlockState());
        level.setBlockAndUpdate(center.offset(-hx - 1, 3, 0), Blocks.LANTERN.defaultBlockState());
        level.setBlockAndUpdate(center.offset(0, 3, hz + 1), Blocks.LANTERN.defaultBlockState());
        level.setBlockAndUpdate(center.offset(0, 3, -hz - 1), Blocks.LANTERN.defaultBlockState());

        // 4) 大厅正中：势力色地毯 + 钟
        BlockState carpet = faction == Faction.CRIMSON
                ? Blocks.RED_CARPET.defaultBlockState()
                : Blocks.LIGHT_BLUE_CARPET.defaultBlockState();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlockAndUpdate(center.offset(dx, 1, dz), carpet);
            }
        }
        level.setBlockAndUpdate(center.offset(0, 1, -hz + 2), Blocks.BELL.defaultBlockState());

        // 5) 床 + 工作方块
        placeBed(level, center.offset(-4, 1, -3));
        placeBed(level, center.offset(-1, 1, -3));
        placeBed(level, center.offset(2, 1, -3));
        placeBed(level, center.offset(4, 1, 3));
        level.setBlockAndUpdate(center.offset(4, 1, -3), Blocks.SMITHING_TABLE.defaultBlockState());
        level.setBlockAndUpdate(center.offset(-4, 1, 3), Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
        level.setBlockAndUpdate(center.offset(1, 1, 3), Blocks.FLETCHING_TABLE.defaultBlockState());

        // 6) 四角小农田
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                BlockPos farm = center.offset(sx * 14, 0, sz * 14);
                placeFarm(level, farm);
            }
        }
    }

    private static void placeBed(Level level, BlockPos foot) {
        if (!level.getBlockState(foot).isAir()) {
            return;
        }
        BlockState head = Blocks.RED_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.SOUTH)
                .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD);
        BlockState footState = Blocks.RED_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.SOUTH)
                .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT);
        level.setBlockAndUpdate(foot, footState);
        level.setBlockAndUpdate(foot.offset(0, 0, 1), head);
    }

    private static void placeFarm(Level level, BlockPos base) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos p = base.offset(dx, 0, dz);
                level.setBlockAndUpdate(p, Blocks.FARMLAND.defaultBlockState());
                if (dx == 0 || dz == 0) {
                    level.setBlockAndUpdate(p, Blocks.WATER.defaultBlockState());
                } else if (level.getBlockState(p).isAir()) {
                    level.setBlockAndUpdate(p, Blocks.WHEAT.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7));
                }
            }
        }
        level.setBlockAndUpdate(base.offset(-4, 1, -4), Blocks.LANTERN.defaultBlockState());
        level.setBlockAndUpdate(base.offset(4, 1, 4), Blocks.LANTERN.defaultBlockState());
    }
}
