package ui.modelysm.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import ui.modelysm.common.EntitySettings;
import ui.modelysm.common.ModelBindingStore;
import ui.modelysm.server.NpcTracker;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：GUI 操作目标实体设置（动画/体型/骨骼显隐/重置）。
 * 服务端校验 OP(2) 权限，成功后广播同步。
 */
public class EntityActionC2S {
    public static final int PLAY_ANIM = 0;
    public static final int STOP_ANIM = 1;
    public static final int SET_SCALE = 2;
    public static final int HIDE_BONE = 3;
    public static final int SHOW_BONE = 4;
    public static final int RESET = 5;
    public static final int SET_PART = 6;   // 强制形态（payload=部件骨骼名，空串=自动）
    public static final int SET_MODEL = 7;  // 切换绑定模型（payload=模型 id）
    public static final int SET_VAR = 8;    // 配置表单变量赋值（payload="v.xxx=值"，静默不刷聊天栏）
    public static final int EXEC_SCRIPT = 9; // 配置表单 molang 常量赋值串（payload="v.a=0; v.b=1;"，radio labels）
    public static final int SET_TEXTURE = 10; // 切换贴图（payload=贴图索引数字，静默）
    public static final int BIND_MODEL = 11;  // GUI 绑定模型（payload=模型 id；未绑定时直接建档）
    public static final int UNBIND_MODEL = 12;// GUI 解除绑定（payload 忽略）

    private final UUID entity;
    private final int action;
    private final String payload;

    public EntityActionC2S(UUID entity, int action, String payload) {
        this.entity = entity;
        this.action = action;
        this.payload = payload;
    }

