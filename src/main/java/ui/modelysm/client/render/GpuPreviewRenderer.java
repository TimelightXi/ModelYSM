package ui.modelysm.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import ui.modelysm.ModelYSM;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第三档：GPU 蒙皮预览渲染（只用于 GUI 预览：卡片 / 左侧大预览）。
 *
 * <p>结构对齐官方 OpenYSM 的 GPU 路径（{@code GpuRenderPath.java} + {@code bone_skin.vsh}），
 * 但完全不依赖 native 和 SSBO（官方用 GL4.3 的 SSBO 传骨骼矩阵，这里改用 RGBA32F 矩阵纹理，
 * GL3.2 核心档即可，任何能跑 1.20.1 的机器都支持）：
 * <ul>
 *   <li>每个模型烘焙一次静态 VBO（pos3 + uv2 + normal3 + boneId1 = 36B/顶点）+ 索引缓冲；</li>
 *   <li>每帧只上传「每骨矩阵 + 每骨法线矩阵 + 每骨光照颜色」到 8 texel/骨 的矩阵纹理；</li>
 *   <li>顶点变换、光照、alpha cutout 全在 GPU，一次 {@code glDrawElements} 画完整只模型，
 *       Java 端每帧只剩 N 个骨骼矩阵合成，不再往 BufferBuilder 写几十万顶点。</li>
 * </ul>
 *
 * <p>与 CPU 路径逐项对齐（不是"照抄个大概"）：
 * 顶点 = ProjMat × ModelViewMat × Pose × BoneMat × pos；
 * 光照 = vanilla {@code light.glsl} 的 {@code min(1, (max(0,dot(L0,n)) + max(0,dot(L1,n))) * 0.6 + 0.4)}，
 * 预览统一 {@code Lighting.setupForEntityInInventory()} → L0=normalize(0.2,-1,-1)、L1=normalize(-0.2,-1,0)；
 * 光照贴图色直接在 CPU 端从原版 {@code LightTexture} 的 16×16 NativeImage 取（等价于
 * {@code texelFetch(lightMap, UV2/16)}，但要按每骨光照取一行，所以按骨预计算进调色板）；
 * 法线只累积旋转（{@code PoseStack.normal()} 同口径），镜像 Z 翻转由 {@code FlipZ} 控制；
 * cutout 阈值 0.1 与 {@code rendertype_entity_cutout_no_cull.fsh} 一致。
 *
 * <p>任何一步失败（GL 不支持 / 编译失败 / 显存不足 / 纹理未就绪）整体降级回 CPU 路径。
 */
public final class GpuPreviewRenderer {
    private GpuPreviewRenderer() {}

    /** 总开关（出问题一行 false 即回到纯 CPU 路径） */
    public static boolean ENABLED = true;
    /** 静态网格缓存预算 */
    private static final long BUDGET_BYTES = 256L * 1024 * 1024;

    private static final int STRIDE = 9 * 4;   // pos3 + uv2 + normal3 + boneId1
    private static final int PAL_W = 8;        // 每骨 8 个 RGBA32F texel：mat4(4) + mat3(3) + lightColor(1)

    private static boolean initTried, initOk, failed;
    private static int program = -1;
    private static int uMVP = -1, uPoseNorm = -1, uFlipZ = -1, uL0 = -1, uL1 = -1;
    private static int uTex = -1, uBoneTex = -1;
    private static int paletteTex = -1, paletteBones = -1;
    private static long usedBytes;

    private static final float[] MAT16 = new float[16];
    private static final float[] MAT9 = new float[9];
    private static final Matrix4f SCRATCH_MVP = new Matrix4f();
    private static final Matrix3f SCRATCH_N = new Matrix3f();

    /** 原版光照贴图的 16×16 像素（取不到就退化为纯白 = 全亮） */
    private static DynamicTexture lightTex;
    private static boolean lightTexTried;

