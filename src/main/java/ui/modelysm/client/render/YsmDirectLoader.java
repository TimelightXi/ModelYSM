package ui.modelysm.client.render;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import ui.modelysm.ModelYSM;
import ui.modelysm.client.ModelLibrary;
import ui.modelysm.client.molang.Molang;
import ui.modelysm.client.ysm.RawYsmModel;
import ui.modelysm.client.ysm.YSMBinaryDeserializer;
import ui.modelysm.client.ysm.crypto.YsmCrypt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * .ysm 文件直读加载器：解密（crypto 3）→ 二进制反序列化（全版本 format 4~32）
 * → 直接构建 BedrockModel/动画/贴图，全程内存内完成，不生成任何中间文件。
 * 移植自 OpenYSM 项目（MIT）的 Java 解密与反序列化实现。
 */
public final class YsmDirectLoader {
    private static final Logger LOGGER = LogManager.getLogger(ModelYSM.MODID);

    private YsmDirectLoader() {}

    /** 解密 + 反序列化 + 构建（失败抛异常，调用方回退文件夹加载） */
    public static ModelLibrary.LoadedModel load(String id, byte[] ysmBytes) throws Exception {
        byte[] clear = YsmCrypt.decryptYsmFile(ysmBytes);
        RawYsmModel raw = new YSMBinaryDeserializer(clear).deserialize();

        RawYsmModel.RawGeometry geo = raw.mainEntity.mainModel;
        if (geo == null || geo.bones.isEmpty()) {
            throw new RuntimeException("no main geometry in " + id);
        }

        // ---- 几何：骨骼树 + 烘焙面片 ----
        // 二进制顶点 = MC 绝对坐标（bedrock x 镜像、÷16 已完成）
        // 二进制 pivot = 官方 RawModel 约定像素（x 已镜像、未 ÷16；rotation 已是 MC 弧度）
        // 顶点需减去骨骼 pivot（MC 方块单位）转为局部坐标
        Map<String, BedrockModel.Bone> byName = new LinkedHashMap<>();
        List<BedrockModel.Bone> roots = new ArrayList<>();
        for (RawYsmModel.RawBone rb : geo.bones) {
            // 二进制 pivot 与面片顶点同空间：x 已镜像、像素单位（官方 RawModel 约定，
            // RightArm JSON -3.45 ↔ 二进制 +3.45 实测确认）→ 只需 ÷16，不能再取反
            // （toMc 会二次镜像，导致 pivot x≠0 的骨骼（手臂等）几何翻到错误一侧）
            Vector3f mcPivot = new Vector3f(rb.pivot[0] / 16f, rb.pivot[1] / 16f, rb.pivot[2] / 16f);
            // 二进制骨骼 rotation 已是 MC 约定弧度（度→弧度与 x/y 取反在导出时完成，
            // 与动画 keyframe 的"度数+运行时转换"不同——实测小流萤 JSON -5° ↔ 二进制 +0.0873 rad 确认）
            Vector3f rest = new Vector3f(rb.rotation[0], rb.rotation[1], rb.rotation[2]);
            BedrockModel.Bone bone = new BedrockModel.Bone(rb.name, null, mcPivot, rest);
            byName.put(rb.name, bone);
        }
        for (RawYsmModel.RawBone rb : geo.bones) {
            BedrockModel.Bone bone = byName.get(rb.name);
            if (rb.parentName != null && !rb.parentName.isEmpty()) {
                BedrockModel.Bone parent = byName.get(rb.parentName);
                if (parent != null) {
                    bone.parent = parent;
                    parent.children.add(bone);
                    continue;
                }
            }
            roots.add(bone);
        }
        for (RawYsmModel.RawBone rb : geo.bones) {
            BedrockModel.Bone bone = byName.get(rb.name);
            for (RawYsmModel.RawCube rc : rb.cubes) {
                BedrockModel.BakedFace[] faces = new BedrockModel.BakedFace[rc.faces.size()];
                for (int f = 0; f < rc.faces.size(); f++) {
                    faces[f] = bakeFace(rc.faces.get(f), bone.mcPivot);
                }
                bone.cubes.add(new BedrockModel.Cube(faces));
            }
        }
        BedrockModel model = BedrockModel.create(geo.textureWidth, geo.textureHeight, roots, byName);

        ModelLibrary.LoadedModel lm = new ModelLibrary.LoadedModel(id, model);
        // 静止姿态包围盒（GUI 卡片/大预览按真实尺寸取景，否则高模型会被裁头）
        bakeModelBounds(lm, model);

        // ---- 元数据（ysm.json 等价信息，二进制内嵌） ----
        if (raw.metadata != null && raw.metadata.name != null && !raw.metadata.name.isEmpty()) {
            lm.displayName = raw.metadata.name;
        }
        lm.widthScale = clampScale(raw.properties != null ? raw.properties.widthScale : 1f);
        lm.heightScale = clampScale(raw.properties != null ? raw.properties.heightScale : 1f);
        // .ysm 二进制里已经带了作者导出时的 height_scale/width_scale（RawYsmModel 缺省就是 0.7），
        // 属于"显式值"，卡片预览不能再按"缺字段"补 0.7 默认，否则末影龙娘这类会被额外缩 30%。
        lm.explicitHeightScale = true;
        // 卡片预览动画（官方 preview_animation，缺失回退 idle）
        if (raw.properties != null && raw.properties.previewAnimation != null
                && !raw.properties.previewAnimation.isEmpty()
                && raw.mainEntity != null && raw.mainEntity.animationFiles != null) {
            String pa = raw.properties.previewAnimation;
            boolean exists = raw.mainEntity.animationFiles.values().stream()
                    .anyMatch(af -> af != null && af.animations != null && af.animations.containsKey(pa));
            if (exists) lm.previewAnimation = pa;
        }
        if (raw.properties != null) lm.disablePreviewRotation = raw.properties.disablePreviewRotation;

        // ---- 卡片底图 / 前景层（官方 properties.gui_background / gui_foreground）----
        // 图本身内嵌在 background_images 里（名字如 gui_background / gui_foreground，
        // 而 properties 里写的是 "textures/gui/background.png" 这种路径，按文件名模糊匹配）。
        if (raw.properties != null && !raw.properties.backgroundImages.isEmpty()) {
            java.util.Map<String, ResourceLocation> guiByName = new java.util.HashMap<>();
            int gi = 0;
            for (RawYsmModel.RawImage im : raw.properties.backgroundImages) {
                if (im == null || im.data == null || im.data.length == 0) continue;
                String nm = im.name == null ? ("img" + gi) : im.name;
                var rl = ModelLibrary.registerTextureBytes(id, "gui_" + gi + "_" + nm, im.data, im.width, im.height);
                gi++;
                if (rl == null) continue;
                lm.guiImages.add(rl);
                guiByName.put(guiKey(nm), rl);
            }
            lm.guiBackground = resolveGuiImage(guiByName, raw.properties.guiBackground);
            lm.guiForeground = resolveGuiImage(guiByName, raw.properties.guiForeground);
            if (lm.guiBackground != null || lm.guiForeground != null) {
                LOGGER.info("[ModelYSM] model '{}' gui images: bg={} fg={}", id,
                        lm.guiBackground != null, lm.guiForeground != null);
            }
        }

        // ---- 贴图（内嵌 PNG/WebP/原始 RGBA 字节，直接注册不落盘） ----
        String defaultTex = raw.properties != null ? raw.properties.defaultTexture : "";
        // default_texture 优先，其余按名字稳定排序
        List<Map.Entry<String, RawYsmModel.RawTexture>> entries = new ArrayList<>(raw.mainEntity.textures.entrySet());
        entries.sort((a, b) -> {
            boolean da = a.getKey().equalsIgnoreCase(defaultTex);
            boolean db = b.getKey().equalsIgnoreCase(defaultTex);
            if (da != db) return da ? -1 : 1;
            return a.getKey().compareToIgnoreCase(b.getKey());
        });
        for (Map.Entry<String, RawYsmModel.RawTexture> e : entries) {
            RawYsmModel.RawTexture t = e.getValue();
            if (t.data == null || t.data.length == 0) continue;
            var rl = ModelLibrary.registerTextureBytes(id, e.getKey(), t.data, t.width, t.height);
            if (rl != null) {
                lm.textures.add(rl);
                lm.textureNames.add(e.getKey());
            }
        }

        // ---- 动画（main 组） ----
        RawYsmModel.RawAnimationFile af = raw.mainEntity.animationFiles.get("main");
        if (af != null) {
            for (RawYsmModel.RawAnimation ra : af.animations.values()) {
                lm.animations.put(ra.name, buildAnim(ra));
            }
        }

        // ---- 其余动画组（extra / tlm / tac / carryon …）----
        // 官方轮盘"播放动画"列的是 properties.extra_animation 里的条目（extra0/extra1/…），
        // 这些动画实体都在 extra 组里；只加载 main 组会导致点击后 loaded.animations 命中不到 →
        // 回落成普通待机动画，脚本（如 Rossi 的穿脱兜帽）永远不执行。
        // 同名以 main 组为准（putIfAbsent），arm 组单独进 armAnimations 不混入。
        for (Map.Entry<String, RawYsmModel.RawAnimationFile> g : raw.mainEntity.animationFiles.entrySet()) {
            String group = g.getKey();
            if ("main".equals(group) || "arm".equals(group) || g.getValue() == null) continue;
            for (RawYsmModel.RawAnimation ra : g.getValue().animations.values()) {
                lm.animations.putIfAbsent(ra.name, buildAnim(ra));
            }
        }

        // ---- animation_controllers 状态机（条件动画层） ----
        if (raw.mainEntity.animationControllers != null) {
            for (Map.Entry<String, RawYsmModel.RawAnimationController> e : raw.mainEntity.animationControllers.entrySet()) {
                RawYsmModel.RawAnimationController rc = e.getValue();
                if (rc == null || rc.states.isEmpty()) continue;
                ui.modelysm.client.anim.ModelControllers.Ctrl ctrl = new ui.modelysm.client.anim.ModelControllers.Ctrl();
                ctrl.key = e.getKey();
                if (rc.initialState != null && !rc.initialState.isEmpty()) ctrl.initial = rc.initialState;
                for (RawYsmModel.RawControllerState rs : rc.states) {
                    ui.modelysm.client.anim.ModelControllers.CState st = new ui.modelysm.client.anim.ModelControllers.CState();
                    st.name = rs.name;
                    st.animations.addAll(rs.animations.keySet());
                    // 官方 on_entry / on_exit（二进制里已解析，v.next_idle=0 之类的状态脚本）
                    if (rs.onEntry != null) st.onEntry.addAll(rs.onEntry);
                    if (rs.onExit != null) st.onExit.addAll(rs.onExit);
                    for (Map.Entry<String, String> t : rs.transitions.entrySet()) {
                        ui.modelysm.client.anim.ModelControllers.Trans tr = new ui.modelysm.client.anim.ModelControllers.Trans();
                        tr.target = t.getKey();
                        tr.expr = t.getValue();
                        st.transitions.add(tr);
                    }
                    ctrl.states.put(rs.name, st);
                }
                lm.controllers.put(ctrl.key, ctrl);
            }
        }

        // ---- 子实体（弹射物/载具替换渲染：minecraft:arrow、minecraft:boat 等） ----
        loadSubEntities(lm, raw.vehicles);
        loadSubEntities(lm, raw.projectiles);

        // ---- 第一人称手臂模型（armModel + "arm" 动画组） ----
        if (raw.mainEntity.armModel != null && !raw.mainEntity.armModel.bones.isEmpty()) {
            try {
                BedrockModel armM = buildGeometry(raw.mainEntity.armModel);
                lm.armModel = armM;
                RawYsmModel.RawAnimationFile armAf = raw.mainEntity.animationFiles.get("arm");
                if (armAf != null) {
                    for (RawYsmModel.RawAnimation ra : armAf.animations.values()) {
                        lm.armAnimations.put(ra.name, buildAnim(ra));
                    }
                }
                LOGGER.info("[ModelYSM] model '{}' arm model loaded ({} bones, {} arm anims)",
                        id, armM.bonesByName.size(), lm.armAnimations.size());
            } catch (Exception ex) {
                LOGGER.warn("[ModelYSM] arm model build failed: {}", ex.getMessage());
            }
        }

        // roaming 变量清单（轮盘"漫游开关"用：官方"换装/穿脱"等价能力）
        collectRoamingVars(lm, raw);

        LOGGER.info("[ModelYSM] direct-loaded .ysm '{}' (format={}, bones={}, anims={}, textures={})",
                id, raw.formatVersion, model.bonesByName.size(), lm.animations.size(), lm.textures.size());
        // ---- 官方 Z 键轮盘数据（properties.extra_animation + 分类 + 内嵌语言表） ----
        if (raw.properties != null) {
            for (Map.Entry<String, RawYsmModel.RawLanguageFile> le : raw.languageFiles.entrySet()) {
                if (le.getValue() != null && le.getValue().data != null)
                    lm.languages.put(le.getKey(), new LinkedHashMap<>(le.getValue().data));
            }
            // 中文优先的显示名解析：properties.extra_animation.<动画名> → 模型内 value → 动画名
            java.util.function.BiFunction<String, String, String> label = (key, fallback) -> {
                for (String locale : new String[]{"zh_cn", "zh_tw", "en_us"}) {
                    Map<String, String> lang = lm.languages.get(locale);
                    if (lang != null) {
                        String v = lang.get(key);
                        if (v != null && !v.isBlank()) return v;
                    }
                }
                return fallback != null && !fallback.isBlank() ? fallback : key;
            };
            if (raw.properties.extraAnimations != null) {
                for (Map.Entry<String, String> e : raw.properties.extraAnimations.entrySet()) {
                    lm.extraAnimations.put(e.getKey(),
                            label.apply("properties.extra_animation." + e.getKey(), e.getValue()));
                }
            }
            if (raw.properties.extraAnimationClassifies != null) {
                for (RawYsmModel.ExtraAnimationClassify c : raw.properties.extraAnimationClassifies) {
                    if (c == null || c.extras == null || c.extras.isEmpty()) continue;
                    Map<String, String> out = new LinkedHashMap<>();
                    for (Map.Entry<String, String> e : c.extras.entrySet()) {
                        out.put(e.getKey(), label.apply("properties.extra_animation." + e.getKey(), e.getValue()));
                    }
                    lm.extraAnimationClassifies.put(c.id, out);
                }
            }
            if (raw.properties.extraAnimationButtons != null) {
                // 官方轮盘 ⚙ 配置表单：title/description/label 用官方 i18n key 模板本地化
                // （AnimationRouletteScreen: properties.extra_animation_buttons.<id>.config_forms.<idx>.title/description/labels.<labelIdx>）
                int bi = 0;
                for (RawYsmModel.ExtraAnimationButton b : raw.properties.extraAnimationButtons) {
                    if (b == null || b.id == null || b.id.isBlank()) continue;
                    ModelLibrary.LoadedModel.ConfigButton cb = new ModelLibrary.LoadedModel.ConfigButton(b.id,
                            label.apply("properties.extra_animation_buttons." + b.id + ".name", b.name));
                    int fi = 0;
                    if (b.forms != null) {
                        for (RawYsmModel.ConfigForm f : b.forms) {
                            if (f == null || f.type == null) continue;
                            String base = "properties.extra_animation_buttons." + b.id + ".config_forms." + fi;
                            Map<String, String> labels = new LinkedHashMap<>();
                            int li = 0;
                            for (Map.Entry<String, String> le : f.labels.entrySet()) {
                                labels.put(label.apply(base + ".labels." + li, le.getKey()),
                                        le.getValue() == null ? "" : le.getValue());
                                li++;
                            }
                            String desc = f.description == null ? "" : label.apply(base + ".description", f.description);
                            // 语言表里没有这条描述时不要显示原始 i18n key（官方就缺 .6.description，
                            // 面板上会横着糊一长条 "properties.extra_animation_buttons..."）
                            if (desc.startsWith("properties.")) desc = "";
                            cb.forms.add(new ModelLibrary.LoadedModel.ConfigForm(f.type,
                                    label.apply(base + ".title", f.title),
                                    desc,
                                    f.defaultValue == null ? "" : f.defaultValue,
                                    f.step, f.min, f.max, labels));
                            fi++;
                        }
                    }
                    lm.configButtons.put(cb.id, cb);
                    bi++;
                }
                // extra_animation 中 value="#id" 的配置入口条目：显示名替换为配置按钮名（保留 # 前缀供 UI 识别跳过）
                for (Map.Entry<String, String> e : lm.extraAnimations.entrySet()) {
                    String v = e.getValue();
                    if (v != null && v.startsWith("#")) {
                        ModelLibrary.LoadedModel.ConfigButton cb = lm.configButtons.get(v.substring(1));
                        if (cb != null) e.setValue("#" + cb.name);
                    }
                }
                if (bi > 0) {
                    LOGGER.info("[ModelYSM] model '{}' config forms: {} buttons (ids={})", id, bi, lm.configButtons.keySet());
                }
            }
            if (!lm.extraAnimations.isEmpty()) {
                LOGGER.info("[ModelYSM] model '{}' official roulette: {} entries, {} submenus",
                        id, lm.extraAnimations.size(), lm.extraAnimationClassifies.size());
            }
        }
        return lm;
    }

