package ui.modelysm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import ui.modelysm.client.render.BedrockAnimation;
import ui.modelysm.client.render.NpcRenderer;

import java.util.Map;

/**
 * EntityRenderDispatcher.render HEAD 钩子（mixin 调入）。
 * 返回 true 表示已自行渲染该实体（调用方取消原版渲染）。
 */
public final class NpcRenderHook {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ModelYSM");

    private NpcRenderHook() {}

    public static boolean hook(LivingEntity entity, double x, double y, double z, float bodyYaw, float partialTicks,
                               PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        String modelId = ClientState.bindingFor(entity.getUUID());
        if (modelId == null) return false;
        ModelLibrary.LoadedModel loaded = ModelLibrary.get(modelId);
        if (loaded == null || loaded.defaultTexture() == null) return false;

        // 关键：HEAD 注入时原版尚未执行 poseStack.translate(x,y,z)（相机相对坐标），
        // 必须自行平移到实体位置，否则模型会画在相机原点（糊脸）
        poseStack.translate(x, y, z);

        // ---- 动画状态更新 ----
        AnimStates.State st = AnimStates.get(entity.getUUID());
        long now = System.nanoTime();
        float dt = (now - st.lastFrameNanos) / 1.0e9f;
        st.lastFrameNanos = now;
        if (dt <= 0 || dt > 0.5f) dt = 0.05f;
        st.lastDt = dt;
        // 弹簧物理（ysm.second_order/first_order）每帧推进，先于动画采样
        ui.modelysm.client.molang.Physics.update(entity.getUUID(), dt);

        double dx = entity.getX() - st.lastX;
        double dy = entity.getY() - st.lastY;
        double dz = entity.getZ() - st.lastZ;
        if (st.hasLastPos) {
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double inst = dist / dt;
            st.speedPerSec = (float) (st.speedPerSec * 0.7 + inst * 0.3);
        }
        st.lastX = entity.getX();
        st.lastY = entity.getY();
        st.lastZ = entity.getZ();
        st.hasLastPos = true;
        // 跑步迟滞（官方 run=isSprinting；NPC 多数生物不置位 → 速度阈值近似：
        // 进入 >5.5 格/秒，退出 <3.0，防阈值附近反复横跳）
        st.running = st.speedPerSec > (st.running ? 3.0 : 5.5);

        // ---- 受击检测（hurtTime 0→正 的上升沿触发 attacked 动画）----
        if (entity.hurtTime > 0 && !st.wasHurt) {
            st.hurtTimer = 0.5f;
        }
        st.wasHurt = entity.hurtTime > 0;
        if (st.hurtTimer > 0) st.hurtTimer = Math.max(0, st.hurtTimer - dt);

        // ---- 选动画（官方行为绑定：检测 NPC 此刻行为，对照官方状态表一一绑定） ----
        ui.modelysm.common.EntitySettings es = ClientState.settingsFor(entity.getUUID());
        String forced = es != null ? es.anim : null;
        // 官方"额外动画"（properties.extra_animation 里的 extra0/h0/…）是一次性手势：
        // 播完自动回状态机，避免永久冻在末帧；再次点击由 GUI 调 restartAnimation() 重播。
        if (forced != null && forced.equals(st.consumedExtra)) forced = null;
        String target;
        if (forced != null && loaded.animations.containsKey(forced)) {
            target = forced;   // GUI 强制动画优先
        } else {
            target = pickAnimation(entity, loaded, st, partialTicks);
        }
        // 进入一次性动画或刚切换强制动画时重置动画时间；
        // 走/跑/待机等循环动画切换时保持时间连续，避免姿态从第0帧跳变（剧烈抖动的根源之一）
        boolean animSwitch = !java.util.Objects.equals(target, st.currentAnim);
        boolean forcedSwitch = forced != null && target.equals(forced) && animSwitch;
        // 官方 0.1s 过渡：main 状态层切换时记录旧动画快照（冻结在切换时刻采样），
        // 渲染端新旧姿态线性混合。一次性动画（受击/挥剑/死亡）不参与过渡——瞬时反应
        // 混 0.1s 旧姿态反而拖沓；从无动画状态（首次渲染）切入也不需要。
        boolean oneShotEnter = target != null && ONE_SHOT_ANIMS.contains(target);
        if (animSwitch && st.currentAnim != null && target != null && !oneShotEnter
                && loaded.animations.containsKey(st.currentAnim)) {
            st.prevAnim = st.currentAnim;
            st.prevAnimTime = st.animTime;
            st.transLeft = TRANSITION_SEC;
        }
        if (animSwitch && target != null && (forcedSwitch || ONE_SHOT_ANIMS.contains(target))) {
            st.animTime = 0;
        }
        st.currentAnim = target;
        st.animTime += dt;
        if (st.transLeft > 0) st.transLeft = Math.max(0, st.transLeft - dt);

        // 额外动画播完 → 标记"已消费"，下一帧回落到自动状态（timeline 脚本已生效）
        if (forced != null && target != null && target.equals(forced)
                && st.consumedExtra == null && loaded.extraAnimations.containsKey(forced)) {
            BedrockAnimation fa = loaded.animations.get(forced);
            if (fa != null && !fa.loop && fa.length > 0 && st.animTime > fa.length) {
                st.consumedExtra = forced;
            }
        }

        // 选不到动画（模型没带可用动画名）时 anim 为 null → NpcRenderer 静态渲染，不崩
        BedrockAnimation anim = st.currentAnim == null ? null : loaded.animations.get(st.currentAnim);
        // 过渡采样：旧动画 + 过渡进度（0=旧姿态 1=新姿态）
        BedrockAnimation blendFrom = null;
        float blendFromTime = 0f, blendAlpha = 1f;
        if (st.transLeft > 0 && st.prevAnim != null) {
            BedrockAnimation prev = loaded.animations.get(st.prevAnim);
            if (prev != null && anim != null) {
                blendFrom = prev;
                blendFromTime = st.prevAnimTime;
                blendAlpha = 1f - st.transLeft / TRANSITION_SEC;
            } else {
                st.transLeft = 0; // 旧动画已不在（模型重载等）→ 直接放弃过渡
            }
        }

        // ---- animation_controllers 状态机推进（条件动画层）----
        java.util.List<ui.modelysm.client.anim.ModelControllers.ActiveLayer> ctrlLayers =
                loaded.controllers.isEmpty() ? java.util.List.of() : updateControllers(entity, loaded, st, es, dt);
        // ---- 官方行为 overlay 层（hold/use/swing/passenger，官方内置控制器语义）----
        // 官方层序：… main → hold_offhand → hold_mainhand → swing → use → passenger …
        // 用伪 ActiveLayer 承载（渲染端 applyAnimLayers 无需感知）
        if (ctrlLayers.isEmpty()) ctrlLayers = new java.util.ArrayList<>();
        else ctrlLayers = new java.util.ArrayList<>(ctrlLayers);
        appendBehaviorLayers(entity, loaded, st, ctrlLayers);

        // ---- 角度 ----
        float lerpedBody = Mth.rotLerp(partialTicks, entity.yBodyRotO, entity.yBodyRot);
        float lerpedHead = Mth.rotLerp(partialTicks, entity.yHeadRotO, entity.yHeadRot);
        float netHeadYaw = Mth.wrapDegrees(lerpedHead - lerpedBody);
        float headPitch = Mth.lerp(partialTicks, entity.xRotO, entity.getXRot());
        // 生物 AI（尤其村民）会长时间低头看地面，原样套用到动漫大头上观感极差；
        // 非玩家实体限制头部俯仰范围
        if (!(entity instanceof net.minecraft.client.player.AbstractClientPlayer)) {
            headPitch = Mth.clamp(headPitch, -25f, 25f);
        }

        NpcRenderer.render(entity, loaded, anim, lerpedBody, netHeadYaw, headPitch, partialTicks, poseStack, buffers, packedLight,
                es != null ? es.scale : 1.0f,
                es != null ? es.hiddenBones : java.util.Set.of(),
                es != null ? es.forcedPart : null, 0f, 0f, 0f, false,
                blendFrom, blendFromTime, blendAlpha, ctrlLayers);
        return true;
    }

