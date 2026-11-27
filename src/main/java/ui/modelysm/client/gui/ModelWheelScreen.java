package ui.modelysm.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec2;
import org.joml.Matrix4f;
import ui.modelysm.client.AnimStates;
import ui.modelysm.client.ClientState;
import ui.modelysm.client.ModelLibrary;
import ui.modelysm.common.EntitySettings;
import ui.modelysm.network.Net;
import ui.modelysm.network.packets.EntityActionC2S;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * YSM 风格径向轮盘 GUI（扇区悬停高亮 + 点击进入子菜单）。
 * 扇区：播放动画 / 模型设置(蓝) / 停止动画 / 重置设置 / 关闭。
 * 点击"模型设置"后轮盘移到左侧，右侧滑出 YSM 风格设置面板：
 *   动画表情（勾选） / 身体大小（滑条） / 骨骼显隐（勾选），支持滚轮滚动。
 * 中央显示目标实体名。
 */
public class ModelWheelScreen extends Screen {
    private static final int SECTORS = 6;
    private static final float GAP_DEG = 3f;
    // 轮盘半径随窗口尺寸自适应（render 里计算，避免小窗口溢出/压快捷栏）
    private float rOuter = 115f, rInner = 30f, centerR = 46f;

    // 0=右上 1=右 2=右下 3=左下 4=左下偏左 5=左上
    // 关闭从左上挪到右下（原"切换形态"位，形态切换已移除）；左上让给"绑定模型"，
    // 原"播放动画"位（右上）改成"解除绑定"，绑定/解绑全部走 V 键轮盘
    private static final String[] TITLES = {"解除绑定", "模型设置", "关闭", "官方轮盘", "重置设置", "绑定模型"};

    private static final float SCALE_MIN = 0.1f, SCALE_MAX = 3.0f;
    private static final String SLIDER_NAME = "身体大小";

    private static final int PANEL_W = 250;
    private static final int CHECK = 22;   // 勾选行高
    private static final int HEADER = 26;  // 分区标题高
    private static final int SLIDER_H = 42;// 滑条区高
    private static final int GAP = 8;

    private final UUID entityUuid;
    private final String entityName;
    private final String modelId;

    private float mouseX = -1, mouseY = -1;

    // ---- 右侧设置面板状态 ----
    private boolean panelOpen = false;
    public double panelScroll = 0;
    public double panelScrollMax = 0;
    private SliderRef draggingSlider = null;     // null=未拖拽；varName=null 表示实体 scale（SET_SCALE）
    private float dragValue = 1.0f;              // 拖拽中的实时值
    private float lastSentValue = Float.NaN;     // 上次发送值（变化≥阈值才再发）
    // 面板滚动条几何（渲染时记录，供拖拽）
    private boolean sbDrag = false;
    private int sbTrackTop, sbTrackBottom, sbBarH;
    /** hover 行的表单描述（scissor 内收集，scissor 外绘制 tooltip） */
    private String pendingTooltip;

    public ModelWheelScreen(UUID entityUuid, String entityName, String modelId) {
        super(Component.literal("ModelYSM 模型设置"));
        this.entityUuid = entityUuid;
        this.entityName = entityName;
        this.modelId = modelId;
    }

    private float wheelCx() {
        if (!panelOpen) return width / 2f;
        // 面板打开时轮盘靠左，但不越出屏幕左缘
        return Math.max(rOuter + 24f, width * 0.27f);
    }

    /** 当前绑定的模型（动态读客户端同步表；绑定/解绑后立即反映，不用构造时的快照） */
    private String boundModel() {
        EntitySettings es = ClientState.settingsFor(entityUuid);
        if (es != null && es.model != null) return es.model;
        // 同步包还没回来的极短窗口里退回构造时传入的模型 id（未绑定就是 ""）
        return modelId == null ? "" : modelId;
    }

    /** 依据窗口大小计算轮盘半径，避免小窗口溢出/压到快捷栏 */
    private void computeLayout() {
        rOuter = Mth.clamp(Math.min(height * 0.24f, width * 0.28f), 70f, 130f);
        rInner = rOuter * 0.28f;
        centerR = rInner + (rOuter - rInner) * 0.22f;
    }

    private String[] sectorStatus() {
        EntitySettings es = ClientState.settingsFor(entityUuid);
        boolean bound = es != null && es.model != null && !es.model.isEmpty();
        // 官方 YSM 风格：状态统一黄色。解除绑定这一格的名称保持固定文案，
        // 不显示当前模型 id（"Ender_Dragon_已适配3.6.2" 这类长名会把扇区文字顶出去）
        return new String[]{
                bound ? "§e[点击解除]" : "§8[未绑定]",
                "§e[编辑]",
                "§e[ESC]",
                "§e[点击打开]",
                "§e[默认]",
                bound ? "§e[重新绑定]" : "§e[点击绑定]"
        };
    }