    /** 几何构建：骨骼树 + 烘焙面片 → BedrockModel（主模型/手臂模型/子实体共用） */
    private static BedrockModel buildGeometry(RawYsmModel.RawGeometry geo) {
        Map<String, BedrockModel.Bone> byName = new LinkedHashMap<>();
        List<BedrockModel.Bone> roots = new ArrayList<>();
        for (RawYsmModel.RawBone rb : geo.bones) {
            // 同上：二进制 pivot 已 x 镜像（官方 RawModel 约定），只 ÷16 不取反
            Vector3f mcPivot = new Vector3f(rb.pivot[0] / 16f, rb.pivot[1] / 16f, rb.pivot[2] / 16f);
            Vector3f rest = new Vector3f(rb.rotation[0], rb.rotation[1], rb.rotation[2]);
            BedrockModel.Bone bone = new BedrockModel.Bone(rb.name, null, mcPivot, rest);
            byName.put(rb.name, bone);
        }
        for (RawYsmModel.RawBone rb : geo.bones) {
            BedrockModel.Bone bone = byName.get(rb.name);
            if (rb.parentName != null && !rb.parentName.isEmpty()) {
                BedrockModel.Bone parent = byName.get(rb.parentName);
                if (parent != null) {
                    bone.parent = parent;
                    parent.children.add(bone);
                    continue;
                }
            }
            roots.add(bone);
        }
        for (RawYsmModel.RawBone rb : geo.bones) {
            BedrockModel.Bone bone = byName.get(rb.name);
            for (RawYsmModel.RawCube rc : rb.cubes) {
                BedrockModel.BakedFace[] faces = new BedrockModel.BakedFace[rc.faces.size()];
                for (int f = 0; f < rc.faces.size(); f++) {
                    faces[f] = bakeFace(rc.faces.get(f), bone.mcPivot);
                }
                bone.cubes.add(new BedrockModel.Cube(faces));
            }
        }
        return BedrockModel.create(geo.textureWidth, geo.textureHeight, roots, byName);
    }

