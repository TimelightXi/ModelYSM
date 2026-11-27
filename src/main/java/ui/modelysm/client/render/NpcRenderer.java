package ui.modelysm.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import ui.modelysm.ModelYSM;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import ui.modelysm.client.AnimStates;
import ui.modelysm.client.ModelLibrary.LoadedModel;
import ui.modelysm.client.molang.Molang;

import java.util.Map;

/**
 * NPC 渲染器：复刻 vanilla LivingEntityRenderer 的外层变换
 * （Y-up 约定，无需 vanilla 的 -1/-1 翻转），再按骨骼层级绘制 Bedrock 立方体。
 * 立方体顶点顺序与 UV 分配 1:1 对齐 vanilla ModelPart.Cube 构造。
 */
public final class NpcRenderer {
    private NpcRenderer() {}

    /** YSM 常驻动画层：pre_parallel 系列（官方控制器顺序在状态动画【前】应用） */
    private static final String[] PRE_LAYERS = {
            "pre_parallel0", "pre_parallel1", "pre_parallel2", "pre_parallel3",
            "pre_parallel4", "pre_parallel5", "pre_parallel6", "pre_parallel7"
    };
    /** parallel 系列（官方控制器顺序在状态动画【后】应用 → 后 apply 的通道覆盖状态动画） */
    private static final String[] POST_LAYERS = {
            "parallel0", "parallel1", "parallel2", "parallel3",
            "parallel4", "parallel5", "parallel6", "parallel7"
    };

    // 【诊断开关】渲染变量隔离实验用（生产版全默认）
    public static boolean DEBUG_ZERO_REST_ROT = false;   // true=骨骼绑定旋转清零
    public static boolean DEBUG_NO_CUBE_ROT = false;     // true=立方体自身旋转禁用
    public static boolean DEBUG_ROOT_OFF_ZERO = false;   // true=根偏移清零
    public static boolean DEBUG_SKIP_BG_ROOTS = false;   // true=跳过 background/molang 根骨骼
    public static ResourceLocation DEBUG_TEXTURE_OVERRIDE = null; // 强制贴图（几何/UV 隔离用）
    public static boolean DEBUG_LOG_EXTREME = false;     // true=打印姿态异常骨骼

    /**
     * 卡片预览专用：gui 背景层（骨架名含 background）的等比缩放系数，0 = 不处理。
     * 官方实测（856x512、guiScale=2）：酒狐框 = 43x70 GUI，卡片 3D 视口 = 52x70，
     * 而我们按 30px/格 画是 86x97 GUI —— 正好是官方的 2 倍，即官方把这一层按 0.7（含 0.9375）缩放。
     * 人物仍是 30px/格（官方同一张卡里头部宽度也对得上），所以背景层必须单独缩放。
     */
    public static float PREVIEW_BG_SCALE = 0f;
    /** 背景层中心在锚点上方的位移（方块单位；锚点 = y + h/2 + 20） */
    public static float PREVIEW_BG_CENTER_Y = 0f;

    // ===================== 第二档：对齐官方纯 Java 兜底路径的扁平渲染 =====================
    // 官方 OpenYSM 在不用 native/shader 时走的就是这条（NativeModelRenderer:130-174 +
    // calculateBoneMatrix:177-249）：骨骼矩阵按父链一次算好存表，遍历期不递归 PoseStack、
    // 不为每个 cube 重算一份投影矩阵、顶点变换与提交在同一个 for 里完成、全程无临时对象。
    //
    // 这里只借它的【算法结构】，不搬官方的类/字段/partMask/glow 语义，所有行为逐条对齐我们
    // 原来的递归路径：rest+anim 旋转、Z→Y→X 顺序、DebugUv 调试档、animScale 缩放（=0 即隐藏）、
    // ysmGlow / bone_glow 光照覆盖、预览期不做行列式剔除、薄片只留 -Z 面、背景层等比缩放、
    // locator 捕获、previewMirrorInModelView 的镜像口径。
    // 只对"全部 cube 都是 .ysm 烘焙面片"的模型生效（高面数模型正是这种）；JSON 几何模型
    // （box UV / 按面 UV / cube 自旋转）继续走原递归路径，零行为变化。
    public static boolean USE_FLAT_BAKED_PATH = true;

    /** 预览 GPU 总开关：卡片/大预览走【离屏 FBO】渲染（PreviewFboRenderer），false 则退回 CPU 扁平路径 */
    public static boolean USE_GPU_PREVIEW = true;

    // ===================== 渲染耗时诊断（[PERF] 日志，每 5 秒一行） =====================
    /** 每渲染一只模型的累计（纳秒 / 次数 / 面数），用来判断 10fps 到底卡在我们 CPU 还是 GPU 管线 */
    private static long PERF_NANOS;
    private static int PERF_CALLS;
    private static long PERF_FLAT_FACES;
    private static long PERF_REC_FACES;
    private static int PERF_FLAT_CALLS;
    private static int PERF_REC_CALLS;
    private static long PERF_LAST_REPORT;
    // 分路径计数：分别看"世界/预览"在 GPU 上跑了几次、回 CPU 几次、GPU 各花多少时间
    private static int PERF_GPUP_OK, PERF_GPUP_CPU, PERF_GPUW_OK, PERF_GPUW_CPU;
    private static long PERF_GPUP_NANOS, PERF_GPUW_NANOS;

    private static void perfReport(long t0, boolean flat) {
        ui.modelysm.client.PerfProbe.tick();   // 500ms 一次的 [FPS] 采样（含界面名、各路径计数）
        PERF_NANOS += System.nanoTime() - t0;
        PERF_CALLS++;
        if (flat) PERF_FLAT_CALLS++; else PERF_REC_CALLS++;
        long now = System.currentTimeMillis();
        if (PERF_LAST_REPORT == 0L) { PERF_LAST_REPORT = now; return; }
        if (now - PERF_LAST_REPORT < 5000L) return;
        double avgMs = PERF_CALLS == 0 ? 0 : PERF_NANOS / 1e6 / PERF_CALLS;
        int fps = -1;
        try { fps = net.minecraft.client.Minecraft.getInstance().getFps(); } catch (Throwable ignored) {}
        ModelYSM.LOGGER.info("[PERF] fps={} 模型渲染次数={} (扁平{} / 递归{}) 平均每只{}(ms) 扁平面/次={} 递归面/次={}",
                fps, PERF_CALLS, PERF_FLAT_CALLS, PERF_REC_CALLS,
                String.format(java.util.Locale.ROOT, "%.2f", avgMs),
                PERF_FLAT_CALLS == 0 ? 0 : PERF_FLAT_FACES / PERF_FLAT_CALLS,
                PERF_REC_CALLS == 0 ? 0 : PERF_REC_FACES / PERF_REC_CALLS);
        // 第二行：分清"世界"与"预览"各走了哪条管子（GPU=显存蒙皮，CPU=逐顶点提交）
        int gpuDraws = PERF_GPUP_OK + PERF_GPUW_OK;
        int cpuDraws = PERF_GPUP_CPU + PERF_GPUW_CPU;
        ModelYSM.LOGGER.info("[PERF] 路径 世界GPU={}({}/次) 世界CPU={} 预览GPU={}({}/次) 预览CPU={} 合计GPU={} CPU={}",
                PERF_GPUW_OK, PERF_GPUW_OK == 0 ? "-" : ms(PERF_GPUW_NANOS, PERF_GPUW_OK),
                PERF_GPUW_CPU,
                PERF_GPUP_OK, PERF_GPUP_OK == 0 ? "-" : ms(PERF_GPUP_NANOS, PERF_GPUP_OK),
                PERF_GPUP_CPU, gpuDraws, cpuDraws);
        PERF_NANOS = 0; PERF_CALLS = 0; PERF_FLAT_CALLS = 0; PERF_REC_CALLS = 0;
        PERF_FLAT_FACES = 0; PERF_REC_FACES = 0;
        PERF_GPUP_OK = PERF_GPUP_CPU = PERF_GPUW_OK = PERF_GPUW_CPU = 0;
        PERF_GPUP_NANOS = 0L; PERF_GPUW_NANOS = 0L;
        PERF_LAST_REPORT = now;
    }

