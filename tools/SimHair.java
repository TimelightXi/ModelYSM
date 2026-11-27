import ui.modelysm.client.ysm.RawYsmModel;
import ui.modelysm.client.ysm.YSMBinaryDeserializer;
import ui.modelysm.client.ysm.crypto.YsmCrypt;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 离线采样模拟器：1:1 复刻 NpcRenderer 的数学路径
 * （rest 折入 animRot + bedrock 度数 x/y 取反 + 状态动画通道优先 + scale 累乘 + 头部旋转叠加），
 * 逐帧计算骨骼链世界变换，检测头发链是否"爆炸"（累计角偏差过大）。
 */
public class SimHair {

    static final double DEG = Math.PI / 180.0;

    static class Bone {
        String name, parent;
        float[] pivot = new float[3];
        float[] rest = new float[3];       // MC 弧度
        float[] animRot = new float[3];    // MC 弧度（含 rest）
        float[] animPos = new float[3];
        float[] animScale = {1, 1, 1};
        boolean[] driven = new boolean[3]; // rot,pos,scale
        Bone parentBone;
        List<Bone> children = new ArrayList<>();
        boolean hasHair = false;
    }

    static class Frame {
        float t;
        double[] v = new double[3];
        ui.modelysm.client.molang.Molang.Expr[] expr = new ui.modelysm.client.molang.Molang.Expr[3];
    }

    static class Curve {
        List<Frame> frames = new ArrayList<>();
        double[] sample(float t, ui.modelysm.client.molang.Molang.Ctx ctx) {
            double[] out = {0, 0, 0};
            if (frames.isEmpty()) return out;
            for (int i = 0; i < 3; i++) {
                double a = evalAt(frames.get(0), i, ctx);
                if (frames.size() == 1 || t <= frames.get(0).t) { out[i] = a; continue; }
                Frame last = frames.get(frames.size() - 1);
                if (t >= last.t) { out[i] = evalAt(last, i, ctx); continue; }
                int lo = 0;
                while (lo + 1 < frames.size() && frames.get(lo + 1).t <= t) lo++;
                Frame fa = frames.get(lo), fb = frames.get(lo + 1);
                float span = fb.t - fa.t;
                float frac = span <= 0 ? 0 : (t - fa.t) / span;
                out[i] = fa.v[i] + (evalAt(fb, i, ctx) - fa.v[i]) * frac;
            }
            return out;
        }
        double evalAt(Frame f, int i, ui.modelysm.client.molang.Molang.Ctx ctx) {
            return f.expr[i] != null ? f.expr[i].eval(ctx) : f.v[i];
        }
    }

    static class Channels { Curve rot = new Curve(), pos = new Curve(), scl = new Curve(); }

    static Map<String, Bone> bones = new LinkedHashMap<>();
    static Map<String, Channels> anims = new HashMap<>(); // animName -> boneName -> channels

    static void load(String path) throws Exception {
        byte[] ysm = Files.readAllBytes(Paths.get(path));
        RawYsmModel raw = new YSMBinaryDeserializer(YsmCrypt.decryptYsmFile(ysm)).deserialize();
        for (RawYsmModel.RawBone rb : raw.mainEntity.mainModel.bones) {
            Bone b = new Bone();
            b.name = rb.name;
            b.parent = rb.parentName == null || rb.parentName.isEmpty() ? null : rb.parentName;
            b.pivot = rb.pivot;
            b.rest = rb.rotation;
            b.hasHair = rb.name.contains("Hair");
            bones.put(b.name, b);
        }
        for (Bone b : bones.values()) {
            if (b.parent != null) {
                Bone p = bones.get(b.parent);
                if (p != null) { b.parentBone = p; p.children.add(b); }
            }
        }
        for (RawYsmModel.RawAnimationFile f : raw.mainEntity.animationFiles.values()) {
            for (RawYsmModel.RawAnimation ra : f.animations.values()) {
                if (!List.of("walk", "idle", "run", "pre_parallel2", "parallel0", "parallel1", "parallel2").contains(ra.name)) continue;
                Channels byAnim = anims.computeIfAbsent(ra.name, k -> new Channels());
                // 简化： Channels 按骨骼存 —— 重新组织
                for (RawYsmModel.RawBoneAnimation ba : ra.boneAnimations) {
                    BoneChannels bc = boneChannels.computeIfAbsent(ra.name + "\u0000" + ba.boneName, k -> new BoneChannels());
                    fill(bc.rot, ba.rotation);
                    fill(bc.pos, ba.position);
                    fill(bc.scl, ba.scale);
                }
            }
        }
    }