    /** 子实体构建：identifier（如 "minecraft:arrow"）→ BedrockModel + 贴图 + 动画 */
    private static void loadSubEntities(ModelLibrary.LoadedModel lm, Map<String, RawYsmModel.RawSubEntity> subs) {
        if (subs == null) return;
        for (Map.Entry<String, RawYsmModel.RawSubEntity> e : subs.entrySet()) {
            RawYsmModel.RawSubEntity sub = e.getValue();
            if (sub == null || sub.model == null || sub.model.bones == null || sub.model.bones.isEmpty()) continue;
            try {
                ModelLibrary.LoadedModel.SubModel sm = buildSubModel(lm.id, e.getKey(), sub);
                if (sm != null) lm.subModels.put(e.getKey().toLowerCase(), sm);
            } catch (Exception ex) {
                LOGGER.warn("[ModelYSM] sub-entity '{}' build failed: {}", e.getKey(), ex.getMessage());
            }
        }
    }

    private static ModelLibrary.LoadedModel.SubModel buildSubModel(String modelId, String identifier, RawYsmModel.RawSubEntity sub) {
        RawYsmModel.RawGeometry geo = sub.model;
        BedrockModel model = buildGeometry(geo);
        String subId = modelId + "_" + identifier.replace(':', '_');
        ResourceLocation tex = null;
        for (Map.Entry<String, RawYsmModel.RawTexture> t : sub.textures.entrySet()) {
            if (t.getValue() != null && t.getValue().data != null && t.getValue().data.length > 0) {
                tex = ModelLibrary.registerTextureBytes(subId, t.getKey(), t.getValue().data, t.getValue().width, t.getValue().height);
                break;
            }
        }
        Map<String, BedrockAnimation> anims = new LinkedHashMap<>();
        if (sub.animationFiles != null) {
            for (RawYsmModel.RawAnimationFile af : sub.animationFiles.values()) {
                if (af == null) continue;
                for (RawYsmModel.RawAnimation ra : af.animations.values()) {
                    BedrockAnimation ba = new BedrockAnimation(ra.name, ra.loopMode == 1, ra.length);
                    for (RawYsmModel.RawBoneAnimation rba : ra.boneAnimations) {
                        BedrockAnimation.Channels ch = ba.boneChannels.computeIfAbsent(rba.boneName,
                                k -> new BedrockAnimation.Channels());
                        fillCurve(ch.rotation, rba.rotation);
                        fillCurve(ch.position, rba.position);
                        fillCurve(ch.scale, rba.scale);
                    }
                    anims.put(ra.name, ba);
                }
            }
        }
        return new ModelLibrary.LoadedModel.SubModel(identifier, model, tex, anims);
    }


