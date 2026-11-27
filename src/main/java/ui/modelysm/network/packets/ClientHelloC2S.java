package ui.modelysm.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import ui.modelysm.server.NpcTracker;

import java.util.function.Supplier;

/** C2S：客户端登录后声明自己安装了 ModelYSM（服务端据此决定是否给该玩家同步映射） */
public class ClientHelloC2S {

    public ClientHelloC2S() {}

    public static void encode(ClientHelloC2S msg, FriendlyByteBuf buf) {}

    public static ClientHelloC2S decode(FriendlyByteBuf buf) {
        return new ClientHelloC2S();
    }

    public static void handle(ClientHelloC2S msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) {
                NpcTracker.onClientHello(sender);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