    static class BoneChannels { Curve rot = new Curve(), pos = new Curve(), scl = new Curve(); }
    static Map<String, BoneChannels> boneChannels = new HashMap<>();

    static void fill(Curve c, List<RawYsmModel.RawKeyframe> kfs) {
        for (RawYsmModel.RawKeyframe kf : kfs) {
            Frame f = new Frame();
            f.t = kf.timestamp;
            for (int i = 0; i < 3; i++) {
                Object o = kf.postData != null && kf.postData.length > i ? kf.postData[i] : null;
                if (o instanceof Float fl) f.v[i] = fl.doubleValue();
                else if (o instanceof String st && !st.isBlank()) f.expr[i] = ui.modelysm.client.molang.Molang.parse(st);
                else f.expr[i] = null;
            }
            c.frames.add(f);
        }
        c.frames.sort(Comparator.comparingDouble(a -> a.t));
    }

    static float animLen(String anim) {
        // 从 boneChannels 重建长度不可靠，这里用常量表
        return switch (anim) {
            case "walk" -> 1.0f; case "idle" -> 3.0f; case "run" -> 0.8f;
            case "pre_parallel2" -> 3.0f; default -> 0f;
        };
    }

    /** 复刻 renderFull 的采样顺序 */
    static ui.modelysm.client.molang.Molang.Ctx mctx() {
        ui.modelysm.client.molang.Molang.Ctx ctx = new ui.modelysm.client.molang.Molang.Ctx();
        ctx.health = 20; ctx.maxHealth = 20;
        return ctx;
    }

    static void samplePose(String stateAnim, float tState, float tBase, float headYawDeg, float headPitchDeg) {
        ui.modelysm.client.molang.Molang.Ctx ctx = mctx();
        ctx.animTime = tState;
        for (Bone b : bones.values()) {
            b.animRot = b.rest.clone();
            b.animPos = new float[3];
            b.animScale = new float[]{1, 1, 1};
            b.driven = new boolean[3];
        }
        // 状态动画 sink
        BoneChannels st = boneChannels.get(stateAnim + "\u0000" + null);
        for (Bone b : bones.values()) {
            BoneChannels bc = boneChannels.get(stateAnim + "\u0000" + b.name);
            if (bc == null) continue;
            applyState(bc, b, ctx);
        }
        // 常驻层 sink（通道优先）——"__none__" 表示纯静止跳过
        if (!stateAnim.equals("__none__"))
        for (String layer : List.of("pre_parallel0", "pre_parallel1", "pre_parallel2", "pre_parallel3",
                "parallel0", "parallel1", "parallel2")) {
            for (Bone b : bones.values()) {
                BoneChannels bc = boneChannels.get(layer + "\u0000" + b.name);
                if (bc == null) continue;
                applyBase(bc, b, ctx);
            }
        }
        // 头部旋转
        Bone head = bones.get("Head");
        if (head != null) {
            head.animRot[0] += -headPitchDeg * DEG;
            head.animRot[1] += -headYawDeg * DEG;
        }
    }

    static boolean ABSOLUTE = Boolean.parseBoolean(System.getProperty("hair.absolute", "false"));
    static double[] applyRotVal(Bone b, double[] r, double[] fallback) {
        if (!ABSOLUTE) return new double[]{b.animRot[0] + fallback[0], b.animRot[1] + fallback[1], b.animRot[2] + fallback[2]};
        return new double[]{-r[0] * DEG, -r[1] * DEG, r[2] * DEG};
    }

    static void applyState(BoneChannels bc, Bone b, ui.modelysm.client.molang.Molang.Ctx ctx) {
        float t = (float) ctx.animTime;
        if (!bc.rot.frames.isEmpty()) {
            double[] r = bc.rot.sample(t, ctx);
            double[] v = applyRotVal(b, r, new double[]{-r[0] * DEG, -r[1] * DEG, r[2] * DEG});
            b.animRot[0] = (float) v[0]; b.animRot[1] = (float) v[1]; b.animRot[2] = (float) v[2];
            b.driven[0] = true;
        }
        if (!bc.pos.frames.isEmpty()) {
            double[] p = bc.pos.sample(t, ctx);
            b.animPos[0] += (float) (-p[0] / 16.0);
            b.animPos[1] += (float) (p[1] / 16.0);
            b.animPos[2] += (float) (p[2] / 16.0);
            b.driven[1] = true;
        }
        if (!bc.scl.frames.isEmpty()) {
            double[] s = bc.scl.sample(t, ctx);
            for (int i = 0; i < 3; i++) b.animScale[i] *= (float) s[i];
            b.driven[2] = true;
        }
    }

