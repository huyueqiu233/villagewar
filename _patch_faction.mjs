import fs from 'node:fs';

// ---------- 1) Faction: 首都在陆地上找 ----------
{
  const p = 'src/main/java/com/huyue/villagewar/Faction.java';
  let s = fs.readFileSync(p, 'utf8');

  s = s.replace(
    'import net.minecraft.ChatFormatting;\nimport net.minecraft.core.BlockPos;\nimport net.minecraft.server.level.ServerLevel;',
    'import java.util.EnumMap;\nimport java.util.Map;\n\nimport net.minecraft.ChatFormatting;\nimport net.minecraft.core.BlockPos;\nimport net.minecraft.server.level.ServerLevel;\nimport net.minecraft.tags.BiomeTags;\nimport net.minecraft.world.level.block.state.BlockState;\nimport net.minecraft.world.level.levelgen.Heightmap;'
  );

  s = s.replace(
    '    /** 首都中心（地表），会顺带把区块加载出来。 */\n    public BlockPos capital(ServerLevel level) {\n        BlockPos spawn = level.getSharedSpawnPos();\n        int x = spawn.getX() + offsetX;\n        int z = spawn.getZ() + offsetZ;\n        level.getChunk(x >> 4, z >> 4);\n        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);\n        return new BlockPos(x, y, z);\n    }',
    `    /** 首都坐标缓存：ownerOf 会被高频调用，不能每次都去扫地表。 */
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
    }`
  );

  fs.writeFileSync(p, s);
  console.log('faction land:', s.includes('findLand'), '| cache:', s.includes('CAPITAL_CACHE'));
}

// ---------- 2) 命令：选过势力不能换 ----------
{
  const p = 'src/main/java/com/huyue.villagewar/VillageWarCommand.java'.replace('com.huyue.', 'com/huyue/');
  const file = 'src/main/java/com/huyue/villagewar/VillageWarCommand.java';
  let s = fs.readFileSync(file, 'utf8');
  s = s.replace(
    "                                    Faction faction = Faction.byId(StringArgumentType.getString(ctx, \"faction\"));\n                                    if (faction == null) {",
    "                                    Faction faction = Faction.byId(StringArgumentType.getString(ctx, \"faction\"));\n                                    Faction current = FactionSpawn.factionOf(player);\n                                    if (current != null && current != faction) {\n                                        ctx.getSource().sendFailure(Component.literal(\"你已经加入 \" + current.display + \"，选过就不能换了\"));\n                                        return 0;\n                                    }\n                                    if (faction == null) {"
  );
  fs.writeFileSync(file, s);
  console.log('join lock:', s.includes('选过就不能换了'));
}

// ---------- 3) 工作方块池去掉堆肥桶 ----------
{
  const file = 'src/main/java/com/huyue/villagewar/VillageGrowth.java';
  let s = fs.readFileSync(file, 'utf8');
  s = s.replace("            Blocks.GRINDSTONE,         // 武器匠\n            Blocks.COMPOSTER          // 农民", '            Blocks.GRINDSTONE          // 武器匠（堆肥桶不放房子里：村民只在农田边认领它）');
  fs.writeFileSync(file, s);
  console.log('workstation:', !s.includes('Blocks.COMPOSTER          // 农民'));
}

// ---------- 4) 大农田 + 含水半砖上的堆肥桶 ----------
{
  const file = 'src/main/java/com/huyue/villagewar/VillageGrowth.java';
  let s = fs.readFileSync(file, 'utf8');
  const start = s.indexOf('    private static void placeFarm(Level level, BlockPos base) {');
  const end = s.indexOf('    private static void placeOutdoorTorches(');
  if (start < 0 || end < 0) { console.log('farm anchors missing'); process.exit(1); }
  const farm = `    /** 大农田（13x13）：中间一条水渠，水渠上铺含水半砖放堆肥桶，其余种作物。 */
    public static void placeFarm(Level level, BlockPos base) {
        RandomSource rnd = level.getRandom();
        int r = 6;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos p = base.offset(dx, -1, dz);
                boolean outer = Math.abs(dx) == r || Math.abs(dz) == r;
                if (outer) {
                    level.setBlockAndUpdate(p, Blocks.OAK_FENCE.defaultBlockState());
                    if ((dx + dz + r) % 6 == 0) {
                        level.setBlockAndUpdate(p.above(), Blocks.LANTERN.defaultBlockState());
                    }
                    continue;
                }
                if (Math.abs(dz) <= 1) {
                    // 水渠：水面上方铺一块含水半砖，堆肥桶放在其中一块半砖上
                    level.setBlockAndUpdate(p, Blocks.WATER.defaultBlockState());
                    level.setBlockAndUpdate(p.above(),
                            Blocks.OAK_SLAB.defaultBlockState()
                                    .setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true));
                    if (dx == -3 && dz == 0) {
                        level.setBlockAndUpdate(p.above(2), Blocks.COMPOSTER.defaultBlockState());
                    }
                    continue;
                }
                level.setBlockAndUpdate(p, Blocks.FARMLAND.defaultBlockState());
                BlockState crop = rnd.nextInt(3) == 0
                        ? Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7)
                        : rnd.nextInt(2) == 0
                        ? Blocks.POTATOES.defaultBlockState().setValue(CropBlock.AGE, 7)
                        : Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
                level.setBlockAndUpdate(p.above(), crop);
            }
        }
    }

`;
  s = s.slice(0, start) + farm + s.slice(end);
  fs.writeFileSync(file, s);
  console.log('big farm:', s.includes('大农田（13x13）'));
}

// ---------- 5) 首都的农田改用同一个大农田 ----------
{
  const file = 'src/main/java/com/huyue/villagewar/CapitalBuilder.java';
  let s = fs.readFileSync(file, 'utf8');
  const start = s.indexOf('    private static void placeFarm(Level level, BlockPos base) {');
  const end = s.lastIndexOf('}');
  if (start < 0) { console.log('capital farm missing'); process.exit(1); }
  s = s.slice(0, start) + '    private static void placeFarm(Level level, BlockPos base) {\n        VillageGrowth.placeFarm(level, base);\n    }\n' + s.slice(end);
  fs.writeFileSync(file, s);
  console.log('capital farm reuse:', s.includes('VillageGrowth.placeFarm'));
}
