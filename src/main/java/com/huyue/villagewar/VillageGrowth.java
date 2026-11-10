package com.huyue.villagewar;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * M0 的核心：村庄会自己长。
 *
 * <p>每 10 秒扫一次玩家周围的村民：够 3 只就算一个村子，按村民数量攒成长点；
 * 攒够就在村子边上盖东西。现在有三种：小屋（门/床/随机工作方块）、长屋（两张床）、农田。</p>
 */
public final class VillageGrowth {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int CHECK_INTERVAL_TICKS = 200; // 10 秒
    private static final int SAVE_INTERVAL_TICKS = 1200; // 1 分钟
    private static final int GROWTH_COST = 60;
    private static final int RADIUS = 48;
    private static final int MIN_VILLAGERS = 3;

    /** 工作方块池：建房子时随机挑，别让整个村子全是渔夫。 */
    private static final Block[] WORKSTATIONS = {
            Blocks.BARREL,            // 渔夫
            Blocks.SMITHING_TABLE,    // 工具匠
            Blocks.LECTERN,           // 图书管理员
            Blocks.CARTOGRAPHY_TABLE, // 制图师
            Blocks.FLETCHING_TABLE,   // 制箭师
            Blocks.LOOM,              // 牧羊人
            Blocks.STONECUTTER,       // 石匠
            Blocks.SMOKER,            // 屠夫
            Blocks.BLAST_FURNACE,     // 盔甲匠
            Blocks.BREWING_STAND,     // 牧师
            Blocks.GRINDSTONE,        // 武器匠
            Blocks.COMPOSTER          // 农民
    };

    private static int tickCounter = 0;
    private static boolean loaded = false;

