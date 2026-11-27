package ui.modelysm.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import ui.modelysm.ModelYSM;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * GUI 预览的<b>离屏</b> GPU 蒙皮渲染（第三档预览方案）。
 *
 * <p>为什么不再在 GUI 里即时绘制：把世界那条管线搬进 GUI 后，卡片会画成碎块——预览的
 * ModelView/投影来自 GUI 自己那套矩阵，而 {@code RenderType.entityCutoutNoCull} 的程序
 * 在 GUI 里并不一定拿到同一批矩阵（开 Iris 时更甚），几何就错位成板片。
 *
 * <p>这里改成完全隔离的一次离屏渲染：
 * <ol>
 *   <li>自己的 FBO（RGBA8 颜色 + 24 位深度），viewport 就是预览区，清成全透明；</li>
 *   <li>矩阵全部自己算：正交投影（区域像素）+ 平移锚点 + Z 镜像，等价于 GUI 里
 *       {@code ModelViewStack} 的 translate/scale(1,1,-1)，但<b>不读也不改</b>
 *       {@code RenderSystem} 的任何矩阵状态；</li>
 *   <li>蒙皮仍走 {@link WorldGpuRenderer} 的 compute（骨骼矩阵没变，顶点已经是
 *       pose×骨 的世界向坐标），只是输出到 buffer 后用<b>自带的极简着色器</b>画一次
 *       （贴图 + cutout + 两盏方向光），不经过原版 RenderType，也就不会被 Iris 换程序；</li>
 *   <li>画完把 GL 状态（FBO 绑定/viewport/scissor/深度/混合/剔除/sRGB/清屏色）原样还原，
 *       再把这个颜色纹理当成一张普通 GUI 贴图 blit 回卡片。</li>
 * </ol>
 *
 * <p>任何一步失败（无 GL3.3、FBO 不完整、区域过大、着色器编译失败）都会让
 * {@link #available()} 为 false，调用方原地退回 CPU 扁平路径，画面一定是对的。
 */
public final class PreviewFboRenderer {
    private PreviewFboRenderer() {}

    /** 总开关：false 则预览一律走 CPU 扁平路径 */
    public static boolean USE_FBO = true;

    private static final int MAX_TEX = 2048;

    private static boolean initTried, initOk, failed, loggedOnce;
    private static int fbo = -1, colorTex = -1, depthRb = -1, texW, texH;
    private static int program = -1, uViewProj = -1, uSampler = -1;

    /** 本帧是否已由界面调用 {@link #beginFrame}（没调用就不能用离屏路径） */
    private static boolean ready, hasImage;
    private static int rx, ry, rw, rh;
    /** 本次离屏渲染实际占用的设备像素尺寸（FBO 容量可能更大，贴图要按这个取 UV） */
    private static int dw, dh;
    private static float ax, ay;

    private static final Matrix4f PROJ = new Matrix4f();
    private static final Matrix4f CAM = new Matrix4f();
    private static final Matrix4f VP = new Matrix4f();

    private static final IntBuffer IV = BufferUtils.createIntBuffer(16);
    private static final IntBuffer IV2 = BufferUtils.createIntBuffer(16);
    private static final FloatBuffer FV = BufferUtils.createFloatBuffer(16);
    private static final ByteBuffer BV = BufferUtils.createByteBuffer(16);

    // ------------------------------------------------------------------ 生命周期

    /** 界面每画一张预览前调用：region 是 GUI 逻辑像素矩形，anchor 是脚的落点（同样逻辑像素） */
    public static void beginFrame(int x, int y, int w, int h, float anchorX, float anchorY) {
        rx = x;
        ry = y;
        rw = Math.max(1, w);
        rh = Math.max(1, h);
        ax = anchorX;
        ay = anchorY;
        hasImage = false;
        ready = init();
    }

    /** 本帧能不能用离屏路径（界面没调 beginFrame 或初始化失败都是 false） */
    public static boolean available() {
        return ready;
    }

    private static boolean init() {
        if (failed || !USE_FBO) return false;
        if (!initTried) {
            initTried = true;
            initOk = createProgram();
        }
        return initOk;
    }

    private static boolean createProgram() {
        try {
            if (!GL11.glGetString(GL11.GL_VERSION).contains("3.") && !GL11.glGetString(GL11.GL_VERSION).contains("4.")) {
                return false;
            }
            int vs = compile(GL20.GL_VERTEX_SHADER, VSH);
            if (vs <= 0) return false;
            int fs = compile(GL20.GL_FRAGMENT_SHADER, FSH);
            if (fs <= 0) {
                GL20.glDeleteShader(vs);
                return false;
            }
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            // 属性槽位与 WorldGpuRenderer 输出的 NEW_ENTITY 布局一一对应（0=pos … 5=normal）
            GL20.glBindAttribLocation(p, 0, "Position");
            GL20.glBindAttribLocation(p, 1, "Color");
            GL20.glBindAttribLocation(p, 2, "UV0");
            GL20.glBindAttribLocation(p, 3, "UV1");
            GL20.glBindAttribLocation(p, 4, "UV2");
            GL20.glBindAttribLocation(p, 5, "Normal");
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
                ModelYSM.LOGGER.warn("[GPUP] 预览着色器链接失败：{}", GL20.glGetProgramInfoLog(p));
                GL20.glDeleteProgram(p);
                return false;
            }
            program = p;
            uViewProj = GL20.glGetUniformLocation(p, "u_viewProj");
            uSampler = GL20.glGetUniformLocation(p, "u_sampler");
            ModelYSM.LOGGER.info("[GPUP] 预览离屏渲染已启用（自建 FBO + 自带着色器）");
            return true;
        } catch (Throwable t) {
            ModelYSM.LOGGER.warn("[GPUP] 预览离屏渲染初始化失败，退回 CPU 路径：{}", t.toString());
            return false;
        }
    }

    private static int compile(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            ModelYSM.LOGGER.warn("[GPUP] 预览着色器编译失败：{}", GL20.glGetShaderInfoLog(s));
            GL20.glDeleteShader(s);
            return -1;
        }
        return s;
    }

    private static final String VSH = String.join("\n",
            "#version 330 core",
            "layout(location = 0) in vec3 Position;",
            "layout(location = 1) in vec4 Color;",
            "layout(location = 2) in vec2 UV0;",
            "layout(location = 3) in ivec2 UV1;",
            "layout(location = 4) in ivec2 UV2;",
            "layout(location = 5) in vec3 Normal;",
            "uniform mat4 u_viewProj;",
            "out vec2 vUV;",
            "out vec4 vColor;",
            "out vec3 vNormal;",
            "void main() {",
            "    vUV = UV0;",
            "    vColor = Color;",
            "    vNormal = Normal;",
            "    gl_Position = u_viewProj * vec4(Position, 1.0);",
            "}",
            "");

    /** cutout + 官方"背包实体预览"的两盏方向光（L0=(0.2,1,-0.7)、L1=(-0.2,1,0.7)，最大光照明度） */
    private static final String FSH = String.join("\n",
            "#version 330 core",
            "uniform sampler2D u_sampler;",
            "in vec2 vUV;",
            "in vec4 vColor;",
            "in vec3 vNormal;",
            "out vec4 fragColor;",
            "void main() {",
            "    vec4 c = texture(u_sampler, vUV) * vColor;",
            "    if (c.a < 0.1) discard;",
            "    vec3 n = normalize(vNormal);",
            "    float d0 = max(dot(n, normalize(vec3(0.2, 1.0, -0.7))), 0.0);",
            "    float d1 = max(dot(n, normalize(vec3(-0.2, 1.0, 0.7))), 0.0);",
            "    float lit = min(0.4 + 0.6 * (d0 + d1), 1.0);",
            "    fragColor = vec4(c.rgb * lit, c.a);",
            "}",
            "");

    // ------------------------------------------------------------------ FBO

    private static boolean ensureCapacity(int w, int h) {
        if (w > MAX_TEX || h > MAX_TEX) return false;
        if (fbo >= 0 && texW >= w && texH >= h) return true;
        // 容量按 256 的整数倍增长：卡片(52x70) 与 大预览(125x171) 只需要扩一次，
        // 不要每帧在两种尺寸之间来回重建 FBO（那样每帧两次分配，反而更慢）
        int nw = Math.min(MAX_TEX, Math.max(256, ((w + 255) / 256) * 256));
        int nh = Math.min(MAX_TEX, Math.max(256, ((h + 255) / 256) * 256));
        destroy();
        try {
            colorTex = GL11.glGenTextures();
            GlStateManager._activeTexture(GL13.GL_TEXTURE7);
            GlStateManager._bindTexture(colorTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, nw, nh, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);

            depthRb = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRb);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, nw, nh);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);

            fbo = GL30.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, colorTex, 0);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_RENDERBUFFER, depthRb);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                ModelYSM.LOGGER.warn("[GPUP] 预览 FBO 不完整（status=0x{}），退回 CPU 路径",
                        Integer.toHexString(status));
                destroy();
                failed = true;
                return false;
            }
            texW = nw;
            texH = nh;
            return true;
        } catch (Throwable t) {
            ModelYSM.LOGGER.warn("[GPUP] 预览 FBO 创建失败，退回 CPU 路径：{}", t.toString());
            destroy();
            failed = true;
            return false;
        }
    }

    private static void destroy() {
        if (fbo >= 0) {
            GL30.glDeleteFramebuffers(fbo);
            fbo = -1;
        }
        if (depthRb >= 0) {
            GL30.glDeleteRenderbuffers(depthRb);
            depthRb = -1;
        }
        if (colorTex >= 0) {
            GL11.glDeleteTextures(colorTex);
            colorTex = -1;
        }
        texW = texH = 0;
    }

    // ------------------------------------------------------------------ 渲染

    /**
     * 在 FBO 里把这只模型画一遍（调用方已算好 {@code table.mats}，包含 PoseStack 的 translate/scale/旋转）。
     * 返回 false 表示没画，调用方要补一遍 CPU 顶点提交。
     */
    public static boolean render(BedrockModel model, NpcRenderer.FlatInfo table, ResourceLocation texture,
                                 boolean cullOn) {
        if (!ready || program <= 0 || table == null || texture == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        double gsD = mc.getWindow().getGuiScale();
        int gs = (int) Math.max(1, Math.round(gsD));
        int w = rw * gs;
        int h = rh * gs;
        if (!ensureCapacity(w, h)) {
            ready = false;
            return false;
        }
        dw = w;
        dh = h;
        int texId = mc.getTextureManager().getTexture(texture).getId();
        if (texId <= 0) return false;

        // ---- 保存 GL 状态（只碰这里列出来的，其余一概不动）
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, IV);
        int vx = IV.get(0), vy = IV.get(1), vw = IV.get(2), vh = IV.get(3);
        boolean scissorOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, IV2);
        int sx = IV2.get(0), sy = IV2.get(1), sw = IV2.get(2), sh = IV2.get(3);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        BV.clear();
        GL11.glGetBooleanv(GL11.GL_DEPTH_WRITEMASK, BV);
        boolean depthMask = BV.get(0) != 0;
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean srgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
        FV.clear();
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, FV);
        float cr = FV.get(0), cg = FV.get(1), cb = FV.get(2), ca = FV.get(3);
        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

        try {
            // ---- 进 FBO
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GlStateManager._viewport(0, 0, w, h);
            GlStateManager._disableScissorTest();
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GlStateManager._clearColor(0f, 0f, 0f, 0f);
            GlStateManager._depthMask(true);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, false);
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._disableBlend();
            GlStateManager._disableCull();

            // ---- 自己算矩阵：正交(区域像素) × 缩放(guiScale) × 平移锚点 × Z 镜像
            //      GUI 里是 ModelViewStack.translate(anchor) * scale(1,1,-1)，PoseStack 留在 mats 里，
            //      所以这里只补"锚点 + 镜像 + 投影"，与界面那套完全等价，但自己算。
            PROJ.identity().setOrtho(0f, (float) w, (float) h, 0f, -2000f, 2000f);
            CAM.identity().scale((float) gs, (float) gs, 1f)
                    .translate(ax - (float) rx, ay - (float) ry, 0f)
                    .scale(1f, 1f, -1f);
            VP.set(PROJ).mul(CAM);

            if (!WorldGpuRenderer.skin(model, table, cullOn, true, VP)) return false;

            GlStateManager._glUseProgram(program);
            if (uViewProj >= 0) {
                FV.clear();
                VP.get(FV);
                GL20.glUniformMatrix4fv(uViewProj, false, FV);
            }
            if (uSampler >= 0) GL20.glUniform1i(uSampler, 7);
            GlStateManager._activeTexture(GL13.GL_TEXTURE7);
            GlStateManager._bindTexture(texId);

            boolean drawn = WorldGpuRenderer.drawSkinned(model);

            BufferUploader.invalidate();
            if (!drawn) return false;
            hasImage = true;
            if (!loggedOnce) {
                loggedOnce = true;
                ModelYSM.LOGGER.info("[GPUP] 首帧离屏预览出图：区域={}x{} 设备像素={}x{} FBO={}x{}",
                        rw, rh, dw, dh, texW, texH);
            }
            return true;
        } catch (Throwable t) {
            ModelYSM.LOGGER.warn("[GPUP] 预览离屏绘制失败，退回 CPU 路径：{}", t.toString());
            ready = false;
            return false;
        } finally {
            // ---- 还原 GL 状态（顺序无关，逐项回到进来时）
            // 程序必须回到"进来时那个"而不是 0：原版 ShaderInstance 会按自己记录的 programId 做
            // 提前返回，如果缓存被我们改成 0，后续 GUI 绘制就不会重绑程序。
            GlStateManager._glUseProgram(prevProgram);
            GlStateManager._activeTexture(prevActiveTex);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GlStateManager._viewport(vx, vy, vw, vh);
            if (scissorOn) {
                GlStateManager._enableScissorTest();
                GlStateManager._scissorBox(sx, sy, sw, sh);
            } else {
                GlStateManager._disableScissorTest();
            }
            if (srgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB); else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            if (depthTest) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
            GlStateManager._depthFunc(depthFunc);
            GlStateManager._depthMask(depthMask);
            if (blend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
            if (cull) GlStateManager._enableCull(); else GlStateManager._disableCull();
            GlStateManager._clearColor(cr, cg, cb, ca);
        }
    }

    /**
     * 把离屏结果当 GUI 贴图贴回预览区（逻辑像素矩形，与 {@link #beginFrame} 一致）。
     * 贴之前先 flush 一次 GUI 批次，保证卡片底色已经画好、我们不会被它盖掉。
     */
    public static void blit(GuiGraphics g) {
        if (!hasImage || colorTex <= 0 || fbo < 0) return;
        try {
            g.flush();
            Matrix4f mat = g.pose().last().pose();

            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.setShaderTexture(0, colorTex);

            BufferBuilder bb = Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            float x0 = rx, y0 = ry, x1 = rx + rw, y1 = ry + rh;
            // 只取实际渲染的那块子区域（FBO 容量可能比预览区大）
            float u1 = texW > 0 ? (float) dw / (float) texW : 1f;
            float v1 = texH > 0 ? (float) dh / (float) texH : 1f;
            // FBO 的第 0 行在 OpenGL 里是底部，GUI 贴图的 v=0 是顶部 —— 这里把 v 翻过来
            bb.vertex(mat, x0, y0, 0f).uv(0f, v1).endVertex();
            bb.vertex(mat, x0, y1, 0f).uv(0f, 0f).endVertex();
            bb.vertex(mat, x1, y1, 0f).uv(u1, 0f).endVertex();
            bb.vertex(mat, x1, y0, 0f).uv(u1, v1).endVertex();
            BufferUploader.drawWithShader(bb.end());

            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            if (depthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (!blend) RenderSystem.disableBlend();
        } catch (Throwable t) {
            ModelYSM.LOGGER.warn("[GPUP] 预览贴图 blit 失败：{}", t.toString());
        } finally {
            hasImage = false;
        }
    }
}