    private static String ms(long nanos, int n) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1e6 / n);
    }

    /** 给 [FPS] 探针读的计数（世界GPU / 世界CPU / 预览GPU / 预览CPU） */
    public static int[] perfCounters() {
        return new int[]{PERF_GPUW_OK, PERF_GPUW_CPU, PERF_GPUP_OK, PERF_GPUP_CPU};
    }

    /** 扁平渲染所需的骨架表（按模型缓存，骨骼树加载后不再变化） */
    static final class FlatInfo {
        final BedrockModel.Bone[] bones;   // DFS 前序，子树在数组里连续
        final int[] parent;                // 父骨骼下标，根为 -1
        final int[] subtreeEnd;            // 子树结束下标（不含）→ 隐藏骨骼可 O(1) 跳过整棵子树
        final String[] lowerName;          // 小写骨骼名（避免每帧 toLowerCase 分配）
        final boolean[] bgRoot;            // 名字含 background 的根骨骼（卡片背景层）
        final boolean[] glowBone;          // 名字以 ysmGlow 开头（官方静态自发光）
        final boolean[] handLocator;       // 名字含 handlocator（手持物品挂接点）
        final boolean[] cullableBone;       // 该骨骼至少有一个可剔除的 cube（决定是否要算本骨投影矩阵）
        final boolean allBaked;            // 全部 cube 都走烘焙面片路径
        final Matrix4f[] mats;             // 每骨矩阵（复用，不再 new）
        final Matrix3f[] norms;            // 每骨法线矩阵（复用，只累积旋转，与 PoseStack 同口径）
        final int[] lights;                // 每骨本帧光照（GPU 路径按骨上传）
        final float[] palette;             // GPU 矩阵纹理的暂存（8 texel × 4 float × 每骨）

        FlatInfo(BedrockModel.Bone[] bones, int[] parent, int[] subtreeEnd, boolean allBaked) {
            int n = bones.length;
            this.bones = bones;
            this.parent = parent;
            this.subtreeEnd = subtreeEnd;
            this.allBaked = allBaked;
            this.lowerName = new String[n];
            this.bgRoot = new boolean[n];
            this.glowBone = new boolean[n];
            this.handLocator = new boolean[n];
            this.cullableBone = new boolean[n];
            for (int i = 0; i < n; i++) {
                String ln = bones[i].name.toLowerCase(java.util.Locale.ROOT);
                lowerName[i] = ln;
                bgRoot[i] = parent[i] < 0 && ln.contains("background");
                glowBone[i] = bones[i].name.startsWith("ysmGlow");
                handLocator[i] = ln.contains("handlocator");
                for (BedrockModel.Cube cube : bones[i].cubes) {
                    if (cube.cullable) { cullableBone[i] = true; break; }
                }
            }
            this.mats = new Matrix4f[n];
            this.norms = new Matrix3f[n];
            this.lights = new int[n];
            this.palette = new float[n * 32];
            for (int i = 0; i < n; i++) {
                mats[i] = new Matrix4f();
                norms[i] = new Matrix3f();
            }
        }
    }

    private static final java.util.Map<BedrockModel, FlatInfo> FLAT_CACHE = new java.util.WeakHashMap<>();

    private static FlatInfo flatInfo(BedrockModel model) {
        FlatInfo cached = FLAT_CACHE.get(model);
        if (cached != null) return cached;
        java.util.List<BedrockModel.Bone> list = new java.util.ArrayList<>();
        java.util.List<Integer> parents = new java.util.ArrayList<>();
        java.util.List<Integer> ends = new java.util.ArrayList<>();
        for (BedrockModel.Bone root : model.rootBones) flatFill(root, -1, list, parents, ends);
        int n = list.size();
        BedrockModel.Bone[] bones = list.toArray(new BedrockModel.Bone[0]);
        int[] parent = new int[n];
        int[] subtreeEnd = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = parents.get(i);
            subtreeEnd[i] = ends.get(i);
        }
        boolean allBaked = n > 0;
        int cubes = 0, bakedCubes = 0, faces = 0;
        for (int i = 0; i < n; i++) {
            for (BedrockModel.Cube cube : bones[i].cubes) {
                cubes++;
                if (cube.bakedFaces == null) allBaked = false;
                else { bakedCubes++; faces += cube.bakedFaces.length; }
            }
        }
        FlatInfo fi = new FlatInfo(bones, parent, subtreeEnd, allBaked);
        FLAT_CACHE.put(model, fi);
        ModelYSM.LOGGER.info("[PERF] 骨架#{} 骨骼={} cube={} 烘焙cube={} 烘焙面={} allBaked={}",
                Integer.toHexString(System.identityHashCode(model)), n, cubes, bakedCubes, faces, allBaked);
        return fi;
    }

    private static int flatFill(BedrockModel.Bone bone, int parentIdx,
                                java.util.List<BedrockModel.Bone> bones,
                                java.util.List<Integer> parents, java.util.List<Integer> ends) {
        int idx = bones.size();
        bones.add(bone);
        parents.add(parentIdx);
        ends.add(0);
        for (BedrockModel.Bone child : bone.children) flatFill(child, idx, bones, parents, ends);
        int end = bones.size();
        ends.set(idx, end);
        return end;
    }

    // 扁平渲染用的 scratch（渲染线程串行，全部复用，杜绝每帧上万次临时对象）
    private static final Matrix4f FLAT_PRE = new Matrix4f();        // 背景层缩放/居中前置矩阵
    private static final Matrix4f FLAT_PROJ_BONE = new Matrix4f();  // 本骨投影矩阵（每骨一次）
    private static final Vector4f CULL_V1 = new Vector4f();
    private static final Vector4f CULL_V2 = new Vector4f();
    private static final Vector4f CULL_V3 = new Vector4f();

    public static void render(LivingEntity entity, LoadedModel loaded, BedrockAnimation anim,
                              float bodyYawDeg, float headYawNet, float headPitchDeg, float partialTicks,
                              PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                              float scaleMul, java.util.Set<String> hiddenBones, String forcedPart) {
        render(entity, loaded, anim, bodyYawDeg, headYawNet, headPitchDeg, partialTicks,
                poseStack, buffers, packedLight, scaleMul, scaleMul, scaleMul, hiddenBones, forcedPart, 0f, 0f, 0f, false);
    }

    /** 三轴缩放入口（官方 YSM 大小 X/Y/Z 同款） */
    public static void render(LivingEntity entity, LoadedModel loaded, BedrockAnimation anim,
                              float bodyYawDeg, float headYawNet, float headPitchDeg, float partialTicks,
                              PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                              float scaleX, float scaleY, float scaleZ,
                              java.util.Set<String> hiddenBones, String forcedPart,
                              float rootOffX, float rootOffY, float rootOffZ, boolean previewMode) {
        renderFull(entity, loaded, anim, bodyYawDeg, headYawNet, headPitchDeg, partialTicks,
                poseStack, buffers, packedLight, scaleX, scaleY, scaleZ, hiddenBones, forcedPart,
                rootOffX, rootOffY, rootOffZ, previewMode, null, 0f, 1f, null);
    }

    /** 旧入口（统一缩放） */
    public static void render(LivingEntity entity, LoadedModel loaded, BedrockAnimation anim,
                              float bodyYawDeg, float headYawNet, float headPitchDeg, float partialTicks,
                              PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                              float scaleMul, java.util.Set<String> hiddenBones, String forcedPart,
                              float rootOffX, float rootOffY, float rootOffZ, boolean previewMode) {
        renderFull(entity, loaded, anim, bodyYawDeg, headYawNet, headPitchDeg, partialTicks,
                poseStack, buffers, packedLight, scaleMul, scaleMul, scaleMul, hiddenBones, forcedPart,
                rootOffX, rootOffY, rootOffZ, previewMode, null, 0f, 1f, null);
    }

    /** 过渡插值入口（NpcRenderHook 专用）：blendAlpha=新动画权重（0=旧姿态 1=新姿态） */
    public static void render(LivingEntity entity, LoadedModel loaded, BedrockAnimation anim,
                              float bodyYawDeg, float headYawNet, float headPitchDeg, float partialTicks,
                              PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                              float scaleMul, java.util.Set<String> hiddenBones, String forcedPart,
                              float rootOffX, float rootOffY, float rootOffZ, boolean previewMode,
                              BedrockAnimation blendFrom, float blendFromTime, float blendAlpha,
                              java.util.List<ui.modelysm.client.anim.ModelControllers.ActiveLayer> ctrlLayers) {
        renderFull(entity, loaded, anim, bodyYawDeg, headYawNet, headPitchDeg, partialTicks,
                poseStack, buffers, packedLight, scaleMul, scaleMul, scaleMul, hiddenBones, forcedPart,
                rootOffX, rootOffY, rootOffZ, previewMode, blendFrom, blendFromTime, blendAlpha, ctrlLayers);
    }

    private static void renderFull(LivingEntity entity, LoadedModel loaded, BedrockAnimation anim,
                              float bodyYawDeg, float headYawNet, float headPitchDeg, float partialTicks,
                              PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                              float scaleX, float scaleY, float scaleZ, java.util.Set<String> hiddenBones, String forcedPart,
                              float rootOffX, float rootOffY, float rootOffZ, boolean previewMode,
                              BedrockAnimation blendFrom, float blendFromTime, float blendAlpha,
                              java.util.List<ui.modelysm.client.anim.ModelControllers.ActiveLayer> ctrlLayers) {

        BedrockModel model = loaded.model;
        model.resetPose();
        final long __t0 = System.nanoTime();   // [PERF] 单只模型渲染耗时
        boolean __flat = false;

        // Molang 上下文（entity 可为 null：GUI 卡片预览只需静态上下文）
        java.util.UUID stateUuid = entity != null ? entity.getUUID()
                : java.util.UUID.nameUUIDFromBytes("modelysm-card-preview".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AnimStates.State state = AnimStates.get(stateUuid);
        Molang.Ctx ctx = new Molang.Ctx();
        ctx.animTime = state.animTime;
        ctx.lifeTime = entity != null ? entity.tickCount / 20.0 : 0;
        ctx.distanceMoved = state.speedPerSec;
        ctx.moveSpeed = state.speedPerSec;
        ctx.headPitch = headPitchDeg;   // 度（与 Bedrock 动画旋转单位一致）
        ctx.headYaw = headYawNet;
        // 官方 QueryBinding:57-58/85-86 的旋转查询 —— 头发/尾巴的二阶物理输入就是这几个量，
        // 之前没实现，模型脚本读到 0 → 头发永远不摆（"物理死板"的根因之一）
        ctx.queries.put("head_x_rotation", (double) headYawNet);      // = netHeadYaw（左右）
        ctx.queries.put("head_y_rotation", (double) headPitchDeg);    // = headPitch（俯仰）
        ctx.queries.put("body_x_rotation", (double) (entity != null
                ? Mth.lerp(partialTicks, entity.xRotO, entity.getXRot()) : 0.0));
        ctx.queries.put("body_y_rotation", (double) bodyYawDeg);
        ctx.health = entity != null ? entity.getHealth() : 20.0;
        ctx.maxHealth = entity != null ? Math.max(1.0, entity.getMaxHealth()) : 20.0;
        // 弹簧物理（ysm.second_order/first_order）：实体上下文 + 帧间隔
        ctx.physicsOwner = entity != null ? stateUuid : null;
        ctx.deltaTime = state.lastDt;
        // 每实体 roaming 变量表（官方 YsmRoamingVars）：时间轴脚本赋值即持久，
        // Rossi 的 'v.roaming.a'（戴帽/摘帽）就存在这里
        ctx.roaming = state.roaming;
        // 官方 YsmModel 变量表：timeline 脚本写的 v.next_idle 等必须跨帧留存，
        // 否则 controller 永远读不到（酒狐 idle 变体切不进去的根因）
        ctx.vars = state.vars;

        // 常用 query 扩充（官方 100+ 查询的高频子集；y/game 状态每帧刷新）。
        // ysm.has_* 也走 queries（variableOrCall 的 ysm 分支先查此表）。
        if (entity != null) {
            ctx.queries.put("is_sneaking", entity.isCrouching() ? 1.0 : 0.0);
            ctx.queries.put("is_sprinting", entity.isSprinting() ? 1.0 : 0.0);
            ctx.queries.put("is_on_ground", entity.onGround() ? 1.0 : 0.0);
            ctx.queries.put("is_in_water", entity.isInWater() ? 1.0 : 0.0);
            ctx.queries.put("is_riding", entity.isPassenger() ? 1.0 : 0.0);
            ctx.queries.put("is_on_fire", entity.isOnFire() ? 1.0 : 0.0);
            ctx.queries.put("is_swimming", entity.isSwimming() ? 1.0 : 0.0);
            ctx.queries.put("is_sleeping", entity.isSleeping() ? 1.0 : 0.0);
            ctx.queries.put("is_climbing", entity.onClimbable() ? 1.0 : 0.0);
            ctx.queries.put("is_fall_flying", entity.isFallFlying() ? 1.0 : 0.0);
            ctx.queries.put("is_dead", entity.isDeadOrDying() ? 1.0 : 0.0);
            ctx.queries.put("attacked", entity.hurtTime > 0 ? 1.0 : 0.0);
            ctx.queries.put("vertical_speed", (entity.getY() - entity.yo) * 20.0);
            ctx.queries.put("is_moving", state.speedPerSec > 0.25 ? 1.0 : 0.0);
            ctx.queries.put("using_item", entity.isUsingItem() ? 1.0 : 0.0);
            ctx.queries.put("swing_progress", (double) Mth.lerp(partialTicks, entity.oAttackAnim, entity.attackAnim));
            ctx.queries.put("has_mainhand", entity.getMainHandItem().isEmpty() ? 0.0 : 1.0);
            ctx.queries.put("has_offhand", entity.getOffhandItem().isEmpty() ? 0.0 : 1.0);
            var armorSlots = entity.getArmorSlots().iterator();
            ctx.queries.put("has_helmet", armorSlots.next().isEmpty() ? 0.0 : 1.0);
            ctx.queries.put("has_chestplate", armorSlots.next().isEmpty() ? 0.0 : 1.0);
            ctx.queries.put("has_leggings", armorSlots.next().isEmpty() ? 0.0 : 1.0);
            ctx.queries.put("has_boots", armorSlots.next().isEmpty() ? 0.0 : 1.0);
        }

        // 官方轮盘 ⚙ 配置表单变量注入（必须在动画采样前）：
        // 用户在设置面板设置过的 v.* 变量视为已定义，动画脚本里的 "v.x=v.x??默认" 不再触发兜底，
        // "v.Head_Eyes==N" 等变体选择器按用户选择生效。未设置的变量走 Molang 原生 null 语义。
        ui.modelysm.common.EntitySettings es = null;
        if (entity != null) {
            es = ui.modelysm.client.ClientState.settingsFor(stateUuid);
            // 用户变量投影：先把本帧服务端 molVars 全量写进 ctx/持久表，
            // 再把"上一帧由用户写入、这一帧已消失"的键清掉。
            // 没有这步，点"重置设置"（服务端 molVars 清空）后 state.vars 里残留旧值，
            // 模型位置 / 大小等由变量驱动的设定永远回不到默认（实机复现：重置无效）。
            java.util.Set<String> nowKeys = es != null && !es.molVars.isEmpty()
                    ? new java.util.HashSet<>() : java.util.Set.of();
            if (es != null && !es.molVars.isEmpty()) {
                for (Map.Entry<String, Float> mv : es.molVars.entrySet()) {
                    // 统一小写 key：Molang 标识符大小写不敏感，模型里既写 v.CBody 也写 V.CBody
                    String key = mv.getKey().toLowerCase(java.util.Locale.ROOT);
                    ctx.queries.put(key, mv.getValue().doubleValue());
                    // 用户设置变量进持久表：动画表达式与 controller 条件同源读到
                    state.vars.put(key, mv.getValue());
                    nowKeys.add(key);
                }
            }
            for (String oldKey : new java.util.ArrayList<>(state.userVarKeys)) {
                if (!nowKeys.contains(oldKey)) {
                    state.vars.remove(oldKey);
                    ctx.queries.remove(oldKey);
                    state.userVarKeys.remove(oldKey);
                }
            }
            state.userVarKeys.addAll(nowKeys);
        }

        // 官方 timeline_events：主状态动画跨过事件时间点时执行一次脚本。
        // 必须早于采样（applyAnimLayers），脚本改的变量当帧就能影响姿态/缩放。
        if (!previewMode && anim != null && !anim.timelineEvents.isEmpty()) {
            float curT = state.animTime;
            float prevT = anim.name.equals(state.evAnim) ? state.evTime : 0f;
            if (curT < prevT) prevT = 0f;   // animTime 被重置（强制动画切换/一次性重播）
            Timelines.fire(anim, prevT, curT, ctx);
            state.evAnim = anim.name;
            state.evTime = curT;
        }

        // 官方 GeckoLib 语义（OpenYSM AnimationProcessor.applyTransform / BoneTopLevelSnapshot.reset）：
        // 1) 每层动画对骨骼 rot/pos/scale 都是【覆盖】（后 apply 的层赢），绝不跨层累加；
        // 2) 最终骨骼旋转 = 动画偏移 + 静止角 initialRotation（渲染时在 renderBone 相加）；
        // 3) 层顺序 = 官方控制器注册顺序：pre_parallel → 状态动画(main) → parallel。
        // v39 之前头发炸开 = 跨层累加；v39 丢静止角 = 大 rest 角骨骼（酒狐耳朵等）摊平。
        java.util.function.BiConsumer<String, BedrockAnimation.Sample> sink = (boneName, sample) -> {
            BedrockModel.Bone bone = model.bonesByName.get(boneName);
            if (bone == null) return;
            if (sample.hasRot) {
                bone.animRot[0] = -sample.rot[0] * Mth.DEG_TO_RAD;  // bedrock x 取反
                bone.animRot[1] = -sample.rot[1] * Mth.DEG_TO_RAD;  // bedrock y 取反
                bone.animRot[2] = sample.rot[2] * Mth.DEG_TO_RAD;
            }
            if (sample.hasPos) {
                bone.animPos[0] = -sample.pos[0] / 16f;             // 位移同样镜像 x
                bone.animPos[1] = sample.pos[1] / 16f;
                bone.animPos[2] = sample.pos[2] / 16f;
            }
            if (sample.hasScale) {
                for (int i = 0; i < 3; i++) bone.animScale[i] = sample.scale3[i]; // scale 0 = 隐藏骨骼
            }
        };

        // 1) pre_parallel 层（先应用）→ 2) 状态动画 → 3) controller 条件层 → 4) parallel 层
        applyAnimLayers(loaded, anim, state.animTime, ctx, sink, previewMode, ctrlLayers, state, true);
        // 5) 盔甲层（官方 armor 控制器语义：模型自带盔甲骨骼动画按装备槽位启用）
        applyArmorAnims(entity, loaded, state.animTime, ctx, sink, previewMode);

        // 官方 0.1s 过渡插值：状态层切换瞬间，旧姿态（冻结在切换时刻）与新姿态线性混合。
        // 实现：捕获当前（新）姿态 → 重置动画量 → 重放旧动画得到旧姿态 → 逐骨骼 lerp。
        // 注意 resetPose 的 rest 值不动（anim 数组才是动画量），头部覆盖在此之后仍生效。
        if (blendFrom != null && blendAlpha < 0.999f) {
            java.util.Map<String, float[]> newPose = captureAnimPose(model);
            resetAnimArrays(model);
            applyAnimLayers(loaded, blendFrom, blendFromTime, ctx, sink, previewMode, ctrlLayers, state, false);
            applyArmorAnims(entity, loaded, blendFromTime, ctx, sink, previewMode);
            float a = Math.max(0f, Math.min(1f, blendAlpha));
            for (Map.Entry<String, float[]> e : newPose.entrySet()) {
                BedrockModel.Bone bone = model.bonesByName.get(e.getKey());
                if (bone == null) continue;
                float[] np = e.getValue();
                for (int i = 0; i < 3; i++) {
                    bone.animRot[i] = Mth.lerp(a, bone.animRot[i], np[i]);
                    bone.animPos[i] = Mth.lerp(a, bone.animPos[i], np[3 + i]);
                    bone.animScale[i] = Mth.lerp(a, bone.animScale[i], np[6 + i]);
                }
            }
        }

        // 头部俯仰/偏航覆盖（MC 弧度，加到采样结果上）
        model.applyHeadRotation(headYawNet * Mth.DEG_TO_RAD, headPitchDeg * Mth.DEG_TO_RAD);

        // 注意：不要在这里清空根骨骼位移。
        // 酒狐 gui 动画的 MRoot 稳态位移是 (4.1, -37.7, -11.5) 模型单位（≈ +0.26, -2.36, -0.72 方块），
        // 配合 Root scale=2，正是官方卡片"只露头肩"的取景；清零后人物会整体上移 2.4 方块、只剩大腿。

        // 【诊断】打印姿态异常的骨骼（位移>1.5方块 或 旋转>200°），定位散架来源
        if (DEBUG_LOG_EXTREME) {
            int n = 0;
            for (BedrockModel.Bone b : model.bonesByName.values()) {
                float pr = (float) Math.sqrt(b.animRot[0] * b.animRot[0] + b.animRot[1] * b.animRot[1] + b.animRot[2] * b.animRot[2]);
                float pp = (float) Math.sqrt(b.animPos[0] * b.animPos[0] + b.animPos[1] * b.animPos[1] + b.animPos[2] * b.animPos[2]);
                boolean extreme = pr > (float) (200 * Mth.DEG_TO_RAD) || pp > 1.5f;
                boolean nan = Float.isNaN(pr) || Float.isNaN(pp);
                if (extreme || nan) {
                    ModelYSM.LOGGER.warn("[EXTREME] {} rot={}{},{},{} pos={}{},{},{} scale={}",
                            b.name, nan ? "NaN!" : "", b.animRot[0], b.animRot[1], b.animRot[2],
                            Float.isNaN(pp) ? "NaN!" : "", b.animPos[0], b.animPos[1], b.animPos[2],
                            java.util.Arrays.toString(b.animScale));
                    if (++n >= 30) { ModelYSM.LOGGER.warn("[EXTREME] ... (truncated)"); break; }
                }
            }
            ModelYSM.LOGGER.warn("[EXTREME] total extreme bones: {} (previewMode={})", n, previewMode);
        }

        // 强制形态：GUI 手动勾选的部件（逗号分隔列表）忽略动画 scale=0
        // （如 wine_fox 的 parallel1 满血时把 FOX 缩为 0，手动列表压过它）
        if (forcedPart != null && !forcedPart.isEmpty()) {
            java.util.List<String> plist = new java.util.ArrayList<>();
            for (String p : forcedPart.split(",")) {
                if (!p.isBlank()) plist.add(p.trim());
            }
            // 互斥形态模型（exclusiveParts，部件=形态只能二选一）出现多条目强制列表，
            // 是旧版本"显示全部部件"残留的脏数据 → 人型+狐狸叠加渲染成乱码。
            // 直接忽略整张列表，回归动画自动控制形态（满血人型/残血狐狸）。
            boolean contradictory = loaded.exclusiveParts && plist.size() > 1;
            if (!contradictory) {
                for (String part : plist) {
                    BedrockModel.Bone fp = model.bonesByName.get(part);
                    if (fp != null) {
                        for (int i = 0; i < 3; i++) {
                            if (Math.abs(fp.animScale[i]) < 0.05f) fp.animScale[i] = 1.0f;
                        }
                    }
                }
            }
        }

        // 贴图选择：DEBUG 覆盖 > 用户切换的贴图（实体渲染）> 默认第一张（卡片预览等）
        ResourceLocation texture;
        if (DEBUG_TEXTURE_OVERRIDE != null) {
            texture = DEBUG_TEXTURE_OVERRIDE;
        } else if (es != null && es.textureIndex != 0) {
            texture = loaded.texture(es.textureIndex);
        } else {
            texture = loaded.defaultTexture();
        }
        if (texture == null) return;

        // ---- 外层变换（vanilla: setupRotations + scale，Y-up 约定）----
        poseStack.pushPose();
        try {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - bodyYawDeg));
            // 三轴缩放（官方 YSM 大小 X/Y/Z 同款）
            // 官方一条链路：几何已是"16 单位=1 格"，renderEarly 只乘 height_scale/width_scale，
            // 没有任何 15/16 补偿。之前的 0.9375 让所有模型比官方小 6%，已去掉。
            float base = 1.0F;
            float sx = base * Math.max(0.05f, scaleX);
            float sy = base * Math.max(0.05f, scaleY);
            float sz = base * Math.max(0.05f, scaleZ);
            poseStack.scale(sx, sy, sz);
            // YSM 工程宽高缩放（ysm.json properties.width_scale/height_scale）
            if (loaded.widthScale != 1f || loaded.heightScale != 1f) {
                poseStack.scale(loaded.widthScale, loaded.heightScale, loaded.widthScale);
            }

        // 根偏移（卡片预览抵消根骨骼 pivot；诊断可清零）
        float roX = DEBUG_ROOT_OFF_ZERO ? 0 : rootOffX;
        float roY = DEBUG_ROOT_OFF_ZERO ? 0 : rootOffY;
        float roZ = DEBUG_ROOT_OFF_ZERO ? 0 : rootOffZ;
        // 双面渲染（关闭背面剔除）：
        // 文件夹模型的贴图是"按面画好的岛"，未上色的面是 alpha=0 的透明像素；
        // 若背面剔除生效，从背面/侧面看过去就是"直接透过模型看到天空"——
        // 实机表现正是"两边耳朵各一块蓝、靴子漏块"。官方 YSM 默认也是双面渲染
        // （ysm.json 里的 all_cutout=true 才是"强制全部剔除"）。
        VertexConsumer vc = buffers.getBuffer(ModelYsmRenderTypes.modelNoCull(texture));
        // 发光骨骼（ysm.bone_glow 动作 + ysmGlow 前缀静态骨骼）：官方 YSM 做法——
        // 不换 RenderType、不加法混合，仅将该骨骼光照覆盖为全亮（分档亮度 pack(v,v)），
        // 避免 RenderType.eyes 的加法混合把浅色贴图叠加饱和成纯白
        java.util.Map<String, Integer> glowMap = ctx.boneGlow.isEmpty()
                ? java.util.Collections.emptyMap() : new java.util.HashMap<>();
        for (Map.Entry<String, Double> g : ctx.boneGlow.entrySet()) {
            int v = (int) Math.max(-1, Math.min(15, Math.round(g.getValue())));
            glowMap.put(g.getKey(), v);
        }
        java.util.function.Function<BedrockModel.Bone, Integer> lightResolver = bone -> {
            if (bone.name.startsWith("ysmGlow")) return LightTexture.pack(15, 15);
            Integer g = glowMap.get(bone.name);
            return (g != null && g > 0) ? LightTexture.pack(g, g) : packedLight;
        };        // locator 捕获（RightHandLocator/LeftHandLocator）：挂接渲染主手/副手物品
        java.util.List<LocatorPose> locators = entity != null && !previewMode
                ? new java.util.ArrayList<>(2) : null;
        // 第二档：全部烘焙面片的模型走扁平路径（算法结构对齐官方纯 Java 兜底），
        // 其余（JSON 几何 / box UV / 按面 UV / cube 自旋转）原样走递归路径。
        FlatInfo fi = USE_FLAT_BAKED_PATH ? flatInfo(model) : null;
        if (fi != null && fi.allBaked) {
            __flat = true;
            renderFlatBaked(model, loaded, poseStack, vc, buffers, packedLight, glowMap, previewMode,
                    roX, roY, roZ, hiddenBones, locators, fi, texture);
        } else {
        for (BedrockModel.Bone root : model.rootBones) {
            if (hiddenBones.contains(root.name)) continue;   // 隐藏骨骼（含子骨骼）
            // 诊断：跳过背景/纯 Molang 变量根骨骼
            if (DEBUG_SKIP_BG_ROOTS) {
                String ln = root.name.toLowerCase();
                if (ln.contains("background") || ln.startsWith("molang") || ln.contains("molang")) continue;
            }
            // 卡片预览：gui 背景层单独等比缩放 + 居中（官方口径：框 43x70 GUI / 视口 52x70）
            float[] bgBox = (previewMode && PREVIEW_BG_SCALE > 0f
                    && root.name.toLowerCase(java.util.Locale.ROOT).contains("background"))
                    ? bgBounds(loaded, root, roX, roY, roZ) : null;
            if (bgBox != null) {
                float bx = (bgBox[0] + bgBox[3]) * 0.5f;
                float by = (bgBox[1] + bgBox[4]) * 0.5f;
                float bz = (bgBox[2] + bgBox[5]) * 0.5f;
                poseStack.pushPose();
                poseStack.translate(0f, PREVIEW_BG_CENTER_Y, 0f);
                poseStack.scale(PREVIEW_BG_SCALE, PREVIEW_BG_SCALE, PREVIEW_BG_SCALE);
                poseStack.translate(-bx, -by, -bz);
                renderBone(root, poseStack, vc, packedLight, model, null, hiddenBones, roX, roY, roZ, locators, lightResolver);
                poseStack.popPose();
            } else {
                renderBone(root, poseStack, vc, packedLight, model, null, hiddenBones, roX, roY, roZ, locators, lightResolver);
            }
        }
        }
        // 手持物品挂接（官方 RightHandLocator/LeftHandLocator 语义）
        if (locators != null && entity != null) {
            renderHandItems(entity, poseStack, buffers, packedLight, locators);
        }
        } finally {
            poseStack.popPose();
            perfReport(__t0, __flat);
        }
    }

    // ---- 卡片预览用：background* 骨骼子树的几何包围盒（方块单位，世界坐标 = bone.pivot + rootOff + cube） ----
    private static final java.util.Map<String, float[]> BG_BOUNDS_CACHE = new java.util.HashMap<>();

    private static float[] bgBounds(LoadedModel loaded, BedrockModel.Bone root, float rox, float roy, float roz) {
        String key = System.identityHashCode(loaded) + "#" + root.name;
        float[] cached = BG_BOUNDS_CACHE.get(key);
        if (cached != null) return cached.length == 0 ? null : cached;
        float[] bb = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = collectBounds(root, rox, roy, roz, bb);
        BG_BOUNDS_CACHE.put(key, any ? bb : new float[0]);
        return any ? bb : null;
    }

    /** 递归累计子树包围盒；立方体自带 rotation（如酒狐两根横条的 [0,0,90]）必须算进去，否则会算成 43x90 的假盒子 */
    private static boolean collectBounds(BedrockModel.Bone bone, float rox, float roy, float roz, float[] bb) {
        boolean any = false;
        float px = bone.mcPivot.x() + rox, py = bone.mcPivot.y() + roy, pz = bone.mcPivot.z() + roz;
        for (BedrockModel.Cube c : bone.cubes) {
            float inf = c.inflate / 16f;
            float[] xs = {c.min.x() - inf, c.max.x() + inf};
            float[] ys = {c.min.y() - inf, c.max.y() + inf};
            float[] zs = {c.min.z() - inf, c.max.z() + inf};
            for (float x0 : xs) for (float y0 : ys) for (float z0 : zs) {
                float x = x0, y = y0, z = z0;
                if (c.rotAnglesRad != null) {
                    float cx0 = c.rotPivot.x(), cy0 = c.rotPivot.y(), cz0 = c.rotPivot.z();
                    float vx = x - cx0, vy = y - cy0, vz = z - cz0;
                    float rx = ui.modelysm.client.DebugUv.ax(c.rotAnglesRad.x());
                    float ry = ui.modelysm.client.DebugUv.ay(c.rotAnglesRad.y());
                    float rz = ui.modelysm.client.DebugUv.az(c.rotAnglesRad.z());
                    // 与 drawCube 同序：mulPose(Z) → (Y) → (X)，顶点先过 X 再过 Y 最后 Z
                    float cs = (float) Math.cos(rx), sn = (float) Math.sin(rx);
                    float ty = vy * cs - vz * sn, tz = vy * sn + vz * cs;
                    vy = ty; vz = tz;
                    cs = (float) Math.cos(ry); sn = (float) Math.sin(ry);
                    float tx = vx * cs + vz * sn;
                    vz = -vx * sn + vz * cs;
                    vx = tx;
                    cs = (float) Math.cos(rz); sn = (float) Math.sin(rz);
                    tx = vx * cs - vy * sn;
                    vy = vx * sn + vy * cs;
                    vx = tx;
                    x = vx + cx0; y = vy + cy0; z = vz + cz0;
                }
                bb[0] = Math.min(bb[0], x + px);
                bb[1] = Math.min(bb[1], y + py);
                bb[2] = Math.min(bb[2], z + pz);
                bb[3] = Math.max(bb[3], x + px);
                bb[4] = Math.max(bb[4], y + py);
                bb[5] = Math.max(bb[5], z + pz);
                any = true;
            }
        }
        for (BedrockModel.Bone child : bone.children) {
            any |= collectBounds(child, rox, roy, roz, bb);
        }
        return any;
    }

    /** 槽位 → 官方盔甲动画名（模型自带盔甲骨骼动画，装备后启用） */    private static final String[][] ARMOR_ANIMS = {
            {"helmet:default", "armor_helmet:default"},
            {"chestplate:default", "armor_chestplate:default"},
            {"leggings:default", "armor_leggings:default"},
            {"boots:default", "armor_boots:default"}
    };
    private static final net.minecraft.world.entity.EquipmentSlot[] ARMOR_SLOTS = {
            net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS,
            net.minecraft.world.entity.EquipmentSlot.FEET
    };

    /** 盔甲层：装备了对应槽位护甲且模型带该动画 → 应用（官方 ArmorPredicate 同语义） */
    private static void applyArmorAnims(LivingEntity entity, LoadedModel loaded, float time,
                                        Molang.Ctx ctx, java.util.function.BiConsumer<String, BedrockAnimation.Sample> sink,
                                        boolean previewMode) {
        if (entity == null || previewMode) return;
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            if (entity.getItemBySlot(ARMOR_SLOTS[i]).isEmpty()) continue;
            for (String an : ARMOR_ANIMS[i]) {
                BedrockAnimation ba = loaded.animations.get(an);
                if (ba != null) {
                    ba.applyTo(time, ctx, sink);
                    break;
                }
            }
        }
    }

    /** 官方层顺序应用：pre_parallel → 状态动画 → controller 条件层 → parallel */
    private static void applyAnimLayers(LoadedModel loaded, BedrockAnimation stateAnim, float stateTime,
                                        Molang.Ctx ctx, java.util.function.BiConsumer<String, BedrockAnimation.Sample> sink,
                                        boolean previewMode,
                                        java.util.List<ui.modelysm.client.anim.ModelControllers.ActiveLayer> ctrlLayers,
                                        ui.modelysm.client.AnimStates.State evState, boolean fireLayerEvents) {
        // 官方预览 = 一个真实玩家实例 + 完整 AnimationTracker，所以 pre_parallel / parallel
        // 这八对常驻层在界面里同样生效（parallel0 驱动 LeftEyebrow/RightEyebrow/Eyelid，
        // parallel1 驱动 LeftEyesBase/RightEyesBase —— 这就是列表里"没有表情动画"的原因）。
        // 只有玩家状态控制器（post_main/hold/swing/use/passenger）才在预览里跳过。
        for (String layer : PRE_LAYERS) {
            BedrockAnimation base = loaded.animations.get(layer);
            if (base != null) {
                fireLayerTimeline(base, evState, stateTime, ctx, fireLayerEvents);
                base.applyTo(stateTime, ctx, sink);
            }
        }
        if (previewMode) {
            // 卡片预览的"静止姿态层"。官方链路里 status "gui" 只驱动它自己那批骨骼
            // （MRoot/Root/UpperBody/Head/手臂/头发/Background1...），面部小部件（Mouth_smile2 等）
            // 完全不由它负责，靠的是 idle 里的那句
            //     Mouth_smile2.position.z = "ysm.food_level<=6?0:-1"    （饱食时 -1 → 顶出 1 单位）
            // 而 Mouth_smile2 的 cube 常驻在头部几何内部（z -3.01..-1.01，头前脸 -3.5），
            // 不推出来就永远被头挡住 = 卡片上"没有小嘴"（世界里跑 idle 所以看得到）。
            // 所以这里把 idle 当作【预览动画之前】的基底层：gui 有的骨骼仍然由 gui 覆盖
            // （取景/相机/背景板完全不受影响），gui 没管的骨骼（嘴、腿、衣摆）落到 idle 的静止值。
            String idleName = BedrockAnimation.pick(loaded.animations, BedrockAnimation.Names.IDLE);
            if (idleName != null) {
                BedrockAnimation idle = loaded.animations.get(idleName);
                if (idle != null) {
                    fireLayerTimeline(idle, evState, stateTime, ctx, fireLayerEvents);
                    idle.applyTo(stateTime, ctx, sink);
                }
            }
        }
        if (stateAnim != null) {
            stateAnim.applyTo(stateTime, ctx, sink);
        }
        // controller 条件层（官方 post_main/hold/swing/use/passenger 等位置；覆盖语义同其他层）
        // 预览里同样要生效：官方卡片的 hover / hover_fadeout 走的就是
        // PlayerCustomAnimationPredicate 这条 controller（读 AnimationTracker.previousAnimation）。
        // 之前 previewMode 直接跳过 ctrlLayers，等于"鼠标滑到卡片上什么都不发生"。
        if (ctrlLayers != null) {
            for (ui.modelysm.client.anim.ModelControllers.ActiveLayer al : ctrlLayers) {
                for (String an : al.state.animations) {
                    BedrockAnimation ba = loaded.animations.get(an);
                    if (ba != null) ba.applyTo(al.stateTime, ctx, sink);
                }
            }
        }
        for (String layer : POST_LAYERS) {
            BedrockAnimation base = loaded.animations.get(layer);
            if (base != null) {
                fireLayerTimeline(base, evState, stateTime, ctx, fireLayerEvents);
                base.applyTo(stateTime, ctx, sink);
            }
        }
    }

    /**
     * 常驻层的 timeline 触发（官方每层独立 tracker）。
     *
     * <p>YSM 把头发/尾巴的弹簧物理整段写在层里：parallel3（animation_length 0.5）的 timeline
     * 只负责算 7 组弹簧常数 v.L*_K1/K2/K3，parallel4（animation_length 0.01）的 timeline
     * 每“帧”积一次速度/位移，pre_parallel2 再用 v.L13_P01y 这类变量摆尾。
     * 之前只触发主状态动画的 timeline，这两层等于从未执行 → 尾巴永远停在静止角。
     */
    private static void fireLayerTimeline(BedrockAnimation anim, ui.modelysm.client.AnimStates.State st,
                                          float stateTime, Molang.Ctx ctx, boolean enabled) {
        if (!enabled || st == null || anim == null || anim.timelineEvents.isEmpty()) return;
        float prev = st.layerEvTime.getOrDefault(anim.name, Float.NaN);
        float cur = stateTime;
        if (Float.isNaN(prev)) {
            // 首次：把当前时刻之前的一个循环当起点，保证 0.0 那批初始化脚本立即执行
            float len = anim.length > 0f ? anim.length : 1.0f;
            prev = Math.max(0f, cur - len);
        }
        // 层被重启（时间回绕）时从头补
        if (cur < prev) prev = 0f;
        Timelines.fire(anim, prev, cur, ctx);
        st.layerEvTime.put(anim.name, cur);
    }

    /** 捕获全部骨骼当前动画量：[rot0..2, pos0..2, scale0..2] 共 9 浮点 */
    private static java.util.Map<String, float[]> captureAnimPose(BedrockModel model) {
        java.util.Map<String, float[]> out = new java.util.HashMap<>(model.bonesByName.size() * 2);
        for (BedrockModel.Bone b : model.bonesByName.values()) {
            float[] v = new float[9];
            System.arraycopy(b.animRot, 0, v, 0, 3);
            System.arraycopy(b.animPos, 0, v, 3, 3);
            System.arraycopy(b.animScale, 0, v, 6, 3);
            out.put(b.name, v);
        }
        return out;
    }

    /** 仅重置动画量（rot=0/pos=0/scale=1），不动静止角 rest */
    private static void resetAnimArrays(BedrockModel model) {
        for (BedrockModel.Bone b : model.bonesByName.values()) {
            java.util.Arrays.fill(b.animRot, 0f);
            java.util.Arrays.fill(b.animPos, 0f);
            java.util.Arrays.fill(b.animScale, 1f);
        }
    }

    /** locator 位姿捕获（世界=实体渲染空间内的最终变换） */
    private record LocatorPose(String bone, org.joml.Matrix4f pose, org.joml.Matrix3f normal) {}

    /** 主手/副手物品渲染到 locator 骨骼位置（官方 PlayerLocator.rightHand/leftHand 同语义） */
    private static void renderHandItems(LivingEntity entity, PoseStack poseStack,
                                        MultiBufferSource buffers, int packedLight,
                                        java.util.List<LocatorPose> locators) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.getItemRenderer() == null) return;
        ItemStack main = entity.getMainHandItem();
        ItemStack off = entity.getOffhandItem();
        for (LocatorPose lp : locators) {
            String n = lp.bone().toLowerCase();
            boolean right = n.contains("righthandlocator") || n.contains("mainhandlocator");
            boolean left = n.contains("lefthandlocator") || n.contains("offhandlocator");
            ItemStack stack = right ? main : (left ? off : null);
            if (stack == null || stack.isEmpty()) continue;
            var display = right ? net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                    : net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
            poseStack.pushPose();
            try {
                // 恢复捕获的骨骼最终变换（pose()/normal() 返回内部引用，可直接覆写）
                poseStack.last().pose().set(lp.pose());
                poseStack.last().normal().set(lp.normal());
                mc.getItemRenderer().renderStatic(stack, display, packedLight,
                        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                        poseStack, buffers, entity.level(), entity.getId());
            } finally {
                poseStack.popPose();
            }
        }
    }

    /** 渲染骨骼（递归）。parentPivot=父骨骼 MC pivot（根为 null，此时叠加 rootOffset） */
    private static void renderBone(BedrockModel.Bone bone, PoseStack poseStack, VertexConsumer vc, int packedLight,
                                   BedrockModel model, BedrockModel.Bone parentBone, java.util.Set<String> hiddenBones,
                                   float rootOffX, float rootOffY, float rootOffZ,
                                   java.util.List<LocatorPose> locators,
                                   java.util.function.Function<BedrockModel.Bone, Integer> lightResolver) {
        poseStack.pushPose();
        try {
            // 骨骼级光照解析：发光骨骼覆盖为全亮（官方式自发光），普通骨骼用实体光照
            int boneLight = lightResolver != null ? lightResolver.apply(bone) : packedLight;
            // 从父 pivot 移动到本 pivot + 动画平移（根骨骼额外叠加外部传入的 rootOffset）
            if (parentBone == null) {
                poseStack.translate(bone.mcPivot.x() + rootOffX + bone.animPos[0],
                        bone.mcPivot.y() + rootOffY + bone.animPos[1],
                        bone.mcPivot.z() + rootOffZ + bone.animPos[2]);
            } else {
                poseStack.translate(bone.mcPivot.x() - parentBone.mcPivot.x() + bone.animPos[0],
                        bone.mcPivot.y() - parentBone.mcPivot.y() + bone.animPos[1],
                        bone.mcPivot.z() - parentBone.mcPivot.z() + bone.animPos[2]);
            }

            // 旋转（动画偏移 + 静止角）——官方 GeckoLib：bone.rotation = snapshot.rotation + initialRotation。
            // GeckoLib 约定：Z→Y→X 顺序（合成 Rz*Ry*Rx）
            float rx = DEBUG_ZERO_REST_ROT ? bone.animRot[0] : bone.restRotRad.x() + bone.animRot[0];
            float ry = DEBUG_ZERO_REST_ROT ? bone.animRot[1] : bone.restRotRad.y() + bone.animRot[1];
            float rz = DEBUG_ZERO_REST_ROT ? bone.animRot[2] : bone.restRotRad.z() + bone.animRot[2];
            // 调试档 F7 可切换旋转约定（默认 = 线上行为）
            rx = ui.modelysm.client.DebugUv.ax(rx);
            ry = ui.modelysm.client.DebugUv.ay(ry);
            rz = ui.modelysm.client.DebugUv.az(rz);
            if (ui.modelysm.client.DebugUv.orderXYZ()) {
                if (rx != 0) poseStack.mulPose(Axis.XP.rotation(rx));
                if (ry != 0) poseStack.mulPose(Axis.YP.rotation(ry));
                if (rz != 0) poseStack.mulPose(Axis.ZP.rotation(rz));
            } else {
                if (rz != 0) poseStack.mulPose(Axis.ZP.rotation(rz));
                if (ry != 0) poseStack.mulPose(Axis.YP.rotation(ry));
                if (rx != 0) poseStack.mulPose(Axis.XP.rotation(rx));
            }

            if (bone.animScale[0] != 1.0f || bone.animScale[1] != 1.0f || bone.animScale[2] != 1.0f) {
                poseStack.scale(bone.animScale[0], bone.animScale[1], bone.animScale[2]); // 某轴为 0 时退化不可见=隐藏
            }

            // 立方体
            for (BedrockModel.Cube cube : bone.cubes) {
                drawCube(cube, poseStack, vc, boneLight, model);
            }

            // locator 骨骼：捕获最终位姿（手持物品挂接点）
            if (locators != null) {
                String ln = bone.name.toLowerCase();
                if (ln.contains("handlocator")) {
                    locators.add(new LocatorPose(bone.name,
                            new org.joml.Matrix4f(poseStack.last().pose()),
                            new org.joml.Matrix3f(poseStack.last().normal())));
                }
            }

            // 子骨骼
            for (BedrockModel.Bone child : bone.children) {
                if (hiddenBones.contains(child.name)) continue;  // 隐藏骨骼（含其子树）
                renderBone(child, poseStack, vc, packedLight, model, bone, hiddenBones, 0f, 0f, 0f, locators, lightResolver);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * 扁平渲染（第二档）：一次 for 走完整棵骨架。逐骨只做一次矩阵合成（父链缓存）、一次投影矩阵、
     * 一次法线累积；顶点变换与提交在同一个循环里，全程复用 scratch（每帧零临时对象）。
     * 与原递归路径逐项等价：push→translate→rotate→scale→draw 的矩阵链在这里就是
     * mat = parentMat × T × R × S，法线只累积旋转（与 PoseStack 的 normal 口径一致）。
     */
    private static void renderFlatBaked(BedrockModel model, LoadedModel loaded, PoseStack poseStack,
                                        VertexConsumer vc, MultiBufferSource buffers, int packedLight,
                                        java.util.Map<String, Integer> glowMap, boolean previewMode,
                                        float roX, float roY, float roZ,
                                        java.util.Set<String> hiddenBones, java.util.List<LocatorPose> locators,
                                        FlatInfo fi, ResourceLocation texture) {
        Matrix4f base = poseStack.last().pose();
        Matrix3f baseNorm = poseStack.last().normal();
        // 预览期（卡片/大预览）不做行列式剔除 → 连投影矩阵都不算，每面省 3 次 4x4 变换
        boolean cullOn = ENABLE_CUBE_CULLING && !previewCullOff;
        Matrix4f proj = RenderSystem.getProjectionMatrix();
        boolean xyz = ui.modelysm.client.DebugUv.orderXYZ();
        // 预览（卡片 / 左侧大预览）走【离屏】GPU 蒙皮：在自建 FBO 里用自带着色器画一遍，
        // 再把纹理当 GUI 贴图贴回去（完全不读写 GUI 的投影/模型视图状态，
        // 这也是官方 GpuRenderPath 对 isPreview 的处理方式：跳过 Iris，用自己的程序直绘）
        boolean fboPreview = USE_GPU_PREVIEW && previewMode && previewCullOff && previewMirrorInModelView
                && PreviewFboRenderer.available() && WorldGpuRenderer.shouldUse(model, texture, fi, true);
        boolean gpuWorld = !previewMode && WorldGpuRenderer.shouldUse(model, texture, fi, false);
        boolean gpu = fboPreview || gpuWorld;
        for (int i = 0; i < fi.bones.length; i++) {
            BedrockModel.Bone bone = fi.bones[i];
            if (hiddenBones.contains(bone.name) || (DEBUG_SKIP_BG_ROOTS
                    && (fi.lowerName[i].contains("background") || fi.lowerName[i].contains("molang")))) {
                // GPU 路径下隐藏骨骼的顶点还在 VBO 里，必须把整棵子树的矩阵置零
                // （顶点全部塌到原点 → 零面积三角形），否则会停在上一帧的位置。
                if (gpu) {
                    for (int k = i; k < fi.subtreeEnd[i]; k++) fi.mats[k].zero();
                }
                i = fi.subtreeEnd[i] - 1;   // 隐藏骨骼连同整棵子树一起跳过（O(1)，不用递归）
                continue;
            }
            int p = fi.parent[i];
            Matrix4f m = fi.mats[i];
            Matrix3f nm = fi.norms[i];
            m.set(p < 0 ? base : fi.mats[p]);
            nm.set(p < 0 ? baseNorm : fi.norms[p]);

            // 卡片预览背景层：整体等比缩放 + 居中（等价于原路径的 push/translate/scale/translate）
            if (previewMode && fi.bgRoot[i] && PREVIEW_BG_SCALE > 0f) {
                float[] bgBox = bgBounds(loaded, bone, roX, roY, roZ);
                if (bgBox != null) {
                    float bx = (bgBox[0] + bgBox[3]) * 0.5f;
                    float by = (bgBox[1] + bgBox[4]) * 0.5f;
                    float bz = (bgBox[2] + bgBox[5]) * 0.5f;
                    FLAT_PRE.identity()
                            .translate(0f, PREVIEW_BG_CENTER_Y, 0f)
                            .scale(PREVIEW_BG_SCALE, PREVIEW_BG_SCALE, PREVIEW_BG_SCALE)
                            .translate(-bx, -by, -bz);
                    m.mul(FLAT_PRE);
                }
            }

            // 平移：根骨骼 = pivot + rootOffset + 动画平移；其余 = 与父 pivot 之差 + 动画平移
            if (p < 0) {
                m.translate(bone.mcPivot.x() + roX + bone.animPos[0],
                        bone.mcPivot.y() + roY + bone.animPos[1],
                        bone.mcPivot.z() + roZ + bone.animPos[2]);
            } else {
                BedrockModel.Bone pb = fi.bones[p];
                m.translate(bone.mcPivot.x() - pb.mcPivot.x() + bone.animPos[0],
                        bone.mcPivot.y() - pb.mcPivot.y() + bone.animPos[1],
                        bone.mcPivot.z() - pb.mcPivot.z() + bone.animPos[2]);
            }

            // 旋转：动画偏移 + 静止角（DebugUv 的 ax/ay/az 与顺序开关原样保留）
            float rx = DEBUG_ZERO_REST_ROT ? bone.animRot[0] : bone.restRotRad.x() + bone.animRot[0];
            float ry = DEBUG_ZERO_REST_ROT ? bone.animRot[1] : bone.restRotRad.y() + bone.animRot[1];
            float rz = DEBUG_ZERO_REST_ROT ? bone.animRot[2] : bone.restRotRad.z() + bone.animRot[2];
            rx = ui.modelysm.client.DebugUv.ax(rx);
            ry = ui.modelysm.client.DebugUv.ay(ry);
            rz = ui.modelysm.client.DebugUv.az(rz);
            if (xyz) {
                if (rx != 0) { m.rotateX(rx); nm.rotateX(rx); }
                if (ry != 0) { m.rotateY(ry); nm.rotateY(ry); }
                if (rz != 0) { m.rotateZ(rz); nm.rotateZ(rz); }
            } else {
                if (rz != 0) { m.rotateZ(rz); nm.rotateZ(rz); }
                if (ry != 0) { m.rotateY(ry); nm.rotateY(ry); }
                if (rx != 0) { m.rotateX(rx); nm.rotateX(rx); }
            }
            // 缩放只管位置，不影响法线（与 PoseStack.scale 同口径；某轴为 0 即该骨骼退化不可见）
            if (bone.animScale[0] != 1.0f || bone.animScale[1] != 1.0f || bone.animScale[2] != 1.0f) {
                m.scale(bone.animScale[0], bone.animScale[1], bone.animScale[2]);
            }

            // 本骨光照：发光骨骼覆盖为全亮（与递归路径 lightResolver 完全同语义）
            int boneLight = packedLight;
            if (fi.glowBone[i]) {
                boneLight = LightTexture.pack(15, 15);
            } else if (!glowMap.isEmpty()) {
                Integer g = glowMap.get(bone.name);
                if (g != null && g > 0) boneLight = LightTexture.pack(g, g);
            }

            // 本骨投影矩阵：整根骨骼只算一次（原来每个 cube 都 new 一份 proj×cubeMat）
            Matrix4f projBone = (cullOn && fi.cullableBone[i]) ? FLAT_PROJ_BONE.set(proj).mul(m) : null;

            if (gpu) {
                fi.lights[i] = boneLight;   // GPU 路径只攒光照，顶点提交交给显存里的 VBO
            } else {
                for (BedrockModel.Cube cube : bone.cubes) {
                    if (cube.bakedFaces != null) emitBakedCube(vc, m, nm, projBone, cube, boneLight);
                }
            }

            if (locators != null && fi.handLocator[i]) {
                locators.add(new LocatorPose(bone.name, new Matrix4f(m), new Matrix3f(nm)));
            }
        }
        if (fboPreview) {
            // 离屏：FBO 里画一次（画完 GL 状态已还原），贴图由界面调用 PreviewFboRenderer.blit 完成
            long g0 = System.nanoTime();
            boolean ok = PreviewFboRenderer.render(model, fi, texture, cullOn);
            PERF_GPUP_NANOS += System.nanoTime() - g0;
            if (ok) PERF_GPUP_OK++; else { PERF_GPUP_CPU++; emitBakedAll(vc, fi); }
        } else if (gpuWorld) {
            long g0 = System.nanoTime();
            boolean ok = WorldGpuRenderer.draw(model, fi, texture, cullOn, false, buffers);
            PERF_GPUW_NANOS += System.nanoTime() - g0;
            if (ok) PERF_GPUW_OK++; else PERF_GPUW_CPU++;
            // GPU 世界路径失败：同一帧把顶点补提交一遍，避免模型整帧消失（之后会自动回 CPU 路径）
            if (!ok) emitBakedAll(vc, fi);
        } else if (!previewMode) {
            PERF_GPUW_CPU++;   // 世界实体走了纯 CPU（主循环里已经逐 cube 提交）
        }
    }

    /** GPU 世界路径失败时的兜底提交：矩阵/光照已算好，这里只把顶点写进 VertexConsumer */
    private static void emitBakedAll(VertexConsumer vc, FlatInfo fi) {
        for (int i = 0; i < fi.bones.length; i++) {
            Matrix4f m = fi.mats[i];
            Matrix3f nm = fi.norms[i];
            for (BedrockModel.Cube cube : fi.bones[i].cubes) {
                if (cube.bakedFaces != null) emitBakedCube(vc, m, nm, null, cube, fi.lights[i]);
            }
        }
    }

    /** 烘焙立方体：逐面剔除 + 手算顶点提交（每面一次法线变换、每顶点一次位置变换，零临时对象） */
    private static void emitBakedCube(VertexConsumer vc, Matrix4f mat, Matrix3f norm, Matrix4f projBone,
                                      BedrockModel.Cube cube, int packedLight) {
        Matrix4f pm = (cube.cullable && projBone != null) ? projBone : null;
        // 预览期只保留法线 -Z 的面：零厚度薄片正反共面，后画的背面会把正面盖暗（官方同结果）
        float cdx = Math.abs(cube.max.x() - cube.min.x());
        float cdy = Math.abs(cube.max.y() - cube.min.y());
        float cdz = Math.abs(cube.max.z() - cube.min.z());
        boolean flatCube = cdx < 1e-3f || cdy < 1e-3f || cdz < 1e-3f;
        for (BedrockModel.BakedFace f : cube.bakedFaces) {
            if (pm != null && faceCulled(pm, f.pos)) continue;
            if (previewCullOff && flatCube && f.normal[2] > 0f) continue;
            quadBakedFast(vc, mat, norm, packedLight, f);
        }
    }

    /** 烘焙面片（扁平路径）：法线/UV 与 quadBaked 完全一致，只是把矩阵乘法搬到 Java 侧手算 */
    private static void quadBakedFast(VertexConsumer vc, Matrix4f mat, Matrix3f norm, int packedLight,
                                      BedrockModel.BakedFace f) {
        PERF_FLAT_FACES++;
        float nx, ny, nz;
        if (f.normal[0] != 0f || f.normal[1] != 0f || f.normal[2] != 0f) {
            nx = f.normal[0]; ny = f.normal[1]; nz = f.normal[2];
            float l0 = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l0 < 1e-6f) { nx = 0; ny = 1; nz = 0; l0 = 1; }
            nx /= l0; ny /= l0; nz /= l0;
        } else {
            float ax = f.pos[1][0] - f.pos[0][0], ay = f.pos[1][1] - f.pos[0][1], az = f.pos[1][2] - f.pos[0][2];
            float bx = f.pos[2][0] - f.pos[0][0], by = f.pos[2][1] - f.pos[0][1], bz = f.pos[2][2] - f.pos[0][2];
            nx = ay * bz - az * by; ny = az * bx - ax * bz; nz = ax * by - ay * bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6f) { nx = 0; ny = 1; nz = 0; len = 1; }
            nx /= len; ny /= len; nz /= len;
        }
        if (previewCullOff && !previewMirrorInModelView) nz = -nz;
        // 法线每面只随本骨矩阵变一次（原路径每顶点一次 Matrix3f.transform + Vector3f 分配）
        float tnx = norm.m00() * nx + norm.m10() * ny + norm.m20() * nz;
        float tny = norm.m01() * nx + norm.m11() * ny + norm.m21() * nz;
        float tnz = norm.m02() * nx + norm.m12() * ny + norm.m22() * nz;
        for (int i = 0; i < 4; i++) {
            float px = f.pos[i][0], py = f.pos[i][1], pz = f.pos[i][2];
            float x = mat.m00() * px + mat.m10() * py + mat.m20() * pz + mat.m30();
            float y = mat.m01() * px + mat.m11() * py + mat.m21() * pz + mat.m31();
            float z = mat.m02() * px + mat.m12() * py + mat.m22() * pz + mat.m32();
            vc.vertex(x, y, z);
            vc.color(1.0f, 1.0f, 1.0f, 1.0f);
            vc.uv(f.u[i], f.v[i]);
            vc.overlayCoords(OverlayTexture.NO_OVERLAY);
            vc.uv2(packedLight);
            vc.normal(tnx, tny, tnz);
            vc.endVertex();
        }
    }

    /** 子模型复用入口（ProjectileRenderHook 渲染弹射物几何） */
    public static void drawCubePublic(BedrockModel.Cube cube, PoseStack poseStack, VertexConsumer vc, int packedLight,
                                      BedrockModel model) {
        drawCube(cube, poseStack, vc, packedLight, model);
    }

    /** 绘制单个立方体（含可选自旋转、膨胀、vanilla 盒式UV布局） */
    private static void drawCube(BedrockModel.Cube cube, PoseStack poseStack, VertexConsumer vc, int packedLight, BedrockModel model) {
        // 烘焙面片路径（.ysm 直读）：顶点/UV 已就绪，无自旋转/膨胀
        if (cube.bakedFaces != null) {
            PoseStack.Pose pose = poseStack.last();
            Matrix4f mat = pose.pose();
            Matrix3f norm = pose.normal();
            // 官方 NativeModelRenderer:156-164 对 cullable 立方体逐面剔除。
            // 之前这条烘焙路径完全没做剔除，六个面全画：零厚度薄片（酒狐 Background1 蓝框、
            // 发丝片）正反共面各画一遍，后画的背面（法线 +Z，inventory 光只有 0.4）盖住正面
            // → 卡片里就是"蓝框发暗"，而实体方块的人物不受影响。
            Matrix4f projMV = cube.cullable ? buildProjMV(mat) : null;
            // 预览（卡片/大预览）里我们的镜像放在 PoseStack 上，投影行列式的符号会被镜像整体翻过来，
            // 逐面剔除会剔错面（实机表现为整张卡空白、大预览变成"透视/半透明"）。所以预览期不做行列式剔除，
            // 只对零厚度薄片做"单面"处理：薄片正反两面在屏幕上完全共面，后画的背面会盖掉正面。
            float cdx = Math.abs(cube.max.x() - cube.min.x());
            float cdy = Math.abs(cube.max.y() - cube.min.y());
            float cdz = Math.abs(cube.max.z() - cube.min.z());
            boolean flatCube = cdx < 1e-3f || cdy < 1e-3f || cdz < 1e-3f;
            for (BedrockModel.BakedFace f : cube.bakedFaces) {
                if (projMV != null && faceCulled(projMV, f.pos)) continue;
                // 官方卡片剔掉背面后，薄片只剩朝前那一面；这里按同一结果只保留法线 -Z 的面
                if (previewCullOff && flatCube && f.normal[2] > 0f) continue;
                quadBaked(vc, mat, norm, packedLight, f);
            }
            return;
        }
        poseStack.pushPose();
        try {
            if (cube.rotAnglesRad != null && !DEBUG_NO_CUBE_ROT) {
                poseStack.translate(cube.rotPivot.x(), cube.rotPivot.y(), cube.rotPivot.z());
                // 调试档 F7 可切换旋转约定（默认 = 线上行为：Z→Y→X）
                float crx = ui.modelysm.client.DebugUv.ax(cube.rotAnglesRad.x());
                float cry = ui.modelysm.client.DebugUv.ay(cube.rotAnglesRad.y());
                float crz = ui.modelysm.client.DebugUv.az(cube.rotAnglesRad.z());
                if (ui.modelysm.client.DebugUv.orderXYZ()) {
                    if (crx != 0) poseStack.mulPose(Axis.XP.rotation(crx));
                    if (cry != 0) poseStack.mulPose(Axis.YP.rotation(cry));
                    if (crz != 0) poseStack.mulPose(Axis.ZP.rotation(crz));
                } else {
                    if (crz != 0) poseStack.mulPose(Axis.ZP.rotation(crz));
                    if (cry != 0) poseStack.mulPose(Axis.YP.rotation(cry));
                    if (crx != 0) poseStack.mulPose(Axis.XP.rotation(crx));
                }
                poseStack.translate(-cube.rotPivot.x(), -cube.rotPivot.y(), -cube.rotPivot.z());
            }

            PoseStack.Pose pose = poseStack.last();
            Matrix4f mat = pose.pose();
            Matrix3f norm = pose.normal();

            float inf = cube.inflate / 16f;
            float minX = cube.min.x() - inf, maxX = cube.max.x() + inf;
            float minY = cube.min.y() - inf, maxY = cube.max.y() + inf;
            float minZ = cube.min.z() - inf, maxZ = cube.max.z() + inf;

            // ---- 按面UV渲染（顶点顺序 1:1 对齐 GeckoLib BakedModelFactory.VertexSet）----
            if (cube.faceUVs != null) {
                float tw = model.textureWidth, th = model.textureHeight;
                Matrix4f projMV = cube.cullable ? buildProjMV(mat) : null;
                // 面索引：0=WEST,1=EAST,2=NORTH,3=SOUTH,4=UP,5=DOWN
                float[][][] faceCorners = {
                        {{minX, maxY, maxZ}, {minX, maxY, minZ}, {minX, minY, minZ}, {minX, minY, maxZ}}, // WEST
                        {{maxX, maxY, minZ}, {maxX, maxY, maxZ}, {maxX, minY, maxZ}, {maxX, minY, minZ}}, // EAST
                        {{minX, maxY, minZ}, {maxX, maxY, minZ}, {maxX, minY, minZ}, {minX, minY, minZ}}, // NORTH
                        {{maxX, maxY, maxZ}, {minX, maxY, maxZ}, {minX, minY, maxZ}, {maxX, minY, maxZ}}, // SOUTH
                        {{minX, maxY, maxZ}, {maxX, maxY, maxZ}, {maxX, maxY, minZ}, {minX, maxY, minZ}}, // UP
                        {{minX, minY, minZ}, {maxX, minY, minZ}, {maxX, minY, maxZ}, {minX, minY, maxZ}}, // DOWN
                };
                for (int f = 0; f < 6; f++) {
                    // 官方 bakeFaceToRaw 的 mirror 语义（YSMFolderDeserializer:594-599 / 616-621）：
                    //   mirror=true  时 East 面取 "west" 的 UV 岛、West 面取 "east" 的 UV 岛，且【不】左右翻 u；
                    //   mirror=false 时各用自己的岛、并左右翻 u。
                    // 也就是"镜像块"是直接把对称的那块贴图贴过来，而不是把同一块贴图翻过来画。
                    // 我们之前对按面UV完全不认 mirror，镜像块（酒狐的 LongRightHair 等全是 mirror=true）
                    // 就画成了"左右对称翻转"的错纹理——实机表现为左侧头发与官方呈镜像。
                    int island = f;
                    if (cube.mirror) {
                        if (f == 0) island = 1;       // WEST 取 EAST 岛
                        else if (f == 1) island = 0;  // EAST 取 WEST 岛
                    }
                    float[] rect = cube.faceUVs[island];
                    if (rect == null) rect = cube.faceUVs[f];
                    if (rect == null) continue; // 缺失面跳过（GeckoLib 同语义）
                    // 官方 NativeModelRenderer:156-164：cullable 的立方体按投影后三顶点的
                    // 行列式符号剔除背面（零厚度薄片否则正反共面画两遍 → 头发噪点/闪频）
                    if (cube.cullable && faceCulled(projMV, faceCorners[f])) continue;
                    quad(vc, mat, norm, packedLight, faceCorners[f], rect[0], rect[1], rect[2], rect[3], tw, th, cube.mirror, FACE_NORMALS[f]);
                }
                return;
            }

            // ---- 盒式UV渲染（vanilla 布局）----
            // 关键：vanilla 的 UV 划分点以"贴图像素"为单位（vanilla 模型坐标单位=像素），
            // 而 min/max 已换算成方块单位（÷16），必须 ×16 还原成像素跨度，
            // 否则 UV 跨度差 16 倍 → 整体贴图错乱（此前 box-UV 模型全乱的根本原因）
            // 官方 OpenYSM YSMFolderDeserializer:604-606 的 Box UV 尺寸口径：
            //     float dx = floor(size[0]); dy = floor(size[1]); dz = floor(size[2]);
            // 关键点有两个，之前都错了：
            //  1) 用的是【未膨胀】的原始 size（不是 cw/ch/cd）——我们的 d/w/h 是拿
            //     已经过 inflate 的 maxZ-minZ 算的。嘴部/眼睛/瞳孔这些 cube 常带
            //     inflate=-0.5（向内缩 1/16 格），算出来的 d/w/h 会小一圈，
            //     于是整个 UV 岛往左上错位，小嘴那 1~2 像素的岛就采到隔壁空白像素 →
            //     实机表现"没有小嘴"（酒狐 Mouth_smile2 尺寸 1.2×1.125×2 尤其明显：
            //     官方 dz=2，我们算成 1，前脸整体偏了 1 像素）。
            //  2) floor 允许为 0（官方不 clamp），0 会把整面退化成"取单像素纯色"——
            //     YSM 的点状瞳孔/小嘴就是靠这个实现的。
            float rawW = (cube.max.x() - cube.min.x()) * 16f;
            float rawH = (cube.max.y() - cube.min.y()) * 16f;
            float rawD = (cube.max.z() - cube.min.z()) * 16f;
            float d = rawD, w = rawW, h = rawH;
            // 调试档 F8：岛边界是否吸附整数像素（基岩文档：Box UV 只取整数并向下取整）
            if (ui.modelysm.client.DebugUv.uvMode == 1) {
                d = Math.max(0f, (float) Math.floor(rawD));
                w = Math.max(0f, (float) Math.floor(rawW));
                h = Math.max(0f, (float) Math.floor(rawH));
            }
            float u = cube.u0, v = cube.v0;
            float tw = model.textureWidth, th = model.textureHeight;

            // vanilla 布局的 UV 分割点（f4..f9, f10..f12 同名对应）
            float f4 = u, f5 = u + d, f6 = u + d + w, f7 = u + d + w + w, f8 = u + d + w + d, f9 = u + d + w + d + w;
            float f10 = v, f11 = v + d, f12 = v + d + h;

            // 顶点顺序 1:1 复刻 vanilla ModelPart.Cube：
            // v7=(mx,my,mz) v=(Mx,my,mz) v1=(Mx,My,mz) v2=(mx,My,mz)
            // v3=(mx,my,Mz) v4=(Mx,my,Mz) v5=(Mx,My,Mz) v6=(mx,My,Mz)
            float[] v7 = {minX, minY, minZ};
            float[] vq = {maxX, minY, minZ};
            float[] v1 = {maxX, maxY, minZ};
            float[] v2 = {minX, maxY, minZ};
            float[] v3 = {minX, minY, maxZ};
            float[] v4 = {maxX, minY, maxZ};
            float[] v5 = {maxX, maxY, maxZ};
            float[] v6 = {minX, maxY, maxZ};

            // 六个面的顶点顺序（绕向统一朝外，与 vanilla ModelPart 一致；镜像只改 UV 不改顺序）
            float[][] DOWN  = {v4, v3, v7, vq};
            float[][] UP    = {v1, v2, v6, v5};
            float[][] WEST  = {v7, v3, v6, v2};
            float[][] NORTH = {vq, v7, v2, v1};
            float[][] EAST  = {v4, vq, v1, v5};
            float[][] SOUTH = {v3, v4, v5, v6};

            // 镜像（vanilla 语义）：只在 UV 上左右镜像，顶点顺序保持不变（否则绕向朝内会被剔除）
            boolean mir = cube.mirror;
            // UP / DOWN 的贴图格子（Bedrock 盒式布局，与本模型一致）：
            //   几何顶面(UP, maxY)   = (u+d,   v) ~ (u+d+w,   v+d)
            //   几何底面(DOWN, minY) = (u+d+w, v) ~ (u+d+2w,  v+d)
            // 底面 DOWN 的 V 方向必须与官方一致：官方 YSMFolderDeserializer:614 给 down 的
            // uv_size 是 (dx, -dz)，即 v0 = v+dz（对应 z1 那条边）、v1 = v（对应 z2），
            // 而我们 DOWN 的顶点顺序是 z1,z1,z2,z2 → V0 必须是 f11（v+d）、V1 是 f10（v）。
            // 之前默认档是反着的（v0=f10），底面岛被上下颠倒，实机表现为头发/裙摆这类
            // "从上方看到平铺底面"的薄片纹理上下反了。
            float dv0 = f11;
            float dv1 = f10;
            Matrix4f projMV = cube.cullable ? buildProjMV(mat) : null;
            if (!(cube.cullable && faceCulled(projMV, DOWN)))
                quad(vc, mat, norm, packedLight, DOWN, f6, dv0, f7, dv1, tw, th, mir, FACE_NORMALS[5]);    // DOWN
            if (!(cube.cullable && faceCulled(projMV, UP)))
                quad(vc, mat, norm, packedLight, UP, f5, f10, f6, f11, tw, th, mir, FACE_NORMALS[4]);      // UP
            // 侧面横排的槽位顺序按 Mojang 官方 ModelGeometryUtilities / Blockbench：
            //   | East | North | West | South |   （宽度依次 d, w, d, w；总计 2d+2w）
            // 之前用的是 Java ModelPart 的老序（West 在 0、East 在 d+w），会把左右两面的
            // 贴图岛互换，非对称件（斜背带、侧袋、裙摆侧片）就会串到隔壁的白/黑/空像素上。
            // 官方 mirror 语义（YSMFolderDeserializer:618-619）：mirror 的 cube 里
            //   East 面用 "west" 岛、West 面用 "east" 岛，且不做 u 翻转（quad 的 mirror 参数传 mir）。
            // 之前我们让镜像块继续用自己的岛再翻 u，等于"把同一块贴图照镜子画一遍"，
            // 酒狐的 LongRightHair / MLongRightHair 这类 mirror=true 的发束就成了左右对称样式。
            float eU0 = mir ? f6 : f4, eU1 = mir ? f8 : f5;
            float wU0 = mir ? f4 : f6, wU1 = mir ? f5 : f8;
            if (!(cube.cullable && faceCulled(projMV, EAST)))
                quad(vc, mat, norm, packedLight, EAST, eU0, f11, eU1, f12, tw, th, mir, FACE_NORMALS[1]);    // EAST  (u .. u+d)
            if (!(cube.cullable && faceCulled(projMV, NORTH)))
                quad(vc, mat, norm, packedLight, NORTH, f5, f11, f6, f12, tw, th, mir, FACE_NORMALS[2]);   // NORTH (u+d .. u+d+w)
            if (!(cube.cullable && faceCulled(projMV, WEST)))
                quad(vc, mat, norm, packedLight, WEST, wU0, f11, wU1, f12, tw, th, mir, FACE_NORMALS[0]);    // WEST  (u+d+w .. u+d+w+d)
            if (!(cube.cullable && faceCulled(projMV, SOUTH)))
                quad(vc, mat, norm, packedLight, SOUTH, f8, f11, f9, f12, tw, th, mir, FACE_NORMALS[3]);   // SOUTH (u+d+w+d .. +w)
        } finally {
            poseStack.popPose();
        }
    }

    /** 卡片/大预览里 GUI 用了 scale(1,1,-1) 镜像，行列式符号会翻转（会把正面当背面剔除、脸变成后脑）
     *  ——预览期关掉逐面背面剔除，世界/手持/弹射物不受影响。 */
    public static boolean previewCullOff = false;

    /**
     * 官方 NativeModelRenderer:146 的 projBoneMat：{@code projMat × globalBoneMat}，
     * 其中 {@code projMat = RenderSystem.getProjectionMatrix()}，globalBoneMat 是 pose 累计矩阵。
     * <b>不含</b> ModelViewMatrix —— 这是官方与我们的关键差别：
     * 我们之前多乘了一层 modelview（相机/世界变换），行列式符号会随相机朝向改变，
     * 于是一开逐面剔除就"后脑勺漏面、发丝消失"，只能整体关掉剔除；
     * 关掉之后零厚度薄片（酒狐自带蓝框）正反两面共面各画一遍，后画的背面把前面盖住 → 蓝框发暗。
     */
    private static Matrix4f buildProjMV(Matrix4f cubeMat) {
        return new Matrix4f(RenderSystem.getProjectionMatrix()).mul(cubeMat);
    }

    /**
     * 逐面背面剔除开关（官方 NativeModelRenderer:156-164 的 cullable 立方体判定）。
     * 官方在<b>世界和 GUI 预览里都做</b>这项剔除；我们之前因为 buildProjMV 多乘了 modelview
     * 导致符号不稳而整体关掉，代价是零厚度薄片正反共面重复绘制（蓝框被背面盖暗、头发闪频）。
     */
    private static final boolean ENABLE_CUBE_CULLING = true;

    /**
     * 官方 NativeModelRenderer:156-164 的背面剔除：把面片前三个顶点送到裁剪空间，
     * 用 (x,y,w) 求行列式，det <= 0 即该面朝里，跳过。
     *
     * 卡片/大预览里我们的镜像放在 PoseStack（官方放在 ModelViewStack），镜像会把绕向整体翻一次，
     * 所以预览期把比较方向反过来 —— 结果仍然"只保留官方会保留的那些面"。
     */
    private static boolean faceCulled(Matrix4f projMV, float[][] corners) {
        if (!ENABLE_CUBE_CULLING || projMV == null) return false;
        return faceCulledDet(projMV, corners[0], corners[1], corners[2]);
    }

    /** 大预览：镜像 scale(1,1,-1) 已经放进 ModelViewStack（官方 ModelPreviewRenderer 口径），
     *  此时法线只需乘 pose.normal()、不能再补 nz；卡片仍是老路径，保留 nz 补偿。 */
    public static boolean previewMirrorInModelView = false;

    private static boolean faceCulledDet(Matrix4f projMV, float[] a, float[] b, float[] c) {
        // 复用 scratch：早先是每面 3 个 new Vector4f，高面数模型每帧上万次分配
        CULL_V1.set(a[0], a[1], a[2], 1f).mul(projMV);
        CULL_V2.set(b[0], b[1], b[2], 1f).mul(projMV);
        CULL_V3.set(c[0], c[1], c[2], 1f).mul(projMV);
        float p1x = CULL_V1.x, p1y = CULL_V1.y, p1w = CULL_V1.w;
        float p2x = CULL_V2.x, p2y = CULL_V2.y, p2w = CULL_V2.w;
        float p3x = CULL_V3.x, p3y = CULL_V3.y, p3w = CULL_V3.w;
        float det = p1x * (p2y * p3w - p3y * p2w)
                - p2x * (p1y * p3w - p3y * p1w)
                + p3x * (p1y * p2w - p2y * p1w);
        return previewCullOff ? false : (det <= 0f);
    }

    /** 烘焙面片绘制：四顶点各自 UV；法线优先用文件显式法线（官方同口径），缺省才叉积 */
    private static void quadBaked(VertexConsumer vc, Matrix4f mat, Matrix3f norm, int packedLight, BedrockModel.BakedFace f) {
        PERF_REC_FACES++;
        float nx, ny, nz;
        if (f.normal[0] != 0f || f.normal[1] != 0f || f.normal[2] != 0f) {
            nx = f.normal[0]; ny = f.normal[1]; nz = f.normal[2];
            float l0 = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l0 < 1e-6f) { nx = 0; ny = 1; nz = 0; l0 = 1; }
            nx /= l0; ny /= l0; nz /= l0;
        } else {
            float ax = f.pos[1][0] - f.pos[0][0], ay = f.pos[1][1] - f.pos[0][1], az = f.pos[1][2] - f.pos[0][2];
            float bx = f.pos[2][0] - f.pos[0][0], by = f.pos[2][1] - f.pos[0][1], bz = f.pos[2][2] - f.pos[0][2];
            nx = ay * bz - az * by; ny = az * bx - ax * bz; nz = ax * by - ay * bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6f) { nx = 0; ny = 1; nz = 0; len = 1; }
            nx /= len; ny /= len; nz /= len;
        }
        // 卡片/大预览两种镜像口径：
        //  · 大预览（previewMirrorInModelView=true）：镜像在 ModelViewStack，pose.normal 是纯旋转，
        //    法线直接按官方 native 路径用，不再做任何补偿（官方 ModelPreviewRenderer:264-268 同款）。
        //  · 卡片（老路径，-z 缩放还在 PoseStack 里）：pose.normal() 会额外翻一次 z，
        //    这里只补 nz 让正对镜头的面回到受光状态（历史上修掉过"卡片蓝框一片暗"）。
        if (previewCullOff && !previewMirrorInModelView) nz = -nz;
        for (int i = 0; i < 4; i++) {
            vc.vertex(mat, f.pos[i][0], f.pos[i][1], f.pos[i][2]);
            vc.color(1.0f, 1.0f, 1.0f, 1.0f);
            vc.uv(f.u[i], f.v[i]);
            vc.overlayCoords(OverlayTexture.NO_OVERLAY);
            vc.uv2(packedLight);
            vc.normal(norm, nx, ny, nz);
            vc.endVertex();
        }
    }

    /** 面种类 → 朝外法线（与官方 YSMFolderDeserializer:594-599 的 rawNormal 完全一致） */
    private static final float[][] FACE_NORMALS = {
            {-1f, 0f, 0f}, {1f, 0f, 0f}, {0f, 0f, -1f}, {0f, 0f, 1f}, {0f, 1f, 0f}, {0f, -1f, 0f}
    };

    /** 面片：uvRect=(u0,v0,u1,v1)；顶点UV分配与 vanilla Polygon 一致：
     *  v0=(u1,v0) v1=(u0,v0) v2=(u0,v1) v3=(u1,v1) */
    private static void quad(VertexConsumer vc, Matrix4f mat, Matrix3f norm, int packedLight,
                             float[][] corners, float u0, float v0, float u1, float v1, float tw, float th,
                             boolean mirror, float[] faceNormal) {
        // v 归一化：JSON 几何的 uv 是"图像左上角"像素坐标，直接除纹理尺寸即可
        // （实测：加 1-v 翻转会让整只模型贴图错位，见离线渲染对照）
        float U0 = u0 / tw, U1 = u1 / tw, V0 = v0 / th, V1 = v1 / th;
        // mirror 语义与 vanilla ModelPart.Polygon 一致：只把 u 左右镜像（交换 u0/u1），
        // 顶点顺序不变。之前用"反转顶点顺序"实现镜像，会把面绕向翻成朝内 → 被剔除判断吃掉，
        // 实机表现为整块面消失（右眼、左臂、右腿上的洞）。
        float[] us = mirror ? new float[]{U0, U1, U1, U0} : new float[]{U1, U0, U0, U1};
        float[] vs = {V0, V0, V1, V1};
        // 侧面/正背面：v 必须按"顶点高度"分配，而不是按顶点序号。
        // 之前按序号分配，凡是第一个顶点在底部的面（如 NORTH 正面）整块矩形被上下倒置，
        // 头的正面就取到了发际线那两行深色像素 → 实机表现为"黑下巴/脸上深色横带"。
        float ymin = Math.min(Math.min(corners[0][1], corners[1][1]), Math.min(corners[2][1], corners[3][1]));
        float ymax = Math.max(Math.max(corners[0][1], corners[1][1]), Math.max(corners[2][1], corners[3][1]));
        if (ymax - ymin > 1e-5f) {
            for (int i = 0; i < 4; i++) vs[i] = (corners[i][1] >= ymax - 1e-5f) ? V0 : V1;
        }
        float ax = corners[1][0] - corners[0][0], ay = corners[1][1] - corners[0][1], az = corners[1][2] - corners[0][2];
        float bx = corners[2][0] - corners[0][0], by = corners[2][1] - corners[0][1], bz = corners[2][2] - corners[0][2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        if (faceNormal != null) {
            // 官方口径：法线来自"面种类"而不是顶点绕向。绕向被脏数据/负 inflate 翻过来时，
            // 叉积法线会整面反过来 → 卡片和世界里就是"同一只模型，某些块莫名发暗"。
            nx = faceNormal[0]; ny = faceNormal[1]; nz = faceNormal[2];
        } else {
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6f) { nx = 0; ny = 1; nz = 0; len = 1; }
            nx /= len; ny /= len; nz /= len;
        }
        // 预览期同 quadBaked：官方镜像只在 ModelViewMat，法线不跟着翻 Z
        if (previewCullOff && !previewMirrorInModelView) nz = -nz;

        for (int i = 0; i < 4; i++) {
            vc.vertex(mat, corners[i][0], corners[i][1], corners[i][2]);
            vc.color(1.0f, 1.0f, 1.0f, 1.0f);   // entity 格式必需：缺颜色元素会抛 Not filled all elements of the vertex
            vc.uv(us[i], vs[i]);
            vc.overlayCoords(OverlayTexture.NO_OVERLAY);
            vc.uv2(packedLight);
            vc.normal(norm, nx, ny, nz);
            vc.endVertex();
        }
    }
}
