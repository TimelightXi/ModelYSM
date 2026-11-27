package ui.modelysm.client;

import ui.modelysm.common.EntitySettings;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 客户端状态：实体→模型绑定+设置 映射缓存 */
public final class ClientState {
    private ClientState() {}

    private static volatile Map<UUID, EntitySettings> bindings = Map.of();

    public static void setBindings(Map<UUID, EntitySettings> map) {
        bindings = new HashMap<>(map);
        // 清理不再绑定的实体动画状态
        AnimStates.cleanup(bindings.keySet());
    }

    public static String bindingFor(UUID npc) {
        EntitySettings s = bindings.get(npc);
        return s == null ? null : s.model;
    }

    public static EntitySettings settingsFor(UUID npc) {
        return bindings.get(npc);
    }

    public static int size() {
        return bindings.size();
    }
}