    /**
     * 烘焙单面：顶点转相对骨骼 pivot 的局部坐标。
     * 与官方 YSMClientMapper:485-495 一致——顶点顺序原样保留，法线直接用文件里的
     * rf.normal（官方 NativeModelRenderer 光照用的就是 quad.normal，从不重算叉积）。
     * 之前的实现会按"叉积 sign"翻转顶点顺序，导致法线与绕向被改写，
     * 光照方向随之整片反过来 → 卡片/世界里 .ysm 模型整体偏暗。
     */
    private static BedrockModel.BakedFace bakeFace(RawYsmModel.RawFace rf, Vector3f mcPivot) {
        BedrockModel.BakedFace out = new BedrockModel.BakedFace();
        for (int i = 0; i < 4; i++) {
            out.pos[i][0] = rf.positions[i][0] - mcPivot.x();
            out.pos[i][1] = rf.positions[i][1] - mcPivot.y();
            out.pos[i][2] = rf.positions[i][2] - mcPivot.z();
            out.u[i] = rf.u[i];
            out.v[i] = rf.v[i];
        }
        if (rf.normal != null) out.normal = new float[]{rf.normal[0], rf.normal[1], rf.normal[2]};
        return out;
    }

    /**
     * 扫描模型里"被动画表达式真正读取"的 roaming 变量名。
     * 只扫 keyframe 表达式、不扫 timeline 脚本——脚本里成对出现的中间量
     * （Rossi 的 v.roaming.b 只是 a 的翻转记忆）不该出现在轮盘开关里。
     */
    private static void collectRoamingVars(ModelLibrary.LoadedModel lm, RawYsmModel raw) {
        if (raw.mainEntity == null || raw.mainEntity.animationFiles == null) return;
        for (RawYsmModel.RawAnimationFile g : raw.mainEntity.animationFiles.values()) {
            if (g == null) continue;
            for (RawYsmModel.RawAnimation ra : g.animations.values()) {
                for (RawYsmModel.RawBoneAnimation ba : ra.boneAnimations) {
                    scanRoaming(lm, ba.rotation);
                    scanRoaming(lm, ba.position);
                    scanRoaming(lm, ba.scale);
                }
            }
        }
    }

