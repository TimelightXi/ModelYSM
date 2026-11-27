package ui.modelysm.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ui.modelysm.client.FpArmHook;

/**
 * 第一人称手臂替换：本地玩家绑定模型且模型带 armModel →
 * 用模型手臂替换原版 FirstPersonRenderer.renderArmFirstPerson 的原版手臂方块。
 */
@Mixin(ItemInHandRenderer.class)
public abstract class FirstPersonRendererMixin {

    @Inject(method = "renderPlayerArm", at = @At("HEAD"), cancellable = true)
    private void modelysm$fpArm(PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource buffers,
                                int packedLight, float attackAnim, float equipProgress, HumanoidArm side,
                                CallbackInfo ci) {
        try {
            if (FpArmHook.hook(poseStack, buffers, packedLight, attackAnim, equipProgress, side)) {
                ci.cancel();
            }
        } catch (Exception e) {
            // 失败退回原版手臂
        }
    }
}
