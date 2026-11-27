package ui.modelysm.client.render;

import net.minecraft.util.Mth;
import ui.modelysm.client.molang.Molang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bedrock 动画：解析 main.animation.json 的一个动画条目，按时间采样。
 * 关键帧值支持 数字 / [x,y,z] / molang 字符串 / {pre,post}。
 *
 * v40：对齐官方 OpenYSM 的关键帧语义（geckolib3/util/json/JsonKeyFrameUtils:40-95 +
 * core/keyframe/bone/EasingType:34-49）：
 *  - 每个关键帧可带 {pre, post}，post = 该时刻的值，pre = 从左侧逼近该时刻的值；
 *    只有 pre 或只有 post 时两者视为同一个（contiguous）；
 *  - 关键帧可带 "lerp_mode": "linear" | "catmullrom"，作用在【以该帧为终点的那一段】上；
 *  - catmullrom 取四点：left=前前一帧 post、begin=前一帧 post、end=本帧 pre、right=后一帧 pre，
 *    公式照抄 core/util/MathUtil.catmullRom。
 *  - 无 lerp_mode 的动画行为与之前完全一致（等价线性）。
 */
public final class BedrockAnimation {
    public final String name;
    public final boolean loop;
    public final float length;
    /** 骨骼名 → 通道组 */
    public final Map<String, Channels> boneChannels = new LinkedHashMap<>();
    /** 官方 timeline_events：到点执行一次的 Molang 脚本（可赋值 v.* / v.roaming.*） */
    public final List<TimelineEvent> timelineEvents = new ArrayList<>();
    /**
     * 官方 blend_weight（PredicateBasedController.TransformProviderRecord）：
     * 每条动画可带一个权重表达式，采样值按它缩放 ——
     * 旋转/位移 ×w，缩放 lerp(1, scale, w)。缺失时 w=1。
     * 酒狐的 pre_parallel2（尾巴/长发物理层）就靠 "0.75*math.sin(anim_time*20)+1.5" 呼吸式加权。
     */
    public Molang.Expr blendWeightExpr;

    public BedrockAnimation(String name, boolean loop, float length) {
        this.name = name;
        this.loop = loop;
        this.length = length;
    }

    /** 时间轴事件（一个时间点可挂多条脚本） */
    public static final class TimelineEvent {
        public final float t;
        public final List<String> scripts = new ArrayList<>();
        public final List<Molang.Expr> exprs = new ArrayList<>();
        public TimelineEvent(float t) { this.t = t; }
    }

    /** 采样结果（bedrock 原始值：旋转=度、位移=模型单位） */
    public static final class Sample {
        public final float[] rot = new float[3];
        public final float[] pos = new float[3];
        public final float[] scale3 = {1.0f, 1.0f, 1.0f};
        public boolean hasScale;
        public boolean hasRot;
        public boolean hasPos;
        public float scale = 1.0f;
        public boolean any;
    }

    /** 单条通道曲线 */
    public static final class Curve {
        public final List<Frame> frames = new ArrayList<>();

        /**
         * 关键帧。expr = post（该时刻的值，也是"离开该帧"时用的值）；
         * preExpr = pre（"进入该帧"时用的值），为空表示与 post 相同。
         */
        public static final class Frame {
            public final float t;
            public Molang.Expr[] expr = new Molang.Expr[3];
            public Molang.Expr[] preExpr;
            /** 以本帧为终点的那一段用 Catmull-Rom */
            public boolean catmull;
            public Frame(float t) { this.t = t; }

            public Molang.Expr pre(int i) { return preExpr != null ? preExpr[i] : expr[i]; }
            public Molang.Expr post(int i) { return expr[i]; }
        }

        private static final class Scratch { final float[][] v = new float[4][3]; }
        private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