    private static final String VS = String.join("\n",
            "#version 150",
            "in vec3 Position;",
            "in vec2 UV0;",
            "in vec3 Normal;",
            "in float Bone;",
            "uniform mat4 MVP;",
            "uniform mat3 PoseNorm;",
            "uniform float FlipZ;",
            "uniform sampler2D BoneTex;",
            "out vec2 vUV;",
            "out vec3 vNormal;",
            "out float vBone;",
            "void main() {",
            "    int b = int(Bone);",
            "    mat4 bm = mat4(texelFetch(BoneTex, ivec2(0, b), 0), texelFetch(BoneTex, ivec2(1, b), 0),",
            "                   texelFetch(BoneTex, ivec2(2, b), 0), texelFetch(BoneTex, ivec2(3, b), 0));",
            "    mat3 nm = mat3(texelFetch(BoneTex, ivec2(4, b), 0).xyz, texelFetch(BoneTex, ivec2(5, b), 0).xyz,",
            "                   texelFetch(BoneTex, ivec2(6, b), 0).xyz);",
            "    gl_Position = MVP * (bm * vec4(Position, 1.0));",
            "    vec3 n = Normal;",
            "    n.z *= FlipZ;",
            "    vNormal = PoseNorm * (nm * n);",
            "    vUV = UV0;",
            "    vBone = Bone;",
            "}",
            "");

    private static final String FS = String.join("\n",
            "#version 150",
            "in vec2 vUV;",
            "in vec3 vNormal;",
            "in float vBone;",
            "uniform sampler2D Tex;",
            "uniform sampler2D BoneTex;",
            "uniform vec3 L0;",
            "uniform vec3 L1;",
            "out vec4 fragColor;",
            "void main() {",
            "    vec4 c = texture(Tex, vUV);",
            "    if (c.a < 0.1) discard;",
            "    vec3 n = normalize(vNormal);",
            "    float acc = min(1.0, (max(0.0, dot(L0, n)) + max(0.0, dot(L1, n))) * 0.6 + 0.4);",
            "    vec3 lightColor = texelFetch(BoneTex, ivec2(7, int(vBone)), 0).rgb;",
            "    fragColor = vec4(c.rgb * acc * lightColor, c.a);",
            "}",
            "");

    private static final class Mesh {
        int vao = -1, vbo = -1, eboFull = -1, eboPrev = -1;
        int idxFull, idxPrev;
        int bones;
        long bytes;
    }

    private static final Map<BedrockModel, Mesh> MESHES = new LinkedHashMap<>(16, 0.75f, true);

    // ------------------------------------------------------------------ 初始化

    private static boolean available() {
        if (failed) return false;
        if (!initTried) {
            initTried = true;
            initOk = init();
        }
        return initOk;
    }

    private static boolean init() {
        try {
            String vendor = GL11.glGetString(GL11.GL_VENDOR);
            program = buildProgram();
            if (program <= 0) return false;
            uMVP = GL20.glGetUniformLocation(program, "MVP");
            uPoseNorm = GL20.glGetUniformLocation(program, "PoseNorm");
            uFlipZ = GL20.glGetUniformLocation(program, "FlipZ");
            uL0 = GL20.glGetUniformLocation(program, "L0");
            uL1 = GL20.glGetUniformLocation(program, "L1");
            uTex = GL20.glGetUniformLocation(program, "Tex");
            uBoneTex = GL20.glGetUniformLocation(program, "BoneTex");
            if (uMVP < 0 || uPoseNorm < 0 || uBoneTex < 0 || uTex < 0) {
                ModelYSM.LOGGER.warn("[GPU] uniform 定位失败，回退 CPU 路径");
                return false;
            }
            paletteTex = GlStateManager._genTexture();
            ModelYSM.LOGGER.info("[GPU] 蒙皮预览渲染已启用（vendor={}）", vendor == null ? "?" : vendor);
            return true;
        } catch (Throwable t) {
            ModelYSM.LOGGER.warn("[GPU] 初始化失败，回退 CPU 路径：{}", t.toString());
            return false;
        }
    }

