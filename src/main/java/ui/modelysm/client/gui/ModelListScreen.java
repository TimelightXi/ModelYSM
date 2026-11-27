package ui.modelysm.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import ui.modelysm.client.ClientState;
import ui.modelysm.client.ModelLibrary;
import ui.modelysm.common.EntitySettings;
import ui.modelysm.network.Net;
import ui.modelysm.network.packets.EntityActionC2S;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * YSM 风格分页列表界面：顶部路径栏 + 页码 + 左右翻页 + 底部返回。
 * 用于动画列表 / 体型缩放 / 骨骼显隐 三类子菜单。
 */
public class ModelListScreen extends Screen {
    /** 一行：主文字 + 状态后缀 + 点击回调 */
    private record Row(String label, String status, Runnable onClick) {}

    private final Screen parent;
    private final UUID entityUuid;
    private final String entityName;
    private final String modelId;
    private final String pathTitle;
    private final List<Row> rows;

    private int page = 0;
    private static final int ROW_H = 22;
    private static final int ROW_W = 240, ROW_GAP = 4;

    /** 每页行数随窗口高度自适应（顶部路径栏 60px + 底部返回区 70px） */
    private int rowsPerPage() {
        return Math.max(3, (height - 130) / (ROW_H + ROW_GAP));
    }

    private Button backBtn, prevBtn, nextBtn;

