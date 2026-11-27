package ui.modelysm.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import ui.modelysm.ModelYSM;
import ui.modelysm.client.render.BedrockAnimation;
import ui.modelysm.client.render.NpcRenderer;

import java.util.Set;

/**
 * 【诊断用】v40 官方语义验证（dev runClient 自动执行，不部署使用）：
 * 复刻生产参数（head yaw/pitch 非零、scale 1.25）+ fly 动画 + 酒狐耳朵检查。
 * 动画时间逐帧推进，跑 4 秒截图 A/B 后自动退出游戏。
 */
public class TestPipelineScreen extends Screen {
    private int ticks = 0;
    private int shot = 0;
    private float animTime = 0;
    private ui.modelysm.client.gui.ModelWheelScreen wheel;
    /** 与 NpcRenderer 内部 entity==null 时的预览状态 UUID 保持一致 */
    private static final java.util.UUID PREVIEW_UUID =
            java.util.UUID.nameUUIDFromBytes("modelysm-card-preview".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final java.util.UUID PREVIEW_UUID2 =
            java.util.UUID.nameUUIDFromBytes("modelysm-card-preview2".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    public TestPipelineScreen() {
        super(Component.literal("v40 semantics test"));
    }

    private record Card(String modelId, String anim, float headYaw, float headPitch, float scale, String label) {}
    private static final Card[] CARDS = {
            new Card("Ender_Dragon", "walk", 25f, 8f, 1.25f, "龙walk+头转"),
            new Card("Ender_Dragon", "idle", 0f, 0f, 1.25f, "龙idle"),
            new Card("Ender_Dragon", "fly", 0f, 0f, 1.25f, "龙fly"),
            new Card("wine_fox", "idle", 0f, 0f, 1.0f, "酒狐耳朵"),
    };

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        g.fill(0, 0, width, height, 0xB0101010);
        animTime += pt;
        // 推进预览状态动画时间（NpcRenderer 内部用它采样关键帧）
        AnimStates.get(PREVIEW_UUID).animTime = animTime;
        AnimStates.get(PREVIEW_UUID2).animTime = animTime;

        // 2x2 布局：dev 窗口 GUI scale=2 时单行 4 卡会被裁掉（诊断卡需完整可见）
        int cw = 100, chh = 112, gap = 4;
        int x0 = 8, y0 = 16;
        for (int c = 0; c < CARDS.length; c++) {
            Card card = CARDS[c];
            int x = x0 + (c % 2) * (cw + gap), y = y0 + (c / 2) * (chh + 14);
            g.fill(x, y, x + cw, y + chh, 0xE0181818);
            g.fill(x + 2, y + 2, x + cw - 2, y + chh - 2, 0xFF101418);
            g.drawCenteredString(font, card.label(), x + cw / 2, y - 10, 0xFFFFC0);
            ModelLibrary.LoadedModel lm = ModelLibrary.get(card.modelId());
            if (lm != null && lm.defaultTexture() != null) {
                BedrockAnimation anim = card.anim() == null ? null : lm.animations.get(card.anim());
                renderCard(g, lm, x + 2, y + 2, cw - 4, chh - 4, pt, anim, card);
            } else {
                g.drawCenteredString(font, "模型缺失", x + cw / 2, y + chh / 2, 0xFF6060);
            }
        }
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();

        if (ticks % 80 == 0 && shot < 2) {
            NpcRenderer.DEBUG_LOG_EXTREME = true;
            String name = shot == 0 ? "A" : "B";
            ModelYSM.LOGGER.info("[HAIRTEST] screenshot {}", name);
            capture("testpipe_" + name);
            shot++;
        }
        // v41：官方轮盘 ⚙ 配置表单 UI 验证——手动 init 并叠加渲染设置面板（不切换 screen，
        // 否则本 Screen 的 render/截图/退出流程被中断）。wine_fox 含官方 extra_animation_buttons。
        if (ticks == 130) {
            wheel = new ui.modelysm.client.gui.ModelWheelScreen(PREVIEW_UUID, "配置表单测试", "Ender_Dragon");
            wheel.init(minecraft, width, height);
            wheel.debugOpenPanel();
            ModelLibrary.LoadedModel lm = ModelLibrary.get("Ender_Dragon");
            ModelYSM.LOGGER.info("[HAIRTEST] wine_fox configButtons={} extraAnims={}",
                    lm != null ? lm.configButtons.size() : -1,
                    lm != null ? lm.extraAnimations.size() : -1);
            // v43 行为绑定验证：官方行为表加载 + 各模型行为动画名盘点
            ModelYSM.LOGGER.info("[HAIRTEST] behavior table entries={} npcUnsupported={}",
                    ui.modelysm.client.Behaviors.TABLE.size(),
                    ui.modelysm.client.Behaviors.TABLE.stream().filter(b -> !b.npcSupport).map(b -> b.id).toList());
            for (String mid : new String[]{"Ender_Dragon", "wine_fox", "Rossi", "123"}) {
                ModelLibrary.LoadedModel m = ModelLibrary.get(mid);
                if (m == null) continue;
                int hold = 0, use = 0, swing = 0, pass = 0, main = 0;
                for (String an : m.animations.keySet()) {
                    if (an.startsWith("hold_")) hold++;
                    else if (an.startsWith("use_")) use++;
                    else if (an.startsWith("swing") || an.equals("attack")) swing++;
                    else if (an.startsWith("passenger")) pass++;
                    main++;
                }
                ModelYSM.LOGGER.info("[HAIRTEST] behavior anims {}: total={} hold={} use={} swing={} passenger={}",
                        mid, main, hold, use, swing, pass);
            }
        }
        if (wheel != null && ticks >= 130) {
            wheel.render(g, width / 2, height / 2, pt);
        }
        if (ticks == 190) {
            // 滚到"行为绑定"分区截图（v43）
            wheel.debugScrollToHeader("行为绑定");
        }
        if (ticks == 200) {
            capture("testpipe_wheel");
            wheel.panelScroll = 0;
        }
        if (ticks > 220) {
            ModelYSM.LOGGER.info("[HAIRTEST] DONE: exiting game");
            minecraft.stop();
        }
        ticks++;
    }

    private void renderCard(GuiGraphics g, ModelLibrary.LoadedModel lm, int x, int y, int w, int h,
                            float pt, BedrockAnimation anim, Card card) {
        g.enableScissor(x, y, x + w, y + h);
        PoseStack ps = new PoseStack();
        ps.translate(x + w / 2.0, y + h - 14, 250);
        ps.scale(24, -24, 24);
        MultiBufferSource.BufferSource bufs = Minecraft.getInstance().renderBuffers().bufferSource();
        NpcRenderer.render(null, lm, anim, 0, card.headYaw(), card.headPitch(), pt, ps, bufs, 0xF000F0,
                card.scale(), card.scale(), card.scale(), Set.of(), null, 0, 0, 0, false);
        g.disableScissor();
    }

    /** vanilla Screenshot.takeScreenshot 同款：主帧缓冲整帧下载保存到 run/screenshots */
    private void capture(String name) {
        try {
            RenderTarget rt = minecraft.getMainRenderTarget();
            NativeImage img = new NativeImage(rt.width, rt.height, false);
            com.mojang.blaze3d.systems.RenderSystem.bindTexture(rt.getColorTextureId());
            img.downloadTexture(0, true); // invertY=true，无需再 flip
            java.io.File dir = new java.io.File("screenshots");
            if (!dir.exists()) dir.mkdirs();
            img.writeToFile(new java.io.File(dir, name + ".png"));
            img.close();
            ModelYSM.LOGGER.info("[HAIRTEST] saved screenshots/{}.png", name);
        } catch (Exception e) {
            ModelYSM.LOGGER.error("[HAIRTEST] capture failed", e);
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
