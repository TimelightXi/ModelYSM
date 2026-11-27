package ui.modelysm.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import ui.modelysm.ModelYSM;
import ui.modelysm.client.ClientState;
import ui.modelysm.client.ModelLibrary;
import ui.modelysm.client.render.BedrockAnimation;
import ui.modelysm.client.render.BedrockModel;
import ui.modelysm.client.render.NpcRenderer;
import ui.modelysm.client.render.PreviewFboRenderer;
import ui.modelysm.common.EntitySettings;
import ui.modelysm.network.Net;
import ui.modelysm.network.packets.EntityActionC2S;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 官方 YSM 2.6.5 同款模型选择界面（对照 OpenYSM PlayerModelScreen 复刻）：
 * 左侧 135px 预览面板（悬停模型 3D 大预览 + 名称 + 版本号），
 * 右侧 5列x2行 卡片网格（每卡 52x90：scissor 3D 预览 + 底部名字条），
 * 顶部搜索框，底部 前一页/页码/后一页。
 */
public class ModelPickerScreen extends Screen {
    private final Screen parent;
    private final UUID entityUuid;
    private final String entityName;

    private List<String> allModels = new ArrayList<>();
    private List<String> models = new ArrayList<>();
    private int page = 0;
    private EditBox searchBox;

    // 官方布局常量（420x235 总面板）
    private static final int PANEL_W = 420, PANEL_H = 235;
    private static final int LEFT_W = 135;
    private static final int CARD_W = 52, CARD_H = 90, CARD_NAME_H = 20;

    /**
     * 官方卡片模型缩放：ModelButton 传给 renderLivingEntityPreview 的 scale 是 30.0f
     * （早期按卡片尺寸/包围盒估算都不对，会偏大或被裁；这里是定值）。
     */
    private static final float CARD_MODEL_SCALE = 30f;

    /** 官方左侧大预览用的缩放：InventoryScreen.renderEntityInInventoryFollowsMouse(..., 70) */
    private static final float BIG_PREVIEW_SCALE = 70f;

    /** 官方卡片的三个状态动画名（模型里存在才生效，否则官方就传 "empty"） */
    private static final String ANIM_HOVER = "hover";
    private static final String ANIM_HOVER_FADE = "hover_fadeout";
    private static final String ANIM_EMPTY = "empty";

    /** 每张卡片的 hover / 离开 hover 时间点，用于官方那套 hover → hover_fadeout → empty 状态机 */
    private final java.util.Map<String, Long> hoverStartMs = new java.util.HashMap<>();
    private final java.util.Map<String, Long> hoverEndMs = new java.util.HashMap<>();

    /** 官方 ModelButton 每张卡各自持有一个 PlayerPreviewEntity，动画状态互不共享；
     *  之前 10 张卡 + 左侧大预览共用一个 dummy 实体 → 同一份 AnimStates，鼠标滑到哪张卡，
     *  那份共享状态（时钟 / roaming 变量）就被那张卡改写，左侧大预览跟着"变成那张卡的样子"。
     *  这里按官方口径：每张卡一个 mock 实体。 */
    private final java.util.Map<String, LivingEntity> cardDummies = new java.util.HashMap<>();
    /** 左侧大预览自己的 mock 实体（官方渲染的是 localPlayer，与卡片实体完全分离） */
    private LivingEntity bigDummy;
    /** 每张卡片 base(preview_animation) 的时钟起点：悬停进入时重置 → 重播进场动画 */
    private final java.util.Map<String, Long> cardT0 = new java.util.HashMap<>();
    /** 上一帧每张卡是否悬停（只用来捕捉"刚进入悬停"那一帧） */
    private final java.util.Map<String, Boolean> hoveredPrev = new java.util.HashMap<>();

    /**
     * 左侧大预览已改成官方口径（PlayerModelScreen:664 + InventoryScreen.renderEntityInInventoryFollowsMouse）：
     * 视角完全由鼠标位置决定（身体 yaw = 180 + atan(dx/40)*20°，头 pitch = -atan(dy/40)*20°），
     * 拖拽 / 滚轮改变视角那套 Blockbench OrbitControls 操控已整体删除。
     * 只保留一个"预览时钟零点"，让 idle 动画从打开界面开始播。
     */
    private long bigT0 = 0L;

    private int guiLeft, guiTop;

    public ModelPickerScreen(Screen parent, UUID entityUuid, String entityName, String currentModel) {
        super(Component.literal("ModelYSM 模型选择"));
        this.parent = parent;
        this.entityUuid = entityUuid;
        this.entityName = entityName;
    }

    /** 当前绑定的模型（动态读取，切换后同步包回来即刷新高亮） */
    private String currentModel() {
        EntitySettings es = ClientState.settingsFor(entityUuid);
        return es != null ? es.model : null;
    }

    @Override
    protected void init() {
        allModels = new ArrayList<>(ModelLibrary.listModels());
        allModels.sort(String::compareTo);
        guiLeft = (width - PANEL_W) / 2;
        guiTop = (height - PANEL_H) / 2;

        String prev = searchBox != null ? searchBox.getValue() : "";
        // 官方 PlayerModelScreen.init: `new EditBox(font, guiLeft+144, guiTop+6, 140, 16)`，
        // 文字颜色 setTextColor(15986656)，重建界面时保留输入并把光标移到末尾。
        searchBox = new EditBox(Minecraft.getInstance().font, guiLeft + 144, guiTop + 6, 140, 16, Component.literal("搜索模型"));
        searchBox.setValue(prev);
        searchBox.setMaxLength(64);
        searchBox.setTextColor(15986656);
        searchBox.moveCursorToEnd();
        addWidget(searchBox);

        // 官方 PlayerModelScreen.init 的翻页按钮：FlatColorButton(52x14)，x = guiLeft+198 / guiLeft+308，
        // y = guiTop+215（= PANEL_H-20）。官方底部只有这两个按钮，没有"返回"——
        // 返回是工具条左侧的 20x20 图标（guiLeft+110, guiTop+27）。之前用的原版 Button 是立体灰渐变，
        // 而且"返回"摆在 +350 会和"后一页"(308..360) 叠在一起。
        addRenderableWidget(new FlatButton(guiLeft + 198, guiTop + PANEL_H - 20, 52, 14,
                Component.literal("前一页"), b -> page = (page + pageCount() - 1) % pageCount()));
        addRenderableWidget(new FlatButton(guiLeft + 308, guiTop + PANEL_H - 20, 52, 14,
                Component.literal("后一页"), b -> page = (page + 1) % pageCount()));
        // 退出按钮放在面板右上角（搜索框同一行、面板右边缘内 4px），"×" 语义就是关闭界面。
        // 之前按官方图标位放在 guiLeft+110/guiTop+27，正好压在左上第一张卡片上，看着像卡片上的按钮。
        addRenderableWidget(new FlatButton(guiLeft + PANEL_W - 24, guiTop + 4, 20, 20,
                Component.literal("×"), b -> onClose()));
        refreshFiltered();
    }

