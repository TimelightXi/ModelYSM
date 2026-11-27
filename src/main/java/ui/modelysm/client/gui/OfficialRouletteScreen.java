package ui.modelysm.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import ui.modelysm.client.AnimStates;
import ui.modelysm.client.ClientState;
import ui.modelysm.client.ModelLibrary;
import ui.modelysm.common.EntitySettings;
import ui.modelysm.network.Net;
import ui.modelysm.network.packets.EntityActionC2S;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 官方 OpenYSM `AnimationRouletteScreen`（Z 键八扇区轮盘）的同款界面，逐行对照几何/配色：
 *   centerX = width/2 - 70、centerY = height/2 - 8（官方 init:169-170，故意偏左给右侧配置面板留位）；
 *   8 等分，每扇区留 0.034906585 rad（2°）缝；未悬停 25..105、悬停 25..115；
 *   底色 -1879048192、悬停色 -251678464；
 *   标签在半径 65、起始角 0.3926991（=π/8）、`font.split(.., 50)` 折行、色 15986656；
 *   子菜单条目（key 以 # 开头）染红；顶层条目下方补一行黄色 `[ 无 ]`。
 *
 * 配置按钮（官方 AnimationRouletteScreen:204-260 / 505-508 / 572-592）：
 *   extra_animation 里 **value** 以 "#" 开头的条目是 ⚙ 配置入口（value="#<ConfigButton.id 或 name>"），
 *   在半径 15..50 的内环画 ⚙ 标记（底色 1879101183、悬停 -268382465）；
 *   点内环 → showConfigGroup：在轮盘右侧（centerX+125 起、centerY-46 起、可视区到 centerY+110）
 *   铺出该按钮的 config_forms 控件列表（checkbox 12 高、range 15 高、radio 多列勾选），
 *   右侧 centerX+242 两枚 ↑/↓ 按钮翻页，滚轮在 x >= centerX+110 时滚配置列表。
 *   控件值直接写模型 molang 变量（checkbox/range → SET_VAR，radio → EXEC_SCRIPT），
 *   与模型动画读的 v.* 同一张表，因此拖动即时生效。
 */
public class OfficialRouletteScreen extends Screen {

    private static final float TAU = 6.2831855f;
    private static final float SLICE = TAU / 8f;
    private static final float GAP = 0.034906585f;
    private static final String SUBMENU_PREFIX = "#";
    private static final String RETURN_KEY = "#return";

    private static final int COLOR_TEXT = 15986656;      // 0xFFF3EFE0
    private static final int COLOR_BG = -1879048192;     // 0x90000000
    private static final int COLOR_BG_HOVER = -251678464;
    private static final int COLOR_PATH = 16777215;
    private static final int COLOR_CFG_IDLE = 1879101183; // 0x7000CEFF 官方内环底色
    private static final int COLOR_CFG_HOVER = -268382465;
    private static final int COLOR_CHECKBOX_BG = 0xEF434242; // 官方 ConfigCheckBox 覆写的 -280804798

    /** 官方 roulette.png 图集（checkbox 128x12 两帧、slider 200x15 多行）；已复制进本模组资源 */
    private static final ResourceLocation TEX = new ResourceLocation("modelysm", "texture/roulette.png");

    private final Screen parent;
    private final UUID entityUuid;
    private final String entityName;
    private final String modelId;
    /** 子菜单 id；null = 根目录（可变：进/退子菜单都在同一实例里换，不新开界面） */
    private String submenu;
    /** 当前页（官方 currentNavEntry 的页码部分；左右按钮/滚轮直接改，不再重建界面） */
    private int page;

    private int centerX, centerY;
    private int hoveredIndex = -1;
    /** 命中内环 ⚙ 的条目索引（全局索引，含分页） */
    private int hoveredConfigIndex = -1;
    /** 当前页的条目：key = 要播放的动画名 / "#子菜单id"，value = 显示文本 / "#配置按钮" */
    private final Map<String, String> props = new LinkedHashMap<>();