    static void applyBase(BoneChannels bc, Bone b, ui.modelysm.client.molang.Molang.Ctx ctx) {
        float t = (float) ctx.animTime;
        if (!bc.rot.frames.isEmpty() && !b.driven[0]) {
            double[] r = bc.rot.sample(t, ctx);
            double[] v = applyRotVal(b, r, new double[]{-r[0] * DEG, -r[1] * DEG, r[2] * DEG});
            b.animRot[0] = (float) v[0]; b.animRot[1] = (float) v[1]; b.animRot[2] = (float) v[2];
        }
        if (!bc.pos.frames.isEmpty() && !b.driven[1]) {
            double[] p = bc.pos.sample(t, ctx);
            b.animPos[0] += (float) (-p[0] / 16.0);
            b.animPos[1] += (float) (p[1] / 16.0);
            b.animPos[2] += (float) (p[2] / 16.0);
        }
        if (!bc.scl.frames.isEmpty()) {
            double[] s = bc.scl.sample(t, ctx);
            for (int i = 0; i < 3; i++) b.animScale[i] *= (float) s[i];
        }
    }

    /** 计算每个骨骼的世界旋转（简化：逐链接累计欧拉角大小近似），并输出头发链最大累计偏差 */
    /** 计算骨骼世界变换（旋转+平移，模型单位），返回发梢骨骼世界坐标 */
    static Map<String, double[]> worldPos = new HashMap<>();

    static void computeWorld() {
        worldPos.clear();
        // 拓扑序处理（父先于子）
        Deque<Bone> q = new ArrayDeque<>();
        for (Bone b : bones.values()) if (b.parentBone == null) q.add(b);
        while (!q.isEmpty()) {
            Bone b = q.poll();
            computeFrom(b, new double[]{0, 0, 0}, identity());
        }
    }

    static double[][] identity() {
        return new double[][]{{1,0,0},{0,1,0},{0,0,1}};
    }

    /** 递归：bone 的世界变换基于父世界原点+父旋转 */
    static void computeFrom(Bone b, double[] parentWorld, double[][] parentRot) {
        // 从父 pivot 到本 pivot 的相对位移（模型单位）+ 动画位移
        double dx = (b.pivot[0] - (b.parentBone != null ? b.parentBone.pivot[0] : 0)) + b.animPos[0] * 16.0;
        double dy = (b.pivot[1] - (b.parentBone != null ? b.parentBone.pivot[1] : 0)) + b.animPos[1] * 16.0;
        double dz = (b.pivot[2] - (b.parentBone != null ? b.parentBone.pivot[2] : 0)) + b.animPos[2] * 16.0;
        double[] myOrigin = {
                parentWorld[0] + parentRot[0][0]*dx + parentRot[0][1]*dy + parentRot[0][2]*dz,
                parentWorld[1] + parentRot[1][0]*dx + parentRot[1][1]*dy + parentRot[1][2]*dz,
                parentWorld[2] + parentRot[2][0]*dx + parentRot[2][1]*dy + parentRot[2][2]*dz
        };
        worldPos.put(b.name, myOrigin);
        // 本骨骼旋转（Z*Y*X）后累计矩阵
        double[][] r = mul(parentRot, eulerZYX(b.animRot[2], b.animRot[1], b.animRot[0]));
        double[][] s = new double[][]{
                {r[0][0]*b.animScale[0], r[0][1]*b.animScale[1], r[0][2]*b.animScale[2]},
                {r[1][0]*b.animScale[0], r[1][1]*b.animScale[1], r[1][2]*b.animScale[2]},
                {r[2][0]*b.animScale[0], r[2][1]*b.animScale[1], r[2][2]*b.animScale[2]}};
        for (Bone c : b.children) computeFrom(c, myOrigin, s);
    }

    static double[] worldOf(Bone b) {
        // 不应走到这里（computeFrom 覆盖所有骨骼）
        return worldPos.getOrDefault(b.name, new double[]{0,0,0});
    }

    static double[][] eulerZYX(double rz, double ry, double rx) {
        double cz=Math.cos(rz), sz=Math.sin(rz), cy=Math.cos(ry), sy=Math.sin(ry), cx=Math.cos(rx), sx=Math.sin(rx);
        // R = Rz * Ry * Rx
        return new double[][]{
            {cz*cy, cz*sy*sx - sz*cx, cz*sy*cx + sz*sx},
            {sz*cy, sz*sy*sx + cz*cx, sz*sy*cx - cz*sx},
            {-sy,   cy*sx,            cy*cx}
        };
    }

