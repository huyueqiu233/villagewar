package com.huyue.villagewar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 成长数据：每个村子的成长点、已建房屋数。
 *
 * <p>M0 故意不用 SavedData —— 版本间 API 改得勤，先用世界目录下一个 JSON 文件，
 * 逻辑简单、坏了也能手改。等核心玩法稳定了再换成正式存档格式。</p>
 */
public final class GrowthStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<LinkedHashMap<String, Entry>>() { }.getType();

    private static final Map<String, Entry> DATA = new LinkedHashMap<>();
    private static boolean dirty = false;

    private GrowthStore() {
    }

    /** 一个村子（按维度 + 区块坐标归并）的成长状态。 */
    public static final class Entry {
        public int points;
        public int houses;
        public int lastX;
        public int lastZ;
        public String dimension = "";
        /** 上次有玩家在附近的时间（毫秒）；离屏模拟用它算补多少成长点。 */
        public long lastSeenAt;
        /** 上次看到的村民数，离屏时按这个估算产出。 */
        public int population;
    }

    public static Entry get(String key) {
        return DATA.computeIfAbsent(key, k -> new Entry());
    }

    public static Map<String, Entry> all() {
        return DATA;
    }

    public static void markDirty() {
        dirty = true;
    }

    public static void load(MinecraftServer server) {
        DATA.clear();
        dirty = false;
        Path path = path(server);
        if (!Files.exists(path)) {
            return;
        }
        try {
            Map<String, Entry> parsed = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), TYPE);
            if (parsed != null) {
                DATA.putAll(parsed);
            }
        } catch (Exception ignored) {
            // 存档坏了就当新世界，不让它把服务器带崩
        }
    }

    public static void saveIfDirty(MinecraftServer server) {
        if (!dirty) {
            return;
        }
        Path path = path(server);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(DATA, TYPE), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception ignored) {
            // 写失败下次再来
        }
    }

    private static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("villagewar_growth.json");
    }
}