    // ---- 官方配置表单面板（AnimationRouletteScreen.currentConfigGroup）----
    private ModelLibrary.LoadedModel.ConfigButton configGroup;
    private int configScroll;
    private int configScrollMax;

    // ---- range 滑条拖拽（官方 AnimationSlider 的 applyValue）----
    private ModelLibrary.LoadedModel.ConfigForm dragForm;
    private float dragValue;

    public OfficialRouletteScreen(Screen parent, UUID entityUuid, String entityName,
                                  String modelId, String submenu, int page) {
        super(Component.literal("官方轮盘"));
        this.parent = parent;
        this.entityUuid = entityUuid;
        this.entityName = entityName;
        this.modelId = modelId;
        this.submenu = submenu;
        this.page = Math.max(0, page);
    }

    @Override
    protected void init() {
        clearWidgets();
        // 官方 AnimationRouletteScreen.init:169-170
        this.centerX = (this.width / 2) - 70;
        this.centerY = (this.height / 2) - 8;
        props.clear();
        ModelLibrary.LoadedModel lm = ModelLibrary.get(modelId);
        if (lm != null) {
            if (submenu == null) {
                props.putAll(lm.extraAnimations);
            } else {
                Map<String, String> sub = lm.extraAnimationClassifies.get(submenu);
                if (sub != null) props.putAll(sub);
            }
        }
        // 官方 init:195-203：右上角三枚按钮 —— 左翻页(30x30, centerX+125/centerY-102)、
        // 右翻页(30x30, centerX+240/centerY-102)、返回(145x22, centerX+125/centerY-70)
        addRenderableWidget(new FlatButton(centerX + 125, centerY - 102, 30, 30,
                Component.literal("<"), b -> prevPage()));
        addRenderableWidget(new FlatButton(centerX + 240, centerY - 102, 30, 30,
                Component.literal(">"), b -> nextPage()));
        addRenderableWidget(new FlatButton(centerX + 125, centerY - 70, 145, 22,
                Component.literal("返回"), b -> back()));
        // 官方 init:204-224：配置面板打开时，右侧两枚 ↑/↓ 滚动按钮（28x60）
        if (configGroup != null) {
            addRenderableWidget(new FlatButton(centerX + 242, centerY - 46, 28, 60,
                    Component.literal("↑"), b -> scrollConfig(-50)));
            addRenderableWidget(new FlatButton(centerX + 242, centerY + 50, 28, 60,
                    Component.literal("↓"), b -> scrollConfig(50)));
        }
    }

    /** 官方 previousPage:437-439 / nextPage:441-445 */
    private void prevPage() {
        if (page > 0) {
            page--;
            hoveredIndex = -1;
            hoveredConfigIndex = -1;
        }
    }

