package com.huyue.villagewar;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 势力与出生点（M3 前置）。
 *
 * <p>首次进服发一排可点击的势力按钮；选完直接传送到该势力首都附近的随机地表点，
 * 每个人落点都不一样。死亡重生也在所属势力范围内复活。</p>
 */
public final class FactionSpawn {
    private static final String KEY = "villagewar:faction";

    private FactionSpawn() {
    }

    public static Faction factionOf(ServerPlayer player) {
        var tag = player.getPersistentData();
        return tag.contains(KEY) ? Faction.byId(tag.getString(KEY)) : null;
    }

    public static void setFaction(ServerPlayer player, Faction faction) {
        player.getPersistentData().putString(KEY, faction.id);
    }

    /** 在首都周围 30 格内找一个能站人的地表点。 */
    public static BlockPos spawnPoint(ServerLevel level, Faction faction) {
        BlockPos capital = faction.capital(level);
        RandomSource rnd = level.random;
        for (int i = 0; i < 40; i++) {
            int x = capital.getX() + rnd.nextInt(61) - 30;
            int z = capital.getZ() + rnd.nextInt(61) - 30;
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            BlockState below = level.getBlockState(p.below());
            boolean standable = !below.isAir()
                    && below.getFluidState().isEmpty()
                    && level.getBlockState(p).isAir()
                    && level.getBlockState(p.above()).isAir();
            if (standable) {
                return p;
            }
        }
        return capital;
    }

    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        // 先读存档，再登记首都村；反过来会被 load() 清空
        VillageGrowth.ensureLoaded(event.getServer());
        for (Faction faction : Faction.values()) {
            CapitalBuilder.ensureBuilt(overworld, faction);
            // 登记成村子，玩家不在时也能靠离屏模拟慢慢长
            VillageGrowth.ensureVillage(overworld, faction.capital(overworld), faction, 6);
        }
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (factionOf(player) == null) {
            sendChoice(player);
        }
    }

    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Faction faction = factionOf(player);
        if (faction == null) {
            return;
        }
        BlockPos p = spawnPoint(player.serverLevel(), faction);
        player.teleportTo(player.serverLevel(), p.getX() + 0.5, p.getY(), p.getZ() + 0.5,
                player.getYRot(), player.getXRot());
    }

    /** 聊天里发一排可点击的按钮（纯服务端，不需要客户端 GUI）。 */
    public static void sendChoice(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("先选个势力吧，选完直接送你去首都：").withStyle(ChatFormatting.GOLD));
        for (Faction faction : Faction.values()) {
            Component button = Component.literal("  [" + faction.display + "] ")
                    .withStyle(style -> style
                            .withColor(faction.color)
                            .withBold(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/villagewar join " + faction.id))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("加入 " + faction.display))));
            player.sendSystemMessage(button);
        }
        player.sendSystemMessage(Component.literal("（也可以打 /villagewar join crimson 或 azure）")
                .withStyle(ChatFormatting.GRAY));
    }

    /** 加入势力并传送。 */
    public static void join(ServerPlayer player, Faction faction) {
        setFaction(player, faction);
        ServerLevel level = player.serverLevel();
        BlockPos p = spawnPoint(level, faction);
        player.teleportTo(level, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, player.getYRot(), player.getXRot());
        player.sendSystemMessage(Component.literal("你加入了 " + faction.display + "，这里是你的首都外围。")
                .withStyle(faction.color));
    }
}