    private VillageGrowth() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!loaded) {
            GrowthStore.load(server);
            loaded = true;
            LOGGER.info("[villagewar] 成长系统已加载");
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
        simulateRemote(System.currentTimeMillis());
        GrowthStore.saveIfDirty(server);
    }

    /**
     * 离屏增长：玩家不在旁边时，记住的村子按上次看到的村民数继续攒点。
     *
     * <p>这就是设计里「离屏抽象模拟」的第一版：不加载区块、不跑实体，
     * 只按时间推移数字；等玩家走回来，growAround 再把积压的房子实体化。</p>
     */
    private static void simulateRemote(long now) {
        for (GrowthStore.Entry entry : GrowthStore.all().values()) {
            if (entry.lastSeenAt <= 0) {
                entry.lastSeenAt = now;
                continue;
            }
            long elapsed = now - entry.lastSeenAt;
            if (elapsed < 30_000L) {
                continue; // 玩家还在附近，交给 growAround 实时算
            }
            int steps = (int) Math.min(360, elapsed / 10_000L); // 离线时每 10 秒算一步
            if (steps <= 0) {
                continue;
            }
            int pop = Math.min(20, Math.max(MIN_VILLAGERS, entry.population));
            int gain = steps * Math.max(1, pop / 2);
            // 积压上限：6 座房子，免得放置久了回来一口气刷出一片
            entry.points = Math.min(GROWTH_COST * 6, entry.points + gain);
            entry.lastSeenAt = now;
            GrowthStore.markDirty();
        }
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
        String key = findVillageKey(level, center);
        if (key == null) {
            key = keyOf(level, center);
        }
        GrowthStore.Entry entry = GrowthStore.get(key);
        entry.lastX = center.getX();
        entry.lastZ = center.getZ();
        entry.dimension = level.dimension().location().toString();
        entry.lastSeenAt = System.currentTimeMillis();
        entry.population = villagers.size();
        entry.points = Math.min(GROWTH_COST * 6, entry.points + Math.max(1, villagers.size() / 2));
        GrowthStore.markDirty();

        // 一次最多实体化 2 座，把离屏攒下的积压慢慢放出来
        for (int i = 0; i < 2 && entry.points >= GROWTH_COST; i++) {
            String built = tryBuild(level, center, entry);
            if (built == null) {
                break;
            }
            entry.points -= GROWTH_COST;
            entry.houses++;
            GrowthStore.markDirty();
            LOGGER.info("[villagewar] {} 在 {} 建了{}（第 {} 个）", key, center, built, entry.houses);
            announce(level, center, built, entry.houses);
        }

        raidTick(level, center, key, entry);
    }

    /** 村子按「维度 + 所在区块」归并，避免玩家走两步就多出一个新村。 */
    private static String keyOf(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "@" + (pos.getX() >> 4) + "," + (pos.getZ() >> 4);
    }

    /** 村子合并距离：离已有村子这么近就当成同一个（村民走动不会分裂出新村）。 */
    private static final double VILLAGE_MERGE_DIST = 64.0;

    /**
     * 找离 center 最近的已有村子（同维度、64 格内）。
     * 村民会走动，中心点在区块边界附近漂移时，按区块归并会把一个村子拆成好几个；
     * 所以先按距离认领，认不到才新建（2026-11-10 冒烟测试发现）。
     */
    private static String findVillageKey(ServerLevel level, BlockPos center) {
        String dim = level.dimension().location().toString();
        String best = null;
        double bestSq = VILLAGE_MERGE_DIST * VILLAGE_MERGE_DIST;
        for (Map.Entry<String, GrowthStore.Entry> e : GrowthStore.all().entrySet()) {
            GrowthStore.Entry v = e.getValue();
            if (!dim.equals(v.dimension)) {
                continue;
            }
            double dx = v.lastX - center.getX();
            double dz = v.lastZ - center.getZ();
            double sq = dx * dx + dz * dz;
            if (sq < bestSq) {
                bestSq = sq;
                best = e.getKey();
            }
        }
        return best;
    }

    private static void announce(ServerLevel level, BlockPos center, String what, int houses) {
        notify(level, center, "附近村子新添了" + what + "（第 " + houses + " 座）");
        level.playSound(null, center, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.6F, 1.4F);
    }

    private static void notify(ServerLevel level, BlockPos center, String text) {
        Component message = Component.literal(text);
        for (ServerPlayer nearby : level.players()) {
            if (nearby.distanceToSqr(center.getX(), center.getY(), center.getZ()) < 96.0 * 96.0) {
                nearby.displayClientMessage(message, false);
            }
        }
    }

    // ── 掠夺者袭击（M2）──────────────────────────────────────────────────────

    /** 村子第一次被排上袭击的延迟；之后每 20~40 分钟一场。 */
    private static final long RAID_FIRST_DELAY_MS = 10 * 60 * 1000L;
    private static final long RAID_INTERVAL_MIN_MS = 20 * 60 * 1000L;
    private static final long RAID_INTERVAL_MAX_MS = 40 * 60 * 1000L;
    private static final long RAID_WAVE_GAP_MS = 45 * 1000L;   // 每一波之间隔 45 秒
    private static final int RAID_WAVES = 3;                    // 一场袭击三波
    private static final long RAID_TAIL_MS = 60 * 1000L;        // 最后一波之后再等 1 分钟收尾

    /** 一场袭击的进度：现在第几波、下一波什么时候来、什么时候结束。 */
    private static final class RaidState {
        int wave;
        long nextWaveAt;
        long endAt;
    }

    /** key -> 这场袭击的状态。临时数据，重启就散，不影响存档。 */
    private static final Map<String, RaidState> ACTIVE_RAIDS = new HashMap<>();

    /** `/villagewar raid` 用：立刻在当前玩家附近的村子开一场袭击（不等计时器）。 */
    public static boolean forceRaid(ServerLevel level, ServerPlayer player) {
        return forceRaidAt(level, player.blockPosition());
    }

    /** 测试用：按坐标找附近的村子开一场袭击（控制台也能调，不需要玩家）。 */
    public static boolean forceRaidAt(ServerLevel level, BlockPos origin) {
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class,
                new net.minecraft.world.phys.AABB(origin).inflate(RADIUS), Villager::isAlive);
        if (villagers.size() < MIN_VILLAGERS) {
            return false;
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
        String key = findVillageKey(level, center);
        if (key == null) {
            key = keyOf(level, center);
        }
        if (ACTIVE_RAIDS.containsKey(key)) {
            return false;
        }
        GrowthStore.Entry entry = GrowthStore.get(key);
        entry.lastX = center.getX();
        entry.lastZ = center.getZ();
        entry.dimension = level.dimension().location().toString();
        startRaid(level, center, key, entry);
        return true;
    }

    private static void raidTick(ServerLevel level, BlockPos center, String key, GrowthStore.Entry entry) {
        long now = System.currentTimeMillis();
        RaidState raid = ACTIVE_RAIDS.get(key);
        if (raid != null) {
            if (now >= raid.nextWaveAt && raid.wave < RAID_WAVES) {
                raid.wave++;
                spawnWave(level, center, entry, raid.wave);
                raid.nextWaveAt = now + RAID_WAVE_GAP_MS;
                notify(level, center, "第 " + raid.wave + " 波掠夺者来了！");
            }
            if (now >= raid.endAt) {
                ACTIVE_RAIDS.remove(key);
                notify(level, center, "袭击结束了，村子还站着");
                LOGGER.info("[villagewar] 袭击结束 {}", key);
            } else if (level.getRandom().nextInt(4) == 0) {
                damageBuilding(level, entry);
            }
            return;
        }
        if (entry.houses < 2) {
            return; // 村子还太小，先不招贼
        }
        if (entry.nextRaidAt <= 0) {
            entry.nextRaidAt = now + RAID_FIRST_DELAY_MS;
            GrowthStore.markDirty();
            return;
        }
        if (now < entry.nextRaidAt) {
            return;
        }
        startRaid(level, center, key, entry);
    }

    private static void startRaid(ServerLevel level, BlockPos center, String key, GrowthStore.Entry entry) {
        RandomSource random = level.getRandom();

        RaidState raid = new RaidState();
        raid.wave = 1;
        raid.nextWaveAt = System.currentTimeMillis() + RAID_WAVE_GAP_MS;
        raid.endAt = System.currentTimeMillis() + (RAID_WAVES - 1) * RAID_WAVE_GAP_MS + RAID_TAIL_MS;
        ACTIVE_RAIDS.put(key, raid);

        // 村子自己武装：房子越多，铁傀儡上限越高；先把缺的补上
        int desiredGolems = Math.min(6, 1 + entry.houses / 3);
        AABB villageBox = new AABB(center.getX() - 48, center.getY() - 48, center.getZ() - 48,
                center.getX() + 48, center.getY() + 48, center.getZ() + 48);
        long existing = level.getEntitiesOfClass(IronGolem.class, villageBox, IronGolem::isAlive).size();
        int toSpawn = (int) Math.min(3, Math.max(0, desiredGolems - existing));
        for (int i = 0; i < toSpawn; i++) {
            spawnAt(level, center, EntityType.IRON_GOLEM, 8 + random.nextInt(6));
        }

        spawnWave(level, center, entry, 1);

        entry.raids++;
        entry.nextRaidAt = System.currentTimeMillis() + RAID_INTERVAL_MIN_MS
                + random.nextInt((int) (RAID_INTERVAL_MAX_MS - RAID_INTERVAL_MIN_MS));
        GrowthStore.markDirty();
        notify(level, center, "掠夺者来袭！（第 " + entry.raids + " 次，共 " + RAID_WAVES + " 波）");
        LOGGER.info("[villagewar] 袭击开始 {}（第 {} 次，房子 {} 座，铁傀儡 {} 个）", key, entry.raids, entry.houses, desiredGolems);
    }

    /** 按村子规模和波次生成一波袭击者：越往后越多、种类越杂。 */
    private static void spawnWave(ServerLevel level, BlockPos center, GrowthStore.Entry entry, int wave) {
        RandomSource random = level.getRandom();
        int houses = entry.houses;

        int pillagers = Math.min(15, 3 + houses / 2 + entry.raids / 2 + wave);
        for (int i = 0; i < pillagers; i++) {
            equipSpawned(spawnAt(level, center, EntityType.PILLAGER, 22 + random.nextInt(8)));
        }
        if (houses >= 4 || wave >= 2) {
            int vindicators = Math.min(4, 1 + houses / 6);
            for (int i = 0; i < vindicators; i++) {
                equipSpawned(spawnAt(level, center, EntityType.VINDICATOR, 22 + random.nextInt(8)));
            }
        }
        if (houses >= 8 && wave >= 2) {
            spawnAt(level, center, EntityType.EVOKER, 24 + random.nextInt(6));
        }
        if (houses >= 12 && wave >= 3) {
            spawnAt(level, center, EntityType.RAVAGER, 24 + random.nextInt(6));
        }
        if (houses >= 6 && wave >= 2) {
            spawnAt(level, center, EntityType.WITCH, 24 + random.nextInt(6));
        }

        // 给村里的村民一点抗性，别一波全被屠了（原版村民没有攻击 AI，只能少死一点）
        AABB villagerBox = new AABB(center.getX() - 40, center.getY() - 40, center.getZ() - 40,
                center.getX() + 40, center.getY() + 40, center.getZ() + 40);
        for (Villager villager : level.getEntitiesOfClass(Villager.class, villagerBox, Villager::isAlive)) {
            villager.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 600, 0));
        }
    }

    private static Entity spawnAt(ServerLevel level, BlockPos center, EntityType<?> type, int radius) {
        double angle = level.getRandom().nextDouble() * Math.PI * 2.0;
        int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
        int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Entity entity = type.create(level);
        if (entity == null) {
            return null;
        }
        entity.moveTo(x + 0.5, y, z + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
        level.addFreshEntity(entity);
        return entity;
    }

    /** 手动生成的怪不会自带装备，这里补上。 */
    private static void equipSpawned(Entity entity) {
        if (entity instanceof Pillager pillager) {
            pillager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            pillager.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        } else if (entity instanceof Vindicator vindicator) {
            vindicator.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            vindicator.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        }
    }

    /** 袭击期间隔一会儿拆几块墙，制造「打坏了」的痕迹。 */
    private static void damageBuilding(ServerLevel level, GrowthStore.Entry entry) {
        if (entry.buildings.isEmpty()) {
            return;
        }
        int[] b = entry.buildings.get(level.getRandom().nextInt(entry.buildings.size()));
        BlockPos base = new BlockPos(b[0], b[1], b[2]);
        RandomSource random = level.getRandom();
        for (int i = 0; i < 3; i++) {
            BlockPos p = base.offset(random.nextInt(5) - 2, random.nextInt(3), random.nextInt(5) - 2);
            BlockState state = level.getBlockState(p);
            if (!state.isAir() && !state.is(Blocks.BEDROCK)) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            }
        }
    }

    // ── 建造 ────────────────────────────────────────────────────────────────

    private static String tryBuild(ServerLevel level, BlockPos center, GrowthStore.Entry entry) {
        int base = (entry.houses + level.getRandom().nextInt(2)) % 3;
        for (int i = 0; i < 3; i++) {
            int type = (base + i) % 3;
            BlockPos spot = findSpot(level, center, type);
            if (spot == null) {
                continue;
            }
            switch (type) {
                case 0 -> {
                    placeHut(level, spot);
                    entry.buildings.add(new int[]{spot.getX(), spot.getY(), spot.getZ()});
                    return "一间小屋";
                }
                case 1 -> {
                    placeFarm(level, spot);
                    return "一块农田";
                }
                default -> {
                    placeLongHouse(level, spot);
                    entry.buildings.add(new int[]{spot.getX(), spot.getY(), spot.getZ()});
                    return "一座长屋";
                }
            }
        }
        return null;
    }

    private static BlockPos findSpot(ServerLevel level, BlockPos center, int type) {
        int halfW = type == 2 ? 3 : (type == 1 ? 3 : 2);
        int halfD = type == 2 ? 3 : 2;
        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < 90; attempt++) {
            // 离村子中心至少 10 格：别插在原版村庄的房子中间
            int dx = random.nextInt(37) - 18;
            int dz = random.nextInt(37) - 18;
            if (Math.abs(dx) < 10 && Math.abs(dz) < 10) {
                continue;
            }
            int x = center.getX() + dx;
            int z = center.getZ() + dz;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos base = new BlockPos(x, y, z);
            // 先试宽松的 2 格净空，实在放不下就退回 1 格（村子很密时不至于一直卡住）
            int margin = attempt < 60 ? 2 : 1;
            if (areaIsClear(level, base, halfW, halfD, margin)) {
                return base;
            }
        }
        return null;
    }

    /** 地基范围：脚下实心、上面 4 层是空气；外圈留 margin 格净空，别贴着别的建筑。 */
    private static boolean areaIsClear(Level level, BlockPos base, int halfW, int halfD, int margin) {
        for (int dx = -halfW; dx <= halfW; dx++) {
            for (int dz = -halfD; dz <= halfD; dz++) {
                if (!level.getBlockState(base.offset(dx, -1, dz)).canOcclude()) {
                    return false;
                }
                for (int dy = 0; dy <= 3; dy++) {
                    if (!level.getBlockState(base.offset(dx, dy, dz)).isAir()) {
                        return false;
                    }
                }
            }
        }
        if (margin <= 0) {
            return true;
        }
        // 外圈只看空中，不看地面高度
        for (int dx = -halfW - margin; dx <= halfW + margin; dx++) {
            for (int dz = -halfD - margin; dz <= halfD + margin; dz++) {
                if (Math.abs(dx) <= halfW && Math.abs(dz) <= halfD) {
                    continue;
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

        // 墙：四角原木，中间木板；南面正中留 1 宽 2 高的门洞
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                for (int dy = 0; dy <= 2; dy++) {
                    if (dz == 2 && dx == 0 && dy <= 1) {
                        continue; // 门洞，稍后放门
                    }
                    level.setBlockAndUpdate(base.offset(dx, dy, dz), corner ? log : planks);
                }
            }
        }

        // 窗
        level.setBlockAndUpdate(base.offset(-2, 1, 0), glass);
        level.setBlockAndUpdate(base.offset(2, 1, 0), glass);

        // 屋顶（两级）
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
        placeBed(level, base.offset(-1, 0, 1), Direction.NORTH);

        // 工作台 + 随机工作方块各一个；灯笼挂屋顶下，别压在工作方块和床上
        level.setBlockAndUpdate(base.offset(1, 0, -1), Blocks.CRAFTING_TABLE.defaultBlockState());
        level.setBlockAndUpdate(base.offset(1, 0, 1), randomWorkstation(level.getRandom()));
        placeHangingLantern(level, base.offset(0, 2, 0));

        placeOutdoorTorches(level, base, 3);
    }

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

        // 墙：只在正中留 1 宽 2 高的门洞，其余全部封死
        for (int dx = -halfW; dx <= halfW; dx++) {
            for (int dz = -halfD; dz <= halfD; dz++) {
                boolean edge = Math.abs(dx) == halfW || Math.abs(dz) == halfD;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(dx) == halfW && Math.abs(dz) == halfD;
                for (int dy = 0; dy <= 2; dy++) {
                    if (dz == halfD && dx == 0 && dy <= 1) {
                        continue; // 门洞
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

        placeDoor(level, base.offset(0, 0, halfD), Direction.SOUTH);

        placeBed(level, base.offset(-2, 0, 0), Direction.NORTH);
        placeBed(level, base.offset(2, 0, 0), Direction.NORTH);

        BlockPos stationA = base.offset(-1, 0, -1);
        BlockPos stationB = base.offset(1, 0, -1);
        level.setBlockAndUpdate(stationA, randomWorkstation(level.getRandom()));
        level.setBlockAndUpdate(stationB, randomWorkstation(level.getRandom()));
        placeHangingLantern(level, base.offset(-1, 2, 1));
        placeHangingLantern(level, base.offset(1, 2, 1));

        placeOutdoorTorches(level, base, 4);
    }

    private static void placeFarm(Level level, BlockPos base) {
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
        // 堆肥桶留给村民认领；灯改挂在四角的栅栏上，不占用工作方块上方
        level.setBlockAndUpdate(base.offset(-3, -1, -3), Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(base.offset(3, -1, 3), Blocks.COMPOSTER.defaultBlockState());
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                BlockPos fence = base.offset(sx * 4, -1, sz * 4);
                BlockPos lamp = fence.above();
                if (level.getBlockState(fence).canOcclude() && level.getBlockState(lamp).isAir()) {
                    level.setBlockAndUpdate(fence, Blocks.OAK_FENCE.defaultBlockState());
                    level.setBlockAndUpdate(lamp, Blocks.LANTERN.defaultBlockState());
                }
            }
        }
    }

    /** 房子四角外面插火把：夜里少刷僵尸， villagers 不容易被偷袭。 */
    private static void placeOutdoorTorches(Level level, BlockPos base, int distance) {
        int[][] offsets = {{-distance, -distance}, {-distance, distance}, {distance, -distance}, {distance, distance}};
        for (int[] o : offsets) {
            BlockPos ground = base.offset(o[0], -1, o[1]);
            BlockPos torch = base.offset(o[0], 0, o[1]);
            if (level.getBlockState(ground).canOcclude() && level.getBlockState(torch).isAir()) {
                level.setBlockAndUpdate(torch, Blocks.TORCH.defaultBlockState());
            }
        }
    }

    private static BlockState randomWorkstation(RandomSource random) {
        return WORKSTATIONS[random.nextInt(WORKSTATIONS.length)].defaultBlockState();
    }

    // ── 小构件 ──────────────────────────────────────────────────────────────

    /** 吊灯：挂在天花板上，不占地面、不挡村民认领工作方块。 */
    private static void placeHangingLantern(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            return;
        }
        if (!level.getBlockState(pos.above()).canOcclude()) {
            return;
        }
        level.setBlockAndUpdate(pos, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

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
