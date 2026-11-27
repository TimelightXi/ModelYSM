package ui.modelysm.client.render;

import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 Bedrock 几何 JSON 烘焙的骨骼模型（Y 向上、单位=方块 1/16）。
 * 坐标换算（与 GeckoLib/Blockbench 导出约定一致）：
 *   MC.x = -bedrock.x（镜像）、MC.y = bedrock.y、MC.z = bedrock.z
 */
public final class BedrockModel {

    /** 烘焙面片（.ysm 二进制直读）：四顶点为相对骨骼 pivot 的 MC 方块坐标，UV 已归一化 0~1 */
    public static final class BakedFace {
        public final float[][] pos = new float[4][3];
        public final float[] u = new float[4];
        public final float[] v = new float[4];
        /** 文件里带的显式法线（官方 NativeModelRenderer 就是用 quad.normal 做光照，不重新叉积）；
         *  全 0 表示文件没给，渲染端退回叉积 */
        public float[] normal = new float[]{0f, 0f, 0f};
    }

    public static final class Cube {
        public final Vector3f min;   // MC 空间，相对骨骼 pivot（方块单位）
        public final Vector3f max;
        public final float u0, v0, u1, v1, tw, th;  // 盒式UV整体参数（仅盒式UV立方体使用）
        public final Vector3f rotPivot;  // 立方体自身旋转 pivot（null=不旋转）
        public final Vector3f rotAnglesRad; // 立方体旋转（MC 弧度）
        public final float inflate;
        public final boolean mirror; // 盒式UV水平镜像
        /** 按面UV：[面索引][u0,v0,u1,v1]（原始贴图坐标，渲染时除以 tw/th）。
         *  面索引：0=WEST,1=EAST,2=NORTH,3=SOUTH,4=UP,5=DOWN；null=盒式UV立方体 */
        public final float[][] faceUVs;
        /** 烘焙面片（.ysm 直读专用）：非 null 时渲染走烘焙路径 */
        public BakedFace[] bakedFaces;
        /**
         * 是否可在渲染时做逐面背面剔除（官方 OpenYSM 的 {@code cube.cullable}，
         * 见 YSMClientMapper:525-533 的判定）：零厚度薄片（头发、发丝、飘带）或
         * 5 面以上的实体方块。零厚度薄片的正反两面完全共面，不剔除就是同一深度
         * 画两遍 → 实机"头发噪点 + 闪频"。
         */
        public boolean cullable;

        Cube(Vector3f min, Vector3f max, float u, float v, float tw, float th,
             Vector3f rotPivot, Vector3f rotAnglesRad, float inflate, boolean mirror, float[][] faceUVs) {
            this.min = min;
            this.max = max;
            this.u0 = u;
            this.v0 = v;
            this.u1 = u; // 占位，实际面片 UV 在渲染时按 vanilla 布局计算
            this.v1 = v;
            this.tw = tw;
            this.th = th;
            this.rotPivot = rotPivot;
            this.rotAnglesRad = rotAnglesRad;
            this.inflate = inflate;
            this.mirror = mirror;
            this.faceUVs = faceUVs;
            this.bakedFaces = null;
        }

        /** 烘焙面片立方体（.ysm 直读）：几何顶点已就绪 */
        public Cube(BakedFace[] bakedFaces) {
            this.min = new Vector3f();
            this.max = new Vector3f();
            this.u0 = this.v0 = this.u1 = this.v1 = 0;
            this.tw = this.th = 0;
            this.rotPivot = null;
            this.rotAnglesRad = null;
            this.inflate = 0;
            this.mirror = false;
            this.faceUVs = null;
            this.bakedFaces = bakedFaces;
        }
    }