    private void refreshFiltered() {
        String q = searchBox != null ? searchBox.getValue().trim().toLowerCase(java.util.Locale.ROOT) : "";
        models = new ArrayList<>();
        for (String id : allModels) {
            if (q.isEmpty()) { models.add(id); continue; }
            ModelLibrary.LoadedModel lm = ModelLibrary.peek(id);
            String disp = lm != null && lm.displayName != null ? lm.displayName : id;
            if (id.toLowerCase(java.util.Locale.ROOT).contains(q) || disp.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                models.add(id);
            }
        }
        if (page >= pageCount()) page = Math.max(0, pageCount() - 1);
    }

    private int perPage() { return 10; }
    private int pageCount() { return Math.max(1, (models.size() + perPage() - 1) / perPage()); }

    /** 卡片坐标（官方：guiLeft+143 + 55*i, guiTop+28 + 93*row） */
    private int cardX(int i) { return guiLeft + 143 + (CARD_W + 3) * (i % 5); }
    private int cardY(int i) { return guiTop + 28 + (CARD_H + 3) * (i / 5); }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        if (page >= pageCount()) page = pageCount() - 1;

        // 面板底（官方同款深色）
        // 官方 PlayerModelScreen:474-475：整块面板两段都用同一个不透明纯色 -14540254 = 0xFF222222
        // （fillGradient(x0,y0,x1,y1,c,c) 等于纯色填充），中间留 3px 不画。
        // 之前用 0xF30E0E12（半透明近黑）+ 0xFF1E232B 的蓝灰分隔条，叠加在世界背景上就变成
        // 一团越往下越黑的"阴影暗区"——官方无论加载什么模型列表，底色都是这块均匀 #222222。
        g.fill(guiLeft, guiTop, guiLeft + LEFT_W, guiTop + PANEL_H, 0xFF222222);
        g.fill(guiLeft + 138, guiTop, guiLeft + PANEL_W, guiTop + PANEL_H, 0xFF222222);

        // 标题 + 目标实体
        g.drawString(font, "§b路径: §f模型选择", guiLeft + 4, guiTop - 12, 0xFFFFFF);
        g.drawString(font, "§7目标: §f" + entityName, guiLeft + 4, guiTop + PANEL_H + 4, 0xFFFFFF);

        searchBox.render(g, mouseX, mouseY, partialTick);
        if (searchBox.getValue().isEmpty() && !searchBox.isFocused()) {
            // 官方：斜体占位，位置 guiLeft+148, guiTop+10，色 7829367
            g.drawString(font, Component.literal("搜索模型...").withStyle(net.minecraft.ChatFormatting.ITALIC),
                    guiLeft + 148, guiTop + 10, 7829367, false);
        }

        // ---- 左侧预览面板：只显示"当前已绑定"的模型 ----
        // 官方 PlayerModelScreen:657-684 `renderModelPreview` 渲染的是 localPlayer 自己，
        // 鼠标悬停卡片完全不参与：hover 在官方只是 ModelButton:170-184 的
        //   setPreviousAnimation(hover ? "hover" : 离手 < duration ? "hover_fadeout" : "empty")
        // 它驱动的是那张卡片自身的 hover 动画，不会把左侧大预览换成人。
        // 只有点击卡片（ModelButton.onPress → 切换玩家模型）之后，左侧才会跟着变成它。
        String cur = currentModel();
        String previewId = cur;
        if (previewId != null) ModelLibrary.prefetch(previewId);   // 官方懒加载：请求后台解，不卡帧
        ModelLibrary.LoadedModel previewLm = previewId != null ? ModelLibrary.peek(previewId) : null;
        if (previewLm != null && ModelLibrary.texturesReady(previewId)) {
            renderBigPreview(g, previewLm, mouseX, mouseY, partialTick);
            // 名称（官方：面板底部居中，超宽换行省略）
            ModelLibrary.LoadedModel plm = previewLm;
            String disp = plm != null && plm.displayName != null ? plm.displayName : previewId;
            List<String> lines = new ArrayList<>();
            while (!disp.isEmpty() && font.width(disp) > LEFT_W - 10) {
                int cut = disp.length();
                while (cut > 0 && font.width(disp.substring(0, cut) + "…") > LEFT_W - 10) cut--;
                if (cut == 0) break;
                lines.add(disp.substring(0, cut) + (disp.length() > cut ? "…" : ""));
                disp = disp.substring(cut);
                if (lines.size() >= 2) break;
            }
            if (lines.size() < 2 && !disp.isEmpty()) lines.add(disp);
            int ly = guiTop + PANEL_H - 14 - (lines.size() - 1) * 10;
            for (String line : lines) {
                g.drawCenteredString(font, line, guiLeft + LEFT_W / 2, ly, 0xF3C060);
                ly += 10;
            }
        } else if (previewId != null) {
            // 官方懒加载：模型还在后台解析 → 图标转圈，不阻塞渲染线程
            drawLoadingPie(g, guiLeft + LEFT_W / 2f, guiTop + PANEL_H / 2f - 8f, 12f);
            g.drawCenteredString(font, "§8加载中…", guiLeft + LEFT_W / 2, guiTop + PANEL_H - 14, 0x787878);
        } else {
            g.drawCenteredString(font, "§8无模型", guiLeft + LEFT_W / 2, guiTop + PANEL_H - 14, 0x787878);
        }
        // 版本号（官方左下角深灰）
        g.drawString(font, "§8ModelYSM 1.0.0", guiLeft + 2, guiTop + PANEL_H - 9, 0x3C3C3C);

