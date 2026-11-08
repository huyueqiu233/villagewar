package com.huyue.villagewar;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 村庄争霸 Village War —— M0：让村庄自己长起来。
 *
 * <p>这一版只做最小的核心循环：玩家附近的村庄按「村民数 + 时间」攒成长点，
 * 攒够就在村子边上盖一间小屋。不掺势力、不掺战争，先验证「世界自己会动」这件事。</p>
 */
@Mod(VillageWar.MODID)
public final class VillageWar {
    public static final String MODID = "villagewar";

    public VillageWar(IEventBus modEventBus, ModContainer modContainer) {
        // 服务端每 tick 的分片检查（内部自己按节流跑）
        NeoForge.EVENT_BUS.addListener(VillageGrowth::onServerTick);
        // /villagewar status 调试命令
        NeoForge.EVENT_BUS.addListener(VillageWarCommand::register);
    }
}