    public static final class Bone {
        public final String name;
        public Bone parent;
        public final List<Bone> children = new ArrayList<>();
        public final List<Cube> cubes = new ArrayList<>();
        public final Vector3f mcPivot;          // MC 空间绝对 pivot（方块单位）
        public final Vector3f restRotRad = new Vector3f(); // 静止旋转（MC 弧度，来自几何 JSON）
        public final float[] animRot = new float[3];    // 当前动画旋转（MC 弧度）
        public final float[] animPos = new float[3];    // 当前动画平移（方块单位）
        public final float[] animScale = {1.0f, 1.0f, 1.0f}; // 当前动画按轴缩放（0=隐藏该骨骼）

        Bone(String name, Bone parent, Vector3f mcPivot, Vector3f restRotRad) {
            this.name = name;
            this.parent = parent;
            this.mcPivot = mcPivot;
            if (restRotRad != null) this.restRotRad.set(restRotRad);
        }

        public boolean isHeadLike() {
            return name.equals("Head") || name.equals("head");
        }
    }

    public final float textureWidth;
    public final float textureHeight;
    public final List<Bone> rootBones;
    public final Map<String, Bone> bonesByName;
    public final Bone headBone; // 名为 Head/head 的骨骼（若有）

    private BedrockModel(float tw, float th, List<Bone> roots, Map<String, Bone> byName) {
        this.textureWidth = tw;
        this.textureHeight = th;
        this.rootBones = roots;
        this.bonesByName = byName;
        Bone h = byName.get("Head");
        if (h == null) h = byName.get("head");
        this.headBone = h;
    }

    /** .ysm 二进制直构工厂（同包 YsmDirectLoader 使用） */
    static BedrockModel create(float tw, float th, List<Bone> roots, Map<String, Bone> byName) {
        return new BedrockModel(tw, th, roots, byName);
    }

    /** 几何解析结果（JSON 已由 ModelLibrary 读出） */
    public static final class GeometryData {
        public float textureWidth = 64, textureHeight = 64;
        public final List<ParsedBone> bones = new ArrayList<>();

        public static final class ParsedCube {
            public float[] origin;   // bedrock 原始
            public float[] size;
            public float uvU, uvV;   // 盒式UV
            public boolean perFace;  // 按面UV标记
            public float[][] facesUV; // 按面UV原始数据 [6][u,v,w,h]，顺序 WEST,EAST,NORTH,SOUTH,UP,DOWN；缺失面为 null
            public float[] rotation; // 立方体旋转（bedrock 角度）
            public float[] rotPivot;
            public float inflate;
            public boolean mirror;   // 盒式UV水平镜像（vanilla ModelPart.Polygon 同语义）
        }

        public static final class ParsedBone {
            public String name;
            public String parent;
            public float[] pivot = {0, 0, 0};
            public float[] rotation = {0, 0, 0}; // bedrock 角度
            public boolean mirror;              // 骨骼级 mirror（cube 未写 mirror 时继承，官方 :556/:565）
            public final List<ParsedCube> cubes = new ArrayList<>();
        }
    }

    /** 由解析数据烘焙 */
    public static BedrockModel bake(GeometryData geo) {
        Map<String, Bone> byName = new LinkedHashMap<>();
        // 先建骨骼（pivot 转 MC 空间）
        for (GeometryData.ParsedBone pb : geo.bones) {
            Vector3f mcPivot = toMc(pb.pivot);
            Vector3f rest = toRotRad(pb.rotation);
            byName.put(pb.name, new Bone(pb.name, null, mcPivot, rest));
        }
        // 连接父子
        List<Bone> roots = new ArrayList<>();
        for (GeometryData.ParsedBone pb : geo.bones) {
            Bone b = byName.get(pb.name);
            if (pb.parent != null) {
                Bone parent = byName.get(pb.parent);
                if (parent != null) {
                    b.parent = parent;
                    parent.children.add(b);
                    continue;
                }
            }
            roots.add(b);
        }
        // 立方体
        for (GeometryData.ParsedBone pb : geo.bones) {
            Bone b = byName.get(pb.name);
            if (b == null) continue;
            for (GeometryData.ParsedCube pc : pb.cubes) {
                addCube(b, pc, geo);
            }
        }
        return new BedrockModel(geo.textureWidth, geo.textureHeight, roots, byName);
    }