        // ---- 右侧卡片网格 ----
        int start = page * perPage();
        for (int i = 0; i < perPage() && start + i < models.size(); i++) {
            String id = models.get(start + i);
            // 官方 ModelButton.renderWidget:196 `ClientModelManager.isModelPending(id)`：
            // 这里同样"画到哪张就请求哪张"，解析在后台线程，没解好先画扇形等待图
            ModelLibrary.prefetch(id);
            ModelLibrary.LoadedModel lm = ModelLibrary.peek(id);
            boolean ready = lm != null && ModelLibrary.texturesReady(id);
            int x = cardX(i), y = cardY(i);
            boolean hover = mouseX >= x && mouseX < x + CARD_W && mouseY >= y && mouseY < y + CARD_H;
            boolean isCur = id.equals(cur);
            // 官方 ModelButton:170-184 只在 hover 时 setPreviousAnimation("hover")，
            // 主播放动画（preview_animation）的时钟是 PlayerModelScreen:464 `setCurrentAnimation(...)`
            // 建卡那一刻起的**连续**时钟，悬停时既不重置也不重播 —— 所以官方悬停时人物不会重做
            // 一遍"从右边翻进来"的开场。之前这里 `cardT0.put(id, now)` 把 base 时钟归零，
            // 表现就是"鼠标一放上去 Rossi 原地打转/重新翻进来"。
            hoveredPrev.put(id, hover);

            // 卡片底：官方 ModelButton.backgroundColor = 0xFF434242（未锁定时），
            // 底色是"整卡垫底"的实色，gui 图（背景板/前景框）盖在上面、按 alpha 混合，
            // 图里透明的地方露出的就是这块灰底 —— 官方卡片名字那一截的深色带其实是"灰底 + 图的下沿"。
            g.fill(x, y, x + CARD_W, y + CARD_H, 0xFF434242);

            // ① 背景板 + 3D（官方顺序：先铺 52x90 背景，再把 3D 裁到上方 70px）
            if (ready) renderCard3D(g, lm, id, x, y, partialTick, hover, isCur);
            else drawLoadingPie(g, x + CARD_W / 2f, y + (CARD_H - CARD_NAME_H) / 2f, 8f);

            // ② 名字：官方没有名字条底色，文字直接压在 gui 图上（y + h - 15）；
            //    超过 45px 宽的名字用 font.split(.., 45) 折成两行，分别落在 y + h - 19 / y + h - 10。
            //    阴影：官方 `guiGraphics.drawCenteredString(font, text, x, y, 15986656)` 走的是
            //    GuiGraphics.drawString(..., dropShadow=true)，每行自带一层右下 1px 暗影；
            //    这里改成显式 drawString(..., true)，逐行按 width/2 手工居中，参数与官方一致。
            List<FormattedCharSequence> nameLines = font.split(
                    Component.literal(lm != null && lm.displayName != null ? lm.displayName : id), 45);
            int textCx = x + CARD_W / 2;
            int textColor = 0xF3EFE0;   // 官方 15986656
            if (nameLines.size() > 1) {
                for (int li = 0; li < 2; li++) {
                    FormattedCharSequence line = nameLines.get(li);
                    g.drawString(font, line, textCx - font.width(line) / 2,
                            y + CARD_H - 19 + li * 9, textColor, true);
                }
            } else {
                FormattedCharSequence line = nameLines.isEmpty() ? FormattedCharSequence.EMPTY : nameLines.get(0);
                g.drawString(font, line, textCx - font.width(line) / 2, y + CARD_H - 15, textColor, true);
            }
            // ③ 边框：官方 ModelButton 只在 `isHoveredOrFocused()` 时画 1px 0xFFF3EFE0 的四周，
            //    平时（包括"当前选中"）没有任何边框 —— 之前那圈常亮的青色选中边是自造的，
            //    会压住 gui_foreground 自带的框线，看起来就是"边框错位/双层框"。
            int borderColor = hover ? 0xFFF3EFE0 : 0;
            if (borderColor != 0) {
                g.fill(x, y, x + CARD_W, y + 1, borderColor);
                g.fill(x, y + CARD_H - 1, x + CARD_W, y + CARD_H, borderColor);
                g.fill(x, y, x + 1, y + CARD_H, borderColor);
                g.fill(x + CARD_W - 1, y, x + CARD_W, y + CARD_H, borderColor);
            }
        }
        if (models.isEmpty()) {
            g.drawCenteredString(font, "§8(无匹配模型)", guiLeft + 280, guiTop + 60, 0x787878);
        }

        // 页码（官方 PlayerModelScreen:490：`String.format("%d/%d", page+1, maxPage+1)` 画在
        //   guiLeft+138+(282-width)/2、guiTop+223-(9/2)、色 15986656，左对齐带阴影）
        String pageStr = String.format("%d/%d", page + 1, pageCount());
        g.drawString(font, pageStr, guiLeft + 138 + (282 - font.width(pageStr)) / 2,
                guiTop + PANEL_H - 16, 15986656, false);
        super.render(g, mouseX, mouseY, partialTick);

