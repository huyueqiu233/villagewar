package com.huyue.villagewar;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** `/villagewar status` 看状态；`/villagewar raid` 开一场袭击；`join <势力>` 选势力。 */
public final class VillageWarCommand {
    private VillageWarCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("villagewar")
                .then(Commands.literal("status").executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    int count = GrowthStore.all().size();
                    source.sendSuccess(() -> Component.literal("村庄争霸：已知村庄 " + count + " 个"), false);
                    GrowthStore.all().forEach((key, entry) -> source.sendSuccess(
                            () -> Component.literal(" - " + key + " 成长点=" + entry.points
                                    + " 房屋=" + entry.houses + " 被袭击=" + entry.raids + " 次"),
                            false));
                    return 1;
                }))
                .then(Commands.literal("raid").executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    net.minecraft.server.level.ServerLevel level = source.getLevel();
                    // 用命令来源的位置：控制台可以用 `execute at <玩家> run villagewar raid`
                    net.minecraft.core.BlockPos origin = net.minecraft.core.BlockPos.containing(source.getPosition());
                    boolean started = VillageGrowth.forceRaidAt(level, origin);
                    source.sendSuccess(() -> Component.literal(started
                            ? "袭击已开始：附近村子进入了战斗"
                            : "没能开打：附近村民不足 3 个，或者这个村子已经在打了"), false);
                    return started ? 1 : 0;
                }))
                .then(Commands.literal("join")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            FactionSpawn.sendChoice(player);
                            return 1;
                        })
                        .then(Commands.argument("faction", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    Faction faction = Faction.byId(StringArgumentType.getString(ctx, "faction"));
                                    Faction current = FactionSpawn.factionOf(player);
                                    if (current != null && current != faction) {
                                        ctx.getSource().sendFailure(Component.literal("你已经加入 " + current.display + "，选过就不能换了"));
                                        return 0;
                                    }
                                    if (faction == null) {
                                        ctx.getSource().sendFailure(Component.literal("没有这个势力，可选：crimson / azure"));
                                        return 0;
                                    }
                                    FactionSpawn.join(player, faction);
                                    return 1;
                                })))
                .then(Commands.literal("camp").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(PillagerCamp.describe()), false);
                    return 1;
                }))
                .then(Commands.literal("wartick").executes(ctx -> {
                    VillageGrowth.forceWarTick(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal("已评估一次势力战争"), false);
                    return 1;
                }))
                .then(Commands.literal("war").executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    source.sendSuccess(() -> Component.literal("势力战争："), false);
                    for (Faction faction : Faction.values()) {
                        int villages = 0;
                        int houses = 0;
                        for (GrowthStore.Entry e : GrowthStore.all().values()) {
                            if (faction.id.equals(e.faction)) {
                                villages++;
                                houses += e.houses;
                            }
                        }
                        int v = villages;
                        int h = houses;
                        source.sendSuccess(() -> Component.literal(" - " + faction.display
                                + "：村子 " + v + " 个，房屋 " + h + " 座").withStyle(faction.color), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("faction").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    Faction faction = FactionSpawn.factionOf(player);
                    ctx.getSource().sendSuccess(() -> Component.literal(faction == null
                            ? "你还没选势力，打 /villagewar join"
                            : "你属于 " + faction.display), false);
                    return 1;
                })));
    }
}
