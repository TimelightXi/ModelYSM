package ui.modelysm.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ui.modelysm.ModelYSM;
import ui.modelysm.client.molang.Molang;
import ui.modelysm.client.render.BedrockAnimation;
import ui.modelysm.client.render.BedrockModel;
import ui.modelysm.client.render.YsmDirectLoader;
import ui.modelysm.common.ModelFolderScanner;
import ui.modelysm.common.YsmImporter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 客户端模型库：从 config/modelysm/models/<id>/ 加载
 *   main.json（或含 minecraft:geometry 的 json）→ BedrockModel
 *   main.animation.json（或任意 *.animation.json）→ BedrockAnimation 集合
 *   *.png → 贴图（第一个为默认）
 */
public final class ModelLibrary {
    private static final Logger LOGGER = LogManager.getLogger(ModelYSM.MODID);
    /** 已加载模型（并发：后台预取线程与渲染线程都会读写） */
    private static final Map<String, LoadedModel> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    /** 加载失败的模型 id → 失败时刻（nanoTime），用于负缓存避免每帧重试 */
    private static final Map<String, Long> FAILED = new java.util.concurrent.ConcurrentHashMap<>();
    /** 失败后多久允许重试一次（秒级，够用户改文件后重新进来） */
    private static final long FAIL_RETRY_NANOS = 5_000_000_000L;

