package ui.modelysm.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ui.modelysm.ModelYSM;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 实体→模型绑定+设置 存储（服务端）。持久化到 config/modelysm/bindings.json。
 * 兼容旧格式（uuid→模型名字符串），自动迁移为新格式（uuid→EntitySettings）。
 */
public final class ModelBindingStore {
    private static final Logger LOGGER = LogManager.getLogger(ModelYSM.MODID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ModelBindingStore INSTANCE;

    private final Path file;
    private final Map<UUID, EntitySettings> entities = new ConcurrentHashMap<>();

    private ModelBindingStore(Path file) {
        this.file = file;
        load();
    }

    public static ModelBindingStore get() {
        if (INSTANCE == null) {
            INSTANCE = new ModelBindingStore(FMLPaths.CONFIGDIR.get().resolve("modelysm").resolve("bindings.json"));
        }
        return INSTANCE;
    }

    private void load() {
        entities.clear();
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, JsonElement> raw = GSON.fromJson(r, new TypeToken<Map<String, JsonElement>>() {}.getType());
            if (raw != null) {
                for (Map.Entry<String, JsonElement> e : raw.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(e.getKey());
                        JsonElement el = e.getValue();
                        if (el.isJsonPrimitive()) {
                            // 旧格式：模型名字符串
                            entities.put(uuid, new EntitySettings(el.getAsString()));
                        } else if (el.isJsonObject()) {
                            EntitySettings s = GSON.fromJson(el, EntitySettings.class);
                            if (s != null && s.model != null) entities.put(uuid, s);
                        }
                    } catch (IllegalArgumentException ignore) {}
                }
            }
            LOGGER.info("[ModelYSM] loaded {} entity model bindings", entities.size());
        } catch (Exception e) {
            LOGGER.warn("[ModelYSM] failed to load bindings.json: {}", e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(entities, w);
            }
        } catch (IOException e) {
            LOGGER.warn("[ModelYSM] failed to save bindings.json: {}", e.getMessage());
        }
    }

    /** 读取绑定模型名（null=未绑定） */
    public String get(UUID npc) {
        EntitySettings s = entities.get(npc);
        return s == null ? null : s.model;
    }

    /** 设置绑定模型（保留已有其它设置） */
    public boolean put(UUID npc, String modelId) {
        EntitySettings s = entities.computeIfAbsent(npc, k -> new EntitySettings(modelId));
        String old = s.model;
        s.model = modelId;
        save();
        return !modelId.equals(old);
    }

    /** 移除整条绑定+设置，返回被移除的模型 id（null=本来没有） */
    public String remove(UUID npc) {
        EntitySettings s = entities.remove(npc);
        if (s != null) save();
        return s == null ? null : s.model;
    }

    /** 读取实体设置（null=未绑定） */
    public EntitySettings settings(UUID npc) {
        return entities.get(npc);
    }

    /** 修改实体设置（未绑定时返回 false） */
    public boolean modify(UUID npc, Consumer<EntitySettings> fn) {
        EntitySettings s = entities.get(npc);
        if (s == null) return false;
        fn.accept(s);
        save();
        return true;
    }

    public Map<UUID, EntitySettings> all() {
        return entities;
    }

    /** /modelysm reload：从磁盘重新读 bindings.json（外部手改后无需重启） */
    public void reload() {
        load();
    }
}
