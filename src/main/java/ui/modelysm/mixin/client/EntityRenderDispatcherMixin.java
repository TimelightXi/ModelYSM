package ui.modelysm.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ui.modelysm.ModelYSM;
import ui.modelysm.client.ClientState;
import ui.modelysm.client.NpcRenderHook;

/**
 * 在 EntityRenderDispatcher.render 最前端拦截被绑定实体（NPC/怪物/动物/玩家均可）。
 * 命中绑定 → 自行渲染 YSM 模型并取消原版渲染；未命中 → 原逻辑不受影响。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void modelysm$beforeRenderEntity(Entity entity, double x, double y, double z, float entityYaw, float partialTicks,
                                             PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                             CallbackInfo ci) {
        // 矩阵栈必须由我们自己封闭：vanilla 的 render() 内部是
        //   pushPose() → translate(x,y,z) → renderer.render() → … → popPose()
        // 我们在 HEAD 就 cancel，原生那份 push/pop 全被跳过；而两个 hook 里都自己做
        // poseStack.translate(x,y,z)（相机相对坐标）。若不补 push/pop，这条 translate 会永久留在栈上，
        // 之后每个实体都在"上一个实体的位置"基础上再叠加 —— 模型随渲染顺序漂移、越走越偏，
        // 实机表现就是"有的模型浮在天上、有的陷进地下、低头只剩 ID"。
        boolean drawn = false;
        poseStack.pushPose();
        try {
            // 弹射物/载具替换渲染：属主已绑定模型且模型自带对应子实体几何 → 替换原版渲染
            if (ui.modelysm.client.ProjectileRenderHook.hook(entity, x, y, z, entityYaw, partialTicks,
                    poseStack, buffer, packedLight)) {
                drawn = true;
            } else if (entity instanceof net.minecraft.world.entity.LivingEntity living
                    && NpcRenderHook.hook(living, x, y, z, entityYaw, partialTicks, poseStack, buffer, packedLight)) {
                drawn = true;
            }
        } catch (Exception e) {
            // 渲染异常不崩游戏，退回原版渲染
            ModelYSM.LOGGER.warn("[ModelYSM] npc render failed: {}", e.toString());
        } finally {
            poseStack.popPose();
        }
        if (drawn) ci.cancel();
    }

    /**
     * 视锥剔除：官方 YSM 从不关剔除，它只是给 YSM 大模型一个够大的剔除盒。
     * 我们这里原来是无条件 {@code true} —— 这会把"相机背后 / 画面外"的实体也提交进渲染，
     * 近平面裁剪会把它画成一大块翻过来的畸形模型，实机表现正是
     * "随视角往上模型移出来、往下就消失、像沉到地下"。
     * 现在改成：只有"我们真的会画这个实体"时才介入，并且用按模型尺寸放大的 AABB 做标准视锥判定；
     * 模型没下载到/没贴图时一律交回原版（否则会连官方 YSM 的模型一起强行提交）。
     */
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void modelysm$frustumForBound(Entity entity, net.minecraft.client.renderer.culling.Frustum frustum,
                                          double x, double y, double z,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)) return;
        Boolean visible = NpcRenderHook.frustumDecision(living, frustum);
        if (visible != null) cir.setReturnValue(visible);
    }
}
