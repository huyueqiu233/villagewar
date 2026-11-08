package com.huyue.villagewar;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** `/villagewar status` 看状态；`/villagewar raid` 立刻在当前村子开一场袭击（测试用）。 */
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
                    ServerPlayer player = source.getPlayerOrException();
                    boolean started = VillageGrowth.forceRaid(player.serverLevel(), player);
                    source.sendSuccess(() -> Component.literal(started
                            ? "袭击已开始：附近村子进入了战斗"
                            : "没能开打：附近村民不足 3 个，或者这个村子已经在打了"), false);
                    return started ? 1 : 0;
                })));
    }
}