    private void nextPage() {
        int pages = (props.size() - 1) / 8 + 1;
        if (page + 1 < pages) {
            page++;
            hoveredIndex = -1;
            hoveredConfigIndex = -1;
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private String[] pageEntries() {
        List<String> keys = new ArrayList<>(props.keySet());
        List<String> vals = new ArrayList<>(props.values());
        int start = page * 8;
        int n = Math.min(8, Math.max(0, keys.size() - start));
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = keys.get(start + i) + "\u0000" + vals.get(start + i);
        return out;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 路径（官方 render:366-368：centerX+195, centerY-100，白色）
        String path = submenu == null ? "根目录" : "根目录 > " + submenu;
        g.drawCenteredString(font, Component.literal("路径：" + path), centerX + 195, centerY - 100, COLOR_PATH);

        // 拖拽中的滑条每帧跟随鼠标（与设置面板同一套兜底思路）
        if (dragForm != null) {
            updateDrag(mouseX);
        }

        renderRadialBackground(g, mouseX, mouseY);
        renderRadialButtons(g);
        renderPageInfo(g);
        super.render(g, mouseX, mouseY, partialTick);

        // 中心文字：根目录显示"官方轮盘"；子菜单里官方那里是一个 40x20 的返回按钮
        if (submenu == null) {
            g.drawCenteredString(font, "官方轮盘", centerX, centerY - 4, COLOR_TEXT);
        }

        if (configGroup != null) {
            renderConfigPanel(g, mouseX, mouseY);
        }

        if (props.isEmpty()) {
            g.drawCenteredString(font, "§7该模型没有 extra_animation 条目",
                    centerX, centerY + 130, 0xAAAAAA);
        }
    }

    private void renderPageInfo(GuiGraphics g) {
        int pages = Math.max(1, (props.size() - 1) / 8 + 1);
        g.fill(centerX + 157, centerY - 87, centerX + 238, centerY - 72, 0, -822083584);
        g.drawCenteredString(font, String.format("%d/%d", page + 1, pages), centerX + 197, centerY - 83,
                ChatFormatting.AQUA.getColor());
    }

    /** 官方 renderRadialBackground:634-677（颜色常量原样，含内环配置环） */
    private void renderRadialBackground(GuiGraphics g, int mouseX, int mouseY) {
        if (props.isEmpty()) return;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f mat = g.pose().last().pose();

        float pointerAngle = (float) Mth.atan2(mouseY - centerY, mouseX - centerX);
        if (pointerAngle < 0f) pointerAngle = TAU + pointerAngle;
        float pointerRadius = Mth.sqrt(Mth.square(mouseY - centerY) + Mth.square(mouseX - centerX));

        String[] entries = pageEntries();
        boolean any = false;
        boolean anyCfg = false;
        for (int i = 0; i < entries.length; i++) {
            float a0 = SLICE * i + GAP;
            float a1 = SLICE * (i + 1) - GAP;
            boolean valueIsCfg = valueOf(entries[i]).startsWith(SUBMENU_PREFIX);
            boolean hov = a0 < pointerAngle && pointerAngle < a1 && 50f < pointerRadius && pointerRadius < 100f;
            if (hov) {
                hoveredIndex = page * 8 + i;
                any = true;
            }
            if (valueIsCfg) {
                // 官方 checkRadialHover:685-691：value 以 # 开头的条目，外环悬停只亮 50..115，内环保留底色
                if (hov) {
                    drawSegment(bb, mat, 50f, 115f, a0, a1, COLOR_BG_HOVER);
                    drawSegment(bb, mat, 25f, 50f, a0, a1, COLOR_BG);
                } else {
                    drawSegment(bb, mat, 25f, 105f, a0, a1, COLOR_BG);
                }
                // 内环 ⚙（官方 658-667）：15..50，悬停 -268382465，否则 1879101183
                boolean cfgHov = a0 < pointerAngle && pointerAngle < a1 && 20f < pointerRadius && pointerRadius < 50f;
                if (cfgHov) {
                    drawSegment(bb, mat, 15f, 50f, a0, a1, COLOR_CFG_HOVER);
                    hoveredConfigIndex = page * 8 + i;
                    anyCfg = true;
                } else {
                    drawSegment(bb, mat, 25f, 50f, a0, a1, COLOR_CFG_IDLE);
                }
            } else {
                drawSegment(bb, mat, 25f, hov ? 115f : 105f, a0, a1, hov ? COLOR_BG_HOVER : COLOR_BG);
            }
        }
        if (!any) hoveredIndex = -1;
        if (!anyCfg) hoveredConfigIndex = -1;
        tess.end();
        RenderSystem.disableBlend();
    }

    private static String keyOf(String entry) { return entry.split("\u0000", 2)[0]; }

    private static String valueOf(String entry) {
        String[] kv = entry.split("\u0000", 2);
        return kv.length > 1 ? kv[1] : "";
    }

    /** 官方 drawRadialSegment:698-707 */
    private void drawSegment(BufferBuilder bb, Matrix4f mat, float inner, float outer,
                             float a0, float a1, int color) {
        float alpha = ((color >> 24) & 255) / 255f;
        float red = ((color >> 16) & 255) / 255f;
        float green = ((color >> 8) & 255) / 255f;
        float blue = (color & 255) / 255f;
        bb.vertex(mat, centerX + outer * Mth.cos(a0), centerY + outer * Mth.sin(a0), 0f).color(red, green, blue, alpha).endVertex();
        bb.vertex(mat, centerX + inner * Mth.cos(a0), centerY + inner * Mth.sin(a0), 0f).color(red, green, blue, alpha).endVertex();
        bb.vertex(mat, centerX + inner * Mth.cos(a1), centerY + inner * Mth.sin(a1), 0f).color(red, green, blue, alpha).endVertex();
        bb.vertex(mat, centerX + outer * Mth.cos(a1), centerY + outer * Mth.sin(a1), 0f).color(red, green, blue, alpha).endVertex();
    }

    /** 官方 renderRadialButtons:572-602 + renderWrappedLabel:617-632 + renderKeyBindings:605-615 */
    private void renderRadialButtons(GuiGraphics g) {
        String[] entries = pageEntries();
        float angle = 0.3926991f;   // π/8
        for (int i = 0; i < entries.length; i++) {
            String key = keyOf(entries[i]);
            String val = valueOf(entries[i]);
            // key 前缀 # = 子菜单（动画分类），value 前缀 # = ⚙ 配置按钮 —— 两套语义，官方也是分开判的
            boolean isSubmenu = key.startsWith(SUBMENU_PREFIX);
            boolean isReturn = key.equals(RETURN_KEY);
            boolean isConfig = val.startsWith(SUBMENU_PREFIX);
            int x = (int) (centerX + 65 * Mth.cos(angle));
            int labelY = (int) (centerY + 65 * Mth.sin(angle) - 4.5f);
            if (isConfig) {
                // 官方 583-591：配置入口在半径 35 画 ⚙，显示名换成配置按钮的 name
                ModelLibrary.LoadedModel.ConfigButton cb = findConfigGroup(val.substring(1));
                if (cb != null) val = cb.name;
                g.drawCenteredString(font, Component.literal("⚙").withStyle(ChatFormatting.BOLD, ChatFormatting.GOLD),
                        (int) (centerX + 35 * Mth.cos(angle)), (int) (centerY + 35 * Mth.sin(angle) - 4.5f), 16777215);
            }
            String label = val.isBlank() ? key.replaceFirst("^#", "") : val;
            net.minecraft.network.chat.MutableComponent text = Component.literal(label);
            if (isSubmenu) text.withStyle(ChatFormatting.RED);
            List<FormattedCharSequence> lines = font.split(text, 50);
            int lineY = labelY - lines.size() * 9 + 2;
            for (FormattedCharSequence line : lines) {
                g.drawCenteredString(font, line, x, lineY, COLOR_TEXT);
                lineY += 9;
            }
            if (submenu == null) {
                // 官方未绑定额外动画键时的提示："[ 无 ]"（黄色）
                g.drawCenteredString(font, Component.literal("[ ").withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal("无").withStyle(ChatFormatting.YELLOW))
                        .append(" ]").withStyle(ChatFormatting.YELLOW), x, labelY + 4, COLOR_TEXT);
            }
            angle += SLICE;
        }
    }

    /** 子菜单显示名：优先模型配置里的分类名，否则用 id */
    private String submenuName(String animKey, String subId) {
        String label = props.getOrDefault(animKey, subId);
        if (label.startsWith(SUBMENU_PREFIX)) label = label.substring(SUBMENU_PREFIX.length());
        return label;
    }

    // ================= 官方配置表单面板（AnimationRouletteScreen:204-260 / 505-508） =================

    /** value（含 # 前缀，可能是 id 也可能是被改写后的显示名）→ ConfigButton */
    private ModelLibrary.LoadedModel.ConfigButton findConfigGroup(String token) {
        ModelLibrary.LoadedModel lm = ModelLibrary.get(modelId);
        if (lm == null || token == null) return null;
        String t = token.startsWith(SUBMENU_PREFIX) ? token.substring(1) : token;
        ModelLibrary.LoadedModel.ConfigButton cb = lm.configButtons.get(t);
        if (cb != null) return cb;
        for (ModelLibrary.LoadedModel.ConfigButton c : lm.configButtons.values()) {
            if (c.name != null && (c.name.equals(t) || c.name.equalsIgnoreCase(t))) return c;
        }
        return null;
    }

    /** 官方 showConfigGroup:505-508：切换当前配置组并复位滚动 */
    private void showConfigGroup(ModelLibrary.LoadedModel.ConfigButton cb) {
        this.configGroup = cb;
        this.configScroll = 0;
        this.configScrollMax = 0;
        this.dragForm = null;
        this.init(minecraft, this.width, this.height);
    }

    private void scrollConfig(int dy) {
        configScroll = Mth.clamp(configScroll + dy, 0, Math.max(0, configScrollMax));
    }

    private int listX() { return centerX + 125; }

    /** 一行控件（官方 renderConfigFormItem 的行高：checkbox 12/次行 +14、range 15/+17、radio pill+多列） */
    private record Row(int kind, ModelLibrary.LoadedModel.ConfigForm form, String label,
                       int x, int y, int w, int h, boolean on, float value, String script) {}
    private static final int K_CHECK = 0, K_RANGE = 1, K_RADIO_HEAD = 2, K_RADIO_OPT = 3;

    /**
     * 官方 init:225-229：iArr 从 -46 起累加，控件 x = centerX+125、y = centerY+rel，
     * maxConfigScroll = rel - 110（官方 240/250/311 行）。
     */
    private List<Row> layoutConfig() {
        List<Row> out = new ArrayList<>();
        if (configGroup == null) return out;
        int rel = -46;
        for (ModelLibrary.LoadedModel.ConfigForm f : configGroup.forms) {
            if (f == null || f.type == null) continue;
            if ("checkbox".equals(f.type)) {
                boolean on = varVal(f.value) > 0.5f;
                out.add(new Row(K_CHECK, f, f.title, listX(), centerY + rel - configScroll, 115, 12, on, 0f, null));
                rel += 14;
            } else if ("range".equals(f.type)) {
                out.add(new Row(K_RANGE, f, f.title, listX(), centerY + rel - configScroll, 115, 15,
                        false, varVal(f.value), null));
                rel += 17;
            } else if ("radio".equals(f.type)) {
                int maxW = 0;
                for (String lab : f.labels.keySet()) maxW = Math.max(maxW, font.width(lab) + 16);
                if (maxW == 0) maxW = 115;
                int cols = Math.max(1, 115 / maxW);
                int optW = Math.round(110f / cols);
                int rows = ((f.labels.size() - 1) / cols) + 1;
                int pillH = rows * 14 + 14;
                out.add(new Row(K_RADIO_HEAD, f, f.title, listX(), centerY + rel - configScroll, 115, pillH,
                        false, 0f, null));
                int sel = Math.round(varVal(f.value));
                int idx = 0;
                for (Map.Entry<String, String> le : f.labels.entrySet()) {
                    int col = idx % cols, row = idx / cols;
                    out.add(new Row(K_RADIO_OPT, f, le.getKey(),
                            listX() + 2 + col * optW, centerY + rel + 14 + row * 14 - configScroll,
                            optW, 12, sel == idx, 0f, le.getValue()));
                    idx++;
                }
                rel += pillH + 3;
            }
        }
        configScrollMax = Math.max(0, rel - 110);
        return out;
    }

    private void renderConfigPanel(GuiGraphics g, int mouseX, int mouseY) {
        List<Row> rows = layoutConfig();
        int scTop = centerY - 46;
        int scBottom = centerY + 110;
        // 官方 render:377-391：scissor 到 centerY+110，列表整体 translate -scroll
        g.enableScissor(0, scTop, width, scBottom);
        int scrolledMouseY = mouseY + configScroll;
        for (Row r : rows) {
            if (r.y + r.h < scTop || r.y > scBottom) continue;
            switch (r.kind) {
                case K_CHECK -> {
                    boolean hover = hit(r, mouseX, scrolledMouseY);
                    g.fill(r.x, r.y, r.x + r.w, r.y + r.h, COLOR_CHECKBOX_BG);
                    blit(g, r.x, r.y, r.on ? 128f : 0f, 0f, r.w, r.h, 256, 256);
                    g.drawString(font, r.label, r.x + 14, r.y + 2, hover ? 0xFFFFE08A : 0xFFFFFFFF, false);
                }
                case K_RANGE -> {
                    boolean hover = hit(r, mouseX, scrolledMouseY);
                    boolean dragging = dragForm == r.form;
                    float val = dragging ? dragValue : r.value;
                    float min = r.form.min, max = r.form.max;
                    float t = max - min == 0 ? 0f : Mth.clamp((val - min) / (max - min), 0f, 1f);
                    // 官方 AnimationSlider.renderWidget:54-61（图集行 v=24/44、滑块 v=64/84、9 宫格 2/3/2/2）
                    blitBorder(g, r.x, r.y, 0f, 24f, r.w, r.h, 200, 15, 2, 3, 2, 2);
                    int handleX = r.x + (int) (t * (r.w - 8));
                    blitBorder(g, handleX, r.y, 0f, hover || dragging ? 84f : 64f, 8, r.h, 200, 15, 2, 3, 2, 2);
                    g.drawCenteredString(font, r.label + formatValue(val, r.form.step),
                            r.x + r.w / 2, r.y + (r.h - 8) / 2, 0xFFFFFF);
                }
                case K_RADIO_HEAD -> {
                    // 官方 FlatIconButton 标题条：深灰底 + 标题
                    g.fill(r.x, r.y, r.x + r.w, r.y + r.h, 0x90202020);
                    g.drawString(font, r.label, r.x + 4, r.y + 2, 0xFFFFFFFF, false);
                }
                case K_RADIO_OPT -> {
                    boolean hover = hit(r, mouseX, scrolledMouseY);
                    g.fill(r.x, r.y, r.x + r.w, r.y + r.h, COLOR_CHECKBOX_BG);
                    blit(g, r.x, r.y, r.on ? 128f : 0f, 0f, r.w, r.h, 256, 256);
                    g.drawString(font, font.plainSubstrByWidth(r.label, r.w - 16), r.x + 14, r.y + 2,
                            hover ? 0xFFFFE08A : 0xFFFFFFFF, false);
                }
                default -> { }
            }
        }
        g.disableScissor();
        // 滚动条（可读性提示，官方只有 ↑/↓ 两枚按钮）
        if (configScrollMax > 0) {
            int trackH = scBottom - scTop - 8;
            int barH = Math.max(16, trackH * 110 / (110 + configScrollMax));
            int barY = scTop + 4 + (int) ((trackH - barH) * (configScroll / (float) configScrollMax));
            g.fill(width - 6, scTop, width - 2, scBottom, 0x30000000);
            g.fill(width - 6, barY, width - 2, barY + barH, 0x908A8A8A);
        }
    }

    private boolean hit(Row r, double mx, double my) {
        return mx >= r.x && mx < r.x + r.w && my >= r.y && my < r.y + r.h;
    }

    /** 官方 blitWithBorder（RangedSliderWidget:162-193）原样移植 */
    private void blitBorder(GuiGraphics g, int x, int y, float u, float v, int w, int h,
                            int texW, int texH, int top, int bottom, int left, int right) {
        int fillerW = texW - left - right;
        int fillerH = texH - top - bottom;
        int canvasW = w - left - right;
        int canvasH = h - top - bottom;
        int xPasses = canvasW / fillerW;
        int remW = canvasW % fillerW;
        int yPasses = canvasH / fillerH;
        int remH = canvasH % fillerH;
        g.blit(TEX, x, y, u, v, left, top, 256, 256);
        g.blit(TEX, x + left + canvasW, y, u + left + fillerW, v, right, top, 256, 256);
        g.blit(TEX, x, y + top + canvasH, u, v + top + fillerH, left, bottom, 256, 256);
        g.blit(TEX, x + left + canvasW, y + top + canvasH, u + left + fillerW, v + top + fillerH,
                right, bottom, 256, 256);
        for (int i = 0; i < xPasses + (remW > 0 ? 1 : 0); i++) {
            int dw = (i == xPasses ? remW : fillerW);
            g.blit(TEX, x + left + i * fillerW, y, u + left, v, dw, top, 256, 256);
            g.blit(TEX, x + left + i * fillerW, y + top + canvasH, u + left, v + top + fillerH,
                    dw, bottom, 256, 256);
            for (int j = 0; j < yPasses + (remH > 0 ? 1 : 0); j++) {
                int dh = (j == yPasses ? remH : fillerH);
                g.blit(TEX, x + left + i * fillerW, y + top + j * fillerH, u + left, v + top,
                        dw, dh, 256, 256);
            }
        }
        for (int j = 0; j < yPasses + (remH > 0 ? 1 : 0); j++) {
            int dh = (j == yPasses ? remH : fillerH);
            g.blit(TEX, x, y + top + j * fillerH, u, v + top, left, dh, 256, 256);
            g.blit(TEX, x + left + canvasW, y + top + j * fillerH, u + left + fillerW, v + top,
                    right, dh, 256, 256);
        }
    }

    private void blit(GuiGraphics g, int x, int y, float u, float v, int w, int h, int texW, int texH) {
        g.blit(TEX, x, y, u, v, w, h, texW, texH);
    }

    private static String formatValue(float v, float step) {
        if (step >= 1f) return String.valueOf(Math.round(v));
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }

    // ================= 变量读写（与 ModelWheelScreen 同口径） =================

    private float varVal(String name) {
        EntitySettings es = ClientState.settingsFor(entityUuid);
        if (es == null || name == null) return 0f;
        Float v = es.molVars.get(name);
        if (v == null) v = es.molVars.get(name.toLowerCase(Locale.ROOT));
        if (v == null) {
            for (Map.Entry<String, Float> e : es.molVars.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) { v = e.getValue(); break; }
            }
        }
        return v != null ? v : 0f;
    }

