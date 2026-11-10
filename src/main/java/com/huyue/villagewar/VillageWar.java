package com.huyue.villagewar;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 村庄争霸 Village War。
 *
 * <p>M0：村庄会自己长；M1：离屏模拟；M2：掠夺者袭击；
 * M3 前置：两个势力 + 首都 + 选势力随机出生。</p>
 */
@Mod(VillageWar.MODID)
public final class VillageWar {
    public static final String MODID = "villagewar";

    public VillageWar(IEventBus modEventBus, ModContainer modContainer) {
        // 服务端每 tick 的分片检查（内部自己按节流跑）
        NeoForge.EVENT_BUS.addListener(VillageGrowth::onServerTick);
        // /villagewar status / raid / join / faction
        NeoForge.EVENT_BUS.addListener(VillageWarCommand::register);
        // 开服时把两个首都搭好
        NeoForge.EVENT_BUS.addListener(FactionSpawn::onServerStarted);
        // 首次进服发势力选择；死亡重生回势力范围
        NeoForge.EVENT_BUS.addListener(FactionSpawn::onLogin);
        NeoForge.EVENT_BUS.addListener(FactionSpawn::onRespawn);
    }
}
