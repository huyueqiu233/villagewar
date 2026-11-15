package com.huyue.villagewar;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 势力点数（M3）：端营地、救村民、守袭击都会给。
 *
 * <p>用途是把“打完了然后呢”接上：点数是下一轮目标（买补给、补铁傀儡），
 * 花掉之后又想去打营地，核心循环才闭环。</p>
 */
public final class FactionPoints {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<LinkedHashMap<String, Integer>>() { }.getType();
    private static final Map<String, Integer> DATA = new LinkedHashMap<>();
    private static boolean dirty = false;

    private FactionPoints() {
    }

    private static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("villagewar_points.json");
    }

    public static void load(MinecraftServer server) {
        DATA.clear();
        dirty = false;
        Path p = path(server);
        if (!Files.exists(p)) {
            return;
        }
        try {
            Map<String, Integer> parsed = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), TYPE);
            if (parsed != null) {
                DATA.putAll(parsed);
            }
        } catch (Exception ignored) {
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

    public static int get(Faction faction) {
        return faction == null ? 0 : DATA.getOrDefault(faction.id, 0);
    }

    public static void add(Faction faction, int amount) {
        if (faction == null || amount <= 0) {
            return;
        }
        DATA.put(faction.id, get(faction) + amount);
        dirty = true;
    }

    /** 扣点数；不够返回 false，不扣。 */
    public static boolean spend(Faction faction, int amount) {
        if (faction == null || amount <= 0) {
            return false;
        }
        int now = get(faction);
        if (now < amount) {
            return false;
        }
        DATA.put(faction.id, now - amount);
        dirty = true;
        return true;
    }
}
