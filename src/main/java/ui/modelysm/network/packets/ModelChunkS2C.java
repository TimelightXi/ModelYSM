package ui.modelysm.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import ui.modelysm.client.ModelReceive;
import ui.modelysm.ModelYSM;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

/**
 * S2C：模型文件分块传输（服务端流式下发 .ysm）。
 * total==0 表示"模型不存在"应答（客户端标记不可用，避免反复请求）。
 */
public class ModelChunkS2C {
    private static final Logger LOGGER = LogManager.getLogger(ModelYSM.MODID);

    public final String modelId;
    public final int seq;          // 0-based
    public final int total;        // 总块数（0=不存在）
    public final byte[] data;

    public ModelChunkS2C(String modelId, int seq, int total, byte[] data) {
        this.modelId = modelId;
        this.seq = seq;
        this.total = total;
        this.data = data;
    }

    public static void encode(ModelChunkS2C msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.modelId, 256);
        buf.writeVarInt(msg.seq);
        buf.writeVarInt(msg.total);
        buf.writeVarInt(msg.data.length);
        buf.writeByteArray(msg.data);
    }

    public static ModelChunkS2C decode(FriendlyByteBuf buf) {
        String id = buf.readUtf(256);
        int seq = buf.readVarInt();
        int total = buf.readVarInt();
        int len = buf.readVarInt();
        byte[] data = new byte[len];
        if (len > 0) data = buf.readByteArray(len);
        return new ModelChunkS2C(id, seq, total, data);
    }

    public static void handle(ModelChunkS2C msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                ModelReceive.receiveChunk(msg.modelId, msg.seq, msg.total, msg.data);
            } catch (Exception e) {
                LOGGER.warn("[ModelYSM] chunk receive failed for {}: {}", msg.modelId, e.getMessage());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