    private void sendVar(String name, float v) {
        Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_VAR,
                name + "=" + formatValue(v, 0.01f)));
    }

    /** 官方 AnimationSlider.setValueFromMouse: (mouseX - (x + 4)) / (width - 8) */
    private void updateDrag(double mouseX) {
        Row target = null;
        for (Row r : layoutConfig()) {
            if (r.kind == K_RANGE && r.form == dragForm) { target = r; break; }
        }
        if (target == null) return;
        float t = (float) ((mouseX - (target.x + 4)) / (target.w - 8));
        t = Mth.clamp(t, 0f, 1f);
        float raw = dragForm.min + t * (dragForm.max - dragForm.min);
        if (dragForm.step > 0f) raw = Math.round(raw / dragForm.step) * dragForm.step;
        if (Math.abs(raw - dragValue) > 1e-4f) {
            dragValue = raw;
            sendVar(dragForm.value, dragValue);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        // 配置面板控件优先于轮盘（官方 children 循环在扇区判定之后，但控件区不与扇区重叠）
        if (configGroup != null) {
            double scrolledY = mouseY + configScroll;
            for (Row r : layoutConfig()) {
                if (!hit(r, mouseX, scrolledY)) continue;
                if (r.kind == K_CHECK) {
                    Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_VAR,
                            r.form.value + "=" + (r.on ? "0" : "1")));
                    return true;
                }
                if (r.kind == K_RANGE) {
                    dragForm = r.form;
                    dragValue = r.value;
                    updateDrag(mouseX);
                    return true;
                }
                if (r.kind == K_RADIO_OPT && r.script != null) {
                    Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid,
                            EntityActionC2S.EXEC_SCRIPT, r.script));
                    return true;
                }
            }
        }

        String[] entries = pageEntries();
        if (hoveredIndex >= 0 && hoveredIndex < page * 8 + entries.length) {
            String key = keyOf(entries[hoveredIndex - page * 8]);
            // 官方 AnimationRouletteScreen:456-465：按 **key** 判断
            //    key == "#return"      → 返回上一层
            //    key 以 "#" 开头        → 进子菜单（找不到就什么都不做）
            //    否则                  → 播这个动画并关界面
            if (key.startsWith(SUBMENU_PREFIX)) {
                String sub = key.substring(SUBMENU_PREFIX.length());
                if ("return".equals(sub) || RETURN_KEY.equals(key)) {
                    back();
                    return true;
                }
                ModelLibrary.LoadedModel lm = ModelLibrary.get(modelId);
                if (lm != null && lm.extraAnimationClassifies.containsKey(sub)) {
                    // 官方是"同一个界面换 currentNavEntry"，不是再开一个子界面。
                    // 旧写法 new OfficialRouletteScreen(this,...) 把父界面设成子界面，
                    // 于是 子→返回→父→返回→又回子，来回弹（用户实测的父子跳转）。
                    openSubmenu(sub);
                    return true;
                }
                return true;
            }
            playAnim(key);
            Minecraft.getInstance().setScreen(null);
            return true;
        }

        // 官方 :466-478：内环 ⚙ → 打开配置表单（value 以 # 开头）
        if (hoveredConfigIndex >= 0 && hoveredConfigIndex < page * 8 + entries.length) {
            String val = valueOf(entries[hoveredConfigIndex - page * 8]);
            if (val.startsWith(SUBMENU_PREFIX)) {
                ModelLibrary.LoadedModel.ConfigButton cb = findConfigGroup(val.substring(1));
                if (cb != null) {
                    showConfigGroup(cb);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragForm != null) {
            updateDrag(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragForm != null) {
            dragForm = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 官方 navigateBack:519-535：子菜单只弹一层回父级，根目录才退出到打开轮盘的界面 */
    private void back() {
        if (submenu != null) {
            openSubmenu(null);
        } else {
            Minecraft.getInstance().setScreen(parent);
        }
    }

    /**
     * 官方 navigateToSubmenu:537-551：同一个 Screen 实例里换 currentProperties。
     * 这里等价实现：换 submenu、页码归零、关掉配置面板、重算 props 与控件。
     */
    private void openSubmenu(String id) {
        this.submenu = id;
        this.page = 0;
        this.hoveredIndex = -1;
        this.hoveredConfigIndex = -1;
        this.configGroup = null;
        this.configScroll = 0;
        this.configScrollMax = 0;
        this.dragForm = null;
        this.init(minecraft, this.width, this.height);
    }

    /** 与 ModelWheelScreen.playAnim 同一口径：本地重启时钟 + 发服务端 */
    private void playAnim(String animName) {
        if (animName == null || animName.isEmpty()) return;
        AnimStates.get(entityUuid).restartAnimation();
        Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.PLAY_ANIM, animName));
    }

    /** 官方 mouseScrolled:417-435：x < centerX+110 翻页，右侧滚配置列表（20/格） */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta != 0) {
            if (mouseX < centerX + 110) {
                if (delta < 0) nextPage();
                else prevPage();
                return true;
            }
            if (configGroup != null) {
                scrollConfig(delta < 0 ? 20 : -20);
                return true;
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