    private static void addCube(Bone bone, GeometryData.ParsedCube pc, GeometryData geo) {
        float ox = pc.origin[0], oy = pc.origin[1], oz = pc.origin[2];
        float sx = pc.size[0], sy = pc.size[1], sz = pc.size[2];
        // MC 空间绝对坐标（镜像 x）
        float minX = -(ox + sx) / 16f;
        float maxX = -ox / 16f;
        float minY = oy / 16f;
        float maxY = (oy + sy) / 16f;
        float minZ = oz / 16f;
        float maxZ = (oz + sz) / 16f;
        // 转为相对骨骼 pivot
        Vector3f min = new Vector3f(minX - bone.mcPivot.x(), minY - bone.mcPivot.y(), minZ - bone.mcPivot.z());
        Vector3f max = new Vector3f(maxX - bone.mcPivot.x(), maxY - bone.mcPivot.y(), maxZ - bone.mcPivot.z());
        Vector3f rotPivot = null, rotRad = null;
        if (pc.rotation != null && pc.rotPivot != null && (pc.rotation[0] != 0 || pc.rotation[1] != 0 || pc.rotation[2] != 0)) {
            Vector3f mcRp = toMc(pc.rotPivot);
            rotPivot = new Vector3f(mcRp.x() - bone.mcPivot.x(), mcRp.y() - bone.mcPivot.y(), mcRp.z() - bone.mcPivot.z());
            rotRad = new Vector3f(toRotRad(pc.rotation));
        }
        Cube cube = new Cube(min, max, pc.uvU, pc.uvV, geo.textureWidth, geo.textureHeight, rotPivot, rotRad, pc.inflate, pc.mirror, pc.facesUV);
        bone.cubes.add(cube);
        // 官方 bakeFaceToRaw 口径烘焙（YSMFolderDeserializer:563-621 + :633-684）：
        // 把 6 个面直接烘成顶点/UV/法线，渲染端不再自己推盒式 UV 布局。
        // 这是"左右纹理方向与官方一致"的唯一可靠做法：官方 cube 的顶点顺序是
        //   顶部两点在前、底部两点在后（west=p4,p3,p1,p2 …），u 数组固定按
        //   u={u0,u1,u1,u0}、v={v0,v0,v1,v1} 分配；我们过去用 vanilla ModelPart 的
        //   "绕一圈"顶点顺序去套同一套 u 数组，等于把每个面都水平翻了一次 ——
        //   实机表现就是"NPC 后脑勺纹理跟官方呈对称/方向相反"。
        cube.bakedFaces = bakeOfficialFaces(pc, bone.mcPivot, geo.textureWidth, geo.textureHeight);
        // 复刻官方 cullable 判定（YSMClientMapper:525-533）：
        //   零厚度薄片（任一维为 0）且有效面 > 1  → 剔除背面（否则正反共面画两遍）
        //   有效面 >= 5（几乎封闭的方块）        → 剔除背面（和 vanilla 一样）
        int faces = 0;
        if (pc.facesUV != null) {
            for (float[] f : pc.facesUV) if (f != null) faces++;
        } else {
            faces = 6;
        }
        boolean flat = Math.abs(sx) < 1e-3f || Math.abs(sy) < 1e-3f || Math.abs(sz) < 1e-3f;
        cube.cullable = (flat && faces > 1) || faces >= 5;
        // 膨胀把某一维压成 0 或负数时，cube 会翻面（绕向整体反转），剔除方向就反了 → 不剔除
        float infl = pc.inflate * 2f;
        if (sx + infl <= 0f || sy + infl <= 0f || sz + infl <= 0f) cube.cullable = false;
    }

