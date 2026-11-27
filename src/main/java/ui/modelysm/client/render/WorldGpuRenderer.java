package ui.modelysm.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import ui.modelysm.ModelYSM;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 世界实体 GPU 蒙皮渲染（对齐官方 OpenYSM {@code IrisRenderPath} 的结构）。
 *
 * <p>为什么要这样做（[PERF] 实测）：鲁普雷希特亲王 38940 面 = 155,760 顶点/次，
 * 开着 Iris 时正片 + 阴影 pass 每帧各渲染一次，Java 逐顶点变换要吃掉 70ms/帧；
 * 官方在 {@code NativeModelRenderer:41-59} 走 GPU，所以同模型 60fps。
 *
 * <p>做法（不是照搬官方代码，而是照它的"两段式"思路自己实现）：
 * <ol>
 *   <li><b>静态源网格</b>：每模型烘焙一次（pos3 + uv2 + normal3 + boneId + cullFlag，40B/顶点）
 *       和索引缓冲，只有模型换贴图/重载才重建；</li>
 *   <li><b>compute shader 蒙皮</b>（GLSL 430）：读源网格 + 骨骼 SSBO（mat4 变换 + mat4 法线 +
 *       packedLight，144B/骨），把结果写进 36B/顶点的 "原版实体顶点格式" 缓冲
 *       （pos3f + color4ub + uv2f + uv1(2s) + uv2(2s) + normal3b，与
 *       {@code DefaultVertexFormat.NEW_ENTITY} 逐字节一致）；逐面剔除也在 compute 里做
 *       （行列式判定，与 CPU 路径 {@code faceCulledDet} 同一公式，剔掉的面塌成零面积）；</li>
 *   <li><b>用原版 RenderType 绘制</b>：{@code RenderType.entityCutoutNoCull(贴图)} +
 *       自己设 ModelView/Projection/ColorModulator 后一次 {@code glDrawElements}。
 *       这一步是兼容 Iris 的关键——官方同理：蒙皮结果喂回原版管线，
 *       光影包的 gbuffer / 阴影 / 雾 / 光照贴图全部照常工作，不用自己写片元着色器。</li>
 * </ol>
 *
 * <p>不可用时（无 GL4.3 / 编译失败 / 网格构建失败）返回 false，调用方原地退回 CPU 扁平路径，
 * 行为与第二档完全一致。
 */
public final class WorldGpuRenderer {
    private WorldGpuRenderer() {}

    /** 总开关：false 则世界实体全部回到 CPU 扁平路径 */
    public static boolean USE_GPU_WORLD = true;

    /** 有界面打开时是否仍允许世界实体走 GPU（关掉=退回"开界面就 CPU"的旧守卫，仅作应急） */
    public static boolean ALLOW_WITH_SCREEN = true;

    private static final long BUDGET_BYTES = 512L * 1024 * 1024;
    private static final int SRC_STRIDE = 40;    // pos3f(0) uv2f(12) normal3f(20) boneId(32) flags(36)
    private static final int OUT_STRIDE = 36;    // 原版 NEW_ENTITY 布局
    private static final int BONE_STRIDE = 144;  // mat4(64) + mat4(64) + int(4) + 3×pad(12)

    private static boolean initTried, initOk, failed;
    private static int program = -1;
    private static int uProjMV = -1, uOverlay = -1, uCullOn = -1, uPreview = -1, uFlipZ = -1;
    private static long usedBytes;

    private static final float[] PROJMV = new float[16];
    private static final Matrix4f SCRATCH = new Matrix4f();

    private static final class Mesh {
        int srcVbo = -1, ibo = -1, boneSsbo = -1, outVbo = -1, outVao = -1;
        int vertexCount, indexCount, bones;
        long bytes;
        ByteBuffer boneBuf;   // 每帧复用的骨骼 SSBO 暂存（144B/骨）
    }

    private static final Map<BedrockModel, Mesh> MESHES = new LinkedHashMap<>(16, 0.75f, true);

    // ------------------------------------------------------------------ 初始化

    private static boolean available() {
        if (failed || !USE_GPU_WORLD) return false;
        if (!initTried) {
            initTried = true;
            initOk = init();
        }
        return initOk;
    }

