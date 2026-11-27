package ui.modelysm.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型专用 RenderType。
 *
 * 【必须是 cutout，不能是 translucent】——官方 YSM 的默认 RenderType 就是
 * {@code RenderType.entityCutoutNoCull(tex)}（OpenYSM IGeoRenderer.getRenderType:60），
 * 只有模型显式标了半透明才换成 entityTranslucent（:58）。
 *
 * 我们之前用 "translucent 透明混合 + 双面" 的自造类型，后果就是用户看到的
 * "头发有噪点、还闪频"：
 *  - translucent 会开 ALPHA 混合、不做 alpha 丢弃，所以 alpha≈0 的像素也会画进缓冲、
 *    也会写深度；头发是大量互相重叠/完全共面的薄片，同深度的两层谁先画不确定，
 *    混合结果逐帧抖动 = 闪频，边缘像素抖动 = 噪点。
 *  - cutout 的 shader 在片元阶段直接 discard(alpha < 0.1)，共面薄片只剩一层写深度，
 *    另一层被深度测试稳定拒掉，不再逐帧跳；颜色也不再被 alpha 混合洗淡。
 *
 * 双面（NO_CULL）保持不变：文件夹模型的贴图是"按面画好的岛"，背面剔除会漏块。
 */
public final class ModelYsmRenderTypes extends RenderStateShard {

    private static final Map<ResourceLocation, RenderType> CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, RenderType> TRANSLUCENT_CACHE = new ConcurrentHashMap<>();

    private ModelYsmRenderTypes() {
        super("modelysm_model_shard_holder", () -> {
        }, () -> {
        });
    }

    /** 官方默认：entityCutoutNoCull 等价（cutout + 双面 + 无 mipmap） */
    public static RenderType modelNoCull(ResourceLocation texture) {
        return CACHE.computeIfAbsent(texture, tex -> RenderType.create(
                "modelysm_model_cutout_nocull",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256, true, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                        .setTextureState(new TextureStateShard(tex, false, false))
                        .setTransparencyState(NO_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        .setWriteMaskState(COLOR_DEPTH_WRITE)
                        .createCompositeState(false)));
    }

    /** 模型显式声明半透明时才用（官方 CustomEntityTranslucentRenderType 同款参数） */
    public static RenderType modelTranslucentNoCull(ResourceLocation texture) {
        return TRANSLUCENT_CACHE.computeIfAbsent(texture, tex -> RenderType.create(
                "modelysm_model_translucent_nocull",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256, true, true,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER)
                        .setTextureState(new TextureStateShard(tex, false, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        .createCompositeState(false)));
    }
}