        // 卡片 tooltip（官方 PlayerModelScreen: 依次给 IconButton/ModelButton 调 renderTooltip，
        // 悬停时 pose z+4000 画 ModelMetadataPresenter.buildModelTooltip 那份内容）
        ModelLibrary.LoadedModel hoverLm = hoveredCard(mouseX, mouseY);
        if (hoverLm != null) renderCardTooltip(g, hoverLm, mouseX, mouseY);
    }

    /**
     * 官方 ModelButton.drawLoading:236-249 的扇形等待图（0x33FFFFFF 底环 + 0xFFF3D08A 扫光）。
     * 用分段小方块近似圆环，避免引入 Pie 那套多边形绘制。
     */
    private static void drawLoadingPie(GuiGraphics g, float cx, float cy, float radius) {
        float thickness = Math.max(1.5f, radius * 0.28f);
        float inner = radius - thickness;
        float time = (System.nanoTime() % 10_000_000_000L) / 1.0E9f;
        float tau = (float) (Math.PI * 2);
        pieArc(g, cx, cy, inner, radius, 0f, tau, 0x33FFFFFF, 48);
        float sweepPhase = (time % 2.0f) / 2.0f;
        float eased = 0.5f - 0.5f * (float) Math.cos(sweepPhase * tau);
        float sweep = (0.12f + 0.66f * eased) * tau;
        float start = ((time % 1.4f) / 1.4f) * tau + sweepPhase * tau;
        pieArc(g, cx, cy, inner, radius, start, start + sweep, 0xFFF3D08A, 32);
    }

    private static void pieArc(GuiGraphics g, float cx, float cy, float inner, float outer,
                               float a0, float a1, int color, int seg) {
        float mr = (inner + outer) * 0.5f;
        float w = Math.max(1f, outer - inner);
        for (int i = 0; i < seg; i++) {
            float t0 = a0 + (a1 - a0) * i / seg;
            float t1 = a0 + (a1 - a0) * (i + 1) / seg;
            float mx = (float) ((Math.cos(t0) + Math.cos(t1)) * 0.5);
            float my = (float) ((Math.sin(t0) + Math.sin(t1)) * 0.5);
            float px = cx + mx * mr, py = cy + my * mr;
            int x0 = (int) Math.floor(px - w / 2f), y0 = (int) Math.floor(py - w / 2f);
            g.fill(x0, y0, x0 + (int) Math.ceil(w), y0 + (int) Math.ceil(w), color);
        }
    }

    /** 鼠标命中的那张卡片对应的模型 */
    private ModelLibrary.LoadedModel hoveredCard(int mouseX, int mouseY) {
        int start = page * perPage();
        for (int i = 0; i < perPage() && start + i < models.size(); i++) {
            int cx = cardX(i), cy = cardY(i);
            if (mouseX >= cx && mouseX < cx + CARD_W && mouseY >= cy && mouseY < cy + CARD_H) {
                return ModelLibrary.peek(models.get(start + i));
            }
        }
        return null;
    }

    /**
     * 官方 ModelMetadataPresenter.buildModelTooltip 的等价内容：
     * 名字（金）→ tips（灰，多行）→ 空行 → 作者/许可（深灰）→ 空行 → "N 骨骼 M 方块 F 面" + "T 材质"（灰）。
     */
    private void renderCardTooltip(GuiGraphics g, ModelLibrary.LoadedModel lm, int mouseX, int mouseY) {
        if (lm == null || lm.model == null) return;
        List<Component> lines = new ArrayList<>();
        String nm = lm.displayName != null && !lm.displayName.isBlank() ? lm.displayName : lm.id;
        lines.add(Component.literal(nm).withStyle(ChatFormatting.GOLD));
        if (lm.metadataTips != null && !lm.metadataTips.isBlank()) {
            for (String t : lm.metadataTips.replace("\r", "").split("\n")) {
                if (!t.isBlank()) lines.add(Component.literal(t).withStyle(ChatFormatting.GRAY));
            }
        }
        if (lm.metadataAuthors != null || lm.metadataLicense != null) lines.add(CommonComponents.space());
        if (lm.metadataAuthors != null) {
            lines.add(Component.literal("作者：").append(Component.literal(lm.metadataAuthors).withStyle(ChatFormatting.DARK_GRAY)));
        }
        if (lm.metadataLicense != null) {
            lines.add(Component.literal("许可：").append(Component.literal(lm.metadataLicense).withStyle(ChatFormatting.DARK_GRAY)));
        }
        int bones = 0, cubes = 0;
        for (BedrockModel.Bone b : lm.model.bonesByName.values()) {
            bones++;
            if (b.cubes != null) cubes += b.cubes.size();
        }
        lines.add(CommonComponents.space());
        lines.add(Component.literal(bones + " 骨骼 " + cubes + " 方块 " + (cubes * 6) + " 面").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(lm.textures.size() + " 材质").withStyle(ChatFormatting.GRAY));
        g.pose().pushPose();
        g.pose().translate(0f, 0f, 4000f);
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
        g.pose().popPose();
    }

    /** 卡片 3D 预览：与官方 ModelButton.renderWidget 完全同序 */
    private void renderCard3D(GuiGraphics g, ModelLibrary.LoadedModel lm, String id,
                              int cx, int cy, float partialTick,
                              boolean hover, boolean selected) {
        if (lm.defaultTexture() == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        LivingEntity cardDummy = cardDummies.get(id);
        if (cardDummy == null || cardDummy.level() != mc.level) {
            cardDummy = EntityType.VILLAGER.create(mc.level);
            if (cardDummy == null) return;
            cardDummies.put(id, cardDummy);
            cardT0.put(id, System.nanoTime());
        }

        // 官方顺序：① 背景板按整卡 52x90 铺满（这里画的就是"底座"条）
        //          ② 只把 3D 裁到上方 70px（scissor 不含背景）
        //          ③ 前景板最后盖整卡
        // 之前把背景板缩进 50x68 的预览区，底座条跟着被压缩上移 —— 这就是"底座卡不上"的原因。
        if (lm.guiBackground != null) blitScaled(g, lm.guiBackground, cx, cy, CARD_W, CARD_H);
        MultiBufferSource.BufferSource bs = mc.renderBuffers().bufferSource();
        g.enableScissor(cx, cy, cx + CARD_W, cy + CARD_H - CARD_NAME_H);

        PoseStack ps = g.pose();
        // 官方 ModelPreviewRenderer:264-268（卡片同样走这条）：镜像 scale(1,1,-1) 放在
        // ModelViewStack 里，PoseStack 只留 translate + 正缩放 + Z180 —— pose.normal() 于是不会
        // 把法线也反演一遍，卡片受光面与大预览完全同口径。
        PoseStack mv = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        mv.pushPose();
        ps.pushPose();
        try {
            boolean noRot = lm.disablePreviewRotation;
            // 官方口径（ModelButton.renderWidget → ModelPreviewRenderer.renderLivingEntityPreview）：
            //   translate(0, disable_preview_rotation ? 5.5 : 0, 1000) → scale(30) → Z180 → X(disable ? 0 : -10)
            //   anchor = (x + 52/2, y + 90/2 + 20) = (cx+26, cy+65)
            //
            // 模型本体大小的唯一来源是 ysm.json 的 height_scale/width_scale：
            //   官方 YSMFolderDeserializer 取缺省值 0.7（Blockbench 插件默认），显式写了才用自己写的。
            //   酒狐 01_taisho_maid / 3_default_boy 都没写 → 官方按 0.7 渲染；
            //   default 写了 0.7 → render() 里已经应用，这里不再重复乘。
            // 之前的 0.85 / 背景层 0.7 / 垂直居中都属于自造系数，官方代码里没有，全部删除。
            // 模型大小只由 height_scale/width_scale 决定（加载器已按官方口径补 0.7 缺省），
            // NpcRenderer.render 里会乘上去；这里只给官方的 30px/格。
            float scale = CARD_MODEL_SCALE;
            // 官方 ModelPreviewRenderer:271 `poseStack.translate(0, disablePreviewRotation ? 5.5 : 0, 1000)`：
            // 这一步发生在 `scale(1,1,-1)` 之后（只翻 Z，Y 仍是 GUI 轴向 = 向下），
            // 所以 disable_preview_rotation 的模型要【往下】挪 5.5，不是往上。
            // 之前写成减号 → 酒狐/default 这类 noRot 卡整整高了 11px（-5.5 vs +5.5），
            // 和开了旋转的卡片摆在一起就是一排"高低不齐"。
            float anchorY = cy + CARD_H / 2f + 20f + (noRot ? 5.5f : 0f);
            // 平移 + 镜像进 ModelViewStack（官方 :266-268），PoseStack 只留深度与缩放。
            // 深度沿用 GUI 的 z=50：-z 镜像会把它翻回来，所以这里写 -50。
            mv.translate(cx + CARD_W / 2f, anchorY, 0f);
            mv.scale(1f, 1f, -1f);
            com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
            ps.translate(0f, 0f, -50f);
            ps.scale(scale, scale, scale);
            ps.mulPose(Axis.ZP.rotationDegrees(180f));
            ps.mulPose(Axis.XP.rotationDegrees(noRot ? 0f : -10f));
            // 官方卡片是固定机位（yaw = disable ? 180 : 200，X = disable ? 0 : -10），
            // 没有转台/自旋。之前那 30°/s 的 Y 旋转是自造的，会让同一张卡在不同瞬间
            // 的宽度和高低都不一样，横向比较时"每张卡都不一样大"。
            // 官方 NativeModelRenderer:217-244：pivot 只是旋转轴心（translate(+pivot)->rotate->translate(-pivot)），
            // 几何顶点本身就是绝对模型坐标，不存在"抵消根骨骼 pivot"这一步。
            // 原先的 -root.mcPivot 是自造平移，会把整只模型按不同 pivot 挪不同距离 —— 某些卡片
            // 顶部就永远差几个像素对不上边框（Rossi 最明显）。
            float ox = 0, oy = 0, oz = 0;
            // 主循环动画：官方卡片一样一直播 preview_animation
            // （Rossi 的 "gui" 就是从右侧翻进来→落定→之后耳朵/眼睛微动，100s 循环）
            BedrockAnimation base = pickPreviewAnim(lm);
            drivePreviewClock(base, cardDummy, id);
            tickPreviewPhysics(cardDummy.getUUID());
            // hover / hover_fadeout 只作为叠加层（官方 card_widget 是独立状态槽，不替换主动画）
            CardAnim overlay = cardOverlay(lm, id, hover, net.minecraft.Util.getMillis());
            java.util.List<ui.modelysm.client.anim.ModelControllers.ActiveLayer> layers = null;
            if (overlay.anim != null) {
                ui.modelysm.client.anim.ModelControllers.CState cs = new ui.modelysm.client.anim.ModelControllers.CState();
                cs.name = overlay.anim.name;
                cs.animations.add(overlay.anim.name);
                layers = java.util.List.of(new ui.modelysm.client.anim.ModelControllers.ActiveLayer(
                        "gui_" + overlay.anim.name, cs, overlay.time));
            }
            // previewMode=true：官方卡片/预览是纯预览管线——只跑 preview_animation，
            // 不叠玩家控制器（player.post_main / parallel / 装备层会把 gui 动画盖掉，
            // 这就是"翻滚入场被跳过、只剩定姿"的原因）。
            // 背景层不再做任何单独缩放/居中：官方 renderLivingEntityPreview 里背景骨
            // 和身体走同一套 pose + scale(30)，框的大小完全由 height_scale(0.7) 决定。
            NpcRenderer.PREVIEW_BG_SCALE = 0f;
            NpcRenderer.PREVIEW_BG_CENTER_Y = 0f;
            // 官方 ModelPreviewRenderer:325 `Lighting.setupForEntityInInventory()`：
            // 卡片里的实体是用 GUI 投影直接画的，必须显式切换成"背包物品/实体预览"的两盏方向光
            // （L0=(0.2,1,-0.7)、L1=(-0.2,1,0.7)，以 0xF000F0 最大亮度贴图）。
            // 我们之前一次都没调用 → 用的是上一帧残留的等级光照方向，正面几乎拿不到漫反射，
            // 整张卡就是"比官方暗一档、脸和衣服都是灰的"。
            com.mojang.blaze3d.platform.Lighting.setupForEntityInInventory();
            NpcRenderer.previewCullOff = true;
            NpcRenderer.previewMirrorInModelView = true;
            // 离屏预览：把 3D 区域与脚的落点报给离屏渲染器（它自己算矩阵，不读 GUI 的投影/模型视图）
            PreviewFboRenderer.beginFrame(cx, cy, CARD_W, CARD_H - CARD_NAME_H,
                    cx + CARD_W / 2f, anchorY);
            // 官方是"叠加"不是"替换"：previous 槽的 hover/hover_fadeout 由 PlayerCustomAnimationPredicate
            // 单独起一个 controller 播（OpenYSM client/animation/predicate/PlayerCustomAnimationPredicate:15-18），
            // preview_animation 那个 controller 照常在跑，后者的骨骼覆盖前者的 —— 所以悬停时模型自带的
            // 背景板/构图都还在，只是人物多了个 hover 动作。
            // 之前写成 (overlay.anim != null ? null : base) 等于把 base 整个丢掉，Rossi 悬停时
            // 背景板（模型自己的 Background 面片）瞬间回到静止位→看不见，只剩卡片底色 0xFF434242，
            // 实机看起来就是"鼠标一放上去背景板没了、冒出个灰面板"。
            NpcRenderer.render(cardDummy, lm, base, noRot ? 180f : 200f, 0f, 0f, partialTick, ps, bs,
                    0xF000F0, 1f, java.util.Set.of(), null, ox, oy, oz, true,
                    null, 0f, 1f, layers);
            NpcRenderer.previewMirrorInModelView = false;
            NpcRenderer.previewCullOff = false;
            bs.endBatch();
            // 官方收尾 `Lighting.setupFor3DItems()`，恢复 GUI 3D 物品的默认光照
            com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
        } finally {
            ps.popPose();
            mv.popPose();
            com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        }
        // 离屏结果当 GUI 贴图贴回卡片（此时 PoseStack 已回到界面根，坐标就是绝对 GUI 像素）；
        // CPU 兜底路径没有离屏结果时这里是空操作。
        PreviewFboRenderer.blit(g);
        // 3D 的 scissor 到这里结束：官方是 `RenderSystem.disableScissor()` 之后才 blit 前景板，
        // 前景板按整卡 52x90 覆盖（含名字条那 20px）。我们之前把 disableScissor 放在 blit 之后，
        // 前景图的底部 20px 被 3D 的裁剪框切掉 —— 这就是"末影龙娘的框底部没铺满"的真正原因。
        g.disableScissor();
        // 卡片前景层（官方 gui_foreground：blit 在 z=3500 覆盖整张卡 52x90）
        blitScaled(g, lm.guiForeground, cx, cy, CARD_W, CARD_H);
        // 按用户要求"完全来自模型"：界面不再补画任何边框/描边。
    }

    /** 一张叠加层动画 + 它自己的播放时间（秒） */
    private static final class CardAnim {
        final BedrockAnimation anim;
        final float time;
        CardAnim(BedrockAnimation anim, float time) { this.anim = anim; this.time = time; }
    }

    private static final CardAnim NO_OVERLAY = new CardAnim(null, 0f);

    /**
     * 官方 card_widget 的叠加状态槽（与主动画 preview_animation 并行）：
     * <pre>
     * hovered  -> play("hover")
     * 刚移开   -> 离手时间 &lt; hover_fadeout 时长(时长 x 50ms) 时 play("hover_fadeout")
     * 其余     -> 不叠加（官方传 "empty" = 不存在的动画名，等价于空层）
     * </pre>
     */
    private CardAnim cardOverlay(ModelLibrary.LoadedModel lm, String id, boolean hover, long now) {
        BedrockAnimation hoverAnim = lm.animations.get(ANIM_HOVER);
        BedrockAnimation fadeAnim = lm.animations.get(ANIM_HOVER_FADE);

        if (hover) {
            if (hoverAnim == null) {
                hoverStartMs.remove(id);
                return NO_OVERLAY;
            }
            Long start = hoverStartMs.get(id);
            if (start == null) {
                start = now;
                hoverStartMs.put(id, start);
            }
            hoverEndMs.remove(id);
            return new CardAnim(hoverAnim, (now - start) / 1000f);
        }

        Long start = hoverStartMs.remove(id);
        if (start != null) hoverEndMs.put(id, now);
        Long end = hoverEndMs.get(id);
        if (fadeAnim != null && end != null) {
            float fadeMs = Math.max(0f, fadeAnim.length) * 1000f;
            if (now - end < fadeMs) return new CardAnim(fadeAnim, (now - end) / 1000f);
        }
        return NO_OVERLAY;
    }

    /** 把内嵌 gui 图按真实像素比例拉伸到指定矩形（官方 ModelButton 对背景板/前景板都是 enableBlend + defaultBlendFunc） */
    private static void blitScaled(GuiGraphics g, ResourceLocation rl, int x, int y, int w, int h) {
        if (rl == null) return;
        int[] sz = ModelLibrary.TEXTURE_SIZE.get(rl);
        int tw = sz != null && sz[0] > 0 ? sz[0] : 256;
        int th = sz != null && sz[1] > 0 ? sz[1] : 256;
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.blit(rl, x, y, w, h, 0f, 0f, tw, th, tw, th);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    /**
     * background* 装饰层骨骼：只在子树方块数 ≤ 12 时才认定为"装饰层"（酒狐 Background1 = 4 根条）。
     * 有些模型把整具身体挂在 Backgrounds 根下（那种隐藏会把人物删掉），用方块数区分。
     */
    private static java.util.Set<String> decorBgBones(ModelLibrary.LoadedModel lm) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (ui.modelysm.client.render.BedrockModel.Bone root : lm.model.rootBones) {
            if (!root.name.toLowerCase(java.util.Locale.ROOT).contains("background")) continue;
            if (countCubes(root) <= 12) out.add(root.name);
        }
        return out;
    }

    private static int countCubes(ui.modelysm.client.render.BedrockModel.Bone b) {
        int n = b.cubes.size();
        for (ui.modelysm.client.render.BedrockModel.Bone c : b.children) n += countCubes(c);
        return n;
    }

/** 卡片预览动画：properties.preview_animation 优先 → gui → idle → 任意可用动画 */
    private static BedrockAnimation pickPreviewAnim(ModelLibrary.LoadedModel lm) {
        if (lm.previewAnimation != null) {
            BedrockAnimation pa = lm.animations.get(lm.previewAnimation);
            if (pa != null) return pa;
        }
        // 官方模型常用 "gui" 作为卡片姿态动画（Rossi / 小流萤 / 末影龙娘的 preview_animation 就是它）
        BedrockAnimation gui = lm.animations.get("gui");
        if (gui != null) return gui;
        BedrockAnimation idle = lm.animations.get("idle");
        if (idle != null) return idle;
        return lm.animations.isEmpty() ? null : lm.animations.values().iterator().next();
    }

    /**
     * 界面打开时刻：官方每张卡片是独立 mock player，界面一打开动画从 0 开始播
     * （所以能看到"从右边翻进来"的开场）。这里用实例定时器，每次打开列表都重播开场。
     */
    private long previewT0 = System.nanoTime();

    /**
     * 驱动开场时间。注意：NpcRenderer 取的是 entity.getUUID() 的状态，
     * 每张卡用的是自己的 cardDummy（villager），状态时钟写到它自己的 UUID 上；
     * 写固定 "modelysm-card-preview" UUID 是无效的（上一版卡片卡死就是这个原因）。
     */
    /** 每张卡按自己的时钟起点播 base 动画（官方每卡一个 PlayerPreviewEntity，互不影响） */
    private void drivePreviewClock(BedrockAnimation anim, LivingEntity e, String id) {
        long t0 = cardT0.computeIfAbsent(id, k -> System.nanoTime());
        float t = (float) ((System.nanoTime() - t0) / 1_000_000_000.0);
        // 官方 AnimationTracker 对 loop=true 的动画是循环播放的（酒狐 gui 就是 loop=true、18s）。
        // 之前这里不取模，18s 之后会一直采样到片段末尾的"下沉退场帧"
        //（Root.position 14.88 = (0,-52,0)，整具人往下沉 3.25 格），
        // 卡片上表现就是"只看到头顶/半张脸挂在名字条上方、上面一大块格子背板"。
        if (anim != null && anim.loop && anim.length > 0.05f) {
            t = t % anim.length;
        }
        if (e != null) applyPreviewState(e.getUUID(), anim, t);
    }

    /** 把指定实体 UUID 的预览状态时钟设成"某动画的第几秒" */
    private static void applyPreviewState(java.util.UUID uuid, BedrockAnimation anim, float time) {
        ui.modelysm.client.AnimStates.State st = ui.modelysm.client.AnimStates.get(uuid);
        st.animTime = time;
        st.evAnim = anim != null ? anim.name : null;
        st.evTime = time;
    }

    /**
     * GUI 预览的弹簧物理推进 —— 对应官方 PhysicsManager.update(renderTicks)：
     * 每个渲染帧按真实帧间隔跑一次 ysm.first_order / ysm.second_order 的积分。
     * 世界渲染是 NpcRenderHook:33-40 在做，GUI 没有这条钩子，必须自己补，
     * 否则头发/尾巴/裙摆的二级弹簧永远停在初始值（看起来"完全死板"）。
     */
    private static void tickPreviewPhysics(java.util.UUID uuid) {
        ui.modelysm.client.AnimStates.State st = ui.modelysm.client.AnimStates.get(uuid);
        long now = System.nanoTime();
        float dt = (now - st.lastFrameNanos) / 1.0e9f;
        st.lastFrameNanos = now;
        if (dt <= 0f || dt > 0.5f) dt = 0.05f;
        st.lastDt = dt;
        ui.modelysm.client.molang.Physics.update(uuid, dt);
    }

    /** 左侧大预览的可交互区域（官方 scissor 框：guiLeft+5, guiTop+29, 125x171） */
    private boolean inBigPreview(double mouseX, double mouseY) {
        return mouseX >= guiLeft + 5 && mouseX < guiLeft + 5 + 125
                && mouseY >= guiTop + 29 && mouseY < guiTop + 29 + 171;
    }

    /** 左侧大预览：完全官方口径 —— 身体 yaw / 头 pitch 都跟随鼠标，无拖拽、无滚轮缩放 */
    private void renderBigPreview(GuiGraphics g, ModelLibrary.LoadedModel lm, int mouseX, int mouseY, float partialTick) {
        if (lm.defaultTexture() == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (bigDummy == null) bigDummy = EntityType.VILLAGER.create(mc.level);
        if (bigDummy == null) return;

        // 官方 PlayerModelScreen：scissor(guiLeft+5, guiTop+29, 125, 171)，
        // 预览本体用 InventoryScreen.renderEntityInInventoryFollowsMouse(guiLeft+67, guiTop+190, 70)
        // 并且不铺 gui_background / gui_foreground（面板底色由界面自己画）
        int px = guiLeft + 5, py = guiTop + 29, pw = 125, ph = 171;
        MultiBufferSource.BufferSource bs = mc.renderBuffers().bufferSource();
        // 官方用 scissor 裁剪预览区
        double guiScale = mc.getWindow().getGuiScale();
        com.mojang.blaze3d.systems.RenderSystem.enableScissor(
                (int) (px * guiScale),
                (int) (mc.getWindow().getHeight() - (py + ph) * guiScale),
                (int) (pw * guiScale), (int) (ph * guiScale));
        // 官方 ModelPreviewRenderer:264-268：镜像 scale(1,1,-1) 放在 ModelViewStack 里，
        // 不进 PoseStack —— 这样 pose.normal() 不会把法线也反演一遍（法线只随身体 yaw 转），
        // 光照口径与官方完全一致。之前把 -z 缩放写在 PoseStack 里，等于对整只模型的法线做了
        // 一次原点反演（x/y/z 全变号），头顶、肩线、头发上表面全被判成背光、只剩 0.4 环境光，
        // 实机观感就是"头顶罩了一层阴影"。
        PoseStack mv = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        mv.pushPose();
        mv.translate(px + 62f, py + ph - 10f, 0f);
        mv.scale(1f, 1f, -1f);
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        PoseStack ps = g.pose();
        ps.pushPose();
        try {
            // 官方左侧大预览：scissor(guiLeft+5, guiTop+29, 125, 171) +
            // renderEntityInInventoryFollowsMouse(guiLeft+67, guiTop+190, 70, angleX, angleY, player)
            //
            // 【不要再做自动缩放】：vanilla `renderEntityInInventory` 里唯一的缩放是
            // 固定的 `scale * entity.getScale()`，没有按包围盒 fit 这一步。官方预览框
            // 一样是 125x171，超过就靠 scissor 裁掉，模型永远按 70px/格画。
            // 这里之前的 `fit = min(1, (ph-8)/模型高, (pw-8)/模型宽)` 是自造系数：
            // 只要模型带一根远离身体的骨骼（投影物/悬浮道具/出框装饰），包围盒就被撑大，
            // fit 直接塌到 0.1 以下 —— 这就是"选了鲁普雷希特亲王，左边预览小成一根手指"的原因。
            float scale = BIG_PREVIEW_SCALE;
            // ---- 官方 InventoryScreen.renderEntityInInventoryFollowsMouse 的原式 ----
            //   f = atan(((guiLeft+67) - mouseX) / 40)   → 身体 yaw = 180 + f*20
            //   g = atan(((guiTop+85) - mouseY) / 40)    → 头 pitch = -g*20
            // 官方 AnimatableEntity:263-266 把 xRot 直接当 headPitch 喂给模型，所以
            // 身体只左右随鼠标转、脑袋单独上下点头 —— 就是"看向鼠标"的手感。
            float angleX = (px + 62f) - mouseX;          // 官方 (guiLeft+67) - mouseX
            float angleY = (py + 56f) - mouseY;          // py+56 == guiTop+85（预览框中线）
            float atX = (float) Math.atan(angleX / 40.0f);
            float atY = (float) Math.atan(angleY / 40.0f);
            float bodyYaw = 180f + atX * 20f;
            float headPitch = -atY * 20f;
            // 官方 InventoryScreen:111-115（1.20.1 原文）：
            //   yBodyRot = 180 + f*20      ← 身体 ±20°
            //   setYRot  = 180 + f*40      ← 实体朝向 ±40°
            //   yHeadRot = getYRot()
            // YSM 的 netHeadYaw = yHeadRot - yBodyRot（AnimatableEntity:248）→ 头相对身体再偏 ±20°，
            // 头一共 ±40°。我们之前传 0，头被焊死在身体上 → 只有 ±20° 的"整体平移感"，
            // 看起来就是"头不动、身子只是横向挪"。这里补上头的额外偏航。
            float headYawNet = atX * 20f;
            // 站位照官方：脚底基线 = guiTop+190 = py + ph - 10，横坐标 = 框中心（已放在
            // ModelViewStack 里），这里只补深度：官方是 modelview z=1050 + pose z=1000，
            // 我们沿用原来的 GUI 深度 z=50，-z 镜像会把它翻回来，所以这里写 -50。
            ps.translate(0f, 0f, -50f);
            ps.scale(scale, scale, scale);
            ps.mulPose(Axis.ZP.rotationDegrees(180f));
            // 原版 InventoryScreen:103-105 + 124-128 的完整变换链：
            //   translate(x,y,50) → scale(s,s,-s) → Rz(π) → Rx(angleY*20°)
            // 那个 Rx 就是"整个身子跟着鼠标上下倾斜"的来源（绕脚底 X 轴整体前后倾），
            // 之前只把俯仰喂给头骨骼，躯干完全没倾 → 实机就是"身子只做水平旋转"。
            // 对照 EntityRenderDispatcher:130-170：render() 根本不消费 cameraOrientation，
            // 所以 overrideCameraOrientation(conjugate) 不会抵消这个 Rx，官方确实整体倾斜。
            ps.mulPose(Axis.XP.rotationDegrees(atY * 20f));
            // 没有手动轴心旋转了（拖拽/滚轮操控已删），yaw/pitch 交给渲染端按官方加到
            // 身体和头部，这里不再做 translate(pivot)->rotate 的绕轴心组。
            float ox = 0, oy = 0, oz = 0;
            // 官方大预览 = 正常渲染实体本体：播 idle（官方是真实玩家实例，走 AnimationTracker
            // 的 idle 状态），不取卡片的 preview_animation（酒狐 gui 的 18s 入场位移 + Root
            // scale=2 + 背板会连人带条一起放大），并隐藏 background* 骨骼。
            java.util.Set<String> hideBg = new java.util.HashSet<>();
            for (ui.modelysm.client.render.BedrockModel.Bone b : lm.model.bonesByName.values()) {
                if (b.name.toLowerCase(java.util.Locale.ROOT).contains("background")) hideBg.add(b.name);
            }
            BedrockAnimation idleAnim = lm.animations.get("idle");
            if (bigT0 == 0L) bigT0 = System.nanoTime();
            float t = (float) ((System.nanoTime() - bigT0) / 1_000_000_000.0);
            if (idleAnim != null && idleAnim.loop && idleAnim.length > 0.05f) t = t % idleAnim.length;
            applyPreviewState(bigDummy.getUUID(), idleAnim, t);
            // 官方 PhysicsManager.update(renderTicks)：弹簧物理每个渲染帧都要推进。
            // 我们只在世界渲染（NpcRenderHook:40）里调过 Physics.update，GUI 里从来没推进过 →
            // 二级/一级弹簧永远停在初值，头发、尾巴、裙摆完全不摆（"物理死板"的直接原因）。
            tickPreviewPhysics(bigDummy.getUUID());
            // 官方左侧大预览同样走 ModelPreviewRenderer → 同样需要 setupForEntityInInventory 的两盏方向光
            com.mojang.blaze3d.platform.Lighting.setupForEntityInInventory();
            NpcRenderer.previewCullOff = true;
            NpcRenderer.previewMirrorInModelView = true;
            // 镜像已在 ModelViewStack 里完成，法线不能再补 nz（官方 native 渲染也只乘 pose.normal）
            NpcRenderer.previewMirrorInModelView = true;
            // 离屏预览：区域 125x171，脚的落点 = 框底往上 10px 的中心（与上面 ModelViewStack 的 translate 一致）
            PreviewFboRenderer.beginFrame(px, py, pw, ph, px + 62f, py + ph - 10f);
            NpcRenderer.render(bigDummy, lm, idleAnim, bodyYaw, headYawNet, headPitch, partialTick, ps, bs,
                    0xF000F0, 1f, hideBg, null, ox, oy, oz, true);
            NpcRenderer.previewMirrorInModelView = false;
            NpcRenderer.previewCullOff = false;
            bs.endBatch();
            com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
        } finally {
            ps.popPose();
            com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().popPose();
            com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        }
        PreviewFboRenderer.blit(g);
        com.mojang.blaze3d.systems.RenderSystem.disableScissor();
    }

    @Override
    public void tick() {
        super.tick();
        if (searchBox != null) searchBox.tick();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.isFocused() && keyCode == 256) {
            searchBox.setFocused(false);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchBox != null && searchBox.charTyped(codePoint, modifiers)) {
            refreshFiltered();
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    /** 帧率探针用的模型摘要（骨骼数；取不到就返回空串，不影响选择） */
    private static String infoOf(String id) {
        try {
            ModelLibrary.LoadedModel lm = ModelLibrary.peek(id);
            if (lm == null || lm.model == null) return "";
            return "（骨骼" + lm.model.bonesByName.size() + "）";
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 官方左侧预览没有任何鼠标交互：视角完全由鼠标位置驱动（follows-mouse），
        // 点击/拖拽/滚轮一概不接管，避免误触。这里只处理搜索框失焦与卡片选择。
        if (searchBox != null && searchBox.isFocused()
                && !(mouseX >= searchBox.getX() && mouseX < searchBox.getX() + searchBox.getWidth()
                && mouseY >= searchBox.getY() && mouseY < searchBox.getY() + searchBox.getHeight())) {
            searchBox.setFocused(false);
        }
        int start = page * perPage();
        for (int i = 0; i < perPage() && start + i < models.size(); i++) {
            String id = models.get(start + i);
            int x = cardX(i), y = cardY(i);
            if (mouseX >= x && mouseX < x + CARD_W && mouseY >= y && mouseY < y + CARD_H) {
                if (!id.equals(currentModel())) {
                    // 绑定/换模型统一走 BIND_MODEL：未绑定的实体也能在这里一次建成绑定
                    // （SET_MODEL 要求"已存在绑定"，是旧命令 bind 之后的换模型接口）
                    // 帧率探针：把"点了哪张卡"和之后的 fps 波动记进日志（[FPS] 前缀）
                    ui.modelysm.client.PerfProbe.event("ModelYSM 选卡 → " + id + infoOf(id));
                    Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.BIND_MODEL, id));
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // 官方预览不支持拖拽改视角（已按用户要求删除），拖拽只交给父类（滚动条等）
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // 预览框内滚轮不再缩放（官方没有这个交互），只在右侧列表区用来翻页
        if (delta != 0 && mouseX >= guiLeft + 138 && mouseX <= guiLeft + PANEL_W
                && mouseY >= guiTop && mouseY <= guiTop + PANEL_H) {
            page = Mth.clamp(page + (delta > 0 ? -1 : 1), 0, pageCount() - 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
