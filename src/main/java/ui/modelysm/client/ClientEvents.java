package ui.modelysm.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import ui.modelysm.ModelYSM;
import ui.modelysm.client.gui.ModelWheelScreen;
import ui.modelysm.network.Net;
import ui.modelysm.network.packets.ClientHelloC2S;

/** 客户端事件：进服发送 hello 声明模组存在；断开清状态；V 键打开模型设置轮盘 */
@Mod.EventBusSubscriber(modid = ModelYSM.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {}

    public static final KeyMapping WHEEL_KEY = new KeyMapping(
            "key.modelysm.wheel", GLFW.GLFW_KEY_V, "key.categories.modelysm");

    /** MOD 总线：注册按键 */
    @Mod.EventBusSubscriber(modid = ModelYSM.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(WHEEL_KEY);
        }
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Net.CHANNEL.sendToServer(new ClientHelloC2S());
        // 后台预导入新放入的 .ysm 模型（打开模型界面时列表即齐全）
        ui.modelysm.common.YsmImporter.importAsync();
        ModelYSM.LOGGER.info("[ModelYSM] logged in, sent hello to server ({} npcs expected)", ClientState.size());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientState.setBindings(java.util.Map.of());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // 官方 UploadManager.processPendingUploads：后台解好的贴图在渲染线程按 20ms/帧 预算上传
        ui.modelysm.client.ModelLibrary.processPendingUploads();
        // 【诊断】dev 环境主菜单自动进档并打开实验界面（生产环境跳过）
        if (!net.minecraftforge.fml.loading.FMLLoader.isProduction()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen
                    && ++devTestTicks == 20) {
                devTestPending = true;
                mc.createWorldOpenFlows().loadLevel(null, "新的世界");
            }
            if (devTestPending && mc.level != null && mc.player != null && mc.screen == null) {
                devTestPending = false;
                smokeTestDirectYsm();
                mc.setScreen(new TestPipelineScreen());
            }
        }
        while (WHEEL_KEY.consumeClick()) {
            openWheel();
        }
    }
    private static int devTestTicks = 0;
    private static boolean devTestPending = false;

    /** 【诊断】dev 冒烟：直读全部 .ysm（解密+反序列化+贴图注册），结果打日志 */
    private static void smokeTestDirectYsm() {
        try {
            var ids = ui.modelysm.client.ModelLibrary.listModels();
            ModelYSM.LOGGER.info("[SMOKE] models: {}", ids);
            for (String id : ids) {
                try {
                    var lm = ui.modelysm.client.ModelLibrary.get(id);
                    ModelYSM.LOGGER.info("[SMOKE] {} -> bones={} anims={} textures={}",
                            id, lm != null ? lm.model.bonesByName.size() : -1,
                            lm != null ? lm.animations.size() : -1,
                            lm != null ? lm.textures.size() : -1);
                } catch (Exception e) {
                    ModelYSM.LOGGER.error("[SMOKE] {} FAILED: {}", id, e.toString());
                }
            }
        } catch (Throwable t) {
            ModelYSM.LOGGER.error("[SMOKE] crashed", t);
        }
    }

    /** 准星 8 格内找已绑定实体 → 打开轮盘 */
    private static void openWheel() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null) return;

        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(8.0));
        AABB searchBox = player.getBoundingBox().expandTowards(player.getLookAngle().scale(8.0)).inflate(2.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, searchBox,
                e -> e instanceof LivingEntity && e != player && e.isPickable(), 64.0);
        if (hit == null) {
            player.displayClientMessage(Component.literal("§c准星范围内(8格)没有实体"), true);
            return;
        }
        LivingEntity target = (LivingEntity) hit.getEntity();
        // 绑定机制改为"V 键对准实体开轮盘 → 点【绑定模型】/【解除绑定】"，
        // 未绑定的实体也允许打开轮盘（旧流程在这里拦掉并提示用命令，已移除）
        String modelId = ClientState.bindingFor(target.getUUID());
        String name = target.hasCustomName() ? target.getCustomName().getString() : target.getName().getString();
        mc.setScreen(new ModelWheelScreen(target.getUUID(), name, modelId == null ? "" : modelId));
    }
}