    /** bedrock [x,y,z] → MC 空间（x 镜像，单位转方块） */
    public static Vector3f toMc(float[] bedrock) {
        return new Vector3f(-bedrock[0] / 16f, bedrock[1] / 16f, bedrock[2] / 16f);
    }

    /**
     * bedrock 角度(度) → MC 弧度。
     * GeckoLib 约定：x、y 取反，z 不变。
     */
    public static Vector3f toRotRad(float[] bedrockDeg) {
        return new Vector3f(-bedrockDeg[0] * Mth.DEG_TO_RAD, -bedrockDeg[1] * Mth.DEG_TO_RAD, bedrockDeg[2] * Mth.DEG_TO_RAD);
    }

    /**
     * 逐字复刻官方 OpenYSM（MIT）{@code YSMFolderDeserializer#bakeFaceToRaw}：
     * 一个 cube 直接烘成 6 个面（顶点顺序 = 官方 positions switch，UV = 官方 u/v 数组，
     * 法线 = 面种类向量 × cube 旋转法线矩阵）。
     *
     * 官方对"中心对称/左右对称"处理的全部规则就在这三行里：
     *   1) 几何：{@code cx=-origin[0]-size[0]-inflate}，与 mirror 无关 —— mirror 只翻贴图，不翻方块位置；
     *   2) 侧面换岛：mirror 的 cube 里 East 取 west 岛、West 取 east 岛；
     *   3) 其余面：{@code if (!mirror) swap(u0,u1)} —— 非镜像块整体左右翻 u，镜像块保持原方向。
     * 顶点顺序是"上面两点在前、下面两点在后"，u={u0,u1,u1,u0}、v={v0,v0,v1,v1} 按这个顺序分配。
     * 用别的顶点顺序（如 vanilla ModelPart 绕圈）套同一套 u 数组，每个面都会被多翻一次 →
     * 实机上就是"NPC 后脑勺纹理和官方呈镜像对称"。
     */
    private static BakedFace[] bakeOfficialFaces(GeometryData.ParsedCube pc, Vector3f bonePivot, float tw, float th) {
        float inflate = pc.inflate;
        float cx = -pc.origin[0] - pc.size[0] - inflate;
        float cy = pc.origin[1] - inflate;
        float cz = pc.origin[2] - inflate;
        float cw = pc.size[0] + inflate * 2f;
        float ch = pc.size[1] + inflate * 2f;
        float cd = pc.size[2] + inflate * 2f;

        Matrix4f bakeMat = new Matrix4f();
        float[] cpvt = pc.rotPivot != null ? pc.rotPivot : new float[]{0f, 0f, 0f};
        float[] crot = pc.rotation != null ? pc.rotation : new float[]{0f, 0f, 0f};
        if (pc.rotPivot != null || pc.rotation != null) {
            bakeMat.translate(-cpvt[0] / 16f, cpvt[1] / 16f, cpvt[2] / 16f);
            bakeMat.rotateZ((float) Math.toRadians(crot[2]));
            bakeMat.rotateY((float) -Math.toRadians(crot[1]));
            bakeMat.rotateX((float) -Math.toRadians(crot[0]));
            bakeMat.translate(cpvt[0] / 16f, -cpvt[1] / 16f, -cpvt[2] / 16f);
        }
        Matrix3f bakeNormalMat = new Matrix3f();
        bakeMat.normal(bakeNormalMat);

        // 六面 UV 岛（像素，u/v/w/h）：0=north 1=south 2=east 3=west 4=up 5=down（官方顺序）
        float[][] isl = new float[6][];
        if (pc.facesUV != null) {
            // 我们解析的顺序是 WEST,EAST,NORTH,SOUTH,UP,DOWN → 映射到官方顺序
            int[] map = {2, 3, 1, 0, 4, 5};
            for (int i = 0; i < 6; i++) {
                float[] r = pc.facesUV[map[i]];
                if (r != null) isl[i] = new float[]{r[0], r[1], r[2] - r[0], r[3] - r[1]};
            }
        } else {
            // 盒式 UV：官方 :604-614（尺寸 floor 到整数，down 面 uv_size=(dx,-dz)）
            float dx = (float) Math.floor(pc.size[0]);
            float dy = (float) Math.floor(pc.size[1]);
            float dz = (float) Math.floor(pc.size[2]);
            float u = pc.uvU, v = pc.uvV;
            isl[0] = new float[]{u + dz, v + dz, dx, dy};              // north
            isl[1] = new float[]{u + dz + dx + dz, v + dz, dx, dy};    // south
            isl[2] = new float[]{u, v + dz, dz, dy};                  // east
            isl[3] = new float[]{u + dz + dx, v + dz, dz, dy};         // west
            isl[4] = new float[]{u + dz, v, dx, dz};                  // up
            isl[5] = new float[]{u + dz + dx, v + dz, dx, -dz};        // down
        }

        float x1 = cx / 16f, x2 = (cx + cw) / 16f;
        float y1 = cy / 16f, y2 = (cy + ch) / 16f;
        float z1 = cz / 16f, z2 = (cz + cd) / 16f;

        float[] p1 = {x1, y1, z1}, p2 = {x1, y1, z2}, p3 = {x1, y2, z1}, p4 = {x1, y2, z2};
        float[] p5 = {x2, y1, z1}, p6 = {x2, y1, z2}, p7 = {x2, y2, z1}, p8 = {x2, y2, z2};

        // 官方 :665-673
        float[][][] posOrder = {
                {p3, p7, p5, p1},   // north
                {p8, p4, p2, p6},   // south
                {p7, p8, p6, p5},   // east
                {p4, p3, p1, p2},   // west
                {p4, p8, p7, p3},   // up
                {p1, p5, p6, p2},   // down
        };
        float[][] rawNormals = {
                {0f, 0f, -1f}, {0f, 0f, 1f}, {1f, 0f, 0f}, {-1f, 0f, 0f}, {0f, 1f, 0f}, {0f, -1f, 0f}
        };

        boolean mirror = pc.mirror;
        BakedFace[] out = new BakedFace[6];
        int n = 0;
        for (int i = 0; i < 6; i++) {
            int src = i;
            if (mirror && i == 2) src = 3;        // east 取 west 岛
            else if (mirror && i == 3) src = 2;   // west 取 east 岛
            float[] island = isl[src];
            if (island == null) continue;         // 缺面跳过（官方 !uvObj.has(name) 直接 return）

            float u0 = island[0] / tw, v0 = island[1] / th;
            float u1 = (island[0] + island[2]) / tw, v1 = (island[1] + island[3]) / th;
            if (!mirror) { float t = u0; u0 = u1; u1 = t; }

            BakedFace f = new BakedFace();
            for (int k = 0; k < 4; k++) {
                Vector4f tv = new Vector4f(posOrder[i][k][0], posOrder[i][k][1], posOrder[i][k][2], 1f).mul(bakeMat);
                f.pos[k][0] = tv.x() - bonePivot.x();
                f.pos[k][1] = tv.y() - bonePivot.y();
                f.pos[k][2] = tv.z() - bonePivot.z();
            }
            f.u[0] = u0; f.u[1] = u1; f.u[2] = u1; f.u[3] = u0;
            f.v[0] = v0; f.v[1] = v0; f.v[2] = v1; f.v[3] = v1;
            Vector3f nrm = new Vector3f(rawNormals[i][0], rawNormals[i][1], rawNormals[i][2]).mul(bakeNormalMat);
            if (nrm.lengthSquared() > 1e-9f) nrm.normalize();
            f.normal = new float[]{nrm.x(), nrm.y(), nrm.z()};
            out[n++] = f;
        }
        return n == 0 ? null : java.util.Arrays.copyOf(out, n);
    }


