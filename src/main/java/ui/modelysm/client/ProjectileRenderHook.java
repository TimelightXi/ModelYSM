package ui.modelysm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import ui.modelysm.client.render.BedrockAnimation;
import ui.modelysm.client.render.BedrockModel;
import ui.modelysm.client.render.NpcRenderer;

/**
 * 弹射物/载具替换渲染钩子（官方 RawSubEntity vehicles/projectiles 同思路）：
 * 箭矢等弹射物的属主是已绑定模型的实体 → 用模型自带的子实体几何替换原版渲染。
 * identifier 匹配 "minecraft:arrow"（spectrum 归属：AbstractArrow 及其子类）。
 */
public final class ProjectileRenderHook {
    private ProjectileRenderHook() {}

    public static boolean hook(Entity entity, double x, double y, double z, float entityYaw, float partialTicks,
                               PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        // 只处理箭类（原版+模组自定义箭）；属主必须已绑定模型
        if (!(entity instanceof AbstractArrow arrow)) return false;
        Entity owner = arrow.getOwner();
        if (owner == null) return false;
        String modelId = ClientState.bindingFor(owner.getUUID());
        if (modelId == null) return false;
        var loaded = ModelLibrary.get(modelId);
        if (loaded == null) return false;
        var sub = loaded.subModels.get("minecraft:arrow");
        if (sub == null || sub.texture == null) return false;

        // 动画（模型自带 arrow 动画；无则静态）
        // 弹射物用自身年龄推进动画时间
        var state = AnimStates.get(entity.getUUID());
        state.animTime += state.lastDt;
        BedrockAnimation anim = sub.animations.isEmpty() ? null
                : sub.animations.values().iterator().next();

        poseStack.translate(x, y, z);
        // 原版箭矢：插值旋转（y=平面偏航，pitch 由 MovementResolver 打包进 entityYaw 的高位）
        float yaw = Mth.lerp(partialTicks, arrow.yRotO, arrow.getYRot()) - 90f;
        float pitch = Mth.lerp(partialTicks, arrow.xRotO, arrow.getXRot());
        poseStack.pushPose();
        try {
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yaw));
            poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(pitch));
            // 用子模型渲染（模型自带 arrow 动画；无则静态几何）
            renderSubModel(sub, state.animTime, partialTicks, poseStack, buffers, packedLight);
        } finally {
            poseStack.popPose();
        }
        return true;
    }

    /** 子模型渲染（骨骼遍历复用 NpcRenderer 的绘制语义） */
    private static void renderSubModel(ui.modelysm.client.ModelLibrary.LoadedModel.SubModel sub,
                                       float time, float partialTicks,
                                       PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        // Molang 上下文（静态兜底）
        ui.modelysm.client.molang.Molang.Ctx ctx = new ui.modelysm.client.molang.Molang.Ctx();
        ctx.animTime = time;
        var sink = new java.util.function.BiConsumer<String, BedrockAnimation.Sample>() {
            @Override
            public void accept(String boneName, BedrockAnimation.Sample sample) {
                BedrockModel.Bone bone = sub.model.bonesByName.get(boneName);
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
        if (sub.animations.size() > 1) {
            BedrockAnimation a = sub.animations.get("fly");
            if (a == null) a = sub.animations.values().iterator().next();
            a.applyTo(time, ctx, sink);
        } else if (sub.animations.size() == 1) {
            sub.animations.values().iterator().next().applyTo(time, ctx, sink);
        }
        // 根骨骼渲染（1:1 对齐 NpcRenderer 的骨骼递归，Y-up 无翻转）
        com.mojang.blaze3d.vertex.VertexConsumer vc = buffers.getBuffer(
                ui.modelysm.client.render.ModelYsmRenderTypes.modelNoCull(sub.texture));
        // 根偏移：抵消第一根骨骼 pivot（模型居中于原点）
        float ox = 0, oy = 0, oz = 0;
        if (!sub.model.rootBones.isEmpty()) {
            var r = sub.model.rootBones.get(0);
            ox = -r.mcPivot.x();
            oy = -r.mcPivot.y();
            oz = -r.mcPivot.z();
        }
        for (BedrockModel.Bone root : sub.model.rootBones) {
            renderBoneSimple(root, poseStack, vc, packedLight, ox, oy, oz, sub.model);
        }
    }

    /** 子模型骨骼递归（与 NpcRenderer.renderBone 相同语义的精简版） */
    private static void renderBoneSimple(BedrockModel.Bone bone, PoseStack poseStack, com.mojang.blaze3d.vertex.VertexConsumer vc,
                                         int packedLight, float rootOffX, float rootOffY, float rootOffZ,
                                         BedrockModel model) {
        poseStack.pushPose();
        try {
            if (bone.parent == null) {
                poseStack.translate(bone.mcPivot.x() + rootOffX + bone.animPos[0],
                        bone.mcPivot.y() + rootOffY + bone.animPos[1],
                        bone.mcPivot.z() + rootOffZ + bone.animPos[2]);
            } else {
                poseStack.translate(bone.mcPivot.x() - bone.parent.mcPivot.x() + bone.animPos[0],
                        bone.mcPivot.y() - bone.parent.mcPivot.y() + bone.animPos[1],
                        bone.mcPivot.z() - bone.parent.mcPivot.z() + bone.animPos[2]);
            }
            float rx = bone.restRotRad.x() + bone.animRot[0];
            float ry = bone.restRotRad.y() + bone.animRot[1];
            float rz = bone.restRotRad.z() + bone.animRot[2];
            if (rz != 0) poseStack.mulPose(com.mojang.math.Axis.ZP.rotation(rz));
            if (ry != 0) poseStack.mulPose(com.mojang.math.Axis.YP.rotation(ry));
            if (rx != 0) poseStack.mulPose(com.mojang.math.Axis.XP.rotation(rx));
            if (bone.animScale[0] != 1.0f || bone.animScale[1] != 1.0f || bone.animScale[2] != 1.0f) {
                poseStack.scale(bone.animScale[0], bone.animScale[1], bone.animScale[2]);
            }
            for (BedrockModel.Cube cube : bone.cubes) {
                NpcRenderer.drawCubePublic(cube, poseStack, vc, packedLight, model);
            }
            for (BedrockModel.Bone child : bone.children) {
                renderBoneSimple(child, poseStack, vc, packedLight, 0f, 0f, 0f, model);
            }
        } finally {
            poseStack.popPose();
        }
    }
}
