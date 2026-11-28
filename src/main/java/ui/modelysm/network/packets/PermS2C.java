package ui.modelysm.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import ui.modelysm.client.ClientEvents;
import ui.modelysm.client.ClientState;

import java.util.function.Supplier;

/**
 * S2C：把玩家自己的权限等级告诉客户端（0=普通，1=帮助员，2+=管理员）。
 * 客户端据此在按 V 时提前拦掉，避免普通玩家看到打不开的轮盘。
 */
public class PermS2C {

    private final int level;

    public PermS2C(int level) {
        this.level = level;
    }

    public static void encode(PermS2C msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.level);
    }

    public static PermS2C decode(FriendlyByteBuf buf) {
        return new PermS2C(buf.readVarInt());
    }

    public static void handle(PermS2C msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientState.setPermLevel(msg.level);
            ClientEvents.onPermissionUpdate(msg.level);
        });
        ctx.get().setPacketHandled(true);
    }
}