    public static ModelListScreen animations(Screen parent, UUID entity, String entityName, String modelId) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("§f▶ 停止动画(恢复自动)", "§e[自动]", () ->
                Net.CHANNEL.sendToServer(new EntityActionC2S(entity, EntityActionC2S.STOP_ANIM, ""))));
        ModelLibrary.LoadedModel loaded = ModelLibrary.get(modelId);
        int emoteCount = 0, stateCount = 0;
        if (loaded != null) {
            EntitySettings es = ClientState.settingsFor(entity);
            String current = es != null ? es.anim : null;
            // 官方轮盘"表情/舞蹈"动画置顶（文件内顺序），其余状态动画按字母序
            for (String n : loaded.emoteAnims()) {
                boolean cur = n.equals(current);
                rows.add(new Row("§d" + n + " §8(表情)", cur ? "§e[播放中]" : "§d[播放]", () ->
                        Net.CHANNEL.sendToServer(new EntityActionC2S(entity, EntityActionC2S.PLAY_ANIM, n))));
                emoteCount++;
            }
            if (emoteCount > 0) {
                rows.add(new Row("§8──────── 状态动画 ────────", "", null));
            }
            List<String> rest = new ArrayList<>(loaded.animations.keySet());
            rest.removeAll(loaded.emoteAnims());
            rest.sort(String::compareTo);
            for (String n : rest) {
                boolean cur = n.equals(current);
                rows.add(new Row("§f" + n, cur ? "§e[播放中]" : "§7[无]", () ->
                        Net.CHANNEL.sendToServer(new EntityActionC2S(entity, EntityActionC2S.PLAY_ANIM, n))));
                stateCount++;
            }
        }
        String title = emoteCount > 0
                ? "动画列表 (表情" + emoteCount + " + 状态" + stateCount + ")"
                : "动画列表 (" + stateCount + " 个)";
        return new ModelListScreen(parent, entity, entityName, modelId, title, rows);
    }

    public static ModelListScreen scales(Screen parent, UUID entity, String entityName, String modelId) {
        float[] presets = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        EntitySettings es = ClientState.settingsFor(entity);
        float cur = es != null ? es.scale : 1.0f;
        List<Row> rows = new ArrayList<>();
        for (float f : presets) {
            boolean sel = Math.abs(f - cur) < 0.01f;
            float ff = f;
            rows.add(new Row("§f体型 x" + f, sel ? "§e[当前]" : "§7[设为]", () ->
                    Net.CHANNEL.sendToServer(new EntityActionC2S(entity, EntityActionC2S.SET_SCALE,
                            String.format("%.2f", ff)))));
        }
        return new ModelListScreen(parent, entity, entityName, modelId, "体型缩放", rows);
    }

    public static ModelListScreen bones(Screen parent, UUID entity, String entityName, String modelId) {
        List<Row> rows = new ArrayList<>();
        ModelLibrary.LoadedModel loaded = ModelLibrary.get(modelId);
        if (loaded != null) {
            EntitySettings es = ClientState.settingsFor(entity);
            java.util.Set<String> hidden = es != null ? es.hiddenBones : java.util.Set.of();
            List<String> names = new ArrayList<>(loaded.model.bonesByName.keySet());
            names.sort(String::compareTo);
            for (String n : names) {
                boolean hid = hidden.contains(n);
                rows.add(new Row("§f" + n, hid ? "§c[隐藏]" : "§a[显示]", () ->
                        Net.CHANNEL.sendToServer(new EntityActionC2S(entity,
                                hid ? EntityActionC2S.SHOW_BONE : EntityActionC2S.HIDE_BONE, n))));
            }
        }
        return new ModelListScreen(parent, entity, entityName, modelId, "骨骼显隐 (" + rows.size() + " 根)", rows);
    }

    /** 模型选择：列出全部已加载模型，点击即切换该实体绑定的模型 */
    public static ModelListScreen models(Screen parent, UUID entity, String entityName, String currentModel) {
        List<Row> rows = new ArrayList<>();
        List<String> ids = ModelLibrary.listModels();
        ids.sort(String::compareTo);
        for (String id : ids) {
            boolean cur = id.equals(currentModel);
            String idCopy = id;
            rows.add(new Row("§f" + id, cur ? "§e[当前]" : "§7[切换]", () ->
                    Net.CHANNEL.sendToServer(new EntityActionC2S(entity, EntityActionC2S.BIND_MODEL, idCopy))));
        }
        return new ModelListScreen(parent, entity, entityName, currentModel,
                "模型选择 (" + rows.size() + " 个)", rows);
    }

    private ModelListScreen(Screen parent, UUID entity, String entityName, String modelId,
                            String pathTitle, List<Row> rows) {
        super(Component.literal("ModelYSM 设置"));
        this.parent = parent;
        this.entityUuid = entity;
        this.entityName = entityName;
        this.modelId = modelId;
        this.pathTitle = pathTitle;
        this.rows = rows;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        prevBtn = Button.builder(Component.literal("<"), b -> {
                    int pages = pageCount();
                    page = (page + pages - 1) % pages;
                })
                .bounds(cx - 110, 40, 30, 20)
                .build();
        nextBtn = Button.builder(Component.literal(">"), b -> {
                    page = (page + 1) % pageCount();
                })
                .bounds(cx + 80, 40, 30, 20)
                .build();
        backBtn = Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(cx - 60, height - 32, 120, 20)
                .build();
        addRenderableWidget(prevBtn);
        addRenderableWidget(nextBtn);
        addRenderableWidget(backBtn);
    }

    private int pageCount() {
        return Math.max(1, (rows.size() + rowsPerPage() - 1) / rowsPerPage());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int cx = width / 2;
        int per = rowsPerPage();
        if (page >= pageCount()) page = pageCount() - 1;
        // 顶部路径栏（YSM 风格）
        g.fill(cx - 110, 8, cx + 110, 30, 0xAA101010);
        g.drawCenteredString(font, "路径: " + pathTitle, cx, 14, 0xFFFFFF);
        g.drawCenteredString(font, "§b" + entityName, cx, height - 54, 0xFFFFFF);

        // 行
        int start = page * per;
        for (int i = 0; i < per && start + i < rows.size(); i++) {
            Row row = rows.get(start + i);
            int y = 64 + i * (ROW_H + ROW_GAP);
            int x0 = cx - ROW_W / 2;
            boolean hover = mouseX >= x0 && mouseX < x0 + ROW_W && mouseY >= y && mouseY < y + ROW_H;
            g.fill(x0, y, x0 + ROW_W, y + ROW_H, hover ? 0x902AB0A8 : 0x90181818);
            g.fill(x0, y, x0 + 2, y + ROW_H, hover ? 0xFF7FFCF4 : 0xFF5A5A5A);
            g.drawString(font, row.label, x0 + 10, y + 7, 0xFFFFFF);
            g.drawString(font, row.status, x0 + ROW_W - font.width(row.status) - 10, y + 7, 0xFFFFFF);
        }

        g.drawCenteredString(font, (page + 1) + "/" + pageCount(), cx, 40, 0x7FFCF4);
        // 简易滚动指示条
        if (rows.size() > per) {
            int trackTop = 64, trackBottom = 64 + per * (ROW_H + ROW_GAP) - ROW_GAP;
            int barH = Math.max(16, (trackBottom - trackTop) * per / rows.size());
            int barY = trackTop + (int) ((trackBottom - trackTop - barH) * ((double) page / Math.max(1, pageCount() - 1)));
            g.fill(cx + ROW_W / 2 + 5, trackTop, cx + ROW_W / 2 + 8, trackBottom, 0x50202020);
            g.fill(cx + ROW_W / 2 + 5, barY, cx + ROW_W / 2 + 8, barY + barH, 0x908A8A8A);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta != 0) {
            page = Mth.clamp(page + (delta > 0 ? -1 : 1), 0, pageCount() - 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cx = width / 2;
        int per = rowsPerPage();
        int start = page * per;
        for (int i = 0; i < per && start + i < rows.size(); i++) {
            int y = 64 + i * (ROW_H + ROW_GAP);
            int x0 = cx - ROW_W / 2;
            if (mouseX >= x0 && mouseX < x0 + ROW_W && mouseY >= y && mouseY < y + ROW_H) {
                Runnable action = rows.get(start + i).onClick();
                if (action != null) action.run();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
