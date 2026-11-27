package ui.modelysm.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 官方 OpenYSM {@code FlatColorButton} 的同款平色按钮（逐行对照）：
 * 底色 -12369342 = 0xFF434242；悬停/聚焦时四边各 1px -790560 = 0xFFF3EFE0；
 * 文字走 renderScrollingString(..., 2, 15986656) 即 0xFFF3EFE0 居中、左右各留 2px。
 * 用它的原因：原版 Button 是带高光/斜切边框的三层灰渐变，官方整块是一层平灰，
 * 放在 #222222 面板上"下面那一排按钮"会明显比卡片亮一档、还多一圈立体边。
 */
public class FlatButton extends Button {
    private static final int BG = 0xFF434242;
    private static final int HOVER_BORDER = 0xFFF3EFE0;
    private static final int TEXT = 15986656;

    public FlatButton(int x, int y, int w, int h, Component text, OnPress onPress) {
        super(x, y, w, h, text, onPress, DEFAULT_NARRATION);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(getX(), getY(), getX() + this.width, getY() + this.height, BG, BG);
        if (isHoveredOrFocused()) {
            g.fillGradient(getX(), getY() + 1, getX() + 1, getY() + this.height - 1, HOVER_BORDER, HOVER_BORDER);
            g.fillGradient(getX(), getY(), getX() + this.width, getY() + 1, HOVER_BORDER, HOVER_BORDER);
            g.fillGradient(getX() + this.width - 1, getY() + 1, getX() + this.width, getY() + this.height - 1, HOVER_BORDER, HOVER_BORDER);
            g.fillGradient(getX(), getY() + this.height - 1, getX() + this.width, getY() + this.height, HOVER_BORDER, HOVER_BORDER);
        }
        Font font = Minecraft.getInstance().font;
        String text = getMessage().getString();
        // 官方 renderScrollingString：单行居中，左右留 2px；太长就按宽度截断加省略号
        int maxW = this.width - 4;
        if (font.width(text) > maxW) {
            text = font.plainSubstrByWidth(text, Math.max(1, maxW - font.width("…"))) + "…";
        }
        int tx = getX() + (this.width - font.width(text)) / 2;
        int ty = getY() + Mth.clamp((this.height - 8) / 2, 1, Math.max(1, this.height - 9));
        g.drawString(font, text, tx, ty, TEXT, false);
    }
}
