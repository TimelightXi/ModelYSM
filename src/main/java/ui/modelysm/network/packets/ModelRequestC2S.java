package ui.modelysm.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import ui.modelysm.server.ModelTransferManager;

import java.util.function.Supplier;

/**
 * C2S：客户端请求下载模型（本地缺该 .ysm 时）。
 * 服务端校验存在后经 ModelTransferManager 分块流式下发。
 */
public class ModelRequestC2S {
    private final String modelId;

    public ModelRequestC2S(String modelId) {
        this.modelId = modelId;
    }

    public static void encode(ModelRequestC2S msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.modelId == null ? "" : msg.modelId, 256);
    }

    public static ModelRequestC2S decode(FriendlyByteBuf buf) {
        return new ModelRequestC2S(buf.readUtf(256));
    }

    public static void handle(ModelRequestC2S msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (sender != null && msg.modelId != null && !msg.modelId.isBlank()) {
                ModelTransferManager.request(sender, msg.modelId.trim());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