    static double[][] mul(double[][] a, double[][] b) {
        double[][] o = new double[3][3];
        for (int i=0;i<3;i++) for (int j=0;j<3;j++) {
            double v=0; for (int k=0;k<3;k++) v+=a[i][k]*b[k][j];
            o[i][j]=v;
        }
        return o;
    }

    /** 输出若干条头发链的末端骨骼世界坐标（模型单位，1/16 像素） */
    static void reportTips(String tag) {
        computeWorld();
        String[] tips = {"LeftSideHangHair_5", "LeftBackHair1_6", "RightSideHangHair_1_6", "FrontHair_mid"};
        StringBuilder sb = new StringBuilder("[" + tag + "] worldPos=" + worldPos.size() + " 发梢世界坐标: ");
        for (String t : tips) {
            Bone b = bones.get(t);
            double[] p = b != null ? worldPos.get(t) : null;
            if (p != null) sb.append(t).append("=").append(String.format("(%.0f,%.0f,%.0f) ", p[0], p[1], p[2]));
        }
        System.out.println(sb);
    }

    static void report(String tag) {
        // 计算 world 位置（沿链变换 pivot）
        Map<Bone, double[]> world = new HashMap<>();
        for (Bone b : bones.values()) {
            if (b.parentBone == null) {
                world.put(b, new double[]{b.pivot[0], b.pivot[1], b.pivot[2]});
            }
        }
        // 需要拓扑顺序：父先处理
        Deque<Bone> stack = new ArrayDeque<>();
        for (Bone b : bones.values()) if (b.parentBone == null) stack.push(b);
        while (!stack.isEmpty()) {
            Bone b = stack.pop();
            double[] wp = world.get(b);
            if (wp == null) continue;
            // 本骨骼变换后的世界位置 = wp + R * (0,0,0) … 简化：位置受旋转影响，但 pivot 偏移用 rest 方向近似
            for (Bone c : b.children) {
                // 子 pivot 相对父 pivot 的偏移，经父链累计旋转——离线简化：只统计每链接的旋转角
                world.put(c, new double[]{c.pivot[0], c.pivot[1], c.pivot[2]});
                stack.push(c);
            }
        }
        // 统计：每个头发链的累计 |rot| 偏差（相对 rest）
        Map<String, double[]> restWorld = null;
        double maxChainDev = 0;
        String worstChain = "";
        for (Bone b : bones.values()) {
            if (!b.hasHair) continue;
            double cum = 0;
            Bone cur = b;
            while (cur != null && cur.hasHair) {
                double dev = Math.sqrt(sq(cur.animRot[0] - cur.rest[0]) + sq(cur.animRot[1] - cur.rest[1]) + sq(cur.animRot[2] - cur.rest[2]));
                cum += dev;
                cur = cur.parentBone;
            }
            if (cum > maxChainDev) { maxChainDev = cum; worstChain = b.name; }
        }
        System.out.printf("[%s] 头发链最大累计旋转偏差: %.1f° (链头=%s)%n", tag, Math.toDegrees(maxChainDev), worstChain);
    }

    static double sq(double v) { return v * v; }

    public static void main(String[] args) throws Exception {
        load(args.length > 0 ? args[0] : "D:/软件下载/我的世界/Content of server/服务端/mohist-1.20.1-test/config/modelysm/models/Ender_Dragon_已适配2.6.2.ysm");
        System.out.println("bones=" + bones.size() + " hairBones=" + bones.values().stream().filter(b -> b.hasHair).count());

        // 纯静止（无动画无常驻）
        samplePose("__none__", 0, 0, 0, 0);
        reportTips("纯rest");

        // 无状态动画 + 常驻层（rest 卡的实际路径）
        // samplePose("__none__") 跳过了常驻层，这里手动补采样
        java.util.function.BiConsumer<String, Object> noop = null;
        for (String layer : java.util.List.of("pre_parallel2", "parallel0", "parallel1", "parallel2")) {
            for (Bone b : bones.values()) {
                BoneChannels bc = boneChannels.get(layer + "\u0000" + b.name);
                if (bc == null) continue;
                ui.modelysm.client.molang.Molang.Ctx ctx = mctx();
                applyBase(bc, b, ctx);
            }
        }
        reportTips("rest+常驻");

        for (String anim : List.of("walk", "idle")) {
            float len = animLen(anim);
            for (float t : new float[]{0f, len * 0.5f}) {
                samplePose(anim, t, t, 0, 0);
                reportTips(String.format("%s t=%.2f", anim, t));
            }
        }
    }
}