    /** 官方 main 状态层切换过渡时长（秒） */
    private static final float TRANSITION_SEC = 0.1f;

    /** 该实体是否会被我们真正渲染（有绑定 + 模型 + 默认贴图）。 */
    public static boolean canRender(LivingEntity entity) {
        String modelId = ClientState.bindingFor(entity.getUUID());
        if (modelId == null) return false;
        ModelLibrary.LoadedModel loaded = ModelLibrary.get(modelId);
        return loaded != null && loaded.defaultTexture() != null;
    }

    /**
     * 视锥判定（官方 YSM 不关剔除，我们只把剔除盒放大到模型真实尺寸）。
     * 返回 null = 不干预，交回原版逻辑。
     *
     * 为什么不能无条件放行：那样"相机背后/画面外"的实体也会被提交，
     * 顶点落到近平面后面会被 GPU 裁出一大块翻面的畸形模型 —— 随视角俯仰忽隐忽现。
     * 也不能对我们<b>不画</b>的实体介入，否则等于替官方 YSM 的渲染器关掉剔除。
     */
    public static Boolean frustumDecision(LivingEntity entity, net.minecraft.client.renderer.culling.Frustum frustum) {
        String modelId = ClientState.bindingFor(entity.getUUID());
        if (modelId == null) return null;
        ModelLibrary.LoadedModel loaded = ModelLibrary.get(modelId);
        if (loaded == null || loaded.defaultTexture() == null) return null;
        // 模型包围盒（.ysm 读到的真实尺寸；文件夹模型用保守值兜底）
        float half = Math.max(2.0f, loaded.modelWidth * 0.5f);
        float up = Math.max(3.0f, loaded.modelHeight + 1.0f);
        net.minecraft.world.phys.AABB bb = entity.getBoundingBox();
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                bb.minX - half, bb.minY - 0.5, bb.minZ - half,
                bb.maxX + half, bb.minY + up, bb.maxZ + half);
        return frustum.isVisible(box);
    }

    /**
     * 推进全部 controller 状态机并产出激活层。
     * player.pre_parallel_N / player.parallel_N 跳过（静态 pre_parallelN/parallelN 层已等价应用）。
     */
    private static java.util.List<ui.modelysm.client.anim.ModelControllers.ActiveLayer> updateControllers(
            LivingEntity entity, ModelLibrary.LoadedModel loaded, AnimStates.State st,
            ui.modelysm.common.EntitySettings es, float dt) {
        // 实体快照
        ui.modelysm.client.anim.ModelControllers.EvalCtx ctx = new ui.modelysm.client.anim.ModelControllers.EvalCtx();
        ctx.entity = entity;
        ctx.speedPerSec = st.speedPerSec;
        ctx.verticalSpeedPerSec = (float) (20.0 * (entity.getY() - entity.yo));
        // 官方模型变量表 = 每实体持久表（timeline 脚本与 controller 共享同一张表，
        // 否则酒狐 idle 的 "v.next_idle=math.random_integer(0,4)" 传不到 controller）
        ctx.molVars = st.vars;
        if (es != null && !es.molVars.isEmpty()) {
            for (java.util.Map.Entry<String, Float> mv : es.molVars.entrySet()) {
                st.vars.put(mv.getKey().toLowerCase(java.util.Locale.ROOT), mv.getValue());
            }
        }
        ctx.selectedControl = ui.modelysm.client.anim.ModelControllers.selectedControl(entity, st.speedPerSec);

        java.util.List<ui.modelysm.client.anim.ModelControllers.ActiveLayer> out = new java.util.ArrayList<>();
        for (var e : loaded.controllers.entrySet()) {
            String key = e.getKey();
            String low = key.toLowerCase();
            if (low.startsWith("player.pre_parallel") || low.startsWith("player.parallel")
                    || low.startsWith("parallel") || low.startsWith("pre_parallel")) continue;
            ui.modelysm.client.anim.ModelControllers.Ctrl c = e.getValue();
            if (c.states.isEmpty()) continue;
            String cur = st.ctrlState.computeIfAbsent(key, k -> c.states.containsKey(c.initial) ? c.initial : c.states.keySet().iterator().next());
            ui.modelysm.client.anim.ModelControllers.CState state = c.states.get(cur);
            if (state == null) { st.ctrlState.put(key, c.initial); continue; }

            // 状态动画完成度（q.all/any_animation_finished）
            // 官方 QueryBinding: all_animations_finished = PlaybackFlags::isPaused
            // → 循环动画永远不算"播完"；本状态无动画时官方 orElse(false)，也是 false。
            // 早前写成 "!hasNonLoop || ..."，把 new_idle_1（loop=true）当成立刻播完，
            // 一进 idle1 就被弹回 default，酒狐的 4 个待机变体等于永远看不到。
            float maxLen = 0f, minLen = Float.MAX_VALUE;
            boolean hasNonLoop = false;
            boolean allNonLoop = true;
            java.util.List<BedrockAnimation> stateAnims = new java.util.ArrayList<>();
            for (String an : state.animations) {
                BedrockAnimation ba = loaded.animations.get(an);
                if (ba == null) continue;
                stateAnims.add(ba);
                if (ba.loop) {
                    allNonLoop = false;
                } else {
                    hasNonLoop = true;
                    maxLen = Math.max(maxLen, ba.length);
                    minLen = Math.min(minLen, ba.length);
                }
            }
            float stateTime = st.ctrlTime.getOrDefault(key, 0f);
            ctx.stateAnims = stateAnims;
            ctx.stateTime = stateTime;
            ctx.allFinished = !stateAnims.isEmpty() && allNonLoop && stateTime >= maxLen;
            ctx.anyFinished = hasNonLoop && stateTime >= minLen;

            // 转移评估（先命中先走）
            boolean switched = false;
            for (ui.modelysm.client.anim.ModelControllers.Trans t : state.transitions) {
                if (ui.modelysm.client.anim.ModelControllers.eval(t.expr, ctx)) {
                    String target = c.states.containsKey(t.target) ? t.target : c.initial;
                    // 官方 AnimationController 语义：离开当前状态先跑 on_exit（如 v.next_idle = 0）
                    for (String sc : state.onExit) ui.modelysm.client.anim.ModelControllers.execScript(sc, ctx);
                    st.ctrlState.put(key, target);
                    st.ctrlTime.put(key, 0f);
                    // 进入新状态跑 on_entry
                    ui.modelysm.client.anim.ModelControllers.CState next = c.states.get(target);
                    if (next != null) {
                        for (String sc : next.onEntry) ui.modelysm.client.anim.ModelControllers.execScript(sc, ctx);
                    }
                    switched = true;
                    // 状态机切换是低频事件，打一条日志便于实机核对（酒狐 idle 变体就靠这条确认）
                    LOGGER.info("[ModelYSM] ctrl {} {} -> {} (v.next_idle={})", key, cur, target,
                            st.vars.getOrDefault("v.next_idle", 0f));
                    break;
                }
            }
            if (switched) continue; // 本帧切到的新状态下一帧才开始应用（时间=0）
            st.ctrlTime.put(key, stateTime + dt);
            if (!stateAnims.isEmpty()) {
                out.add(new ui.modelysm.client.anim.ModelControllers.ActiveLayer(key, state, stateTime));
            }
        }
        return out;
    }

    /** 一次性动画名集合：进入这些动画时才重置动画时间 */
    private static final java.util.Set<String> ONE_SHOT_ANIMS = java.util.Set.of(
            "death", "die", "attacked", "swing_hand", "swing_offhand", "swing", "swing_attack", "attack",
            "use_mainhand", "use_offhand", "sleep", "riptide");

    private static String pickAnimation(LivingEntity e, ModelLibrary.LoadedModel loaded, AnimStates.State st, float partialTicks) {
        Map<String, BedrockAnimation> anims = loaded.animations;
        // 死亡最高优先（官方 main 首位；骑乘中死亡也优先播死亡）
        if (e.deathTime > 0) {
            String s = BedrockAnimation.pick(anims, BedrockAnimation.Names.DEATH);
            if (s != null) return s;
        }
        // 骑乘：官方 main 在骑乘时 STOP，vehicle 层接管（ride_pig/ride/boat/sit，按载具类型）
        String vehicle = Behaviors.detectVehicle(e);
        if (vehicle != null) {
            if (anims.containsKey(vehicle)) return vehicle;
            return BedrockAnimation.pick(anims, BedrockAnimation.Names.RIDE);
        }
        // 官方 19 状态优先级序检测（death 已处理），逐项回退到模型拥有的动画
        java.util.List<String> behaviors = Behaviors.detectAll(e, st.speedPerSec, st.running);
        // 受击上升沿延长（hurtTime 只有 ~0.5s，保证受击动画完整播一遍）
        if (st.hurtTimer > 0 && !behaviors.contains("attacked")) {
            int idx = behaviors.indexOf("jump");
            if (idx < 0) idx = behaviors.size();
            behaviors.add(idx, "attacked");
        }
        for (String b : behaviors) {
            if (anims.containsKey(b)) return b;
        }
        // 全部行为模型都没有对应动画 → 官方兜底 idle（NPC 默认行为=静态站立），
        // 连 idle 都没有再退到 walk/任意
        String idle = BedrockAnimation.pick(anims, BedrockAnimation.Names.IDLE);
        return idle != null ? idle : BedrockAnimation.pick(anims, BedrockAnimation.Names.WALK);
    }

    /**
     * 官方行为 overlay 层追加（模型带对应动画才生效）：
     * hold_offhand → hold_mainhand → swing → use → passenger（官方注册序）。
     * 层计时：swing 上升沿清零（PLAY_ONCE）；use 使用中累计；hold 换动画清零；passenger 累计。
     */
    private static void appendBehaviorLayers(LivingEntity e, ModelLibrary.LoadedModel loaded,
                                             AnimStates.State st,
                                             java.util.List<ui.modelysm.client.anim.ModelControllers.ActiveLayer> out) {
        // swing 层（PLAY_ONCE：挥动上升沿重置）
        String swing = Behaviors.detectSwing(e, loaded);
        if (swing != null && loaded.animations.containsKey(swing)) {
            if (!st.wasSwing) st.swingLayerTime = 0;
            st.wasSwing = true;
            out.add(pseudoLayer("behavior.swing", swing, st.swingLayerTime));
            st.swingLayerTime += st.lastDt;
        } else {
            st.wasSwing = false;
            st.swingLayerTime = 0;
        }
        // use 层（LOOP：使用中累计）
        String useMain = Behaviors.detectUse(e, net.minecraft.world.InteractionHand.MAIN_HAND, loaded);
        String useOff = Behaviors.detectUse(e, net.minecraft.world.InteractionHand.OFF_HAND, loaded);
        String use = useMain != null && loaded.animations.containsKey(useMain) ? useMain
                : useOff != null && loaded.animations.containsKey(useOff) ? useOff : null;
        if (use != null) {
            out.add(pseudoLayer("behavior.use", use, st.useLayerTime));
            st.useLayerTime += st.lastDt;
        } else {
            st.useLayerTime = 0;
        }
        // hold 层（LOOP：换动画时清零，官方换物品 indicateReload 语义）
        String holdMain = Behaviors.detectHold(e, net.minecraft.world.InteractionHand.MAIN_HAND, loaded);
        String holdOff = Behaviors.detectHold(e, net.minecraft.world.InteractionHand.OFF_HAND, loaded);
        if (holdOff != null && loaded.animations.containsKey(holdOff)) {
            if (!java.util.Objects.equals(holdOff, st.lastHoldOff)) st.holdLayerTime = 0;
            st.lastHoldOff = holdOff;
            out.add(pseudoLayer("behavior.hold_offhand", holdOff, st.holdLayerTime));
        } else {
            st.lastHoldOff = null;
        }
        if (holdMain != null && loaded.animations.containsKey(holdMain)) {
            if (!java.util.Objects.equals(holdMain, st.lastHoldMain)) st.holdLayerTime = 0;
            st.lastHoldMain = holdMain;
            out.add(pseudoLayer("behavior.hold_mainhand", holdMain, st.holdLayerTime));
        } else {
            st.lastHoldMain = null;
        }
        if (holdMain == null && holdOff == null) st.holdLayerTime = 0;
        else if (holdMain != null || holdOff != null) st.holdLayerTime += st.lastDt;
        // passenger 层（LOOP：NPC 被骑，模型带 passenger$ 实体id/passenger#tag 动画才生效）
        String passenger = Behaviors.detectPassenger(e, loaded);
        if (passenger != null) {
            out.add(pseudoLayer("behavior.passenger", passenger, st.passengerTime));
            st.passengerTime += st.lastDt;
        } else {
            st.passengerTime = 0;
        }
    }

    /** 行为层伪 ActiveLayer（渲染端按 controller 层同语义应用） */
    private static ui.modelysm.client.anim.ModelControllers.ActiveLayer pseudoLayer(
            String key, String animName, float time) {
        ui.modelysm.client.anim.ModelControllers.CState cs = new ui.modelysm.client.anim.ModelControllers.CState();
        cs.name = animName;
        cs.animations.add(animName);
        return new ui.modelysm.client.anim.ModelControllers.ActiveLayer(key, cs, time);
    }
}