    public static void encode(EntityActionC2S msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.entity);
        buf.writeVarInt(msg.action);
        // 4096：EXEC_SCRIPT 携带 radio labels 的整段 molang 赋值串（官方实测最长 ~400 字符）
        buf.writeUtf(msg.payload == null ? "" : msg.payload, 4096);
    }

    public static EntityActionC2S decode(FriendlyByteBuf buf) {
        return new EntityActionC2S(buf.readUUID(), buf.readVarInt(), buf.readUtf(4096));
    }

    /** 变量名规范化：variable.x → v.x（Molang 查询/注入统一用 "v." 规范形式，保证命中） */
    private static String normalizeVarName(String name) {
        // 统一小写：Molang 标识符大小写不敏感（官方模型表单既有 v.CBody 也有 V.CBody）
        if (name.startsWith("variable.") || name.startsWith("Variable."))
            name = "v." + name.substring("variable.".length());
        return name.toLowerCase(java.util.Locale.ROOT);
    }

    /** 模型变量名白名单：仅允许 v./variable. 前缀（配置表单写入的模型自定义变量） */
    private static boolean isValidVarName(String name) {
        return (name.startsWith("v.") || name.startsWith("variable."))
                && name.length() <= 128
                && name.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '.' || c == '_');
    }

    public static void handle(EntityActionC2S msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !player.hasPermissions(2)) return;

            ModelBindingStore store = ModelBindingStore.get();
            // ---- 绑定 / 解绑：唯一能在 GUI 里创建、删除绑定的入口（旧 /modelysm bind 已移除） ----
            if (msg.action == UNBIND_MODEL) {
                String old = store.remove(msg.entity);
                NpcTracker.broadcastSync();
                player.sendSystemMessage(Component.literal(old == null
                        ? "§e该实体本来就没有绑定模型"
                        : "§a已解除绑定（原模型: §e" + old + "§a）"));
                return;
            }
            if (msg.action == BIND_MODEL) {
                String m = msg.payload == null ? "" : msg.payload.trim();
                if (m.isEmpty()) return;
                EntitySettings cur = store.settings(msg.entity);
                if (cur == null) {
                    store.put(msg.entity, m);
                } else if (!m.equals(cur.model)) {
                    // 换模型：清空旧模型专属设置，保留体型
                    cur.model = m;
                    cur.anim = null;
                    cur.forcedPart = null;
                    cur.hiddenBones.clear();
                    cur.molVars.clear();
                    cur.textureIndex = 0;
                    store.save();
                }
                NpcTracker.broadcastSync();
                player.sendSystemMessage(Component.literal("§a已绑定模型 §e" + m));
                return;
            }

            EntitySettings s = store.settings(msg.entity);
            if (s == null) {
                player.sendSystemMessage(Component.literal("§c该实体未绑定模型，请按 V 打开轮盘点【绑定模型】"));
                return;
            }
            String ok;
            switch (msg.action) {
                case PLAY_ANIM -> {
                    String anim = msg.payload.trim();
                    if (anim.isEmpty()) return;
                    s.anim = anim;
                    ok = "§a已设置播放动画 §e" + anim;
                }
                case STOP_ANIM -> {
                    s.anim = null;
                    ok = "§a已停止强制动画，恢复自动状态机";
                }
                case SET_SCALE -> {
                    // payload: 单值整体缩放（兼容旧的三轴 "x,y,z" 格式，取第一个值）
                    float v;
                    try {
                        v = Float.parseFloat(msg.payload.trim().split(",")[0].trim());
                    } catch (NumberFormatException e) {
                        return;
                    }
                    s.scale = Math.max(0.1f, Math.min(3.0f, v));
                    // 静默：GUI 滑条实时调控会连续发包，不刷聊天栏
                    store.save();
                    NpcTracker.broadcastSync();
                    return;
                }
                case HIDE_BONE -> {
                    if (msg.payload.isBlank()) return;
                    s.hiddenBones.add(msg.payload);
                    ok = "§a已隐藏骨骼 §e" + msg.payload;
                }
                case SHOW_BONE -> {
                    s.hiddenBones.remove(msg.payload);
                    ok = "§a已显示骨骼 §e" + msg.payload;
                }
                case RESET -> {
                    s.reset();
                    ok = "§a已重置该实体的全部模型设置";
                }
                case SET_PART -> {
                    String p = msg.payload == null ? "" : msg.payload.trim();
                    s.forcedPart = p.isEmpty() ? null : p;
                    ok = p.isEmpty() ? "§a已恢复自动形态"
                            : "§a已强制形态为 §e" + p;
                }
                case SET_MODEL -> {
                    String m = msg.payload == null ? "" : msg.payload.trim();
                    if (m.isEmpty()) return;
                    s.model = m;
                    // 新模型：清空旧模型专属设置（动画名/骨骼名/形态/变量/贴图不再有效），保留体型
                    s.anim = null;
                    s.forcedPart = null;
                    s.hiddenBones.clear();
                    s.molVars.clear();
                    s.textureIndex = 0;
                    ok = "§a已切换模型为 §e" + m;
                }
                case SET_TEXTURE -> {
                    // payload: 贴图索引（数字字符串），静默；服务端不校验模型贴图数（客户端渲染端越界回退默认）
                    try {
                        s.textureIndex = Math.max(0, Integer.parseInt(msg.payload.trim()));
                        store.save();
                        NpcTracker.broadcastSync();
                    } catch (NumberFormatException ignored) {}
                    return;
                }
                case SET_VAR -> {
                    // payload: "v.xxx=数值"（配置表单 checkbox=0/1、range=滑条值），静默处理
                    int eq = msg.payload.indexOf('=');
                    if (eq <= 0) return;
                    String name = normalizeVarName(msg.payload.substring(0, eq).trim());
                    float v;
                    try {
                        v = Float.parseFloat(msg.payload.substring(eq + 1).trim());
                    } catch (NumberFormatException e) {
                        return;
                    }
                    if (!isValidVarName(name)) return;
                    s.molVars.put(name, v);
                    store.save();
                    NpcTracker.broadcastSync();
                    return;
                }
                case EXEC_SCRIPT -> {
                    // payload: 分号分隔的 molang 常量赋值串（官方 radio labels 83 条全为 "v.x=数字"）
                    // 非常量表达式（依赖实体上下文）服务端无法求值，忽略该条并提示
                    boolean any = false, skipped = false;
                    for (String stmt : msg.payload.split(";")) {
                        String t = stmt.trim();
                        if (t.isEmpty()) continue;
                        int e2 = t.indexOf('=');
                        // 排除 ==/<=/>=/!=/?? 等比较运算
                        if (e2 <= 0 || (e2 + 1 < t.length() && "==<>=!?".indexOf(t.charAt(e2 + 1)) >= 0)) {
                            skipped = true;
                            continue;
                        }
                        String name = normalizeVarName(t.substring(0, e2).trim());
                        float v;
                        try {
                            v = Float.parseFloat(t.substring(e2 + 1).trim());
                        } catch (NumberFormatException ex) {
                            skipped = true;
                            continue;
                        }
                        if (!isValidVarName(name)) continue;
                        s.molVars.put(name, v);
                        any = true;
                    }
                    if (any) {
                        store.save();
                        NpcTracker.broadcastSync();
                    }
                    if (skipped) {
                        player.sendSystemMessage(Component.literal("§7[配置表单] 部分赋值为非常量表达式，已跳过"));
                    }
                    return;
                }
                default -> {
                    return;
                }
            }
            store.save();
            NpcTracker.broadcastSync();
            player.sendSystemMessage(Component.literal(ok));
        });
        ctx.get().setPacketHandled(true);
    }
}
