package com.huyue.villagewar;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 玩家 → 势力。
 *
 * <p>不用 persistentData 的原因：玩家死一次、重建实体后数据可能丢（测试里踩到过），
 * 这个映射直接落盘，跟成长数据放一起。</p>
 */
public final class FactionStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<LinkedHashMap<String, String>>() { }.getType();
    private static final Map<String, String> DATA = new LinkedHashMap<>();
    private static boolean dirty = false;

    private FactionStore() {
    }

    private static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("villagewar_factions.json");
    }

    public static void load(MinecraftServer server) {
        DATA.clear();
        dirty = false;
        Path p = path(server);
        if (!Files.exists(p)) {
            return;
        }
        try {
            Map<String, String> parsed = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), TYPE);
            if (parsed != null) {
                DATA.putAll(parsed);
            }
        } catch (Exception ignored) {
            // 文件坏了就当没有，别把服务器带崩
        }
    }

    public static void saveIfDirty(MinecraftServer server) {
        if (!dirty) {
            return;
        }
        try {
            Files.writeString(path(server), GSON.toJson(DATA), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception ignored) {
        }
    }

    public static Faction get(UUID uuid) {
        return Faction.byId(DATA.get(uuid.toString()));
    }

    public static void set(UUID uuid, Faction faction) {
        DATA.put(uuid.toString(), faction.id);
        dirty = true;
    }
}