    /** 应用骨骼动画姿态（清零由调用方负责）。
     *  animRot 为【动画偏移】（官方 GeckoLib 快照语义：最终旋转 = 动画偏移 + 静止角 restRotRad，
     *  渲染时在 renderBone 中相加），多层驱动时后层覆盖前层（官方 applyTransform 语义）。 */
    public void resetPose() {
        for (Bone b : bonesByName.values()) {
            b.animRot[0] = 0;
            b.animRot[1] = 0;
            b.animRot[2] = 0;
            b.animPos[0] = 0;
            b.animPos[1] = 0;
            b.animPos[2] = 0;
            b.animScale[0] = 1;
            b.animScale[1] = 1;
            b.animScale[2] = 1;
        }
    }

    /** 额外覆盖：对 Head 骨骼叠加头部俯仰/偏航（MC 弧度，符号对齐 GeckoLib EntityModelData: -netHeadYaw / -headPitch） */
    public void applyHeadRotation(float headYawRad, float headPitchRad) {
        if (headBone != null) {
            headBone.animRot[0] += -headPitchRad;
            headBone.animRot[1] += -headYawRad;
        }
    }

    /**
     * 模型几何包围盒（MC 方块单位，静止姿态）。
     *
     * <p>用 {@code Cube.min/max}（addCube 里已经减过骨骼 pivot，是"相对 pivot"的局部坐标）
     * 加回 {@code bone.mcPivot} 得到绝对坐标 —— 只用这一条路径。
     * 之前把烘焙面片顶点（bakeOfficialFaces 里也减过一次 pivot）再加 pivot，等于混算了一遍，
     * 酒狐的中心被算到 y≈0.2 格、高度被低估 20%，实机表现就是"轴心掉回脚底 + 头顶被框切掉"。
     *
     * <p>取顶点 p05~p95 分位：YSM 模型普遍有停在远处待用的表情/幕布面片
     * （酒狐 Expression_3 在 x=±1.94 格、Expression_7 在 y=6 格），纯 min/max 会把包围盒撑大。
     * 预览里本来也隐藏 background* 骨骼，这里同样剔掉。
     *
     * @return {minX,minY,minZ,maxX,maxY,maxZ}；无几何时返回 null
     */
    public float[] bounds() {
        java.util.List<Float> xs = new java.util.ArrayList<>();
        java.util.List<Float> ys = new java.util.ArrayList<>();
        java.util.List<Float> zs = new java.util.ArrayList<>();
        for (Bone bone : bonesByName.values()) {
            if (bone == null || bone.cubes == null || bone.cubes.isEmpty()) continue;
            if (bone.name != null && bone.name.toLowerCase(java.util.Locale.ROOT).contains("background")) continue;
            Vector3f p = bone.mcPivot != null ? bone.mcPivot : new Vector3f();
            for (Cube c : bone.cubes) {
                if (c == null || c.min == null || c.max == null) continue;
                for (int ax = 0; ax < 2; ax++) {
                    for (int ay = 0; ay < 2; ay++) {
                        for (int az = 0; az < 2; az++) {
                            xs.add(p.x + (ax == 0 ? c.min.x : c.max.x));
                            ys.add(p.y + (ay == 0 ? c.min.y : c.max.y));
                            zs.add(p.z + (az == 0 ? c.min.z : c.max.z));
                        }
                    }
                }
            }
        }
        if (xs.size() < 8) return null;
        return new float[]{pct(xs, 0.05f), pct(ys, 0.05f), pct(zs, 0.05f),
                pct(xs, 0.95f), pct(ys, 0.95f), pct(zs, 0.95f)};
    }

    /** 顶点分位值（去掉远处待用面片这类离群点） */
    private static float pct(java.util.List<Float> v, float q) {
        float[] a = new float[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        java.util.Arrays.sort(a);
        int idx = Math.round((a.length - 1) * q);
        if (idx < 0) idx = 0;
        if (idx >= a.length) idx = a.length - 1;
        return a[idx];
    }
}
