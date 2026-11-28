package ui.modelysm.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import ui.modelysm.network.Net;
import ui.modelysm.server.Perms;

import java.util.function.Supplier;

/**
 * C2S：玩家按 V 想开轮盘，但客户端不确定自己有没有权限时发这个。
 * 服务端回 PermS2C（当前等级）；没有管理员权限就直接在聊天栏告诉他没权限，不发任何界面包。
 */
public class WheelRequestC2S {

    public WheelRequestC2S() {
    }

    public static void encode(WheelRequestC2S msg, FriendlyByteBuf buf) {
    }

    public static WheelRequestC2S decode(FriendlyByteBuf buf) {
        return new WheelRequestC2S();
    }

    public static void handle(WheelRequestC2S msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            int level = Perms.level(player);
            Net.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PermS2C(level));
            if (!Perms.canEdit(player)) {
                player.sendSystemMessage(Component.literal(Perms.DENY_WHEEL));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