    private static boolean init() {
        try {
            if (!GL.getCapabilities().OpenGL43) {
                ModelYSM.LOGGER.info("[GPUW] 显卡不支持 GL4.3（compute/SSBO），世界实体继续走 CPU 路径");
                return false;
            }
            program = buildProgram();
            if (program <= 0) return false;
            uProjMV = GL20.glGetUniformLocation(program, "u_projMV");
            uOverlay = GL20.glGetUniformLocation(program, "u_overlay");
            uCullOn = GL20.glGetUniformLocation(program, "u_cullOn");
            uPreview = GL20.glGetUniformLocation(program, "u_preview");
            uFlipZ = GL20.glGetUniformLocation(program, "u_flipZ");
            ModelYSM.LOGGER.info("[GPUW] GPU 蒙皮世界渲染已启用（vendor={}）", GL11.glGetString(GL11.GL_VENDOR));
            return true;
        } catch (Throwable t) {
            ModelYSM.LOGGER.warn("[GPUW] 初始化失败，世界实体继续走 CPU 路径：{}", t.toString());
            return false;
        }
    }

    private static int buildProgram() {
        int cs = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(cs, CSH);
        GL20.glCompileShader(cs);
        if (GL20.glGetShaderi(cs, GL20.GL_COMPILE_STATUS) == 0) {
            ModelYSM.LOGGER.warn("[GPUW] compute 着色器编译失败：{}", GL20.glGetShaderInfoLog(cs));
            GL20.glDeleteShader(cs);
            return -1;
        }
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, cs);
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(cs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            ModelYSM.LOGGER.warn("[GPUW] compute 着色器链接失败：{}", GL20.glGetProgramInfoLog(p));
            GL20.glDeleteProgram(p);
            return -1;
        }
        return p;
    }

    private static final String CSH = String.join("\n",
            "#version 430 core",
            "layout(local_size_x = 64) in;",
            "layout(std430, binding = 0) readonly buffer Src { uint raw_in[]; };",
            "layout(std430, binding = 1) writeonly buffer Dst { uint raw_out[]; };",
            "struct BoneData { mat4 transform; mat4 normal; int packedLight; int pad0; int pad1; int pad2; };",
            "layout(std430, binding = 2) readonly buffer Bones { BoneData bones[]; };",
            "uniform mat4 u_projMV;",
            "uniform int u_overlay;",
            "uniform int u_cullOn;",
            "uniform int u_preview;",
            "uniform float u_flipZ;",
            "float u2f(uint u) { return uintBitsToFloat(u); }",
            "uint f2u(float f) { return floatBitsToUint(f); }",
            "vec3 readPos(uint b) { return vec3(u2f(raw_in[b]), u2f(raw_in[b + 1u]), u2f(raw_in[b + 2u])); }",
            "uint packShorts(int a, int b) { return (uint(a) & 0xFFFFu) | ((uint(b) & 0xFFFFu) << 16u); }",
            "uint packNormalBytes(vec3 n) {",
            "    ivec3 q = ivec3(clamp(n, -1.0, 1.0) * 127.0);",
            "    return (uint(q.x) & 0xFFu) | ((uint(q.y) & 0xFFu) << 8u) | ((uint(q.z) & 0xFFu) << 16u);",
            "}",
            "void main() {",
            "    uint vid = gl_GlobalInvocationID.x;",
            "    if (vid * 10u >= uint(raw_in.length())) return;",
            "    uint ib = vid * 10u;",
            "    uint ob = vid * 9u;",
            "    vec3 lp = readPos(ib);",
            "    vec2 uv = vec2(u2f(raw_in[ib + 3u]), u2f(raw_in[ib + 4u]));",
            "    vec3 ln = readPos(ib + 5u);",
            "    uint boneId = raw_in[ib + 8u];",
            "    uint flags = raw_in[ib + 9u];",
            "    BoneData b = bones[boneId];",
            "    ln.z *= u_flipZ;",   // 预览镜像口径（与 CPU 路径 quadBakedFast 的 nz 补偿一致）
            "    uint qb = vid & ~3u;",
            "    vec3 anchor = (b.transform * vec4(readPos(qb * 10u), 1.0)).xyz;",
            "    vec3 op = (b.transform * vec4(lp, 1.0)).xyz;",
            "    vec3 on = normalize((b.normal * vec4(ln, 0.0)).xyz);",
            "    bool collapse = false;",
            "    if (u_cullOn != 0 && (flags & 1u) != 0u) {",
            // 逐面行列式剔除：与 CPU 路径 faceCulledDet 同一公式（用 proj×MV×骨矩阵的 clip 坐标 x/y/w）
            "        vec4 c0 = u_projMV * b.transform * vec4(readPos(qb * 10u), 1.0);",
            "        vec4 c1 = u_projMV * b.transform * vec4(readPos(qb * 10u + 10u), 1.0);",
            "        vec4 c2 = u_projMV * b.transform * vec4(readPos(qb * 10u + 20u), 1.0);",
            "        float det = c0.x * (c1.y * c2.w - c2.y * c1.w)",
            "                  - c1.x * (c0.y * c2.w - c2.y * c0.w)",
            "                  + c2.x * (c0.y * c1.w - c1.y * c0.w);",
            "        if (det <= 0.0) collapse = true;",
            "    }",
            // 预览期零厚度薄片只保留法线 -Z 的面（flags bit1=薄片, bit2=法线 z>0），
            // 与 CPU 路径 emitBakedCube 的规则一致，否则背面会把正面盖暗
            "    if (u_preview != 0 && (flags & 2u) != 0u && (flags & 4u) != 0u) collapse = true;",
            "    if (collapse) {",
            // 剔掉的面把四个顶点塌到 v0（零面积三角形），等价于 CPU 路径"这一面不提交顶点"
            "        op = anchor;",
            "        on = vec3(0.0, 1.0, 0.0);",
            "    }",
            "    int blockL = (b.packedLight >> 4) & 0xF;",
            "    int skyL = (b.packedLight >> 20) & 0xF;",
            "    int ou = u_overlay & 0xFFFF;",
            "    int ov = (u_overlay >> 16) & 0xFFFF;",
            "    raw_out[ob] = f2u(op.x);",
            "    raw_out[ob + 1u] = f2u(op.y);",
            "    raw_out[ob + 2u] = f2u(op.z);",
            "    raw_out[ob + 3u] = 0xFFFFFFFFu;",            // 顶点色全白，与 CPU 路径 vc.color(1,1,1,1) 一致
            "    raw_out[ob + 4u] = f2u(uv.x);",
            "    raw_out[ob + 5u] = f2u(uv.y);",
            "    raw_out[ob + 6u] = packShorts(ou, ov);",     // UV1 = overlay
            "    raw_out[ob + 7u] = packShorts(blockL * 16, skyL * 16);", // UV2 = 光照贴图坐标
            "    raw_out[ob + 8u] = packNormalBytes(on);",
            "}",
            "");

    // ------------------------------------------------------------------ 网格

    private static Mesh mesh(BedrockModel model, NpcRenderer.FlatInfo table) {
        Mesh m = MESHES.get(model);
        if (m != null) return m;
        if (!available()) return null;
        try {
            m = build(table);
        } catch (Throwable t) {
            ModelYSM.LOGGER.warn("[GPUW] 网格构建失败，世界实体继续走 CPU 路径：{}", t.toString());
            failed = true;
            return null;
        }
        if (m == null) return null;
        MESHES.put(model, m);
        usedBytes += m.bytes;
        evict();
        ModelYSM.LOGGER.info("[GPUW] 网格已上传：顶点={} 三角={} 骨骼={} 显存≈{}KB",
                m.vertexCount, m.indexCount / 3, m.bones, m.bytes / 1024);
        return m;
    }

    private static void evict() {
        Iterator<Map.Entry<BedrockModel, Mesh>> it = MESHES.entrySet().iterator();
        while (usedBytes > BUDGET_BYTES && it.hasNext() && MESHES.size() > 1) {
            Map.Entry<BedrockModel, Mesh> e = it.next();
            free(e.getValue());
            usedBytes -= e.getValue().bytes;
            it.remove();
        }
    }

    private static void free(Mesh m) {
        if (m.outVao >= 0) GlStateManager._glDeleteVertexArrays(m.outVao);
        if (m.srcVbo >= 0) GlStateManager._glDeleteBuffers(m.srcVbo);
        if (m.ibo >= 0) GlStateManager._glDeleteBuffers(m.ibo);
        if (m.boneSsbo >= 0) GlStateManager._glDeleteBuffers(m.boneSsbo);
        if (m.outVbo >= 0) GlStateManager._glDeleteBuffers(m.outVbo);
        m.srcVbo = m.ibo = m.boneSsbo = m.outVbo = m.outVao = -1;
    }

    private static Mesh build(NpcRenderer.FlatInfo table) {
        int faces = 0;
        for (BedrockModel.Bone b : table.bones) {
            for (BedrockModel.Cube c : b.cubes) {
                if (c.bakedFaces != null) faces += c.bakedFaces.length;
            }
        }
        if (faces <= 0) return null;

        ByteBuffer src = BufferUtils.createByteBuffer(faces * 4 * SRC_STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        IntBuffer idx = BufferUtils.createIntBuffer(faces * 6);

        int vcount = 0;
        for (int bi = 0; bi < table.bones.length; bi++) {
            for (BedrockModel.Cube cube : table.bones[bi].cubes) {
                if (cube.bakedFaces == null) continue;
                // flags: bit0=可剔除 cube，bit1=零厚度薄片，bit2=该面法线 z>0（预览期用它只保留 -Z 面）
                boolean flat = Math.abs(cube.max.x() - cube.min.x()) < 1e-3f
                        || Math.abs(cube.max.y() - cube.min.y()) < 1e-3f
                        || Math.abs(cube.max.z() - cube.min.z()) < 1e-3f;
                int cubeFlags = (cube.cullable ? 1 : 0) | (flat ? 2 : 0);
                for (BedrockModel.BakedFace f : cube.bakedFaces) {
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
                    int faceFlags = cubeFlags | (f.normal[2] > 0f ? 4 : 0);
                    for (int k = 0; k < 4; k++) {
                        src.putFloat(f.pos[k][0]).putFloat(f.pos[k][1]).putFloat(f.pos[k][2]);
                        src.putFloat(f.u[k]).putFloat(f.v[k]);
                        src.putFloat(nx).putFloat(ny).putFloat(nz);
                        src.putInt(bi).putInt(faceFlags);
                    }
                    int b0 = vcount;
                    idx.put(b0).put(b0 + 1).put(b0 + 2);
                    idx.put(b0 + 2).put(b0 + 3).put(b0);
                    vcount += 4;
                }
            }
        }
        src.flip();
        idx.flip();

        Mesh m = new Mesh();
        m.vertexCount = vcount;
        m.indexCount = idx.remaining();
        m.bones = table.bones.length;
        m.boneBuf = BufferUtils.createByteBuffer(m.bones * BONE_STRIDE).order(ByteOrder.LITTLE_ENDIAN);

        m.srcVbo = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, m.srcVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, src, GL15.GL_STATIC_DRAW);

        m.ibo = GlStateManager._glGenBuffers();

        m.boneSsbo = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, m.boneSsbo);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) m.bones * BONE_STRIDE, GL15.GL_DYNAMIC_DRAW);

        GlStateManager._glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        // 蒙皮输出：原版 NEW_ENTITY 顶点格式（pos3f / color4ub / uv2f / uv1 2s / uv2 2s / normal3b）
        // 注意核心模式里 GL_ELEMENT_ARRAY_BUFFER 只能挂在 VAO 上，所以索引缓冲必须在 VAO 绑定后创建
        m.outVbo = GlStateManager._glGenBuffers();
        m.outVao = GlStateManager._glGenVertexArrays();
        GlStateManager._glBindVertexArray(m.outVao);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, m.outVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) m.vertexCount * OUT_STRIDE, GL15.GL_DYNAMIC_DRAW);
        GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, m.ibo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idx, GL15.GL_STATIC_DRAW);
        GlStateManager._vertexAttribPointer(0, 3, GL11.GL_FLOAT, false, OUT_STRIDE, 0L);
        GlStateManager._vertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, OUT_STRIDE, 12L);
        GlStateManager._vertexAttribPointer(2, 2, GL11.GL_FLOAT, false, OUT_STRIDE, 16L);
        GL30.glVertexAttribIPointer(3, 2, GL11.GL_SHORT, OUT_STRIDE, 24L);
        GL30.glVertexAttribIPointer(4, 2, GL11.GL_SHORT, OUT_STRIDE, 28L);
        GlStateManager._vertexAttribPointer(5, 3, GL11.GL_BYTE, true, OUT_STRIDE, 32L);
        GlStateManager._enableVertexAttribArray(0);
        GlStateManager._enableVertexAttribArray(1);
        GlStateManager._enableVertexAttribArray(2);
        GlStateManager._enableVertexAttribArray(3);
        GlStateManager._enableVertexAttribArray(4);
        GlStateManager._enableVertexAttribArray(5);
        GlStateManager._glBindVertexArray(0);
        // EBO 绑定归属 VAO，解绑 VAO 后不要再碰 GL_ELEMENT_ARRAY_BUFFER（VAO 0 上绑定即 GL_INVALID_OPERATION）
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        m.bytes = (long) vcount * SRC_STRIDE + (long) m.indexCount * 4L + (long) m.bones * BONE_STRIDE
                + (long) vcount * OUT_STRIDE;
        return m;
    }

    // ------------------------------------------------------------------ 绘制

    /** 这个世界实体/预览这一帧能不能走 GPU（否则调用方回 CPU 扁平路径） */
    public static boolean shouldUse(BedrockModel model, ResourceLocation texture, NpcRenderer.FlatInfo table,
                                    boolean preview) {
        if (!USE_GPU_WORLD || failed || table == null || !table.allBaked || texture == null) return false;
        // 【不要再按"有没有界面"退回 CPU】：
        // 曾经为了躲开 GUI 事故加过 `if (!preview && screen != null) return false`，但那是当时
        // 世界路径漏写 !previewMode、在 GUI 里即时绘制造成的；现在预览已改成离屏 FBO，
        // 世界绘制发生在 renderLevel 阶段、用的是和原版实体完全同一个 RenderType，
        // 留下的状态与"原版画完一个实体"没有区别。
        // 这条守卫的实际后果是：世界里放一只高面数 NPC 后，只要打开任何界面（我们的列表、
        // 官方 YSM 的列表、背包……）它立刻退回 CPU 每帧 2 个 pass 各 30ms，把所有界面一起拖到 10fps。
        // 应急开关（默认放开）：关掉时退回旧守卫"有界面就 CPU"
        if (!preview && !ALLOW_WITH_SCREEN && net.minecraft.client.Minecraft.getInstance().screen != null) {
            return false;
        }
        return mesh(model, table) != null;
    }

    /**
     * 只做 compute 蒙皮：上传骨骼 SSBO 并 dispatch，把结果写进 {@code outVbo}。
     * 不碰任何渲染/程序状态之外的东西，供离屏预览（{@link PreviewFboRenderer}）复用。
     *
     * @param viewProj 蒙皮剔除要用的 proj×view×骨 口径（预览不剔除，随便传）
     */
    public static boolean skin(BedrockModel model, NpcRenderer.FlatInfo table, boolean cullOn, boolean preview,
                               Matrix4f viewProj) {
        Mesh m = MESHES.get(model);
        if (m == null || program <= 0) return false;
        // 1) 骨骼数据：mat4 变换 + mat4 法线 + packedLight（与 CPU 路径同一批 fi.mats/fi.norms）
        ByteBuffer bb = m.boneBuf;
        bb.clear();
        for (int i = 0; i < m.bones; i++) {
            Matrix4f mat = table.mats[i];
            bb.putFloat(mat.m00()).putFloat(mat.m01()).putFloat(mat.m02()).putFloat(mat.m03());
            bb.putFloat(mat.m10()).putFloat(mat.m11()).putFloat(mat.m12()).putFloat(mat.m13());
            bb.putFloat(mat.m20()).putFloat(mat.m21()).putFloat(mat.m22()).putFloat(mat.m23());
            bb.putFloat(mat.m30()).putFloat(mat.m31()).putFloat(mat.m32()).putFloat(mat.m33());
            org.joml.Matrix3f n = table.norms[i];
            // 列向量口径与 CPU 的 tnx = norm.m00()*x + norm.m10()*y + norm.m20()*z 完全一致
            bb.putFloat(n.m00()).putFloat(n.m01()).putFloat(n.m02()).putFloat(0f);
            bb.putFloat(n.m10()).putFloat(n.m11()).putFloat(n.m12()).putFloat(0f);
            bb.putFloat(n.m20()).putFloat(n.m21()).putFloat(n.m22()).putFloat(0f);
            bb.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f);
            bb.putInt(i < table.lights.length ? table.lights[i] : 0xF000F0);
            bb.putInt(0).putInt(0).putInt(0);
        }
        bb.position(0);

        // 2) 上传骨骼 SSBO + 绑定三段缓冲，dispatch
        GlStateManager._glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, m.boneSsbo);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, bb);

        GlStateManager._glUseProgram(program);
        SCRATCH.set(viewProj);
        SCRATCH.get(PROJMV);
        if (uProjMV >= 0) GL20.glUniformMatrix4fv(uProjMV, false, PROJMV);
        if (uOverlay >= 0) GL20.glUniform1i(uOverlay, OverlayTexture.NO_OVERLAY);
        if (uCullOn >= 0) GL20.glUniform1i(uCullOn, cullOn ? 1 : 0);
        if (uPreview >= 0) GL20.glUniform1i(uPreview, preview ? 1 : 0);
        if (uFlipZ >= 0) GL20.glUniform1f(uFlipZ,
                (NpcRenderer.previewCullOff && !NpcRenderer.previewMirrorInModelView) ? -1f : 1f);

        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, m.srcVbo);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, m.outVbo);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, m.boneSsbo);
        GL43.glDispatchCompute((m.vertexCount + 63) / 64, 1, 1);
        GL43.glMemoryBarrier(GL43.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT | GL43.GL_ELEMENT_ARRAY_BARRIER_BIT);
        GlStateManager._glUseProgram(0);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, 0);
        GlStateManager._glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        return true;
    }

    /** 蒙皮结果一次 glDrawElements（调用方自己负责程序/纹理/状态；离屏预览专用） */
    public static boolean drawSkinned(BedrockModel model) {
        Mesh m = MESHES.get(model);
        if (m == null || m.outVao < 0) return false;
        GlStateManager._glBindVertexArray(m.outVao);
        GL11.glDrawElements(GL11.GL_TRIANGLES, m.indexCount, GL11.GL_UNSIGNED_INT, 0L);
        GlStateManager._glBindVertexArray(0);
        return true;
    }

    /**
     * compute 蒙皮 + 原版 RenderType 绘制（世界实体路径）。返回 false 时调用方需要补一遍 CPU 路径。
     *
     * @param cullOn 与 CPU 路径同口径的逐面剔除开关（世界用 ENABLE_CUBE_CULLING，预览关）
     * @param preview true=GUI 预览（已由离屏方案取代，仅保留兼容分支）
     */
    public static boolean draw(BedrockModel model, NpcRenderer.FlatInfo table, ResourceLocation texture,
                               boolean cullOn, boolean preview, MultiBufferSource buffers) {
        Mesh m = MESHES.get(model);
        if (m == null || program <= 0) return false;
        // 预览：先把已排队的 GUI 顶点刷出去（卡片底图/边框），否则它们会在我们之后绘制、盖住模型
        boolean restoreState = preview;
        boolean depthTest = false, depthMask = true, blend = false, cull = true;
        int depthFunc = GL11.GL_LEQUAL;
        if (restoreState) {
            if (buffers instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
            depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            blend = GL11.glIsEnabled(GL11.GL_BLEND);
            cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        }
        try {
            SCRATCH.set(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix());
            if (!skin(model, table, cullOn, preview, SCRATCH)) return false;

            // 3) 原版 RenderType 绘制（Iris 光影包下由 Iris 接管程序，阴影/gbuffer 照常）
            RenderType rt = RenderType.entityCutoutNoCull(texture);
            rt.setupRenderState();
            ShaderInstance sh = RenderSystem.getShader();
            if (sh == null) {
                rt.clearRenderState();
                return false;
            }
            if (sh.MODEL_VIEW_MATRIX != null) sh.MODEL_VIEW_MATRIX.set(RenderSystem.getModelViewMatrix());
            if (sh.PROJECTION_MATRIX != null) sh.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
            if (sh.COLOR_MODULATOR != null) sh.COLOR_MODULATOR.set(1.0f, 1.0f, 1.0f, 1.0f);
            sh.apply();

            drawSkinned(model);

            sh.clear();
            BufferUploader.invalidate();
            rt.clearRenderState();
            if (restoreState) {
                // GUI 预览：把进来时的 GL 状态原样放回去（深度/混合/剔除），避免污染后续 GUI 绘制
                if (depthTest) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
                GlStateManager._depthFunc(depthFunc);
                GlStateManager._depthMask(depthMask);
                if (blend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
                if (cull) GlStateManager._enableCull(); else GlStateManager._disableCull();
            }
            return true;
        } catch (Throwable t) {
            ModelYSM.LOGGER.warn("[GPUW] 绘制失败，世界实体退回 CPU 路径：{}", t.toString());
            failed = true;
            return false;
        }
    }
}