    private static void scanRoaming(ModelLibrary.LoadedModel lm, List<RawYsmModel.RawKeyframe> channel) {
        for (RawYsmModel.RawKeyframe kf : channel) {
            for (Object o : kf.postData) {
                if (o instanceof String s) addRoaming(lm, s);
            }
            if (kf.preData != null) {
                for (Object o : kf.preData) {
                    if (o instanceof String s) addRoaming(lm, s);
                }
            }
        }
    }

    private static void addRoaming(ModelLibrary.LoadedModel lm, String expr) {
        if (expr == null || !expr.contains("roaming.")) return;
        java.util.regex.Matcher m = ROAMING_VAR.matcher(expr);
        while (m.find()) lm.roamingVars.add("v.roaming." + m.group(1));
    }

    /** 变量名（v./variable. 前缀均可，统一成 v.roaming.x） */
    private static final java.util.regex.Pattern ROAMING_VAR =
            java.util.regex.Pattern.compile("(?:variable|v)\\.roaming\\.([A-Za-z0-9_]+)");

    /** gui 图名字归一化：只留小写字母数字（"gui_background" / "background" 都能对上） */
    private static String guiKey(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * properties 里写的是路径（"textures/gui/background.png"），
     * background_images 里记的是名字（"gui_background"）——按"路径主名包含/被包含"匹配。
     */
    private static ResourceLocation resolveGuiImage(Map<String, ResourceLocation> byName, String path) {
        if (path == null || path.isBlank() || byName.isEmpty()) return null;
        String base = path;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String want = guiKey(base);
        for (Map.Entry<String, ResourceLocation> e : byName.entrySet()) {
            if (e.getKey().equals(want) || e.getKey().endsWith(want) || want.endsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 静止姿态包围盒（方块单位）：GUI 取景按真实尺寸算缩放，
     * 否则 2 米以上或带大尾巴/翅膀的模型会被卡片裁掉头部（对比官方卡片一眼就能看出）。
     */
    private static void bakeModelBounds(ModelLibrary.LoadedModel lm, BedrockModel model) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        boolean any = false;
        for (BedrockModel.Bone b : model.bonesByName.values()) {
            for (BedrockModel.Cube c : b.cubes) {
                if (c.bakedFaces != null) {
                    // .ysm 烘焙面片的顶点本身就是已镜像的 MC 绝对方块坐标，不能再加 bone.mcPivot
                    // （加了会得到 2 倍偏移：小流萤算出 h=6.15、123 算出 w=30，卡片被缩到看不见）
                    for (BedrockModel.BakedFace f : c.bakedFaces) {
                        for (int i = 0; i < 4; i++) {
                            float x = f.pos[i][0];
                            float y = f.pos[i][1];
                            float z = f.pos[i][2];
                            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                            any = true;
                        }
                    }
                } else if (c.min != null && c.max != null) {
                    for (int sx = 0; sx < 2; sx++) {
                        for (int sy = 0; sy < 2; sy++) {
                            for (int sz = 0; sz < 2; sz++) {
                                float x = b.mcPivot.x() + (sx == 0 ? c.min.x() : c.max.x());
                                float y = b.mcPivot.y() + (sy == 0 ? c.min.y() : c.max.y());
                                float z = b.mcPivot.z() + (sz == 0 ? c.min.z() : c.max.z());
                                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                                any = true;
                            }
                        }
                    }
                }
            }
        }
        if (!any) return;
        lm.modelWidth = Math.max(0.4f, Math.max(maxX - minX, maxZ - minZ));
        lm.modelHeight = Math.max(0.4f, maxY - minY);
        lm.modelMinY = minY;
        LOGGER.info("[ModelYSM] model '{}' preview bounds: w={} h={} minY={}",
                lm.id, String.format("%.2f", lm.modelWidth), String.format("%.2f", lm.modelHeight),
                String.format("%.2f", lm.modelMinY));
    }
    private static BedrockAnimation buildAnim(RawYsmModel.RawAnimation ra) {
        BedrockAnimation ba = new BedrockAnimation(ra.name, ra.loopMode == 1, ra.length);
        // 官方 blend_weight（二进制里可能是常数 Float 或 Molang 字符串）
        Object bw = ra.blendWeight;
        if (bw instanceof Number n) {
            float c = n.floatValue();
            ba.blendWeightExpr = ctx -> c;
        } else if (bw instanceof String s && !s.isBlank()) {
            try {
                ba.blendWeightExpr = ui.modelysm.client.molang.Molang.parse(s);
            } catch (Throwable ignored) {
            }
        }
        for (RawYsmModel.RawBoneAnimation rba : ra.boneAnimations) {
            BedrockAnimation.Channels ch = ba.boneChannels.computeIfAbsent(rba.boneName,
                    k -> new BedrockAnimation.Channels());
            fillCurve(ch.rotation, rba.rotation);
            fillCurve(ch.position, rba.position);
            fillCurve(ch.scale, rba.scale);
        }
        // 官方 timeline_events：到点执行的 Molang 脚本（穿脱开关、弹簧积分、湿润度等都挂在这里）
        for (RawYsmModel.RawTimelineEvent ev : ra.timelineEvents) {
            if (ev == null || ev.events == null || ev.events.isEmpty()) continue;
            BedrockAnimation.TimelineEvent te = new BedrockAnimation.TimelineEvent(ev.timestamp);
            for (String script : ev.events) {
                if (script == null || script.isBlank()) continue;
                te.scripts.add(script);
                te.exprs.add(Molang.parse(script));
            }
            if (!te.exprs.isEmpty()) ba.timelineEvents.add(te);
        }
        return ba;
    }

    private static void fillCurve(BedrockAnimation.Curve curve, List<RawYsmModel.RawKeyframe> frames) {
        for (RawYsmModel.RawKeyframe kf : frames) {
            BedrockAnimation.Curve.Frame f = new BedrockAnimation.Curve.Frame(kf.timestamp);
            // 与 JSON 路径一致：只取 post（pre 用于阶跃动画，此处简化）
            Object[] data = kf.postData;
            for (int i = 0; i < 3; i++) {
                Object o = data != null && data.length > i ? data[i] : null;
                if (o instanceof Float fl) {
                    double v = fl.doubleValue();
                    f.expr[i] = ctx -> v;
                } else if (o instanceof String s && !s.isBlank()) {
                    f.expr[i] = Molang.parse(s);
                } else {
                    f.expr[i] = ctx -> 0;
                }
            }
            curve.frames.add(f);
        }
        curve.frames.sort((a, b) -> Float.compare(a.t, b.t));
    }

    private static float clampScale(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v) || v <= 0) return 1f;
        return Math.max(0.05f, Math.min(20f, v));
    }
}