    private static int buildProgram() {
        int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vs, VS);
        GL20.glCompileShader(vs);
        if (GL20.glGetShaderi(vs, GL20.GL_COMPILE_STATUS) == 0) {
            ModelYSM.LOGGER.warn("[GPU] 顶点着色器编译失败：{}", GL20.glGetShaderInfoLog(vs));
            GL20.glDeleteShader(vs);
            return -1;
        }
        int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(fs, FS);
        GL20.glCompileShader(fs);
        if (GL20.glGetShaderi(fs, GL20.GL_COMPILE_STATUS) == 0) {
            ModelYSM.LOGGER.warn("[GPU] 片元着色器编译失败：{}", GL20.glGetShaderInfoLog(fs));
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            return -1;
        }
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GlStateManager._glBindAttribLocation(p, 0, "Position");
        GlStateManager._glBindAttribLocation(p, 1, "UV0");
        GlStateManager._glBindAttribLocation(p, 2, "Normal");
        GlStateManager._glBindAttribLocation(p, 3, "Bone");
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            ModelYSM.LOGGER.warn("[GPU] 着色器链接失败：{}", GL20.glGetProgramInfoLog(p));
            GL20.glDeleteProgram(p);
            return -1;
        }
        return p;
    }

    /**
     * 原版 LightTexture 是 16×16 DynamicTexture，注册名固定 {@code dynamic/light_map_N}。
     * 只用来读像素（等价于 shader 里 texelFetch），取不到就退化成全亮。
     */
    private static DynamicTexture lightTexture() {
        if (lightTexTried) return lightTex;
        lightTexTried = true;
        try {
            for (int i = 1; i <= 4; i++) {
                ResourceLocation rl = new ResourceLocation("minecraft", "dynamic/light_map_" + i);
                AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(rl, null);
                if (t instanceof DynamicTexture dt && dt.getPixels() != null
                        && dt.getPixels().getWidth() == 16 && dt.getPixels().getHeight() == 16) {
                    lightTex = dt;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return lightTex;
    }

    /** packedLight → 原版光照贴图在该 (block,sky) 格子上的颜色 */
    private static void lightColor(int packedLight, float[] out) {
        DynamicTexture dt = lightTexture();
        if (dt == null) {
            out[0] = 1f; out[1] = 1f; out[2] = 1f;
            return;
        }
        int bx = (packedLight & 0xFFFF) / 16;
        int by = ((packedLight >>> 16) & 0xFFFF) / 16;
        bx = Math.max(0, Math.min(15, bx));
        by = Math.max(0, Math.min(15, by));
        int argb = dt.getPixels().getPixelRGBA(bx, by);
        out[0] = (float) (argb & 0xFF) / 255f;
        out[1] = (float) ((argb >>> 8) & 0xFF) / 255f;
        out[2] = (float) ((argb >>> 16) & 0xFF) / 255f;
    }

    // ------------------------------------------------------------------ 网格

    private static Mesh mesh(BedrockModel model, NpcRenderer.FlatInfo table) {
        Mesh m = MESHES.get(model);
        if (m != null) return m;
        if (!available()) return null;
        try {
            m = buildMesh(table);
        } catch (Throwable t) {
            ModelYSM.LOGGER.warn("[GPU] 网格构建失败，回退 CPU：{}", t.toString());
            failed = true;
            return null;
        }
        if (m == null) return null;
        MESHES.put(model, m);
        usedBytes += m.bytes;
        evict();
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
        if (m.vao >= 0) GlStateManager._glDeleteVertexArrays(m.vao);
        if (m.vbo >= 0) GlStateManager._glDeleteBuffers(m.vbo);
        if (m.eboFull >= 0) GlStateManager._glDeleteBuffers(m.eboFull);
        if (m.eboPrev >= 0) GlStateManager._glDeleteBuffers(m.eboPrev);
        m.vao = m.vbo = m.eboFull = m.eboPrev = -1;
    }

    /** 从扁平骨架表烘焙静态网格；boneId = 扁平索引，与 renderFlatBaked 的矩阵下标一致 */
    private static Mesh buildMesh(NpcRenderer.FlatInfo table) {
        int faces = 0;
        int prevFaces = 0;
        for (BedrockModel.Bone b : table.bones) {
            for (BedrockModel.Cube c : b.cubes) {
                if (c.bakedFaces == null) continue;
                faces += c.bakedFaces.length;
                for (BedrockModel.BakedFace f : c.bakedFaces) {
                    if (f.normal[2] <= 0f) prevFaces++;
                }
            }
        }
        if (faces <= 0) return null;

        FloatBuffer verts = BufferUtils.createFloatBuffer(faces * 4 * 9);
        IntBuffer idxFull = BufferUtils.createIntBuffer(faces * 6);
        IntBuffer idxPrev = BufferUtils.createIntBuffer(Math.max(prevFaces, 1) * 6);

        int base = 0, prevBase = 0;
        for (int bi = 0; bi < table.bones.length; bi++) {
            for (BedrockModel.Cube c : table.bones[bi].cubes) {
                if (c.bakedFaces == null) continue;
                for (BedrockModel.BakedFace f : c.bakedFaces) {
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
                    for (int i = 0; i < 4; i++) {
                        verts.put(f.pos[i][0]).put(f.pos[i][1]).put(f.pos[i][2]);
                        verts.put(f.u[i]).put(f.v[i]);
                        verts.put(nx).put(ny).put(nz);
                        verts.put((float) bi);
                    }
                    idxFull.put(base).put(base + 1).put(base + 2);
                    idxFull.put(base).put(base + 2).put(base + 3);
                    // 预览期（previewCullOff）只保留法线 -Z 的面：零厚度薄片正反共面，
                    // 后画的背面会把正面盖暗。规则与 CPU 路径 emitBakedCube 完全一致。
                    if (f.normal[2] <= 0f) {
                        idxPrev.put(prevBase).put(prevBase + 1).put(prevBase + 2);
                        idxPrev.put(prevBase).put(prevBase + 2).put(prevBase + 3);
                        prevBase += 4;
                    }
                    base += 4;
                }
            }
        }
        verts.flip();
        idxFull.flip();
        idxPrev.flip();

        Mesh m = new Mesh();
        m.bones = table.bones.length;
        m.idxFull = idxFull.remaining();
        m.idxPrev = idxPrev.remaining();
        m.vao = GlStateManager._glGenVertexArrays();
        GlStateManager._glBindVertexArray(m.vao);

        m.vbo = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, m.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, verts, GL15.GL_STATIC_DRAW);
        GlStateManager._vertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0L);
        GlStateManager._vertexAttribPointer(1, 2, GL11.GL_FLOAT, false, STRIDE, 12L);
        GlStateManager._vertexAttribPointer(2, 3, GL11.GL_FLOAT, false, STRIDE, 20L);
        GlStateManager._vertexAttribPointer(3, 1, GL11.GL_FLOAT, false, STRIDE, 32L);
        GlStateManager._enableVertexAttribArray(0);
        GlStateManager._enableVertexAttribArray(1);
        GlStateManager._enableVertexAttribArray(2);
        GlStateManager._enableVertexAttribArray(3);

        m.eboFull = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, m.eboFull);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idxFull, GL15.GL_STATIC_DRAW);

        m.eboPrev = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, m.eboPrev);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idxPrev, GL15.GL_STATIC_DRAW);

        GlStateManager._glBindVertexArray(0);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        m.bytes = (long) faces * 4L * STRIDE + (long) m.idxFull * 4L + (long) m.idxPrev * 4L;
        return m;
    }

    // ------------------------------------------------------------------ 绘制

    /** 该模型这一帧能不能走 GPU（不能就让调用方走 CPU 路径） */
    public static boolean shouldUse(BedrockModel model, ResourceLocation texture, NpcRenderer.FlatInfo table) {
        if (!ENABLED || table == null || !table.allBaked || texture == null) return false;
        if (!available()) return false;
        if (mesh(model, table) == null) return false;
        try {
            AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(texture);
            return tex != null && tex.getId() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一次 uniform 上传 + 一次 glDrawElements 画完整只模型 */
    public static boolean draw(BedrockModel model, NpcRenderer.FlatInfo table, PoseStack poseStack,
                               ResourceLocation texture, int[] lights, float[] palette) {
        Mesh m = MESHES.get(model);
        if (m == null || paletteTex <= 0) return false;
        try {
            fillPalette(table, lights, palette);
            uploadPalette(m.bones, palette);

            Matrix4f pose = poseStack.last().pose();
            SCRATCH_MVP.set(RenderSystem.getProjectionMatrix());
            SCRATCH_MVP.mul(RenderSystem.getModelViewMatrix());
            SCRATCH_MVP.mul(pose);
            SCRATCH_N.set(poseStack.last().normal());

            GlStateManager._glUseProgram(program);
            SCRATCH_MVP.get(MAT16);
            GL20.glUniformMatrix4fv(uMVP, false, MAT16);
            SCRATCH_N.get(MAT9);
            GL20.glUniformMatrix3fv(uPoseNorm, false, MAT9);
            GL20.glUniform1f(uFlipZ, (NpcRenderer.previewCullOff && !NpcRenderer.previewMirrorInModelView) ? -1f : 1f);
            GL20.glUniform3f(uL0, 0.140028f, -0.700140f, -0.700140f);   // normalize(0.2,-1,-1)
            GL20.glUniform3f(uL1, -0.196116f, -0.980581f, 0f);           // normalize(-0.2,-1,0)
            GL20.glUniform1i(uTex, 0);
            GL20.glUniform1i(uBoneTex, 1);

            AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(texture);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(tex.getId());
            GlStateManager._activeTexture(GL13.GL_TEXTURE1);
            GlStateManager._bindTexture(paletteTex);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);

            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._depthMask(true);
            GlStateManager._enableBlend();
            GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager._disableCull();

            GlStateManager._glBindVertexArray(m.vao);
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,
                    NpcRenderer.previewCullOff ? m.eboPrev : m.eboFull);
            GL11.glDrawElements(GL11.GL_TRIANGLES,
                    NpcRenderer.previewCullOff ? m.idxPrev : m.idxFull, GL11.GL_UNSIGNED_INT, 0L);

            GlStateManager._glBindVertexArray(0);
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GlStateManager._glUseProgram(0);
            return true;
        } catch (Throwable t) {
            ModelYSM.LOGGER.warn("[GPU] 绘制失败，回退 CPU 路径：{}", t.toString());
            failed = true;
            return false;
        }
    }

    private static void uploadPalette(int bones, float[] palette) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE1);
        GlStateManager._bindTexture(paletteTex);
        if (paletteBones < bones) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, PAL_W, bones, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            paletteBones = bones;
        }
        FloatBuffer buf = BufferUtils.createFloatBuffer(PAL_W * 4 * bones);
        buf.put(palette, 0, PAL_W * 4 * bones).flip();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, PAL_W, bones,
                GL11.GL_RGBA, GL11.GL_FLOAT, buf);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    /** 每帧把每骨矩阵 / 法线矩阵 / 光照颜色写进 palette（宽 8 texel × 骨） */
    private static void fillPalette(NpcRenderer.FlatInfo table, int[] lights, float[] palette) {
        float[] lm = new float[3];
        for (int i = 0; i < table.bones.length; i++) {
            int o = i * PAL_W * 4;
            Matrix4f mat = table.mats[i];
            palette[o] = mat.m00(); palette[o + 1] = mat.m01(); palette[o + 2] = mat.m02(); palette[o + 3] = mat.m03();
            palette[o + 4] = mat.m10(); palette[o + 5] = mat.m11(); palette[o + 6] = mat.m12(); palette[o + 7] = mat.m13();
            palette[o + 8] = mat.m20(); palette[o + 9] = mat.m21(); palette[o + 10] = mat.m22(); palette[o + 11] = mat.m23();
            palette[o + 12] = mat.m30(); palette[o + 13] = mat.m31(); palette[o + 14] = mat.m32(); palette[o + 15] = mat.m33();
            Matrix3f n = table.norms[i];
            palette[o + 16] = n.m00(); palette[o + 17] = n.m10(); palette[o + 18] = n.m20(); palette[o + 19] = 0f;
            palette[o + 20] = n.m01(); palette[o + 21] = n.m11(); palette[o + 22] = n.m21(); palette[o + 23] = 0f;
            palette[o + 24] = n.m02(); palette[o + 25] = n.m12(); palette[o + 26] = n.m22(); palette[o + 27] = 0f;
            lightColor(lights[i], lm);
            palette[o + 28] = lm[0];
            palette[o + 29] = lm[1];
            palette[o + 30] = lm[2];
            palette[o + 31] = 1f;
        }
    }
}
