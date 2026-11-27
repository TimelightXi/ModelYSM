package ui.modelysm.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderStateShard;

/**
 * 自定义 GUI 图元 RenderType：与 vanilla RenderType.gui() 等价但**关闭背面剔除**。
 * gui() 默认 CULL——多边形顶点绕向为背面（CW）的图元会被整体剔除，
 * 这是轮盘中心圆盘/扇区底色"画了却看不见"的根因（缝隙线绕向恰好正确才一直可见）。
 * 本类通过继承 RenderStateShard 获取其 protected shard 字段。
 */
public final class ModelGuiRenderType extends RenderStateShard {
    /** no-cull 版 GUI 图元类型（QUADS + POSITION_COLOR + 半透明 + LEQUAL 深度） */
    public static final RenderType GUI_NO_CULL = RenderType.create(
            "modelysm_gui_no_cull",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_GUI_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .createCompositeState(false));

    private ModelGuiRenderType() {
        super("modelysm_shard_holder", () -> {}, () -> {});
    }
}
