package ui.modelysm;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ui.modelysm.common.ModelBindingStore;
import ui.modelysm.network.Net;
import ui.modelysm.server.ModelYsmCommand;
import ui.modelysm.server.NpcTracker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ModelYSM — YSM(Yes Steve Model) 格式模型渲染器，可将 YSM 格式模型绑定到
 * Citizens 等插件创建的 NPC（玩家类型实体）上渲染。
 *
 * 服务端：识别 Citizens NPC、绑定模型、同步映射给装有本模组的客户端；
 * 客户端：加载 config/modelysm/models/ 下的 YSM 格式模型（Bedrock 几何+动画），
 *         拦截 NPC 渲染并用自己的 Bedrock 渲染器绘制。
 */
@Mod(ModelYSM.MODID)
public class ModelYSM {
    public static final String MODID = "modelysm";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public ModelYSM() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);
        extractBuiltinModels();
    }

    /** 首次启动：把 jar 内置的示例 YSM 模型释放到 config/modelysm/models/ */
    private void extractBuiltinModels() {
        try {
            Path root = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("modelysm").resolve("models");
            if (Files.isDirectory(root)) {
                try (var s = Files.list(root)) {
                    if (s.findAny().isPresent()) return; // 已有模型，不覆盖
                }
            }
            Files.createDirectories(root);
            String[] bundled = {"default", "alex", "steve", "default_boy", "wine_fox"};
            int copied = 0;
            for (String name : bundled) {
                Path dir = root.resolve(name);
                Files.createDirectories(dir);
                for (String file : List.of("main.json", "main.animation.json")) {
                    try (var in = getClass().getResourceAsStream("/com/fox/ysmu/custom/" + name + "/" + file)) {
                        if (in == null) continue;
                        Files.copy(in, dir.resolve(file));
                        copied++;
                    }
                }
                // 贴图：尝试常见的几个文件名
                for (String file : List.of("default.png", "gsl.png", "tartaric_acid.png", "red.png", "skin.png")) {
                    try (var in = getClass().getResourceAsStream("/com/fox/ysmu/custom/" + name + "/" + file)) {
                        if (in == null) continue;
                        Files.copy(in, dir.resolve(file));
                        copied++;
                    }
                }
            }
            LOGGER.info("[ModelYSM] extracted {} builtin sample model files to {}", copied, root);
        } catch (Exception e) {
            LOGGER.warn("[ModelYSM] failed to extract builtin models: {}", e.getMessage());
        }
    }

    private void setup(final FMLCommonSetupEvent event) {
        event.enqueueWork(Net::register);
        LOGGER.info("ModelYSM loaded: YSM-format NPC model renderer (server: bind+sync, client: render)");
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerAboutToStart(net.minecraftforge.event.server.ServerAboutToStartEvent event) {
        // 服务端后台导入新放入的 .ysm（/modelysm bind 校验模型列表用）
        ui.modelysm.common.YsmImporter.importAsync();
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ModelYsmCommand.register(event.getDispatcher());
    }

    /** 获取绑定存储（服务端） */
    public static ModelBindingStore bindings() {
        return ModelBindingStore.get();
    }
}
