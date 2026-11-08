package com.huyue.villagewar;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** `/villagewar status` —— 看看现在记了几个村子、每个攒了多少成长点。 */
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
                            () -> Component.literal(" - " + key + " 成长点=" + entry.points + " 房屋=" + entry.houses),
                            false));
                    return 1;
                })));
    }
}
