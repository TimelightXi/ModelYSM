package ui.modelysm.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import ui.modelysm.ModelYSM;
import ui.modelysm.network.packets.ClientHelloC2S;
import ui.modelysm.network.packets.EntityActionC2S;
import ui.modelysm.network.packets.ModelChunkS2C;
import ui.modelysm.network.packets.ModelRequestC2S;
import ui.modelysm.network.packets.PermS2C;
import ui.modelysm.network.packets.SyncNpcModelsS2C;
import ui.modelysm.network.packets.WheelRequestC2S;

/**
 * 自定义网络通道：
 *  C2S：ClientHello 客户端声明已安装；EntityAction GUI 修改实体设置；ModelRequest 请求下载模型
 *       WheelRequest 按 V 问权限（普通玩家被服务端回绝）
 *  S2C：SyncNpcModels 全量同步 绑定+设置；ModelChunk 模型分块下发；Perm 权限等级
 * 版本 5：v3 增加配置变量/贴图索引，v4 增加模型流式分发，v5 增加轮盘权限校验
 */
public final class Net {
    private static final String VERSION = "5";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ModelYSM.MODID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private static int id = 0;

    public static void register() {
        CHANNEL.registerMessage(id++, ClientHelloC2S.class, ClientHelloC2S::encode, ClientHelloC2S::decode, ClientHelloC2S::handle);
        CHANNEL.registerMessage(id++, SyncNpcModelsS2C.class, SyncNpcModelsS2C::encode, SyncNpcModelsS2C::decode, SyncNpcModelsS2C::handle);
        CHANNEL.registerMessage(id++, EntityActionC2S.class, EntityActionC2S::encode, EntityActionC2S::decode, EntityActionC2S::handle);
        CHANNEL.registerMessage(id++, ModelRequestC2S.class, ModelRequestC2S::encode, ModelRequestC2S::decode, ModelRequestC2S::handle);
        CHANNEL.registerMessage(id++, ModelChunkS2C.class, ModelChunkS2C::encode, ModelChunkS2C::decode, ModelChunkS2C::handle);
        CHANNEL.registerMessage(id++, WheelRequestC2S.class, WheelRequestC2S::encode, WheelRequestC2S::decode, WheelRequestC2S::handle);
        CHANNEL.registerMessage(id++, PermS2C.class, PermS2C::encode, PermS2C::decode, PermS2C::handle);
    }
}