    // ---- 官方 ClientModelManager 的懒加载同款骨架（GUI 可见卡片后台预取） ----
    /** 正在后台解析的 id（官方 LazyModelAssembly 的等价物：列表里有、几何还没好） */
    private static final java.util.Set<String> LOADING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 单线程解析池：官方 modelPhraseExecutor 也是单线程（避免同时解多份大 JSON） */
    private static final java.util.concurrent.ExecutorService LOADER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ModelYSM-Loader");
                t.setDaemon(true);
                return t;
            });

    /** 待上传贴图（官方 UploadManager.pendingUploads）：后台线程只解码，GL 注册留给渲染线程 */
    private static final class PendingTex {
        final ResourceLocation rl;
        final NativeImage img;
        final String ownerId;      // 归属模型 id：上传完一个就把它身上的计数减一
        PendingTex(ResourceLocation rl, NativeImage img, String ownerId) {
            this.rl = rl; this.img = img; this.ownerId = ownerId;
        }
    }
    /** 后台预取线程正在解析哪个模型（贴图延迟注册要靠它找回归属） */
    private static final ThreadLocal<String> CURRENT_LOAD_ID = new ThreadLocal<>();
    /** 每个模型还没上传的贴图数（>0 = 卡片继续转圈） */
    private static final Map<String, java.util.concurrent.atomic.AtomicInteger> PENDING_COUNT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 贴图是否全部上到 GPU（官方：`getResourceLocation()` 有值才算可用） */
    public static boolean texturesReady(String id) {
        java.util.concurrent.atomic.AtomicInteger c = PENDING_COUNT.get(id);
        return c == null || c.get() <= 0;
    }
    private static final java.util.Queue<PendingTex> PENDING_TEX = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 每帧贴图上传时间预算（官方 UploadManager.UPLOAD_TIME_LIMIT_MS = 20） */
    private static final long UPLOAD_BUDGET_MS = 20;

    /** 取已加载模型但不触发同步加载（GUI 用：pending 就画转圈，别卡帧） */
    public static LoadedModel peek(String id) {
        return CACHE.get(id);
    }

    /** 该模型是否正在后台加载（官方 ClientModelManager.isModelPending） */
    public static boolean isPending(String id) {
        return LOADING.contains(id) && !CACHE.containsKey(id);
    }

    /** 请求后台加载（幂等；已缓存/已在加载则直接返回） */
    public static void prefetch(String id) {
        if (id == null || CACHE.containsKey(id)) return;
        Long bad = FAILED.get(id);
        if (bad != null && System.nanoTime() - bad < FAIL_RETRY_NANOS) return;
        if (!LOADING.add(id)) return;
        LOADER.submit(() -> {
            try {
                LoadedModel m = load(id);
                if (m != null) {
                    CACHE.put(id, m);
                    FAILED.remove(id);
                } else {
                    FAILED.put(id, System.nanoTime());
                }
            } catch (Throwable t) {
                FAILED.put(id, System.nanoTime());
                LOGGER.warn("[ModelYSM] async load failed for {}: {}", id, t.getMessage());
            } finally {
                CURRENT_LOAD_ID.remove();
                LOADING.remove(id);
            }
        });
    }

    /**
     * 渲染线程每 tick 调用：按 20ms 预算把后台线程解好的 PNG 注册成 GL 贴图
     * （官方 UploadManager.processPendingUploads 的等价物）。
     */
    public static void processPendingUploads() {
        if (PENDING_TEX.isEmpty()) return;
        long t0 = System.nanoTime();
        while (System.nanoTime() - t0 < UPLOAD_BUDGET_MS * 1_000_000L) {
            PendingTex pt = PENDING_TEX.poll();
            if (pt == null) return;
            try {
                DynamicTexture tex = new DynamicTexture(pt.img);
                Minecraft.getInstance().getTextureManager().register(pt.rl, tex);
            } catch (Exception e) {
                LOGGER.warn("[ModelYSM] deferred texture upload failed for {}: {}", pt.rl, e.getMessage());
            } finally {
                if (pt.ownerId != null) {
                    java.util.concurrent.atomic.AtomicInteger c = PENDING_COUNT.get(pt.ownerId);
                    if (c != null) c.decrementAndGet();
                }
            }
        }
    }

    public static final class LoadedModel {
        public final BedrockModel model;
        public final Map<String, BedrockAnimation> animations = new LinkedHashMap<>();
        public final List<ResourceLocation> textures = new ArrayList<>();
        /** 贴图显示名（与 textures 索引一一对应，来自内嵌贴图名/文件名） */
        public final List<String> textureNames = new ArrayList<>();

        /** 按索引取贴图（越界/负数回退默认第一张） */
        public ResourceLocation texture(int index) {
            if (textures.isEmpty()) return null;
            return (index >= 0 && index < textures.size()) ? textures.get(index) : textures.get(0);
        }
        public final String id;
        /** 还没上传到 GPU 的贴图数（后台预取时 >0，卡片要等它归零才画 3D） */
        public final java.util.concurrent.atomic.AtomicInteger pendingTextures =
                new java.util.concurrent.atomic.AtomicInteger();
        public boolean texturesReady() { return pendingTextures.get() <= 0; }
        /** 角色/部件：主要部件骨骼（子树开关用），按显示顺序 */
        public final List<String> partBones = new ArrayList<>();
        /** 角色/部件显示名（bone → 展示名，来自 parts.json，缺省=骨骼名） */
        public final Map<String, String> partNames = new LinkedHashMap<>();
        /**
         * 互斥形态模式（parts.json "exclusive":true）——官方 YSM 机制：
         * 部件即形态（如 wine_fox 人型/狐狸，靠动画层 scale 互斥控制），
         * 只能独占显示其一，没有"全部显示"的概念。
         */
        public boolean exclusiveParts = false;
        /** ysm.json metadata.name（解密 YSM 工程的显示名；普通文件夹模型为 null） */
        public String displayName;
        /** metadata.tips（多行，\n 分隔，官方 tooltip 的灰色说明行） */
        public String metadataTips;
        /** metadata.authors（"角色: 名字"，官方用"丨"连接） */
        public String metadataAuthors;
        /** metadata.license.type */
        public String metadataLicense;
        /** properties.preview_animation：卡片/预览用展示动画（null/缺失时回退 gui → idle） */
        public String previewAnimation;
        /** properties.disable_preview_rotation：为真时卡片不做自动旋转（官方模型多数为真） */
        public boolean disablePreviewRotation;
        /** 卡片底图（properties.gui_background，指向内嵌 background_images 里的 PNG） */
        public ResourceLocation guiBackground;
        /** 卡片前景层（properties.gui_foreground，画在模型之上，做相框/装饰） */
        public ResourceLocation guiForeground;
        /** 模型内嵌的全部 gui 图（官方 background_images） */
        public final List<ResourceLocation> guiImages = new ArrayList<>();
        /** 静止姿态包围盒（方块单位）：GUI 卡片/大预览按真实尺寸取景，避免高模型被裁头 */
        public float modelWidth = 1f, modelHeight = 2f;
        /** 包围盒最低点 Y（一般≈0，即脚底） */
        public float modelMinY = 0f;
        /** 官方 animation_controllers 状态机（条件动画层，key=controller 名） */
        public final Map<String, ui.modelysm.client.anim.ModelControllers.Ctrl> controllers = new LinkedHashMap<>();
        /** 子实体（弹射物/载具替换渲染）：identifier 小写（"minecraft:arrow"）→ 模型 */
        public final Map<String, SubModel> subModels = new LinkedHashMap<>();
        /** 第一人称手臂模型（armModel + "arm" 动画组，官方 CustomFirstPersonArm 同思路） */
        public BedrockModel armModel;
        public final Map<String, BedrockAnimation> armAnimations = new LinkedHashMap<>();

        /** 子实体：独立几何 + 贴图 + 动画（如模型自带的箭矢模型） */
        public static final class SubModel {
            public final String identifier;
            public final BedrockModel model;
            public final ResourceLocation texture;
            public final Map<String, BedrockAnimation> animations;
            public SubModel(String identifier, BedrockModel model, ResourceLocation texture,
                            Map<String, BedrockAnimation> animations) {
                this.identifier = identifier;
                this.model = model;
                this.texture = texture;
                this.animations = animations;
            }
        }
        /** ysm.json properties.width_scale / height_scale（YSM 全模型身形缩放） */
        public float widthScale = 1f, heightScale = 1f;
        /**
         * 模型是否显式写了 height_scale / width_scale。
         * 官方 YSMFolderDeserializer 的取法：字段缺省时按 Blockbench 插件默认 0.7 处理
         *（`getDouble(propsObj, "height_scale", 0.7)`），显式写了才用自己的值。
         * 卡片预览用这个标记决定要不要补 0.7，世界渲染不受影响。
         */
        public boolean explicitHeightScale = false;
        /** 懒计算的静止姿态包围盒（MC 方块单位），GUI 预览拿几何中心当旋转轴心用 */
        public float[] cachedBounds;
        public float[] boundsNow() {
            if (cachedBounds == null) cachedBounds = model.bounds();
            return cachedBounds;
        }
        /**
         * 官方 Z 键轮盘数据（模型 properties.extra_animation）：
         * 动画名 → 显示名（语言文件 properties.extra_animation.%s 优先，回退模型内 value）。
         * value 为 "#xxx" 表示子菜单入口。
         */
        public final Map<String, String> extraAnimations = new LinkedHashMap<>();
        /** 官方轮盘子菜单：分类 id → (动画名 → 显示名) */
        public final Map<String, Map<String, String>> extraAnimationClassifies = new LinkedHashMap<>();
        /**
         * 模型用到的 roaming 变量名（"v.roaming.xxx"，取自全部动画表达式与时间轴脚本）。
         * 官方语义：每实体一张持久表、未赋值即 0；Rossi 的 Hat 用 '!v.roaming.a' 控制戴帽，
         * 轮盘里直接把这张表做成开关，等同于官方"换装"脚本的效果。
         */
        public final java.util.Set<String> roamingVars = new java.util.TreeSet<>();
        /** 模型内嵌语言表（locale → key → 文本），用于轮盘/分类显示名本地化 */
        public final Map<String, Map<String, String>> languages = new LinkedHashMap<>();
        /**
         * 官方轮盘 ⚙ 配置表单（properties.extra_animation_buttons，format>9 二进制内嵌）：
         * id → 按钮（extra_animation 中 value="#id" 的条目即此按钮的入口）。
         * 表单执行 = 对模型 molang 变量（v.*）赋值，渲染动画实时读取。
         */
        public final Map<String, ConfigButton> configButtons = new LinkedHashMap<>();

        /** 单个配置表单（checkbox 布尔 / radio 单选 / range 滑条） */
        public static final class ConfigForm {
            public final String type;     // checkbox | radio | range
            public final String title;
            public final String description;
            /** checkbox/range：目标变量名（v.xxx）；radio：当前选中索引变量名 */
            public final String value;
            public final float step, min, max;
            /** radio：显示名 → molang 常量赋值串（"v.a=0; v.b=1;"，官方 83 条全为常量） */
            public final Map<String, String> labels;

            public ConfigForm(String type, String title, String description, String value,
                              float step, float min, float max, Map<String, String> labels) {
                this.type = type;
                this.title = title;
                this.description = description;
                this.value = value;
                this.step = step;
                this.min = min;
                this.max = max;
                this.labels = labels;
            }
        }

        /** 轮盘 ⚙ 配置按钮（id 对应 extra_animation 中 "#id" 入口） */
        public static final class ConfigButton {
            public final String id;
            public final String name;
            public final List<ConfigForm> forms = new ArrayList<>();

            public ConfigButton(String id, String name) {
                this.id = id;
                this.name = name;
            }
        }

        public LoadedModel(String id, BedrockModel model) {
            this.id = id;
            this.model = model;
        }

        public ResourceLocation defaultTexture() {
            return textures.isEmpty() ? null : textures.get(0);
        }

        /** 表情/舞蹈动画缓存（官方轮盘"动画"页内容），懒计算 */
        private List<String> emoteCache;

        /**
         * 官方 YSM 轮盘可播放的"表情/舞蹈"动画：文件内不属于标准状态集、
         * 不属于常驻层//gui/载具、也不是分组头动画的全部动画，按文件顺序。
         */
        public List<String> emoteAnims() {
            if (emoteCache == null) {
                emoteCache = new ArrayList<>();
                for (String n : animations.keySet()) {
                    if (isEmoteAnim(n)) emoteCache.add(n);
                }
            }
            return emoteCache;
        }
    }

    /** 标准状态动画名（官方状态机绑定；这些不出现在轮盘动画页） */
    private static final java.util.Set<String> STATE_ANIMS = java.util.Set.of(
            "attacked", "boat", "climb", "climbing", "death", "elytra_fly", "fly", "hover",
            "idle", "jump", "ride", "ride_pig", "run", "run2", "run3", "sit", "sleep",
            "sneak", "sneaking", "swim", "swim_stand", "swing_hand",
            "use_mainhand", "use_offhand", "walk");

    /** 判定是否为官方轮盘"表情/舞蹈"动画（非状态/非常驻层/非分组头） */
    public static boolean isEmoteAnim(String n) {
        if (STATE_ANIMS.contains(n)) return false;
        String lower = n.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("parallel") || lower.startsWith("pre_parallel")) return false;
        if (lower.startsWith("gui") || lower.startsWith("vehicle$")) return false;
        // 分组头动画（Blockbench 分组导出的占位，如"————物理参数"）
        if (n.startsWith("——") || n.startsWith("--") || n.startsWith("——")) return false;
        return true;
    }

    private ModelLibrary() {}

    /** 获取模型（未加载则同步加载；失败返回 null）。后台正在加载时返回 null，由调用方回退 */
    public static LoadedModel get(String id) {
        LoadedModel m = CACHE.get(id);
        if (m == null) {
            // 后台预取线程正在解这个模型：不要在渲染线程上再解一遍（那等于把卡顿加回来）
            if (LOADING.contains(id)) return null;
            long now = System.nanoTime();
            Long bad = FAILED.get(id);
            // 失败负缓存：加载失败（解密/解析异常）时不要每帧重试——
            // 每次重试都是"整文件解密 + 反序列化"，会让模型列表/渲染卡到个位数帧率
            if (bad != null && now - bad < FAIL_RETRY_NANOS) return null;
            m = load(id);
            if (m != null) {
                CACHE.put(id, m);
                FAILED.remove(id);
            } else {
                FAILED.put(id, now);
            }
        }
        return m;
    }

    /** 缓存失效（模型文件更新/下载完成后调用，下次 get 自动重载） */
    public static void invalidate(String id) {
        CACHE.remove(id);
        FAILED.remove(id);
    }

    public static List<String> listModels() {
        // .ysm 文件由 YsmDirectLoader 内存直读，无需外部导入
        return ModelFolderScanner.listModels();
    }

    private static LoadedModel load(String id) {
        // 记下"当前线程在解哪个模型"：后台线程解出来的贴图要按 id 记待上传数
        CURRENT_LOAD_ID.set(id);
        // 优先直读 .ysm（解密+反序列化全程内存内，不生成中间文件；失败回退文件夹）
        Path ysmFile = ModelFolderScanner.modelsRoot().resolve(id + ".ysm");
        if (Files.isRegularFile(ysmFile)) {
            try {
                return YsmDirectLoader.load(id, Files.readAllBytes(ysmFile));
            } catch (Exception e) {
                LOGGER.warn("[ModelYSM] direct .ysm load failed for {}: {}, trying folder", id, e.getMessage());
            }
        }
        Path dir = ModelFolderScanner.modelDir(id);
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> walk = Files.walk(dir, 4)) {
            // 递归收集：兼容解密 YSM 工程结构（models/ + animations/ + textures/ + ysm.json）
            // 以及旧平铺结构（顶层 main.json + *.animation.json + *.png）
            List<Path> geoJsons = new ArrayList<>();
            List<Path> animFiles = new ArrayList<>();
            List<Path> pngs = new ArrayList<>();
            List<Path> ctrlFiles = new ArrayList<>();
            Path ysmJson = null, partsFile = null;
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String n = f.getFileName().toString().toLowerCase();
                if (n.equals("ysm.json")) ysmJson = f;
                else if (n.equals("parts.json")) partsFile = f;
                else if (n.endsWith(".png")) pngs.add(f);
                else if (n.endsWith("animation_controllers.json")) ctrlFiles.add(f);
                else if (n.endsWith(".animation.json")) animFiles.add(f);
                else if (n.endsWith(".json")) geoJsons.add(f);
            }

            // 几何：优先 main.json，其次任一含 minecraft:geometry 的
            Path geoFile = null;
            for (Path j : geoJsons) {
                if (j.getFileName().toString().equalsIgnoreCase("main.json")) { geoFile = j; break; }
            }
            if (geoFile == null && !geoJsons.isEmpty()) {
                for (Path j : geoJsons) {
                    String txt = Files.readString(j, StandardCharsets.UTF_8);
                    if (txt.contains("minecraft:geometry")) { geoFile = j; break; }
                }
                if (geoFile == null) geoFile = geoJsons.get(0);
            }
            if (geoFile == null) {
                LOGGER.warn("[ModelYSM] model {} has no geometry json", id);
                return null;
            }

            BedrockModel.GeometryData geo = parseGeometry(Files.readString(geoFile, StandardCharsets.UTF_8));
            BedrockModel model = BedrockModel.bake(geo);

            LoadedModel lm = new LoadedModel(id, model);
            // 第一人称手臂几何（models/arm.json）；缺失则保持 null，渲染时回退主模型
            for (Path j : geoJsons) {
                if (j.getFileName().toString().equalsIgnoreCase("arm.json")) {
                    try {
                        lm.armModel = BedrockModel.bake(parseGeometry(Files.readString(j, StandardCharsets.UTF_8)));
                    } catch (Exception e) {
                        LOGGER.warn("[ModelYSM] failed to parse arm model {}: {}", j.getFileName(), e.getMessage());
                    }
                    break;
                }
            }

            // ysm.json（解密 YSM 工程）：显示名 / 默认贴图 / 宽高缩放 /
            // preview_animation / disable_preview_rotation / gui_background / gui_foreground
            Path[] guiPaths = new Path[2];
            if (ysmJson != null) {
                parseYsmMeta(Files.readString(ysmJson, StandardCharsets.UTF_8), lm, pngs, dir, guiPaths);
            }
            // 官方 YSMFolderDeserializer：properties 里没写 height_scale/width_scale 时，
            // 按 Blockbench 插件默认值 0.7 处理（`getDouble(propsObj, "height_scale", 0.7)`）。
            // 酒狐 01_taisho_maid / 3_default_boy 都没写 → 官方按 0.7 渲染；
            // 我们之前按 1.0 渲染，所以在世界和卡片里都大了一圈。
            if (!lm.explicitHeightScale) {
                lm.widthScale = 0.7f;
                lm.heightScale = 0.7f;
            }

            // 角色/部件：优先读 parts.json（自定义分组与中文名），否则按骨骼树自动推导
            if (partsFile != null) {
                parseParts(Files.readString(partsFile, StandardCharsets.UTF_8), model, lm);
            }
            if (lm.partBones.isEmpty()) {
                computeParts(model, lm);
            }
            // 动画：合并全部 *.animation.json（解密工程动画分布在 main/arm/arrow/extra 等多个文件）
            animFiles.sort((a, b) -> {
                boolean ma = a.getFileName().toString().equalsIgnoreCase("main.animation.json");
                boolean mb = b.getFileName().toString().equalsIgnoreCase("main.animation.json");
                if (ma != mb) return ma ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            });
            for (Path af : animFiles) {
                try {
                    Map<String, BedrockAnimation> tmp = new LinkedHashMap<>();
                    parseAnimations(Files.readString(af, StandardCharsets.UTF_8), tmp);
                    boolean isMain = af.getFileName().toString().equalsIgnoreCase("main.animation.json");
                    for (Map.Entry<String, BedrockAnimation> en : tmp.entrySet()) {
                        // main 优先占位；后续文件同名的让位（或覆盖——main 文件则覆盖）
                        if (isMain) lm.animations.put(en.getKey(), en.getValue());
                        else lm.animations.putIfAbsent(en.getKey(), en.getValue());
                    }
                } catch (Exception e) {
                    LOGGER.warn("[ModelYSM] failed to parse anim file {}: {}", af.getFileName(), e.getMessage());
                }
            }
            Map<Path, ResourceLocation> texByPath = new LinkedHashMap<>();
            for (Path png : pngs) {
                // avatar/（作者头像）和 textures/gui/（卡片底图前景）不是模型皮肤，不进贴图列表
                String rel = dir.relativize(png).toString().replace('\\', '/').toLowerCase();
                boolean uiPng = rel.startsWith("avatar/") || rel.contains("/gui/") || rel.startsWith("gui/");
                try (InputStream in = Files.newInputStream(png)) {
                    NativeImage img = readImage(in);
                    String safe = id.replaceAll("[^a-z0-9_.-]", "_");
                    ResourceLocation rl;
                    if (uiPng) {
                        rl = new ResourceLocation(ModelYSM.MODID,
                                "dyn/" + safe + "/_ui_" + png.getFileName().toString().toLowerCase().replace('.', '_'));
                    } else {
                        rl = new ResourceLocation(ModelYSM.MODID,
                                "dyn/" + safe + "/" + png.getFileName().toString().toLowerCase().replace('.', '_'));
                    }
                    registerOrDefer(rl, img);
                    if (!uiPng) {
                        lm.textureNames.add(png.getFileName().toString().replaceAll("(?i)\\.png$", ""));
                        lm.textures.add(rl);
                    }
                    texByPath.put(png, rl);
                    ModelLibrary.TEXTURE_SIZE.put(rl, new int[]{img.getWidth(), img.getHeight()});
                } catch (Exception e) {
                    LOGGER.warn("[ModelYSM] failed to load texture {}: {}", png, e.getMessage());
                }
            }
            // 文件夹模型的卡片底图/前景层（ysm.json 的 gui_background / gui_foreground）
            if (guiPaths[0] != null) {
                lm.guiBackground = texByPath.get(guiPaths[0]);
                if (lm.guiBackground != null) lm.guiImages.add(lm.guiBackground);
            }
            if (guiPaths[1] != null) {
                lm.guiForeground = texByPath.get(guiPaths[1]);
                if (lm.guiForeground != null) lm.guiImages.add(lm.guiForeground);
            }
            // animation_controllers 状态机（*.animation_controllers.json，官方条件动画层）
            for (Path cf : ctrlFiles) {
                try {
                    parseControllers(Files.readString(cf, StandardCharsets.UTF_8), lm);
                } catch (Exception e) {
                    LOGGER.warn("[ModelYSM] failed to parse controller file {}: {}", cf.getFileName(), e.getMessage());
                }
            }
            LOGGER.info("[ModelYSM] loaded model '{}' (bones={}, anims={}, textures={})",
                    id, model.bonesByName.size(), lm.animations.size(), lm.textures.size());
            return lm;
        } catch (IOException e) {
            LOGGER.warn("[ModelYSM] failed to load model {}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 ysm.json（YSMParser 解密工程）：metadata.name 显示名、
     * properties.default_texture 默认贴图（把对应 png 排到第一位）、
     * properties.width_scale / height_scale 全模型身形缩放。
     */
    private static void parseYsmMeta(String json, LoadedModel out, List<Path> pngs,
                                     Path modelDir, Path[] guiOut) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("metadata") && root.get("metadata").isJsonObject()) {
                JsonObject md = root.getAsJsonObject("metadata");
                if (md.has("name") && md.get("name").isJsonPrimitive()) {
                    out.displayName = md.get("name").getAsString();
                }
                // 官方 ModelMetadataPresenter 的 tooltip 挂钩的同一批字段
                if (md.has("tips") && md.get("tips").isJsonPrimitive()) {
                    out.metadataTips = md.get("tips").getAsString();
                }
                if (md.has("license") && md.get("license").isJsonObject()
                        && md.getAsJsonObject("license").has("type")) {
                    out.metadataLicense = md.getAsJsonObject("license").get("type").getAsString();
                }
                if (md.has("authors") && md.get("authors").isJsonArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (com.google.gson.JsonElement ae : md.getAsJsonArray("authors")) {
                        if (!ae.isJsonObject()) continue;
                        com.google.gson.JsonObject ao = ae.getAsJsonObject();
                        String an = ao.has("name") ? ao.get("name").getAsString() : "";
                        String role = ao.has("role") ? ao.get("role").getAsString() : "";
                        if (an.isBlank()) continue;
                        if (sb.length() > 0) sb.append("丨");
                        sb.append(role.isBlank() ? an : role + ": " + an);
                    }
                    if (sb.length() > 0) out.metadataAuthors = sb.toString();
                }
            }
            if (root.has("properties") && root.get("properties").isJsonObject()) {
                JsonObject props = root.getAsJsonObject("properties");
                if (props.has("width_scale") && props.get("width_scale").isJsonPrimitive()) {
                    out.widthScale = clampScale(props.get("width_scale").getAsFloat());
                }
                if (props.has("height_scale") && props.get("height_scale").isJsonPrimitive()) {
                    out.heightScale = clampScale(props.get("height_scale").getAsFloat());
                    out.explicitHeightScale = true;
                }
                if (props.has("default_texture") && props.get("default_texture").isJsonPrimitive()) {
                    String want = props.get("default_texture").getAsString().toLowerCase();
                    // 形如 "texture" → textures/texture.png；找到则排到贴图列表第一位
                    for (int i = 0; i < pngs.size(); i++) {
                        String fn = pngs.get(i).getFileName().toString().toLowerCase();
                        if (fn.equals(want + ".png") || fn.endsWith("/" + want + ".png")
                                || fn.replace('\\', '/').endsWith("/" + want + ".png")) {
                            Path chosen = pngs.remove(i);
                            pngs.add(0, chosen);
                            break;
                        }
                    }
                }
                // 官方"选择界面动画"：preview_animation（卡片主循环动画，常命名 gui）
                if (props.has("preview_animation") && props.get("preview_animation").isJsonPrimitive()) {
                    String pa = props.get("preview_animation").getAsString();
                    if (!pa.isBlank()) out.previewAnimation = pa;
                }
                if (props.has("disable_preview_rotation") && props.get("disable_preview_rotation").isJsonPrimitive()) {
                    out.disablePreviewRotation = props.get("disable_preview_rotation").getAsBoolean();
                }
                // 2.5.0 前景/背景图（推荐 52x90）：路径相对模型目录
                if (props.has("gui_background") && props.get("gui_background").isJsonPrimitive()) {
                    guiOut[0] = matchRelativePng(props.get("gui_background").getAsString(), modelDir, pngs);
                }
                if (props.has("gui_foreground") && props.get("gui_foreground").isJsonPrimitive()) {
                    guiOut[1] = matchRelativePng(props.get("gui_foreground").getAsString(), modelDir, pngs);
                }
                // 轮盘动画表（文件夹模型同样来自 ysm.json）
                if (props.has("extra_animation") && props.get("extra_animation").isJsonObject()) {
                    JsonObject ea = props.getAsJsonObject("extra_animation");
                    for (Map.Entry<String, com.google.gson.JsonElement> en : ea.entrySet()) {
                        if (en.getValue().isJsonPrimitive() && en.getValue().getAsJsonPrimitive().isString()) {
                            out.extraAnimations.put(en.getKey(), en.getValue().getAsString());
                        } else {
                            out.extraAnimations.put(en.getKey(), "");
                        }
                    }
                }
            }
            // files.player.texture：官方皮肤列表（顺序即皮肤页顺序）；
            // 没有 default_texture 的模型（如 01_taisho_maid）就靠这里定默认皮肤，
            // 同时把弹射物/载具贴图（arrow/boat/trident/minecart/foxcar…）排除出皮肤列表
            if (root.has("files") && root.get("files").isJsonObject()) {
                JsonObject files = root.getAsJsonObject("files");
                if (files.has("player") && files.get("player").isJsonObject()) {
                    JsonObject player = files.getAsJsonObject("player");
                    if (player.has("texture") && player.get("texture").isJsonArray()) {
                        List<Path> ordered = new ArrayList<>();
                        for (com.google.gson.JsonElement e : player.getAsJsonArray("texture")) {
                            if (!e.isJsonPrimitive()) continue;
                            Path p = matchRelativePng(e.getAsString(), modelDir, pngs);
                            if (p != null && !ordered.contains(p)) ordered.add(p);
                        }
                        if (!ordered.isEmpty()) {
                            for (int i = ordered.size() - 1; i >= 0; i--) {
                                Path p = ordered.get(i);
                                pngs.remove(p);
                                pngs.add(0, p);
                            }
                            List<Path> keep = new ArrayList<>(ordered);
                            if (guiOut[0] != null) keep.add(guiOut[0]);
                            if (guiOut[1] != null) keep.add(guiOut[1]);
                            pngs.removeIf(p -> !keep.contains(p));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[ModelYSM] failed to parse ysm.json for {}: {}", out.id, e.getMessage());
        }
    }

    private static float clampScale(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v) || v <= 0) return 1f;
        return Mth.clamp(v, 0.05f, 20f);
    }

    /** ysm.json 里的相对路径（"textures/gui/background.png"）→ 实际 png 文件 */
    private static Path matchRelativePng(String rel, Path modelDir, List<Path> pngs) {
        if (rel == null || rel.isBlank()) return null;
        String want = rel.replace('\\', '/').toLowerCase();
        if (want.startsWith("/")) want = want.substring(1);
        // 兼容只写文件名的写法（"background.png"）
        for (Path p : pngs) {
            String full = modelDir.relativize(p).toString().replace('\\', '/').toLowerCase();
            if (full.equals(want) || full.endsWith("/" + want)) return p;
        }
        String onlyName = want.substring(want.lastIndexOf('/') + 1);
        for (Path p : pngs) {
            if (p.getFileName().toString().equalsIgnoreCase(onlyName)) return p;
        }
        return null;
    }

    /**
     * 读贴图：优先按 PNG 读；检测到 WebP 魔数（RIFF....WEBP，YSM 导出的贴图常伪装 .png 扩展名）
     * 或 PNG 解码失败时，回退到 ImageIO（含 TwelveMonkeys imageio-webp，纯 Java 解码）。
     */
    private static NativeImage readImage(InputStream in) throws java.io.IOException {
        byte[] data = in.readAllBytes();
        String err = null;
        try {
            return NativeImage.read(new java.io.ByteArrayInputStream(data));
        } catch (Exception e) {
            err = e.getMessage();
        }
        // WebP 魔数：RIFF + 8字节 + WEBP
        boolean webp = data.length > 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
        try {
            java.awt.image.BufferedImage bi = decodeImage(data);
            if (bi != null) return toNativeImage(bi);
        } catch (Throwable t) {
            throw new java.io.IOException("image decode failed" + (webp ? " (WebP" : " (PNG") + "): " + t.getMessage(), t);
        }
        throw new java.io.IOException("image decode failed: " + err);
    }

    /** ImageIO 解码：先走 SPI 发现（若已注册），失败则反射实例化 TwelveMonkeys WebP reader */
    private static java.awt.image.BufferedImage decodeImage(byte[] data) throws Exception {
        javax.imageio.stream.ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(
                new java.io.ByteArrayInputStream(data));
        try {
            java.util.Iterator<javax.imageio.ImageReader> it = javax.imageio.ImageIO.getImageReaders(iis);
            if (it.hasNext()) {
                javax.imageio.ImageReader r = it.next();
                try {
                    r.setInput(iis);
                    return r.read(0);
                } finally {
                    r.dispose();
                }
            }
        } catch (Throwable ignored) {
            // SPI 发现失败 → 走直接实例化
        }
        // 重定位后的包名（build.gradle relocateWebp 任务把 com.twelvemonkeys 同长度重命名为 ysm.twelvemonkeys）
        Class<?> spiCls = Class.forName("ysm.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi",
                true, ModelLibrary.class.getClassLoader());
        javax.imageio.spi.ImageReaderSpi spi = (javax.imageio.spi.ImageReaderSpi) spiCls.getDeclaredConstructor().newInstance();
        javax.imageio.ImageReader r = spi.createReaderInstance();
        r.setInput(javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(data)));
        try {
            return r.read(0);
        } finally {
            r.dispose();
        }
    }

    /** BufferedImage → NativeImage（MC 内部像素为 ABGR 打包，需交换 R/B） */
    private static NativeImage toNativeImage(java.awt.image.BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        int[] argb = new int[w * h];
        src.getRGB(0, 0, w, h, argb, 0, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                int a = (p >>> 24) & 0xFF, r = (p >>> 16) & 0xFF, g = (p >>> 8) & 0xFF, b = p & 0xFF;
                img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return img;
    }

    /**
     * 注册 .ysm 内嵌贴图字节（不落盘）：PNG/WebP 编码图像走解码；
     * 原始 RGBA 像素（长度 = w*h*4 且无文件魔数）直接逐像素填充。
     */
    /** 动态注册贴图的像素尺寸（GUI 按真实比例拉伸内嵌底图时要用） */
    public static final java.util.Map<ResourceLocation, int[]> TEXTURE_SIZE = new java.util.concurrent.ConcurrentHashMap<>();

    public static ResourceLocation registerTextureBytes(String id, String texName, byte[] data, int w, int h) {
        boolean png = data.length > 8 && data[0] == (byte) 0x89 && data[1] == 0x50;
        boolean riff = data.length > 12 && data[0] == 'R' && data[1] == 'I';
        try {
            NativeImage img;
            if (!png && !riff && data.length == w * h * 4 && w > 0 && h > 0) {
                img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = (y * w + x) * 4;
                        int r = data[i] & 0xFF, g = data[i + 1] & 0xFF, b = data[i + 2] & 0xFF, a = data[i + 3] & 0xFF;
                        img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                    }
                }
            } else {
                img = readImage(new java.io.ByteArrayInputStream(data));
            }
            String safe = id.replaceAll("[^a-z0-9_.-]", "_");
            String safeTex = texName.replaceAll("[^a-z0-9_.-]", "_");
            ResourceLocation rl = new ResourceLocation(ModelYSM.MODID, "dyn/" + safe + "/tex_" + safeTex);
            registerOrDefer(rl, img);
            TEXTURE_SIZE.put(rl, new int[]{img.getWidth(), img.getHeight()});
            return rl;
        } catch (Exception e) {
            LOGGER.warn("[ModelYSM] failed to register embedded texture '{}' for {}: {}", texName, id, e.getMessage());
            return null;
        }
    }

    /**
     * 渲染线程上直接注册（同步路径，立刻可画）；后台预取线程上只解码、把 GL 注册推进队列，
     * 由 processPendingUploads 每帧按 20ms 预算消化（官方 UploadManager 同款）。
     */
    private static void registerOrDefer(ResourceLocation rl, NativeImage img) {
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            DynamicTexture tex = new DynamicTexture(img);
            Minecraft.getInstance().getTextureManager().register(rl, tex);
        } else {
            String owner = CURRENT_LOAD_ID.get();
            if (owner != null) {
                PENDING_COUNT.computeIfAbsent(owner, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            }
            PENDING_TEX.add(new PendingTex(rl, img, owner));
        }
    }

    // ================= 几何解析 =================

    /**
     * 解析 parts.json（角色/部件分组，GUI"角色/部件"栏用）。
     * 支持格式：{"parts":[{"bone":"AllBody","name":"人型"},...], "exclusive":true}
     * 或 [{"bone":...},...]。
     * exclusive=true 表示部件为互斥形态（官方 YSM 机制：切换形态独占显示，不做全部显示）。
     * name 缺省时用骨骼名；bone 不存在时忽略。
     */
    private static void parseParts(String json, BedrockModel model, LoadedModel out) {
        try {
            JsonElement rootEl = JsonParser.parseString(json);
            if (rootEl.isJsonObject()) {
                JsonObject obj = rootEl.getAsJsonObject();
                out.exclusiveParts = obj.has("exclusive") && obj.get("exclusive").isJsonPrimitive()
                        && obj.get("exclusive").getAsBoolean();
            }
            JsonArray arr;
            if (rootEl.isJsonArray()) {
                arr = rootEl.getAsJsonArray();
            } else if (rootEl.isJsonObject() && rootEl.getAsJsonObject().has("parts")) {
                arr = rootEl.getAsJsonObject().getAsJsonArray("parts");
            } else {
                return;
            }
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (!o.has("bone")) continue;
                String bone = o.get("bone").getAsString();
                if (!model.bonesByName.containsKey(bone)) continue;
                String name = o.has("name") ? o.get("name").getAsString() : bone;
                if (!out.partBones.contains(bone)) out.partBones.add(bone);
                out.partNames.put(bone, name);
            }
        } catch (Exception e) {
            LOGGER.warn("[ModelYSM] failed to parse parts.json for {}: {}", out.id, e.getMessage());
        }
    }

    /**
     * 自动推导"角色/部件"：根骨骼有子骨骼时取其带模型数据的子树，
     * 否则取根骨骼自身（如 wine_fox：Root 下分 AllBody=人型 / FOX=狐狸）。
     * 解密 YSM 工程常有 MUpBody 等无几何包装骨骼，优先取自身带 cubes 的子骨骼。
     */
    private static void computeParts(BedrockModel model, LoadedModel out) {
        for (BedrockModel.Bone root : model.rootBones) {
            if (!root.children.isEmpty()) {
                // 优先：自身直接带几何的子骨骼（跳过纯包装骨骼）
                List<BedrockModel.Bone> withCubes = new ArrayList<>();
                for (BedrockModel.Bone c : root.children) {
                    if (!c.cubes.isEmpty()) withCubes.add(c);
                }
                List<BedrockModel.Bone> chosen = withCubes.isEmpty() ? root.children : withCubes;
                for (BedrockModel.Bone c : chosen) {
                    if (hasGeometry(c)) {
                        if (!out.partBones.contains(c.name)) out.partBones.add(c.name);
                        out.partNames.putIfAbsent(c.name, c.name);
                    }
                }
            } else if (hasGeometry(root)) {
                if (!out.partBones.contains(root.name)) out.partBones.add(root.name);
                out.partNames.putIfAbsent(root.name, root.name);
            }
        }
    }

    private static boolean hasGeometry(BedrockModel.Bone b) {
        if (!b.cubes.isEmpty()) return true;
        for (BedrockModel.Bone c : b.children) if (hasGeometry(c)) return true;
        return false;
    }

    public static BedrockModel.GeometryData parseGeometry(String json) {
        BedrockModel.GeometryData geo = new BedrockModel.GeometryData();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("minecraft:geometry");
        JsonObject g = arr.get(0).getAsJsonObject();
        JsonObject desc = g.getAsJsonObject("description");
        if (desc.has("texture_width")) geo.textureWidth = desc.get("texture_width").getAsFloat();
        if (desc.has("texture_height")) geo.textureHeight = desc.get("texture_height").getAsFloat();
        for (JsonElement el : g.getAsJsonArray("bones")) {
            JsonObject b = el.getAsJsonObject();
            BedrockModel.GeometryData.ParsedBone pb = new BedrockModel.GeometryData.ParsedBone();
            pb.name = b.get("name").getAsString();
            if (b.has("parent")) pb.parent = b.get("parent").getAsString();
            if (b.has("pivot")) pb.pivot = toFloat3(b.getAsJsonArray("pivot"));
            if (b.has("rotation")) pb.rotation = toFloat3(b.getAsJsonArray("rotation"));
            // 官方 :556 骨骼级 mirror：cube 自己没写 mirror 就继承骨骼的（:565）
            if (b.has("mirror") && b.get("mirror").isJsonPrimitive()) pb.mirror = b.get("mirror").getAsBoolean();
            if (b.has("cubes")) {
                for (JsonElement ce : b.getAsJsonArray("cubes")) {
                    JsonObject c = ce.getAsJsonObject();
                    BedrockModel.GeometryData.ParsedCube pc = new BedrockModel.GeometryData.ParsedCube();
                    pc.origin = toFloat3(c.getAsJsonArray("origin"));
                    pc.size = toFloat3(c.getAsJsonArray("size"));
                    if (c.has("rotation")) pc.rotation = toFloat3(c.getAsJsonArray("rotation"));
                    if (c.has("pivot")) pc.rotPivot = toFloat3(c.getAsJsonArray("pivot"));
                    if (c.has("inflate")) pc.inflate = c.get("inflate").getAsFloat();
                    if (c.has("mirror") && c.get("mirror").isJsonPrimitive()) pc.mirror = c.get("mirror").getAsBoolean();
                    else pc.mirror = pb.mirror;   // 官方 :565 继承骨骼级 mirror
                    JsonElement uv = c.get("uv");
                    if (uv != null && uv.isJsonPrimitive()) {
                        // 旧版单数字 UV：当作 [u, 0]
                        pc.uvU = uv.getAsFloat();
                        pc.uvV = 0;
                    } else if (uv != null && uv.isJsonArray()) {
                        pc.uvU = uv.getAsJsonArray().get(0).getAsFloat();
                        pc.uvV = uv.getAsJsonArray().get(1).getAsFloat();
                    } else if (uv != null && uv.isJsonObject()) {
                        // 按面UV：每面独立 {uv:[u,v], uv_size:[w,h]}（GeckoLib FaceUV 约定）
                        JsonObject faces = uv.getAsJsonObject();
                        String[] keys = {"west", "east", "north", "south", "up", "down"};
                        float[][] fuv = new float[6][];
                        boolean any = false;
                        for (int i = 0; i < 6; i++) {
                            if (!faces.has(keys[i])) continue;
                            JsonObject f = faces.getAsJsonObject(keys[i]);
                            float u0 = 0, v0 = 0, w = 0, h = 0;
                            if (f.has("uv") && f.getAsJsonArray("uv").size() >= 2) {
                                JsonArray fu = f.getAsJsonArray("uv");
                                u0 = fu.get(0).getAsFloat();
                                v0 = fu.get(1).getAsFloat();
                            }
                            if (f.has("uv_size") && f.getAsJsonArray("uv_size").size() >= 2) {
                                JsonArray fs = f.getAsJsonArray("uv_size");
                                w = fs.get(0).getAsFloat();
                                h = fs.get(1).getAsFloat();
                            } else {
                                // 缺省 uv_size 时按面尺寸兜底：北南=(sx,sy) 东西=(sz,sy) 上下=(sx,sz)
                                float sx = pc.size[0], sy = pc.size[1], sz = pc.size[2];
                                float[] wh = switch (i) {
                                    case 0, 1 -> new float[]{sz, sy}; // WEST/EAST
                                    case 2, 3 -> new float[]{sx, sy}; // NORTH/SOUTH
                                    default -> new float[]{sx, sz};   // UP/DOWN
                                };
                                w = wh[0];
                                h = wh[1];
                            }
                            fuv[i] = new float[]{u0, v0, u0 + w, v0 + h};
                            any = true;
                        }
                        if (any) pc.facesUV = fuv;
                        pc.perFace = true;
                    }
                    pb.cubes.add(pc);
                }
            }
            geo.bones.add(pb);
        }
        return geo;
    }

    /** 官方 on_entry / on_exit 可以是单条字符串，也可以是字符串数组 */
    private static void addScripts(JsonElement el, java.util.List<String> out) {
        if (el == null || el.isJsonNull()) return;
        try {
            if (el.isJsonArray()) {
                for (JsonElement e : el.getAsJsonArray()) {
                    if (e.isJsonPrimitive()) out.add(e.getAsString());
                }
            } else if (el.isJsonPrimitive()) {
                out.add(el.getAsString());
            }
        } catch (Exception ignored) {
        }
    }

    /** 解析 *.animation_controllers.json（官方格式：animation_controllers.name.states） */
    private static void parseControllers(String json, LoadedModel lm) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("animation_controllers")) return;
        JsonObject ctrls = root.getAsJsonObject("animation_controllers");
        for (Map.Entry<String, JsonElement> e : ctrls.entrySet()) {
            try {
                JsonObject c = e.getValue().getAsJsonObject();
                ui.modelysm.client.anim.ModelControllers.Ctrl ctrl = new ui.modelysm.client.anim.ModelControllers.Ctrl();
                ctrl.key = e.getKey();
                JsonObject states = c.has("states") ? c.getAsJsonObject("states") : null;
                if (states == null) continue;
                for (Map.Entry<String, JsonElement> se : states.entrySet()) {
                    JsonObject stj = se.getValue().getAsJsonObject();
                    ui.modelysm.client.anim.ModelControllers.CState st = new ui.modelysm.client.anim.ModelControllers.CState();
                    st.name = se.getKey();
                    if (stj.has("animations") && stj.get("animations").isJsonArray()) {
                        for (JsonElement an : stj.getAsJsonArray("animations")) {
                            st.animations.add(an.getAsString());
                        }
                    }
                    if (stj.has("transitions") && stj.get("transitions").isJsonArray()) {
                        for (JsonElement te : stj.getAsJsonArray("transitions")) {
                            if (!te.isJsonObject()) continue;
                            for (Map.Entry<String, JsonElement> t : te.getAsJsonObject().entrySet()) {
                                ui.modelysm.client.anim.ModelControllers.Trans tr = new ui.modelysm.client.anim.ModelControllers.Trans();
                                tr.target = t.getKey();
                                tr.expr = t.getValue().getAsString();
                                st.transitions.add(tr);
                            }
                        }
                    }
                    if (stj.has("on_entry")) addScripts(stj.get("on_entry"), st.onEntry);
                    if (stj.has("on_exit")) addScripts(stj.get("on_exit"), st.onExit);
                    ctrl.states.put(st.name, st);
                }
                lm.controllers.put(ctrl.key, ctrl);            } catch (Exception ex) {
                LOGGER.warn("[ModelYSM] controller '{}' parse error: {}", e.getKey(), ex.getMessage());
            }
        }
    }

    // ================= 动画解析 =================
    public static void parseAnimations(String json, Map<String, BedrockAnimation> out) {        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("animations")) return;
        JsonObject anims = root.getAsJsonObject("animations");
        for (Map.Entry<String, JsonElement> e : anims.entrySet()) {
            try {
                JsonObject a = e.getValue().getAsJsonObject();
                boolean loop = false;
                if (a.has("loop")) {
                    JsonElement le = a.get("loop");
                    // 官方 LoopType.fromJson：true/"LOOP"→循环；false/"PLAY_ONCE"/其他→单次；
                    // "hold_on_last_frame"→停在最后一帧（本引擎非循环一律钳制末帧，HOLD/PLAY_ONCE 采样行为一致）
                    if (le.isJsonPrimitive() && !le.getAsJsonPrimitive().isBoolean()) {
                        String s = le.getAsString();
                        loop = "true".equalsIgnoreCase(s) || "LOOP".equalsIgnoreCase(s);
                    } else {
                        loop = le.isJsonPrimitive() && le.getAsBoolean();
                    }
                }
                float length = a.has("animation_length") ? a.get("animation_length").getAsFloat() : 0f;
                BedrockAnimation anim = new BedrockAnimation(e.getKey(), loop, length);
                // 官方 blend_weight（PredicateBasedController 每帧求值 → 采样值按权重缩放）
                if (a.has("blend_weight") && !a.get("blend_weight").isJsonNull()) {
                    anim.blendWeightExpr = toExpr(a.get("blend_weight"));
                }
                if (a.has("bones")) {
                    JsonObject bones = a.getAsJsonObject("bones");
                    for (Map.Entry<String, JsonElement> be : bones.entrySet()) {
                        JsonObject b = be.getValue().getAsJsonObject();
                        BedrockAnimation.Channels ch = new BedrockAnimation.Channels();
                        if (b.has("rotation")) parseCurve(b.get("rotation"), ch.rotation, false);
                        if (b.has("position")) parseCurve(b.get("position"), ch.position, false);
                        if (b.has("scale")) parseCurve(b.get("scale"), ch.scale, true);
                        anim.boneChannels.put(be.getKey(), ch);
                    }
                }
                // 官方 timeline_events（YSM/Bedrock 的"到点脚本"）：文件夹模型以前整段漏解析，
                // 于是酒狐 idle 里的 "9.75: v.next_idle=math.random_integer(0, 4);" 永远不执行，
                // idle 变体 controller 也就永远停在 default → 世界内只剩表情微动。
                if (a.has("timeline") && a.get("timeline").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> te : a.getAsJsonObject("timeline").entrySet()) {
                        float t;
                        try {
                            t = Float.parseFloat(te.getKey());
                        } catch (NumberFormatException nfe) {
                            continue;
                        }
                        BedrockAnimation.TimelineEvent ev = new BedrockAnimation.TimelineEvent(t);
                        JsonElement val = te.getValue();
                        java.util.List<String> scripts = new java.util.ArrayList<>();
                        if (val != null && val.isJsonArray()) {
                            for (JsonElement se : val.getAsJsonArray()) {
                                if (se.isJsonPrimitive()) scripts.add(se.getAsString());
                            }
                        } else if (val != null && val.isJsonPrimitive()) {
                            scripts.add(val.getAsString());
                        }
                        for (String sc : scripts) {
                            if (sc == null || sc.isBlank()) continue;
                            ev.scripts.add(sc);
                            ev.exprs.add(ui.modelysm.client.molang.Molang.parse(sc));
                        }
                        if (!ev.exprs.isEmpty()) anim.timelineEvents.add(ev);
                    }
                }
                out.put(e.getKey(), anim);
            } catch (Exception ex) {
                LOGGER.warn("[ModelYSM] failed to parse animation {}: {}", e.getKey(), ex.getMessage());
            }
        }
    }

    private static void parseCurve(JsonElement el, BedrockAnimation.Curve curve, boolean scalarScale) {
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                float t;
                try {
                    t = Float.parseFloat(e.getKey());
                } catch (NumberFormatException nfe) {
                    continue; // 忽略非时间键
                }
                addFrame(curve, t, e.getValue(), scalarScale);
            }
        } else if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            // 两种格式：[{timestamp/post},...] 关键帧列表 或 [x,y,z] 直接常量向量
            boolean frameList = arr.size() > 0 && arr.get(0).isJsonObject();
            if (frameList) {
                for (JsonElement fe : arr) {
                    JsonObject f = fe.getAsJsonObject();
                    float t = f.has("timestamp") ? f.get("timestamp").getAsFloat() : 0f;
                    addFrame(curve, t, f.has("post") ? f.get("post") : f, scalarScale);
                }
            } else {
                addFrame(curve, 0f, el, scalarScale);
            }
        } else if (el.isJsonPrimitive()) {
            // 通道直接写标量常量/表达式（如 "scale": 1、"rotation": "-90-ysm.head_pitch"）
            addFrame(curve, 0f, el, scalarScale);
        }
        curve.frames.sort((a, b) -> Float.compare(a.t, b.t));
    }

    private static void addFrame(BedrockAnimation.Curve curve, float t, JsonElement valueEl, boolean scalarScale) {
        BedrockAnimation.Curve.Frame f = new BedrockAnimation.Curve.Frame(t);
        JsonElement preEl = valueEl, postEl = valueEl;
        boolean catmull = false;
        // 官方 JsonKeyFrameUtils:40-95 的关键帧读取口径：
        //   {"vector":..., "easing":"catmullrom"} 旧格式；{"pre":[...], "post":[...], "lerp_mode":"catmullrom"} 新格式。
        //   只有 pre 或只有 post 时，两者视为同一值（contiguous）。
        if (valueEl.isJsonObject()) {
            JsonObject o = valueEl.getAsJsonObject();
            if (o.has("vector")) {
                preEl = postEl = o.get("vector");
                if (o.has("easing") && o.get("easing").isJsonPrimitive()) {
                    catmull = "catmullrom".equalsIgnoreCase(o.get("easing").getAsString());
                }
            } else {
                boolean hasPre = o.has("pre");
                boolean hasPost = o.has("post");
                postEl = hasPost ? o.get("post") : (hasPre ? o.get("pre") : valueEl);
                preEl = hasPre ? o.get("pre") : postEl;
                if (o.has("lerp_mode") && o.get("lerp_mode").isJsonPrimitive()) {
                    catmull = "catmullrom".equalsIgnoreCase(o.get("lerp_mode").getAsString());
                }
            }
            if (postEl != null && postEl.isJsonObject() && postEl.getAsJsonObject().has("post")) {
                postEl = postEl.getAsJsonObject().get("post");
            }
        }
        Molang.Expr[] post = exprs3(postEl);
        Molang.Expr[] pre = exprs3(preEl);
        if (post == null && pre == null) return;
        f.expr = post != null ? post : pre;
        if (pre != null) f.preExpr = pre;
        f.catmull = catmull;
        curve.frames.add(f);
    }

    /** 把 数字 / [x,y,z] / 表达式 摊成 3 个表达式；null 表示无法解析 */
    private static Molang.Expr[] exprs3(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        Molang.Expr[] e = new Molang.Expr[3];
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            for (int i = 0; i < 3; i++) {
                e[i] = toExpr(arr.size() > i ? arr.get(i) : null);
            }
        } else {
            Molang.Expr ex = toExpr(el);
            e[0] = e[1] = e[2] = ex;
        }
        return e;
    }

    private static Molang.Expr toExpr(JsonElement el) {
        if (el == null || el.isJsonNull()) return ctx -> 0;
        if (el.isJsonPrimitive()) {
            if (el.getAsJsonPrimitive().isNumber()) {
                double v = el.getAsDouble();
                return ctx -> v;
            }
            return Molang.parse(el.getAsString());
        }
        return ctx -> 0;
    }

    private static float[] toFloat3(JsonArray arr) {
        return new float[]{
                arr.get(0).getAsFloat(),
                arr.size() > 1 ? arr.get(1).getAsFloat() : 0,
                arr.size() > 2 ? arr.get(2).getAsFloat() : 0
        };
    }
}
