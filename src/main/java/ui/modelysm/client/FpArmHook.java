package ui.modelysm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import ui.modelysm.client.render.BedrockAnimation;
import ui.modelysm.client.render.BedrockModel;
import ui.modelysm.client.render.NpcRenderer;

/**
 * 第一人称手臂（官方 CustomFirstPersonArmRenderer 同思路）：
 * 本地玩家绑定模型且模型带 armModel → 用模型手臂替换原版第一人称手。
 * 变换复刻原版 FirstPersonRenderer.renderArmFirstPerson 的手部空间（挥动/装备进度），
 * 官方对照：translate(±0.25, 1.8, 0) + scale(-1,-1,1) 后渲染 armModel。
 * 动画选组："hold_mainhand:<分类>"（空手=empty / sword / bow / …），无匹配回退 hold_mainhand:empty。
 */
public final class FpArmHook {
    private FpArmHook() {}

    public static boolean hook(PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource buffers,
                               int packedLight, float attackAnim, float equipProgress, HumanoidArm side) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return false;
        String modelId = ClientState.bindingFor(player.getUUID());
        if (modelId == null) return false;
        var loaded = ModelLibrary.get(modelId);
        if (loaded == null || loaded.armModel == null || loaded.defaultTexture() == null) return false;

        boolean right = side == HumanoidArm.RIGHT;
        float sx = right ? 1f : -1f;
        // 原版 renderPlayerArm 变换逐行对应（挥动/装备进度一致）
        float f1 = -2.0F * attackAnim + 3.0F;
        float f2 = attackAnim * f1 - Mth.sin(Mth.sqrt(attackAnim) * (float) Math.PI) * 2.0F;
        float f3 = Mth.sin(Mth.sqrt(attackAnim) * (float) Math.PI) * 3.2F;
        float f4 = Mth.sin(Mth.sqrt(attackAnim) * (float) Math.PI) * -0.2F;
        float f5 = Mth.sin(Mth.sqrt(attackAnim) * (float) Math.PI) * 0.4F;
        float f6 = f4 * -5.0F;
        float f7 = f5 * -10.0F;

        poseStack.pushPose();
        try {
            poseStack.mulPose(Axis.XP.rotationDegrees(f2 * -45.0F));
            poseStack.mulPose(Axis.YP.rotationDegrees(f3 * -20.0F));
            poseStack.translate(sx * (f6 + 5.6F), 0.84F + f7 + equipProgress * -0.6F, -0.72F);
            poseStack.mulPose(Axis.YP.rotationDegrees(sx * 75.0F));
            poseStack.mulPose(Axis.ZP.rotationDegrees(sx * -45.0F));
            poseStack.translate(sx * -0.6414F, 0.78F, -0.5F);
            poseStack.mulPose(Axis.YP.rotationDegrees(-75.0F));
            poseStack.mulPose(Axis.YP.rotationDegrees(45.0F));
            // 官方手臂模型空间：scale(-1,-1,1) + 位置修正（CustomFirstPersonArmRenderer 同款）
            poseStack.translate(right ? -0.25 : 0.25, 1.8, 0);
            poseStack.scale(-1, -1, 1);

            // 动画：hold_<hand>:<分类>，回退 empty
            ItemStack held = right ? player.getMainHandItem() : player.getOffhandItem();
            String cls = held.isEmpty() ? "empty" : ui.modelysm.client.anim.ModelControllers.classify(held);
            String animName = (right ? "hold_mainhand:" : "hold_offhand:")
                    + (cls.isEmpty() ? "empty" : cls);
            BedrockAnimation anim = loaded.armAnimations.get(animName);
            if (anim == null) anim = loaded.armAnimations.get(right ? "hold_mainhand:empty" : "hold_offhand:empty");

            BedrockModel model = loaded.armModel;
            model.resetPose();
            var ctx = new ui.modelysm.client.molang.Molang.Ctx();
            var sink = new java.util.function.BiConsumer<String, BedrockAnimation.Sample>() {
                @Override
                public void accept(String boneName, BedrockAnimation.Sample sample) {
                    BedrockModel.Bone bone = model.bonesByName.get(boneName);
                    if (bone == null) return;
                    if (sample.hasRot) {
                        bone.animRot[0] = -sample.rot[0] * Mth.DEG_TO_RAD;
                        bone.animRot[1] = -sample.rot[1] * Mth.DEG_TO_RAD;
                        bone.animRot[2] = sample.rot[2] * Mth.DEG_TO_RAD;
                    }
                    if (sample.hasPos) {
                        bone.animPos[0] = -sample.pos[0] / 16f;
                        bone.animPos[1] = sample.pos[1] / 16f;
                        bone.animPos[2] = sample.pos[2] / 16f;
                    }
                    if (sample.hasScale) {
                        for (int i = 0; i < 3; i++) bone.animScale[i] = sample.scale3[i];
                    }
                }
            };
            if (anim != null) {
                float t = player.tickCount % Math.max(0.001f, anim.length > 0 ? anim.length : 1f);
                anim.applyTo(t, ctx, sink);
            }
            // 只渲染对应侧手臂（骨骼名含 left/right 匹配；无侧标记则全部渲染）
            com.mojang.blaze3d.vertex.VertexConsumer vc = buffers.getBuffer(
                    ui.modelysm.client.render.ModelYsmRenderTypes.modelNoCull(loaded.defaultTexture()));
            String want = right ? "right" : "left";
            for (BedrockModel.Bone root : model.rootBones) {
                String ln = root.name.toLowerCase();
                boolean sideMatched = ln.contains("left") || ln.contains("right");
                if (sideMatched && !ln.contains(want)) continue;
                renderBone(root, poseStack, vc, packedLight, model);
            }
        } finally {
            poseStack.popPose();
        }
        return true;
    }

    private static void renderBone(BedrockModel.Bone bone, PoseStack poseStack,
                                   com.mojang.blaze3d.vertex.VertexConsumer vc, int packedLight,
                                   BedrockModel model) {
        poseStack.pushPose();
        try {
            if (bone.parent == null) {
                poseStack.translate(bone.mcPivot.x() + bone.animPos[0],
                        bone.mcPivot.y() + bone.animPos[1], bone.mcPivot.z() + bone.animPos[2]);
            } else {
                poseStack.translate(bone.mcPivot.x() - bone.parent.mcPivot.x() + bone.animPos[0],
                        bone.mcPivot.y() - bone.parent.mcPivot.y() + bone.animPos[1],
                        bone.mcPivot.z() - bone.parent.mcPivot.z() + bone.animPos[2]);
            }
            float rx = bone.restRotRad.x() + bone.animRot[0];
            float ry = bone.restRotRad.y() + bone.animRot[1];
            float rz = bone.restRotRad.z() + bone.animRot[2];
            if (rz != 0) poseStack.mulPose(Axis.ZP.rotation(rz));
            if (ry != 0) poseStack.mulPose(Axis.YP.rotation(ry));
            if (rx != 0) poseStack.mulPose(Axis.XP.rotation(rx));
            if (bone.animScale[0] != 1.0f || bone.animScale[1] != 1.0f || bone.animScale[2] != 1.0f) {
                poseStack.scale(bone.animScale[0], bone.animScale[1], bone.animScale[2]);
            }
            for (BedrockModel.Cube cube : bone.cubes) {
                NpcRenderer.drawCubePublic(cube, poseStack, vc, packedLight, model);
            }
            for (BedrockModel.Bone child : bone.children) {
                renderBone(child, poseStack, vc, packedLight, model);
            }
        } finally {
            poseStack.popPose();
        }
    }
}
