package ui.modelysm.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import ui.modelysm.client.ClientState;
import ui.modelysm.common.EntitySettings;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** S2C：全量同步 实体→模型绑定+设置（模型/体型/动画/骨骼显隐） */
public class SyncNpcModelsS2C {
    private final Map<UUID, EntitySettings> map;

    public SyncNpcModelsS2C(Map<UUID, EntitySettings> map) {
        this.map = map;
    }

    public static void encode(SyncNpcModelsS2C msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.map.size());
        for (Map.Entry<UUID, EntitySettings> e : msg.map.entrySet()) {
            EntitySettings s = e.getValue();
            buf.writeUUID(e.getKey());
            buf.writeUtf(s.model == null ? "" : s.model, 256);
            buf.writeFloat(s.scale);
            buf.writeBoolean(s.anim != null);
            if (s.anim != null) buf.writeUtf(s.anim, 256);
            buf.writeVarInt(s.hiddenBones.size());
            for (String b : s.hiddenBones) {
                buf.writeUtf(b, 256);
            }
            buf.writeBoolean(s.forcedPart != null);
            if (s.forcedPart != null) buf.writeUtf(s.forcedPart, 256);
            buf.writeVarInt(s.molVars.size());
            for (Map.Entry<String, Float> mv : s.molVars.entrySet()) {
                buf.writeUtf(mv.getKey(), 256);
                buf.writeFloat(mv.getValue());
            }
            buf.writeVarInt(s.textureIndex);
        }
    }

    public static SyncNpcModelsS2C decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<UUID, EntitySettings> map = new HashMap<>();
        for (int i = 0; i < n; i++) {
            UUID uuid = buf.readUUID();
            EntitySettings s = new EntitySettings();
            s.model = buf.readUtf(256);
            s.scale = buf.readFloat();
            if (buf.readBoolean()) s.anim = buf.readUtf(256);
            int hn = buf.readVarInt();
            for (int j = 0; j < hn; j++) {
                s.hiddenBones.add(buf.readUtf(256));
            }
            if (buf.readBoolean()) s.forcedPart = buf.readUtf(256);
            int vn = buf.readVarInt();
            for (int j = 0; j < vn; j++) {
                // 变量名统一小写：Molang 标识符大小写不敏感（官方模型有 V.CBody 写法）
                s.molVars.put(buf.readUtf(256).toLowerCase(java.util.Locale.ROOT), buf.readFloat());
            }
            s.textureIndex = buf.readVarInt();
            map.put(uuid, s);
        }
        return new SyncNpcModelsS2C(map);
    }

    public static void handle(SyncNpcModelsS2C msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientState.setBindings(msg.map);
            // 模型流式分发：本地缺失的绑定模型向服务端请求下载（去重+冷却）
            try {
                java.util.HashSet<String> ids = new java.util.HashSet<>();
                for (EntitySettings s : msg.map.values()) {
                    if (s.model != null && !s.model.isBlank()) ids.add(s.model);
                }
                ui.modelysm.client.ModelReceive.requestMissing(ids);
            } catch (Exception ignored) {}
        });
        ctx.get().setPacketHandled(true);
    }
}