        public void evalInto(float t, Molang.Ctx ctx, float[] out) {
            if (frames.isEmpty()) return;
            Frame f0 = frames.get(0);
            Frame last = frames.get(frames.size() - 1);
            if (frames.size() == 1 || t <= f0.t) {
                evalFrame(f0, ctx, out, false);
                return;
            }
            if (t >= last.t) {
                evalFrame(last, ctx, out, true);
                return;
            }
            int lo = 0;
            while (lo + 1 < frames.size() && frames.get(lo + 1).t <= t) lo++;
            Frame a = frames.get(lo);
            Frame b = frames.get(lo + 1);
            float span = b.t - a.t;
            float frac = span <= 0 ? 0 : (t - a.t) / span;

            if (b.catmull) {
                Frame lf = frames.get(Math.max(0, lo - 1));
                Frame rf = frames.get(Math.min(frames.size() - 1, lo + 2));
                Scratch s = SCRATCH.get();
                for (int i = 0; i < 3; i++) {
                    s.v[0][i] = (float) lf.post(i).eval(ctx);
                    s.v[1][i] = (float) a.post(i).eval(ctx);
                    s.v[2][i] = (float) b.pre(i).eval(ctx);
                    s.v[3][i] = (float) rf.pre(i).eval(ctx);
                    out[i] = catmullRom(frac, s.v[0][i], s.v[1][i], s.v[2][i], s.v[3][i]);
                }
                return;
            }
            for (int i = 0; i < 3; i++) {
                out[i] = (float) Mth.lerp(frac, a.post(i).eval(ctx), b.pre(i).eval(ctx));
            }
        }

        /** 官方 core/util/MathUtil.catmullRom 原式 */
        private static float catmullRom(float percent, float left, float begin, float end, float right) {
            float v0 = (end - left) * 0.5f;
            float v1 = (right - begin) * 0.5f;
            float t2 = percent * percent;
            float t3 = percent * t2;
            return (2 * begin - 2 * end + v0 + v1) * t3 + (-3 * begin + 3 * end - 2 * v0 - v1) * t2 + v0 * percent + begin;
        }

        private void evalFrame(Frame f, Molang.Ctx ctx, float[] out, boolean post) {
            for (int i = 0; i < 3; i++) out[i] = (float) (post ? f.post(i).eval(ctx) : f.pre(i).eval(ctx));
        }
    }

    public static final class Channels {
        public Curve rotation = new Curve();
        public Curve position = new Curve();
        public Curve scale = new Curve();

        public Sample sample(float t, Molang.Ctx ctx) {
            Sample s = new Sample();
            if (!rotation.frames.isEmpty()) {
                rotation.evalInto(t, ctx, s.rot);
                s.hasRot = true;
                s.any = true;
            }
            if (!position.frames.isEmpty()) {
                position.evalInto(t, ctx, s.pos);
                s.hasPos = true;
                s.any = true;
            }
            if (!scale.frames.isEmpty()) {
                scale.evalInto(t, ctx, s.scale3);
                s.hasScale = true;
                s.scale = s.scale3[0];
                s.any = true;
            }
            return s;
        }
    }

    /** 遍历骨骼通道采样（时间按 loop 折算），boneSink 收到 (骨骼名, 采样值) */
    public void applyTo(float time, Molang.Ctx ctx, java.util.function.BiConsumer<String, Sample> boneSink) {
        float t = time;
        if (loop && length > 0) t = t % length;
        float w = 1.0f;
        if (blendWeightExpr != null) {
            try {
                w = (float) blendWeightExpr.eval(ctx);
                if (!Float.isFinite(w) || w < 0f) w = 0f;
            } catch (Throwable ignored) {
                w = 1.0f;
            }
        }
        for (Map.Entry<String, Channels> e : boneChannels.entrySet()) {
            Sample s = e.getValue().sample(t, ctx);
            if (s.any) {
                if (w != 1.0f) {
                    for (int i = 0; i < 3; i++) {
                        s.rot[i] *= w;
                        s.pos[i] *= w;
                        s.scale3[i] = 1.0f + (s.scale3[i] - 1.0f) * w;
                    }
                    s.scale = s.scale3[0];
                }
                boneSink.accept(e.getKey(), s);
            }
        }
    }

    /** 动画名候选（按状态） */
    public static final class Names {
        public static final String[] IDLE = {"idle", "idle_general"};
        public static final String[] WALK = {"walk"};
        public static final String[] RUN = {"run", "sprint"};
        public static final String[] JUMP = {"jump", "fly"};
        public static final String[] SNEAK = {"sneak", "sneaking"};
        public static final String[] SWING = {"swing_hand", "attack", "swing"};
        public static final String[] RIDE = {"ride", "sit"};
        public static final String[] DEATH = {"death", "die"};
        public static final String[] SWIM = {"swim", "swim_stand"};
    }

    public static String pick(Map<String, BedrockAnimation> anims, String[] candidates) {
        for (String c : candidates) {
            if (anims.containsKey(c)) return c;
        }
        return null;
    }
}
