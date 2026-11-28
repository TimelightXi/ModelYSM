package ui.modelysm.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 权限判定（服务端权威）。
 * <p>
 * 规则：OP 等级 ≥ 2 的管理员可操作模型轮盘（绑定 / 解绑 / 动画 / 体型 / 表单）；
 * 单人存档（集成服务端）不做 OP 校验，避免单机玩家把自己锁在门外；
 * 其余（专用服务器上的普通玩家）一律拒绝。
 */
public final class Perms {

    private Perms() {
    }

    /** 权限等级：0=普通，1=帮助员，2+=管理员（下发给客户端用于提前拦 V 键） */
    public static int level(ServerPlayer player) {
        if (player.hasPermissions(2)) {
            return 2;
        }
        if (player.hasPermissions(1)) {
            return 1;
        }
        return 0;
    }

    /** 是否可以修改实体模型设置 */
    public static boolean canEdit(ServerPlayer player) {
        if (player.hasPermissions(2)) {
            return true;
        }
        MinecraftServer server = player.getServer();
        return server != null && server.isSingleplayer();
    }

    public static final String DENY_WHEEL = "§c你没有权限使用模型轮盘（需要管理员权限）";
    public static final String DENY_EDIT = "§c你没有权限修改模型设置（需要管理员权限）";
}