    /** 解析 forcedPart 逗号分隔列表为集合（容错空串/空白） */
    private static java.util.LinkedHashSet<String> forcedSet(EntitySettings es) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (es != null && es.forcedPart != null) {
            for (String s : es.forcedPart.split(",")) {
                if (!s.isBlank()) out.add(s.trim());
            }
        }
        return out;
    }

    /** 互斥形态：该部件是否处于激活状态（被强制选中；自动模式下看渲染器最近一帧的实际缩放） */
    private boolean isFormActive(String pb, ModelLibrary.LoadedModel loaded) {
        EntitySettings es = ClientState.settingsFor(entityUuid);
        java.util.Set<String> fp = forcedSet(es);
        if (!fp.isEmpty()) return fp.contains(pb);
        // 自动模式：互斥形态由动画层 scale=0 切换（如 wine_fox 满血隐藏 FOX），
        // hiddenBones 不反映它 → 读最近一帧该部件的实际渲染缩放
        ui.modelysm.client.render.BedrockModel.Bone bone = loaded.model.bonesByName.get(pb);
        if (bone != null) {
            float s = Math.max(bone.animScale[0], Math.max(bone.animScale[1], bone.animScale[2]));
            return s > 0.05f;
        }
        boolean hiddenNow = es != null && es.hiddenBones.contains(pb);
        return !hiddenNow;
    }

    /** 形态状态描述：自动 / 仅 <部件名> / 手动显示 / 自定义 */
    private String formStatus() {
        ModelLibrary.LoadedModel loaded = ModelLibrary.get(boundModel());
        if (loaded == null || loaded.partBones.size() < 2) return "§8[无形态]";
        EntitySettings es = ClientState.settingsFor(entityUuid);
        java.util.Set<String> hidden = es != null ? es.hiddenBones : java.util.Set.of();
        // 优先显示手动列表状态
        java.util.Set<String> fp = forcedSet(es);
        fp.retainAll(loaded.partBones);
        // 互斥形态模型的多条目强制列表是旧版残留脏数据（渲染端已忽略）→ 视为自动
        if (loaded.exclusiveParts && fp.size() >= 2) fp.clear();
        if (fp.size() == 1) {
            String p = fp.iterator().next();
            return "§e[仅 " + loaded.partNames.getOrDefault(p, p) + "]";
        }
        if (fp.size() >= 2) return "§e[手动显示]";
        int visible = 0;
        for (String pb : loaded.partBones) {
            if (!hidden.contains(pb)) visible++;
        }
        if (visible == loaded.partBones.size()) return "§e[自动]";
        return "§e[自定义]";
    }

    /**
     * 播放轮盘动画：先本地清零该实体动画时间（同一动画连点也能重播，
     * 官方额外动画的 timeline 脚本如"穿脱兜帽"才能反复翻值），再发包让服务端记 anim。
     */
    private void playAnim(String animName) {
        if (animName == null || animName.isEmpty()) return;
        AnimStates.get(entityUuid).restartAnimation();
        Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.PLAY_ANIM, animName));
    }

    /** 官方"穿脱 / 换装 / 外饰"分组判定：这类分组才是帽子/披甲的开关归属 */
    private static boolean isDressGroup(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return name.contains("兜帽") || name.contains("穿脱") || name.contains("外饰")
                || name.contains("换装") || name.contains("帽")
                || n.contains("dress") || n.contains("hat") || n.contains("outfit");
    }

    /** roaming 变量 → 勾选行；groupName 非空时用它给第一行取名（如"穿脱兜帽"） */
    private void addRoamingRows(List<Item> items, ModelLibrary.LoadedModel lm, String groupName) {
        int i = 0;
        for (String key : lm.roamingVars) {
            String shortName = key.startsWith("v.roaming.") ? key.substring("v.roaming.".length()) : key;
            boolean first = i++ == 0;
            String title = (first && groupName != null && !groupName.isEmpty())
                    ? groupName : "漫游 " + shortName;
            double val = AnimStates.get(entityUuid).roaming.getOrDefault(key, 0.0);
            boolean on = val > 0.5;
            items.add(new Item("check", "§a" + title + (on ? " §a[开]" : " §7[关]"), on, () -> {
                // 点按瞬间读真实值，避免连点错位
                java.util.Map<String, Double> rm = AnimStates.get(entityUuid).roaming;
                double cur = rm.getOrDefault(key, 0.0);
                rm.put(key, cur > 0.5 ? 0.0 : 1.0);
            }, null, "官方漫游变量 v.roaming." + shortName
                    + "：每实体持久，模型动画直接读取（=1 摘帽 / 露出背甲，=0 恢复）"));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        computeLayout();

        // 滑条拖动兜底：render 每帧都携带最新鼠标坐标，不依赖 mouseMoved/mouseDragged
        // 事件链（部分客户端环境事件链被拦截导致只能点击跳位）。按住期间逐帧跟随。
        if (draggingSlider != null) {
            updateDragValue(mouseX);
            sendValueIfChanged();
        }

        renderWheel(g);

        if (panelOpen) {
            renderPanel(g);
        }
    }

    // ================= 轮盘（官方 YSM 风格：深灰扇区+可见缝隙+青色悬停+深色中心盘） =================

    private void renderWheel(GuiGraphics g) {
        float cx = wheelCx(), cy = height / 2f;
        int hover = panelOpen ? -1 : hoverSector();

        // 上下文字位置做夹取：不越出屏幕顶部、不压快捷栏/经验栏
        int topY = Math.max(4, (int) (cy - rOuter - 24));
        // 底部模型名：轮盘外沿（含 2.5px 描边）再往下 12px 起，紧贴但不压扇区；
        // 下限 height-44 保证离原版快捷栏/血条还有一段距离
        int botY = Math.min((int) (cy + rOuter) + 15, height - 44);
        g.drawCenteredString(font, "§b目标: §f" + entityName, (int) cx, topY, 0xFFFFFF);

        Matrix4f mat = g.pose().last().pose();
        // 全部图元走自定义 no-cull RenderType（vanilla RenderType.gui() 默认剔除背面，
        // 顶点绕向为 CW 的扇形/圆盘会被整体剔除——之前"画了却看不见"的根因）
        var bs = minecraft.renderBuffers().bufferSource();
        var vc = bs.getBuffer(ui.modelysm.client.ModelGuiRenderType.GUI_NO_CULL);
        // 1) 扇区描边（让可点击区块清晰可见）
        for (int i = 0; i < SECTORS; i++) {
            addSector(vc, mat, cx, cy, i, i == hover ? 0xC0FFFFFF : 0x60C8C8C8, rOuter + 2.5f, 0.8f);
        }
        // 2) 扇区本体（深灰，悬停青色；所有扇区统一配色）
        for (int i = 0; i < SECTORS; i++) {
            int color;
            if (i == hover) color = 0xE82AB0A8;                 // 悬停青蓝（YSM 同款）
            else color = 0xC81E1E1E;                            // 常规：深灰
            addSector(vc, mat, cx, cy, i, color, rOuter, 0f);
        }
        // 2.5) 扇区缝隙描边（黑暗背景下也能看清轮盘结构）
        for (int i = 0; i < SECTORS; i++) {
            float ang = i * (360f / SECTORS);
            addLine(vc, mat, cx, cy, ang, centerR, rOuter, 0x50D8D8D8);
        }
        // 3) 中心盘（切换模型按钮：平时纯白，悬停扇区同款青蓝高亮）
        boolean centerHover = !panelOpen && isMouseInCenter();
        addDisc(vc, mat, cx, cy, centerR, centerHover ? 0xF02AB0A8 : 0xFFFFFFFF);
        addDisc(vc, mat, cx, cy, centerR - 2, centerHover ? 0x302AB0A8 : 0x18101010);
        bs.endBatch(ui.modelysm.client.ModelGuiRenderType.GUI_NO_CULL);

        // 中心文字：白底用深色字、蓝底用白字（drawCenteredString 的 color 参数在无 § 码时直接生效）
        g.drawCenteredString(font, "⇄ 选择模型", (int) cx, (int) cy - 10, centerHover ? 0xFFFFFF : 0x2B2B2B);
        g.drawCenteredString(font, "点击打开", (int) cx, (int) cy + 3, centerHover ? 0xFFFFFF : 0x606060);
        g.drawCenteredString(font, entityName, (int) cx, (int) cy + 16, centerHover ? 0xFFFFFF : 0x505050);

        // 扇区文字（名称白色 + 状态黄色，官方排版）
        String[] status = sectorStatus();
        for (int i = 0; i < SECTORS; i++) {
            Vec2 p = sectorCenter(i, cx, cy);
            // 文字超出轮盘上下边界时夹回轮盘内
            int ty = Mth.clamp((int) p.y - 10, topY + 12, height - 40);
            g.drawCenteredString(font, TITLES[i], (int) p.x, ty,
                    i == hover ? 0x7FFCF4 : 0xFFFFFF);
            g.drawCenteredString(font, status[i], (int) p.x, ty + 12, 0xFFFFFF);
        }

        String bm = boundModel();
        g.drawCenteredString(font, bm.isEmpty() ? "§8模型: 未绑定" : "§8模型: " + bm, (int) cx, botY, 0xFFFFFF);
    }

    /**
     * 画一个扇形环段（QUADS：每步一个梯形 in0-out0-out1-in1，近似弧面）。
     * @param index       扇区索引（0=正上方起，顺时针）
     * @param radiusOuter 外半径（描边时略大）
     * @param padDeg      角度外扩（描边时向两侧扩，形成边框）
     */
    private void addSector(VertexConsumer vc, Matrix4f mat, float cx, float cy, int index, int argb,
                           float radiusOuter, float padDeg) {
        float span = 360f / SECTORS - GAP_DEG;
        float start = index * (360f / SECTORS) + GAP_DEG / 2 - padDeg;
        float end = start + span + padDeg * 2;
        int steps = 10;
        Vec2 inPrev = point(cx, cy, start, rInner);
        Vec2 outPrev = point(cx, cy, start, radiusOuter);
        for (int s = 1; s <= steps; s++) {
            float ang = start + (end - start) * s / steps;
            Vec2 inN = point(cx, cy, ang, rInner);
            Vec2 outN = point(cx, cy, ang, radiusOuter);
            guiQuad(vc, mat, inPrev, outPrev, outN, inN, argb);
            inPrev = inN;
            outPrev = outN;
        }
    }

    /** 画实心圆盘（退化四边形拼三角扇：圆心→弧上两点） */
    private void addDisc(VertexConsumer vc, Matrix4f mat, float cx, float cy, float radius, int argb) {
        int seg = 24;
        Vec2 c = new Vec2(cx, cy);
        Vec2 prev = new Vec2(cx + radius, cy);
        for (int i = 1; i <= seg; i++) {
            double a = 2 * Math.PI * i / seg;
            Vec2 next = new Vec2(cx + (float) Math.cos(a) * radius, cy + (float) Math.sin(a) * radius);
            guiQuad(vc, mat, c, prev, next, c, argb);
            prev = next;
        }
    }

    /** 画一条从 r0 到 r1 的细径向线（角度制，0°=正上） */
    private void addLine(VertexConsumer vc, Matrix4f mat, float cx, float cy, float deg, float r0, float r1, int argb) {
        float rad = deg * Mth.DEG_TO_RAD;
        float nx = Mth.cos(rad) * 0.5f, ny = Mth.sin(rad) * 0.5f; // 垂直方向半宽
        Vec2 p0 = point(cx, cy, deg, r0);
        Vec2 p1 = point(cx, cy, deg, r1);
        guiQuad(vc, mat,
                new Vec2(p0.x - nx, p0.y - ny), new Vec2(p0.x + nx, p0.y + ny),
                new Vec2(p1.x + nx, p1.y + ny), new Vec2(p1.x - nx, p1.y - ny),
                argb);
    }

    /** QUADS 模式四顶点（argb 内部归一化 0~1，color(float) 期望 0~1 分量） */
    private void guiQuad(VertexConsumer vc, Matrix4f mat, Vec2 p1, Vec2 p2, Vec2 p3, Vec2 p4, int argb) {
        float a = (argb >>> 24 & 255) / 255f;
        float r = (argb >>> 16 & 255) / 255f;
        float g = (argb >>> 8 & 255) / 255f;
        float b = (argb & 255) / 255f;
        vc.vertex(mat, p1.x, p1.y, 0).color(r, g, b, a).endVertex();
        vc.vertex(mat, p2.x, p2.y, 0).color(r, g, b, a).endVertex();
        vc.vertex(mat, p3.x, p3.y, 0).color(r, g, b, a).endVertex();
        vc.vertex(mat, p4.x, p4.y, 0).color(r, g, b, a).endVertex();
    }

    private Vec2 point(float cx, float cy, float deg, float radius) {
        float rad = deg * Mth.DEG_TO_RAD;
        return new Vec2(cx + Mth.sin(rad) * radius, cy - Mth.cos(rad) * radius);
    }

    private Vec2 sectorCenter(int index, float cx, float cy) {
        // 注意：point()/hoverSector() 的角度约定是 0°=正上方、顺时针增长，
        // 这里必须与扇区绘制(addSector: start=index*span)完全一致，不能额外偏移
        float mid = index * (360f / SECTORS) + (360f / SECTORS) / 2;
        float rad = mid * Mth.DEG_TO_RAD;
        float r = rInner + (rOuter - rInner) * 0.55f;
        return new Vec2(cx + Mth.sin(rad) * r, cy - Mth.cos(rad) * r);
    }

    /** 鼠标是否在轮盘中心盘内（切换模型按钮） */
    private boolean isMouseInCenter() {
        if (mouseX < 0) return false;
        float cx = wheelCx(), cy = height / 2f;
        double dx = mouseX - cx, dy = mouseY - cy;
        return Math.sqrt(dx * dx + dy * dy) <= centerR;
    }

    private int hoverSector() {
        if (mouseX < 0) return -1;
        float cx = wheelCx(), cy = height / 2f;
        double dx = mouseX - cx, dy = mouseY - cy;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < centerR || dist > rOuter) return -1;
        double deg = Math.toDegrees(Math.atan2(dx, -dy));
        deg = (deg + 360) % 360;
        float sector = 360f / SECTORS;
        for (int i = 0; i < SECTORS; i++) {
            float lo = i * sector + GAP_DEG / 2;
            float hi = (i + 1) * sector - GAP_DEG / 2;
            if (deg >= lo && deg < hi) return i;        }
        return -1;
    }

    // ================= 右侧设置面板 =================

    /**
     * 面板条目：header=分区标题 / check=勾选行 / slider=大小滑条 / vslider=变量滑条(配置表单) / action=按钮行 / gap=空隙。
     * desc=官方表单 description（hover 提示，scissor 外绘制）。
     */
    private record Item(String kind, String label, boolean checked, Runnable action, Object tag, String desc) {
        Item(String kind, String label, boolean checked, Runnable action) { this(kind, label, checked, action, null, null); }
        Item(String kind, String label, boolean checked, Runnable action, Object tag) { this(kind, label, checked, action, tag, null); }
    }

    /** vslider 拖拽绑定：变量名（null=实体 scale 走 SET_SCALE）+ 取值范围 */
    private record SliderRef(String varName, float min, float max, float step) {}

    /** 两个滑条引用是否指同一个控件（buildPanelItems 每帧重建 ref，只能按内容比较） */
    private static boolean sameSlider(SliderRef a, SliderRef b) {
        if (a == null || b == null) return a == b;
        return java.util.Objects.equals(a.varName(), b.varName())
                && Float.compare(a.min(), b.min()) == 0
                && Float.compare(a.max(), b.max()) == 0
                && Float.compare(a.step(), b.step()) == 0;
    }

    private List<Item> buildPanelItems() {
        List<Item> items = new ArrayList<>();
        // 未绑定：设置面板没有意义，只给一条提示（模型相关字段全部来自绑定模型）
        if (boundModel().isEmpty()) {
            items.add(new Item("header", "§c该实体未绑定模型", false, null));
            items.add(new Item("check", "§7关闭面板后点轮盘左上角【绑定模型】选择模型", false, null));
            return items;
        }
        EntitySettings es = ClientState.settingsFor(entityUuid);
        String curAnim = es != null ? es.anim : null;
        java.util.Set<String> hidden = es != null ? es.hiddenBones : java.util.Set.of();

        // （模型切换入口已移除：轮盘中心盘即切换模型，主菜单已有，避免重复）

        // ---- 模型配置（官方轮盘 ⚙：properties.extra_animation_buttons，变量驱动） ----
        // 【已按用户要求下线】V 键面板不再重复摆这批 ⚙ 配置表单（物品大小 / 角色大小 / 位置 x-y-z 等），
        // 官方轮盘右上角的配置面板（OfficialRouletteScreen）才保留这份功能，两边不再重复。
        // 下线范围：本段循环产出的全部 config 表单条目（含"穿脱"组里的漫游行）。
        ModelLibrary.LoadedModel loadedCfg = ModelLibrary.get(boundModel());
        boolean roamingEmbedded = false;
        if (false && loadedCfg != null && !loadedCfg.configButtons.isEmpty()) {
            for (ModelLibrary.LoadedModel.ConfigButton cb : loadedCfg.configButtons.values()) {
                items.add(new Item("header", "§d⚙ " + cb.name, false, null));
                // 官方"穿脱"分组的 4 个 checkbox 只是"穿了真盔甲就藏模型部件"；
                // 真正决定帽子/披甲显隐的是 v.roaming.*。把漫游开关放在该组第一行（披肩之上），
                // 用户才点得到，不然它埋在下面几屏。
                if (!roamingEmbedded && isDressGroup(cb.name)
                        && !loadedCfg.roamingVars.isEmpty() && loadedCfg.roamingVars.size() <= 4) {
                    addRoamingRows(items, loadedCfg, cb.name);
                    roamingEmbedded = true;
                }
                for (ModelLibrary.LoadedModel.ConfigForm form : cb.forms) {
                    if ("checkbox".equals(form.type)) {
                        boolean on = varVal(entityUuid, form.value) > 0.5f;
                        items.add(new Item("check", form.title, on, () -> {
                            // 读取点击时刻状态，避免连点错位
                            boolean nowOn = varVal(entityUuid, form.value) > 0.5f;
                            Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_VAR,
                                    form.value + "=" + (nowOn ? "0" : "1")));
                        }, null, form.description));
                    } else if ("range".equals(form.type)) {
                        items.add(new Item("vslider", form.title, false, null,
                                new SliderRef(form.value, form.min, form.max,
                                        form.step > 0 ? form.step : 0.05f),
                                form.description));
                    } else if ("radio".equals(form.type)) {
                        // 官方 radio 没有 value 字段（整段脚本才是"值"）：按当前变量反推选中项，
                        // 否则点击后按钮永远显灰、看起来像没反应
                        int sel = radioSelected(es, form);
                        int idx = 0;
                        for (Map.Entry<String, String> le : form.labels.entrySet()) {
                            String script = le.getValue();
                            items.add(new Item("check", le.getKey(), sel == idx, () ->
                                    Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid,
                                            EntityActionC2S.EXEC_SCRIPT, script)), null, form.description));
                            idx++;
                        }
                    }
                }
                items.add(new Item("gap", "", false, null));
            }
        }

        // ---- 贴图切换（官方多套皮肤：textures + properties.default_texture，点选即换） ----
        if (loadedCfg != null && loadedCfg.textures.size() > 1) {
            items.add(new Item("header", "§b皮肤 / 贴图", false, null));
            int texSel = es != null ? es.textureIndex : 0;
            int ti = 0;
            for (String tn : loadedCfg.textureNames) {
                int thisIdx = ti;
                items.add(new Item("check", tn, texSel == thisIdx, () ->
                        Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_TEXTURE,
                                String.valueOf(thisIdx)))));
                ti++;
            }
            items.add(new Item("gap", "", false, null));
        }

        // ---- 模型大小（单滑条整体缩放）----
        // 【已按用户要求下线】"模型大小"标题 + "身体大小"滑条 + "⟳ 重置大小"按钮整组删除：
        // 官方 YSM 的缩放是 ⚙ 配置表单里的一项，V 面板里的这套自造入口不再需要。

        // ---- 角色 / 部件（根骨骼子树开关：如 wine_fox = 人型 / 狐狸） ----
        items.add(new Item("header", "§b角色 / 部件 " + formStatus(), false, null));
        ModelLibrary.LoadedModel loaded = ModelLibrary.get(boundModel());
        boolean exclusive = loaded != null && loaded.exclusiveParts;
        if (loaded != null && !loaded.partBones.isEmpty()) {
            for (String pb : loaded.partBones) {
                String disp = loaded.partNames.getOrDefault(pb, pb);
                if (exclusive) {
                    // 互斥形态（官方 YSM 机制）：点击 = 切换到该形态（独占显示），再点 = 恢复自动
                    items.add(new Item("check", disp + " §8(" + pb + ")", isFormActive(pb, loaded), () -> {
                        EntitySettings now = ClientState.settingsFor(entityUuid);
                        boolean active = isFormActive(pb, loaded);
                        if (active) {
                            // 已是该形态 → 恢复自动（动画层互斥控制，默认形态如满血人型）
                            // 恢复时清除所有部件的残留隐藏，否则另一形态 hiddenBones 残留导致再也显示不出
                            Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_PART, ""));
                            for (String p : loaded.partBones) {
                                Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SHOW_BONE, p));
                            }
                        } else {
                            // 切换形态：独占显示该部件（渲染端强制 scale，压过动画）
                            java.util.LinkedHashSet<String> fp = new java.util.LinkedHashSet<>();
                            fp.add(pb);
                            Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_PART,
                                    String.join(",", fp)));
                            Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SHOW_BONE, pb));
                            for (String other : loaded.partBones) {
                                if (!other.equals(pb)) {
                                    Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.HIDE_BONE, other));
                                }
                            }
                        }
                    }));
                } else {
                    // 普通部件：勾选显示/隐藏，手动显示的部件压过动画 scale
                    items.add(new Item("check", disp + " §8(" + pb + ")", !hidden.contains(pb), () -> {
                        EntitySettings now = ClientState.settingsFor(entityUuid);
                        boolean isHidden = now != null && now.hiddenBones.contains(pb);
                        if (isHidden) {
                            java.util.LinkedHashSet<String> fp = forcedSet(now);
                            fp.add(pb);
                            Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_PART,
                                    String.join(",", fp)));
                            Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SHOW_BONE, pb));
                        } else {
                            java.util.LinkedHashSet<String> fp = forcedSet(now);
                            fp.remove(pb);
                            Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_PART,
                                    String.join(",", fp)));
                            Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.HIDE_BONE, pb));
                        }
                    }));
                }
            }
            if (exclusive) {
                // 互斥形态：恢复自动（动画层接管，默认形态如官方满血人型）
                items.add(new Item("action", "§e⟳ 恢复自动形态", false, () -> {
                    Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_PART, ""));
                    for (String pb : loaded.partBones) {
                        Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SHOW_BONE, pb));
                    }
                }));
            } else {
                items.add(new Item("action", "§e⟳ 显示全部部件", false, () -> {
                    // 全部部件加入手动列表并显示（非互斥模型才提供）
                    Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_PART,
                            String.join(",", loaded.partBones)));
                    EntitySettings now = ClientState.settingsFor(entityUuid);
                    if (now != null) {
                        for (String h : now.hiddenBones) {
                            Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SHOW_BONE, h));
                        }
                    }
                    for (String pb : loaded.partBones) {
                        Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SHOW_BONE, pb));
                    }
                }));
            }
        } else {
            items.add(new Item("check", "§8(无可用部件)", false, null));
        }
        items.add(new Item("gap", "", false, null));

        // ---- 行为绑定（官方玩家行为状态机 → NPC 对照表：可适配项实时高亮当前行为，不可适配项灰显原因） ----
        items.add(new Item("header", "§b行为绑定（官方状态机）", false, null));
        LivingEntity behEnt = findTrackedEntity();
        java.util.List<String> curBehaviors = behEnt != null
                ? ui.modelysm.client.Behaviors.detectAll(behEnt, 0f, false) : java.util.List.of();
        for (var be : ui.modelysm.client.Behaviors.TABLE) {
            boolean active = be.npcSupport && curBehaviors.contains(be.id);
            String label;
            if (!be.npcSupport) {
                label = "§8" + be.id + " " + be.cn + " §7✕";
            } else {
                label = (active ? "§a" : "§f") + be.id + " " + be.cn + (active ? " §a◀当前" : "");
            }
            items.add(new Item("check", label, active, null, null, be.note));
        }
        // overlay 层实时状态（官方 vehicle/hold/use/swing/passenger 内置控制器）
        if (behEnt != null) {
            String veh = ui.modelysm.client.Behaviors.detectVehicle(behEnt);
            if (veh != null) items.add(new Item("check", "§avehicle 骑乘(" + veh + ") §a◀当前", true, null, null,
                    "官方 vehicle 层：骑乘时 main 停止，按载具类型播 ride_pig/ride/boat/sit"));
            String sw = ui.modelysm.client.Behaviors.detectSwing(behEnt, loaded);
            if (sw != null) items.add(new Item("check", "§aswing 挥手(" + sw + ") ◀当前", true, null, null,
                    "挥动主/副手物品时叠加 swing_hand/swing_offhand（PLAY_ONCE）"));
            String hu = ui.modelysm.client.Behaviors.detectUse(behEnt, net.minecraft.world.InteractionHand.MAIN_HAND, loaded);
            if (hu != null) items.add(new Item("check", "§ause 使用(" + hu + ") ◀当前", true, null, null,
                    "正在使用物品（吃/喝/拉弓/格挡）时叠加 use_mainhand（LOOP）"));
            String hm = ui.modelysm.client.Behaviors.detectHold(behEnt, net.minecraft.world.InteractionHand.MAIN_HAND, loaded);
            if (hm != null) items.add(new Item("check", "§ahold 持物(" + hm + ") ◀当前", true, null, null,
                    "按手持物自动匹配 hold_mainhand:<id/#tag/分类/动作> 动画（LOOP）"));
        }
        items.add(new Item("gap", "", false, null));

        // ---- 漫游变量（官方"换装 / 穿脱"开关）----
        // 官方模型把戴帽/摘帽、披甲等功能挂成 v.roaming.xxx（每实体持久表，未赋值即 0）。
        // 若上面的配置分组已经内嵌过（穿脱兜帽那组），这里就不再重复。
        if (!roamingEmbedded && loaded != null && !loaded.roamingVars.isEmpty()) {
            items.add(new Item("header", "§b漫游变量（穿脱 / 换装）", false, null));
            addRoamingRows(items, loaded, null);
            items.add(new Item("action", "§e⟳ 清除漫游状态", false,
                    () -> AnimStates.get(entityUuid).roaming.clear()));
            items.add(new Item("gap", "", false, null));
        }

        // ---- 动画表情 ----
        // 官方 Z 键轮盘数据驱动（properties.extra_animation + 内嵌语言表本地化）；
        // 无官方数据的模型回退：非状态/非常驻层动画列表
        items.add(new Item("header", "§b动画 / 表情（轮盘）", false, null));
        items.add(new Item("check", "默认(自动)", curAnim == null,
                () -> Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.STOP_ANIM, ""))));
        if (loaded != null && !loaded.extraAnimations.isEmpty()) {
            // 子菜单分类（官方 #分类id）排在前面显示为小节头
            for (var ce : loaded.extraAnimations.entrySet()) {
                String animName = ce.getKey();
                String label = ce.getValue();
                if (label != null && label.startsWith("#")) {
                    String cid = label.substring(1);
                    Map<String, String> sub = loaded.extraAnimationClassifies.get(cid);
                    if (sub == null || sub.isEmpty()) {
                        // 没有子菜单数据：这条 "#xxx" 本身就是动作入口（Rossi 的 "#换装" 就是 extra0，
                        // 播放到 1.2s 执行 v.roaming 翻转 → 摘/戴帽子），直接做成可点条目
                        boolean curH = animName.equals(curAnim);
                        items.add(new Item("check", "§d" + cid, curH, () -> playAnim(animName), null,
                                "官方换装/穿脱动作：播放后执行模型内 timeline 脚本（v.roaming.* 翻转）"));
                        continue;
                    }
                    items.add(new Item("header", "§d" + cid, false, null));
                    for (var se : sub.entrySet()) {
                        boolean curS = se.getKey().equals(curAnim);
                        items.add(new Item("check", se.getValue(), curS, () -> playAnim(se.getKey())));
                    }
                    continue;
                }
                boolean cur = animName.equals(curAnim);
                items.add(new Item("check", label, cur, () -> playAnim(animName)));
            }
        } else if (loaded != null) {
            for (String n : loaded.emoteAnims()) {
                boolean cur = n.equals(curAnim);
                items.add(new Item("check", n, cur, () -> playAnim(n)));
            }
            items.add(new Item("header", "§8全部动画", false, null));
            List<String> names = new ArrayList<>(loaded.animations.keySet());
            names.sort(String::compareTo);
            for (String n : names) {
                boolean cur = n.equals(curAnim);
                items.add(new Item("check", n, cur, () -> playAnim(n)));
            }
        }
        items.add(new Item("gap", "", false, null));

        // ---- 骨骼显隐 ----
        items.add(new Item("header", "§b骨骼显隐", false, null));
        if (loaded != null) {
            List<String> bones = new ArrayList<>(loaded.model.bonesByName.keySet());
            bones.sort(String::compareTo);
            for (String n : bones) {
                boolean hid = hidden.contains(n);
                items.add(new Item("check", n, !hid, () -> {
                    // 读取点击时刻的真实状态，避免连点错位
                    EntitySettings now = ClientState.settingsFor(entityUuid);
                    boolean isHidden = now != null && now.hiddenBones.contains(n);
                    Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid,
                            isHidden ? EntityActionC2S.SHOW_BONE : EntityActionC2S.HIDE_BONE, n));
                }));
            }
        } else {
            items.add(new Item("check", "§8(未加载模型)", false, null));
        }
        return items;
    }

    /** 读取配置变量的当前值（未设置返回 0，与 Molang 未定义语义一致；变量名大小写不敏感） */
    private static float varVal(UUID uuid, String varName) {
        EntitySettings es = ClientState.settingsFor(uuid);
        Float v = es != null ? es.molVars.get(varName) : null;
        if (v == null && es != null && varName != null) {
            v = es.molVars.get(varName.toLowerCase(java.util.Locale.ROOT));
        }
        if (v == null && es != null && varName != null) {
            for (Map.Entry<String, Float> e : es.molVars.entrySet()) {
                if (e.getKey().equalsIgnoreCase(varName)) { v = e.getValue(); break; }
            }
        }
        return v != null ? v : 0f;
    }

    /** radio 选中项：逐条比对脚本里的赋值与当前变量值（脚本写 v.x=数字） */
    private static int radioSelected(EntitySettings es, ModelLibrary.LoadedModel.ConfigForm form) {
        if (es == null || form.labels == null || form.labels.isEmpty()) return -1;
        int idx = 0;
        for (Map.Entry<String, String> le : form.labels.entrySet()) {
            if (scriptMatches(es, le.getValue())) return idx;
            idx++;
        }
        return -1;
    }

    /** 判断 "v.a=1;v.b=0;" 这类常量赋值串是否与当前变量表一致（未赋值按 0） */
    private static boolean scriptMatches(EntitySettings es, String script) {
        if (script == null || script.isBlank()) return false;
        boolean any = false;
        for (String stmt : script.split(";")) {
            String t = stmt.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            if (eq + 1 < t.length() && "=<>!?".indexOf(t.charAt(eq + 1)) >= 0) continue;
            String k = t.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
            float want;
            try {
                want = Float.parseFloat(t.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                return false;
            }
            Float cur = es.molVars.get(k);
            float have = cur != null ? cur : 0f;
            if (Math.abs(have - want) > 1e-3f) return false;
            any = true;
        }
        return any;
    }

    private int itemHeight(Item it) {
        return switch (it.kind()) {
            case "header" -> HEADER;
            case "check", "action" -> CHECK;
            case "slider", "vslider" -> SLIDER_H;
            default -> GAP;
        };
    }

    /** 按 UUID 找到轮盘目标实体（行为绑定分区实时检测用；找不到返回 null） */
    private LivingEntity findTrackedEntity() {
        if (minecraft == null || minecraft.level == null) return null;
        for (net.minecraft.world.entity.Entity ent : minecraft.level.entitiesForRendering()) {
            if (ent.getUUID().equals(entityUuid) && ent instanceof LivingEntity le) return le;
        }
        return null;
    }

    private int panelContentHeight(List<Item> items) {
        int h = 0;
        for (Item it : items) h += itemHeight(it);
        return h;
    }

    private void renderPanel(GuiGraphics g) {
        int px = width - PANEL_W - 16;
        int py = 24;
        int ph = height - 48;
        // 官方风格：深灰面板 + 灰色描边
        g.fill(px, py, px + PANEL_W, py + ph, 0xD0101010);
        g.fill(px, py, px + PANEL_W, py + 1, 0xFF4A4A4A);
        g.fill(px, py + ph - 1, px + PANEL_W, py + ph, 0xFF4A4A4A);
        g.fill(px, py, px + 1, py + ph, 0xFF4A4A4A);
        g.fill(px + PANEL_W - 1, py, px + PANEL_W, py + ph, 0xFF4A4A4A);

        // 标题栏：路径 + 实体名
        g.drawString(font, "§f路径: §b模型设置", px + 10, py + 8, 0xFFFFFF);
        g.drawString(font, "§6" + entityName, px + 10, py + 22, 0xFFFFFF);
        // 返回按钮（官方灰色按钮样式）
        boolean backHover = mouseX >= px + PANEL_W - 58 && mouseX < px + PANEL_W - 8
                && mouseY >= py + 6 && mouseY < py + 26;
        g.fill(px + PANEL_W - 58, py + 6, px + PANEL_W - 8, py + 26, backHover ? 0xFF8A8A8A : 0xFF5A5A5A);
        g.drawCenteredString(font, "§f返回", px + PANEL_W - 33, py + 11, 0xFFFFFF);

        List<Item> items = buildPanelItems();
        int contentH = panelContentHeight(items);
        int listTop = py + 36;
        int listBottom = py + ph - 6;
        int viewH = listBottom - listTop;
        panelScrollMax = Math.max(0, contentH - viewH);
        if (panelScroll > panelScrollMax) panelScroll = panelScrollMax;
        if (panelScroll < 0) panelScroll = 0;

        g.enableScissor(px + 2, listTop, px + PANEL_W - 2, listBottom);
        int y = listTop - (int) panelScroll;
        int x0 = px + 8;
        int rowW = PANEL_W - 16;
        pendingTooltip = null;

        for (Item it : items) {
            int h = itemHeight(it);
            if (y + h > listTop && y < listBottom) {
                switch (it.kind()) {
                    case "header" -> {
                        g.fill(x0, y + HEADER - 4, x0 + rowW, y + HEADER - 2, 0xFF4A4A4A);
                        g.drawString(font, it.label(), x0 + 2, y + 6, 0xFFFFFF);
                    }
                    case "check" -> {
                        boolean hover = mouseX >= x0 && mouseX < x0 + rowW && mouseY >= y && mouseY < y + CHECK - 2;
                        g.fill(x0, y, x0 + rowW, y + CHECK - 2, hover ? 0x802AB0A8 : 0x60181818);
                        drawCheckbox(g, x0 + 8, y + 6, it.checked());
                        g.drawString(font, "§f" + it.label(), x0 + 24, y + 6, 0xFFFFFF);
                        if (hover && it.desc() != null && !it.desc().isBlank()) pendingTooltip = it.desc();
                    }
                    case "action" -> {
                        boolean hover = mouseX >= x0 && mouseX < x0 + rowW && mouseY >= y && mouseY < y + CHECK - 2;
                        g.fill(x0, y, x0 + rowW, y + CHECK - 2, hover ? 0xFF8A8A8A : 0xFF5A5A5A);
                        g.drawCenteredString(font, it.label(), x0 + rowW / 2, y + 6, 0xFFFFFF);
                    }
                    case "slider", "vslider" -> {
                        SliderRef ref = (SliderRef) it.tag();
                        float val;
                        String valText;
                        if (ref != null && ref.varName() != null) {
                            val = varVal(entityUuid, ref.varName());
                            valText = String.format("%.2f", val);
                        } else {
                            EntitySettings ses = ClientState.settingsFor(entityUuid);
                            val = ses != null ? ses.scale : 1f;
                            valText = String.format("x %.2f", val);
                        }
                        // 只有"正在拖的这一个滑条"跟随鼠标；其余滑条继续显示自己的真实值。
                        // 旧代码是 draggingSlider != null 就全员用 dragValue → 拖一个全列表跟着动。
                        boolean activeDrag = draggingSlider != null && sameSlider(draggingSlider, ref);
                        if (activeDrag) {
                            valText = ref != null && ref.varName() != null
                                    ? String.format("%.2f", dragValue)
                                    : String.format("x %.2f", dragValue);
                        }
                        float showVal = activeDrag ? dragValue : val;
                        g.drawString(font, "§f" + it.label() + ": §e" + valText,
                                x0 + 2, y + 2, 0xFFFFFF);
                        int trackY = y + 20;
                        g.fill(x0 + 8, trackY, x0 + rowW - 8, trackY + 6, 0xFF303030);
                        float min = ref != null ? ref.min() : SCALE_MIN;
                        float max = ref != null ? ref.max() : SCALE_MAX;
                        float t = Mth.clamp((showVal - min) / (max - min), 0f, 1f);
                        int knobX = (int) (x0 + 8 + t * (rowW - 24));
                        boolean hover = mouseX >= x0 && mouseX < x0 + rowW && mouseY >= y && mouseY < y + SLIDER_H;
                        g.fill(knobX - 3, trackY - 4, knobX + 11, trackY + 10,
                                activeDrag || hover ? 0xFF2AB0A8 : 0xFF7A7A7A);
                        if (hover && it.desc() != null && !it.desc().isBlank()) pendingTooltip = it.desc();
                    }
                    default -> { }
                }
            }
            y += h;
        }
        g.disableScissor();

        // 官方表单 description 提示（scissor 内收集，scissor 外绘制避免被裁剪）
        if (pendingTooltip != null && draggingSlider == null) {
            g.renderTooltip(font, Component.literal("§7" + pendingTooltip), (int) mouseX, (int) mouseY);
        }

        // 滚动条（可拖拽：轨道 + 滑块）
        sbTrackTop = listTop;
        sbTrackBottom = listBottom;
        sbBarH = 0;
        if (panelScrollMax > 0) {
            int trackH = listBottom - listTop;
            sbBarH = Math.max(20, viewH * viewH / (contentH + viewH));
            int barY = listTop + (int) ((trackH - sbBarH) * (panelScroll / panelScrollMax));
            g.fill(px + PANEL_W - 6, listTop, px + PANEL_W - 2, listBottom, 0x50202020);
            g.fill(px + PANEL_W - 6, barY, px + PANEL_W - 2, barY + sbBarH, sbDrag ? 0xFFD0D0D0 : 0x908A8A8A);
        }
    }

    private void drawCheckbox(GuiGraphics g, int x, int y, boolean checked) {
        g.fill(x, y, x + 10, y + 10, 0xFF101010);
        g.fill(x + 1, y + 1, x + 9, y + 9, checked ? 0xFF2E7DBE : 0xFF383838);
        if (checked) {
            g.fill(x + 3, y + 3, x + 7, y + 7, 0xFFBFD9FF);
        }
    }

    // ================= 交互 =================

    /** [DEBUG] 复制点击命中循环，返回包含 labelPart 的可点行中心坐标（找不到返回 null） */
    public double[] debugFindRow(String labelPart) {
        int px = width - PANEL_W - 16;
        int py = 24;
        int ph = height - 48;
        int listTop = py + 36;
        int listBottom = py + ph - 6;
        int y = listTop - (int) panelScroll;
        int x0 = px + 8;
        int rowW = PANEL_W - 16;
        for (Item it : buildPanelItems()) {
            int h = itemHeight(it);
            if (("check".equals(it.kind()) || "action".equals(it.kind())) && it.action() != null
                    && it.label().contains(labelPart)) {
                return new double[]{x0 + rowW / 2.0, y + (CHECK - 2) / 2.0};
            }
            y += h;
        }
        return null;
    }

    /** [DEBUG] 程序化打开设置面板并完成布局 */
    public void debugOpenPanel() {
        panelOpen = true;
        panelScroll = 0;
        init(minecraft, width, height);
    }

    /** [DEBUG] 滚动面板使含指定文字的分区标题置于列表顶部（诊断截图用） */
    public void debugScrollToHeader(String contains) {
        List<Item> items = buildPanelItems();
        int acc = 0;
        for (Item it : items) {
            if ("header".equals(it.kind()) && it.label() != null && it.label().contains(contains)) {
                panelScroll = acc;
                return;
            }
            acc += itemHeight(it);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (panelOpen) {
            int px = width - PANEL_W - 16;
            int py = 24;
            int ph = height - 48;
            // 面板内
            if (mx >= px && mx < px + PANEL_W && my >= py && my < py + ph) {
                // 返回
                if (mx >= px + PANEL_W - 58 && mx < px + PANEL_W - 8 && my >= py + 6 && my < py + 26) {
                    panelOpen = false;
                    return true;
                }
                // 滚动条按下：开始拖拽
                if (panelScrollMax > 0 && mx >= px + PANEL_W - 6 && mx < px + PANEL_W - 1
                        && my >= sbTrackTop && my < sbTrackBottom) {
                    sbDrag = true;
                    updateScrollDrag(my);
                    return true;
                }
                // 勾选/滑条行：只要行至少部分可见即可命中（行跨越列表底边时，点击行中心
                // 落在 listBottom 之外——旧守卫会静默吞掉点击，导致底部勾选行点不动）
                List<Item> items = buildPanelItems();
                int listTop = py + 36;
                int listBottom = py + ph - 6;
                int y = listTop - (int) panelScroll;
                int x0 = px + 8;
                int rowW = PANEL_W - 16;
                for (Item it : items) {
                    int h = itemHeight(it);
                    boolean rowVisible = y < listBottom && y + h > listTop;
                    if (("check".equals(it.kind()) || "action".equals(it.kind())) && it.action() != null
                            && rowVisible
                            && mx >= x0 && mx < x0 + rowW && my >= y && my < y + CHECK - 2) {
                        it.action().run();
                        return true;
                    }
                    // 滑条行按下 → 开始拖动（render 每帧轮询鼠标跟随；泛化支持 scale/配置变量滑条）
                    if (("slider".equals(it.kind()) || "vslider".equals(it.kind()))
                            && it.tag() instanceof SliderRef ref && rowVisible
                            && mx >= x0 && mx < x0 + rowW && my >= y && my < y + SLIDER_H) {
                        draggingSlider = ref;
                        lastSentValue = Float.NaN;
                        if (ref.varName() != null) {
                            dragValue = varVal(entityUuid, ref.varName());
                        } else {
                            EntitySettings ses = ClientState.settingsFor(entityUuid);
                            dragValue = ses != null ? ses.scale : 1f;
                        }
                        updateDragValue(mx);
                        sendValueIfChanged();
                        return true;
                    }
                    y += h;
                }
                return true;
            }
            // 面板外点扇区
            int hover = hoverSector();
            if (hover >= 0) {
                sectorClick(hover);
                return true;
            }
            return true;
        }

        // 中心盘：打开模型选择界面（同一个绑定入口，未绑定的实体也能从这里建绑定）
        if (isMouseInCenter()) {
            minecraft.setScreen(new ModelPickerScreen(this, entityUuid, entityName, boundModel()));
            return true;
        }

        int hover = hoverSector();
        if (hover >= 0) {
            sectorClick(hover);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void sectorClick(int hover) {
        EntitySettings es = ClientState.settingsFor(entityUuid);
        String bound = es != null ? es.model : null;
        boolean has = bound != null && !bound.isEmpty();
        switch (hover) {
            case 0 -> {            // 右上：解除绑定
                if (!has) {
                    if (minecraft != null && minecraft.player != null) {
                        minecraft.player.displayClientMessage(
                                Component.literal("§e该实体本来就没有绑定模型"), true);
                    }
                    return;
                }
                Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.UNBIND_MODEL, ""));
                onClose();
            }
            case 1 -> {            // 右：模型设置面板（未绑定先去选模型）
                if (!has) {
                    minecraft.setScreen(new ModelPickerScreen(this, entityUuid, entityName, ""));
                    return;
                }
                panelOpen = true;
                panelScroll = 0;
            }
            case 2 -> onClose();   // 右下：关闭（原形态切换位）
            case 3 -> {            // 左下：官方轮盘
                if (!has) {
                    if (minecraft != null && minecraft.player != null) {
                        minecraft.player.displayClientMessage(
                                Component.literal("§e先点【绑定模型】给这个实体选一个模型"), true);
                    }
                    return;
                }
                minecraft.setScreen(new OfficialRouletteScreen(this, entityUuid, entityName, bound, null, 0));
            }
            case 4 -> {            // 左：重置设置
                if (!has) return;
                Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.RESET, ""));
            }
            case 5 -> minecraft.setScreen(new ModelPickerScreen(this, entityUuid, entityName,   // 左上：绑定模型
                    has ? bound : ""));
        }
    }

    /** 通用滑条拖拽换算：按鼠标 x 映射到 [min,max]，按 step 量化 */
    private void updateDragValue(double mx) {
        SliderRef ref = draggingSlider;
        if (ref == null) return;
        int px = width - PANEL_W - 16;
        int x0 = px + 8;
        int rowW = PANEL_W - 16;
        float t = (float) ((mx - (x0 + 8)) / (rowW - 24));
        t = Mth.clamp(t, 0f, 1f);
        float raw = ref.min() + t * (ref.max() - ref.min());
        if (ref.step() > 0) {
            raw = Math.round(raw / ref.step()) * ref.step();
        }
        dragValue = raw;
    }

    /** 实时调控：拖拽值变化量达到阈值才发送（scale 走 SET_SCALE，配置变量走 SET_VAR） */
    private void sendValueIfChanged() {
        SliderRef ref = draggingSlider;
        if (ref == null) return;
        float threshold = ref.varName() != null ? Math.max(0.001f, ref.step() / 2f) : 0.05f;
        if (Float.isNaN(lastSentValue) || Math.abs(dragValue - lastSentValue) >= threshold) {
            lastSentValue = dragValue;
            if (ref.varName() != null) {
                Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_VAR,
                        ref.varName() + "=" + dragValue));
            } else {
                Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_SCALE,
                        String.format("%.2f", dragValue)));
            }
        }
    }

    /** 滚动条拖拽：按滑块中心位置换算滚动距离 */
    private void updateScrollDrag(double my) {
        int trackH = sbTrackBottom - sbTrackTop - sbBarH;
        double ratio = trackH > 0 ? (my - sbTrackTop - sbBarH / 2.0) / trackH : 0;
        panelScroll = Mth.clamp(ratio, 0.0, 1.0) * panelScrollMax;
    }

    @Override
    public void mouseMoved(double mx, double my) {
        // 滑条拖动跟随：mouseMoved 每次鼠标移动必然回调（不依赖 mouseDragged 链路，
        // 某些环境下 mouseDragged 会被 Forge 事件或其它模组拦截导致只能点击跳位不能拖动）。
        // 按住状态由 draggingSlider 标志（mouseClicked 置位 / mouseReleased 复位）判断。
        if (draggingSlider != null) {
            updateDragValue(mx);
            sendValueIfChanged();
        }
        super.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (draggingSlider != null) {
            updateDragValue(mx);
            sendValueIfChanged(); // 实时调控：拖动过程中即生效（mouseMoved 已兜底，此处为冗余保险）
            return true;
        }
        if (sbDrag) {
            updateScrollDrag(my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (draggingSlider != null) {
            draggingSlider = null;
            // 松手时补发最终值（与最后发送值不同才发）
            if (!Float.isNaN(lastSentValue) && Math.abs(dragValue - lastSentValue) >= 0.001f) {
                sendValueFinal();
            }
            return true;
        }
        if (sbDrag) {
            sbDrag = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    /** 松手补发最终值（绕过节流阈值） */
    private void sendValueFinal() {
        SliderRef ref = draggingSlider;
        lastSentValue = dragValue;
        if (ref != null && ref.varName() != null) {
            Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_VAR,
                    ref.varName() + "=" + dragValue));
        } else {
            Net.CHANNEL.sendToServer(new EntityActionC2S(entityUuid, EntityActionC2S.SET_SCALE,
                    String.format("%.2f", dragValue)));
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (panelOpen) {
            panelScroll -= delta * 30;
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (draggingSlider != null) {
                draggingSlider = null;
                return true;
            }
            if (panelOpen) {
                panelOpen = false;
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
