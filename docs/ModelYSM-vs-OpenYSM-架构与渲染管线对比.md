# ModelYSM ↔ OpenYSM：架构、功能与渲染管线逐层对比

> 对照对象
> - **ModelYSM**：`D:\软件下载\ideaIU\IdeaProjects\ModelYSM`（modid `modelysm`，包 `ui.modelysm`，Forge 47 / MC 1.20.1）
> - **OpenYSM**：`D:\软件下载\ideaIU\IdeaProjects\OpenYSM-1.20.1-forge`（包 `com.elfmcys.yesstevemodel` + `rip.ysm`，Architectury：common / fabric / forge）
>
> 本文所有行号均取自本地源码实测，可直接跳转核对。

---

## 0. 一句话结论

OpenYSM 是一套**把 GeckoLib3 整体重写进模组内部、并把渲染下沉到 GPU/原生 SIMD 的完整 YSM 运行时**；
ModelYSM 是一套**在 EntityRenderDispatcher 上做一次性拦截、用 PoseStack 递归自己重算骨骼矩阵的最小可用实现**。

两者"看起来一样"的部分集中在**烘焙几何 + 每面 UV + 逐面投影剔除 + entityCutoutNoCull** 这条主干；差异集中在**入口挂载方式、动画引擎、Molang 覆盖面、渲染后端、事件/音频、网络与权限、扩展兼容**这七块。

---

## 1. 规模与定位

| 维度 | ModelYSM | OpenYSM |
|---|---|---|
| Java 文件 | 87 | 1,133（common 912 + forge 155 + fabric 66） |
| 代码行 | 16,488 | common 95,142 + forge 5,984 ≈ 101,000 |
| 包结构 | `ui.modelysm.client` 71 个文件为主，network 6 / common 4 / server 3 / mixin 2 | `com.elfmcys.yesstevemodel`（563）+ `rip.ysm`（131）+ 内嵌 geckolib3（151+）/molang/audio/zstd/pinyin4j/gagravarr |
| 加载器 | 仅 Forge | Architectury 三平台 |
| 内嵌第三方 | zstd（读写 .ysm）、crypto（CityHash / XChaCha20 / MT19937）≈ 40 文件 | zstd 37 / crypto 3 / pinyin4j 18 / gagravarr 25 / concentus（Opus）数十 / native JNI |
| 定位 | NPC 绑定 + 模型库 + 轮盘，只服务于"给实体套 YSM 外观" | 完整复刻官方 YSM：玩家模型、弹射物、载具、音频、上传、授权、API |
| 量级比 | 1× | ≈ 6.5× 行数 |

ModelYSM 的压缩方式很明确：**砍掉音频/粒子/上传/授权/GPU/原生/多加载器，把 GeckoLib 的控制器运行时压成 316 行的 `ModelControllers`，把 Molang 压成 527 行的 `Molang` 子集。**

---

## 2. 架构分层对照

| 层 | ModelYSM | OpenYSM |
|---|---|---|
| 入口/挂载 | `EntityRenderDispatcherMixin` 在 `render(...)` **HEAD** 拦截，命中绑定就自绘并 `ci.cancel()`（`mixin/client/EntityRenderDispatcherMixin.java:23-52`）；`shouldRender` HEAD 换 AABB 判定（`:62-69`）；`ItemInHandRenderer.renderPlayerArm` HEAD 换第一人称手臂（`mixin/client/FirstPersonRendererMixin.java:19`） | **不拦截 dispatcher**，而是自建渲染器实例：`RendererManager:36-70` 在资源重载时 new `CustomPlayerRenderer / ProjectileRenderer / HandItemRenderer / VehicleRenderer`，这些类 `extends LivingEntityRenderer<PlayerEntity, PlayerModel>`（`GeoReplacedEntityRenderer:41`） |
| 实体适配 | 无"每实体渲染器对象"，只有 `ClientState.bindingFor(uuid)` 查表 + `AnimStates.get(uuid)` 存动画时钟/速度/事件游标 | `AnimatableEntity` / `GeoEntity` / `PlayerGeoEntity` / `LivingAnimatable` 一整套对象模型，能力用 `capability`（`PlayerModelCapability`、`StarModelsCapability`、`AuthModelsCapability`、`VehicleCapability`…）挂在实体上 |
| 动画引擎 | `BedrockAnimation`（线性采样）+ `ModelControllers`（状态机/条件层）+ `Molang` 子集 + `Timelines` + `Physics`（弹簧）+ `AnimStates` | `AnimationProcessor`（`tickAnimation / applyTransform / initBones / pre-postProcess`）+ `AnimationControllerRuntime/Instance/Composite` + `BoneTopLevelSnapshot` + 关键帧体系（Linear / CatmullRom / Transition / Easing）+ 事件关键帧（Sound / Particle / Instruction） |
| 几何/资源 | `ModelLibrary.load` 一次性解析 → `LoadedModel{BedrockModel, Map<String,BedrockAnimation>, textures, controllers, subModels}`；`.ysm` 走 `YsmDirectLoader` + `YSMBinaryDeserializer` | `YSMFolderDeserializer`(1138) / `YSMBinaryDeserializer`(857) / `YSMBinarySerializer`(515) / `YSMClientMapper`(996) → 烘焙成 `GeoModel.bakedBones/BakedCube/BakedQuad`（含 `partMask`、`cullable`、`glow`）+ 扁平 `float[] matrixData` |
| 渲染后端 | 只有 CPU：`NpcRenderer.renderBone` 递归 PoseStack，逐顶点直写 `VertexConsumer` | 三档：GPU（`BoneSkinShader`，骨骼矩阵一次性蒙皮）/ Iris 阴影包（`IrisRenderPath`）/ 原生 SIMD（`GeoModel.nComputeModelVertices` JNI）/ CPU 兜底（`NativeModelRenderer.renderModel:96-175`） |
| 光照/材质 | 单一 `ModelYsmRenderTypes.modelNoCull` = cutout + NO_CULL；自发光靠 lightmap `pack(15,15)` | `IGeoRenderer.getRenderType:55-66`：`entityCutoutNoCull` / `CustomEntityTranslucentRenderType`（按 `isTranslucentTexture` 自动切）/ `RenderType.outline`（实体发光）；骨骼 `glow` 同样用 `pack(15,15)`（`NativeModelRenderer:152`） |
| 网络 | 6 包：`ClientHello/ModelRequest/ModelChunk/SyncNpcModels/EntityAction` + `Net` | 25 包：玩家状态同步、切模型、播动画、挥臂、Molang 表达式同步、星标收藏、授权模型、上传三段式、版本检查、执行 Molang 请求 |
| 界面 | 3 屏：`ModelPickerScreen`(641)、`ModelListScreen`(215)、`ModelWheelScreen`(1067) | 9+ 屏：`PlayerModelScreen`(811)、`PlayerTextureScreen`(298)/`ModernPlayerTextureScreen`、`AnimationRouletteScreen`(651)、`ModelInfoScreen`、`ModelUploadScreen`、`ExtraPlayerRenderScreen`、`SearchSuggestions`(374)、`DisclaimerScreen`、`DownloadScreen`、`OpenModelFolderScreen` |
| 音频 | 无 | `AudioPlayerManager` / `NativeAudioDecoder` / `OggVorbisAudioStream` / `OggOpusAudioStream` + 声音关键帧 |
| 安全 | 只做读取：`YsmCrypt` / `XChaCha20` / `CityHash` 解 `.ysm` | 读+写：`YSMBinarySerializer` 反序列化回模型、`security` 包、授权模型、上传审批与反馈 |
| 对外 API / 兼容 | 无 | `rip.ysm.api`(13) + `rip.ysm.compat`(31)：EMF/ETF、Oculus、OptiFine、SBackpack、TACZ（刀/枪/腰挂/肩挂/背包骨骼分组） |

---

## 3. 渲染管线：两栏时序对照

### 3.1 ModelYSM 一帧的完整链路

```
EntityRenderDispatcher.render(HEAD)                       mixin:EntityRenderDispatcherMixin:23
├─ pushPose()                                             (:35 —— 因为要 cancel，vanilla 的 push/pop 被跳过)
├─ ProjectileRenderHook.hook  → 命中则画弹射物几何
├─ NpcRenderHook.hook(living, x,y,z, ...)                 client/NpcRenderHook.java:19
│   ├─ ClientState.bindingFor(uuid) 为空 → 返回 false（交回原版渲染）
│   ├─ poseStack.translate(x, y, z)                       缆(HEAD 注入时原版还没 translate)
│   ├─ AnimStates 采样 dt / 速度(0.7 旧 + 0.3 新) / 受击上升沿   NpcRenderHook:40-60
│   ├─ Physics.update(uuid, dt)                           弹簧物理推进
│   └─ NpcRenderer.renderFull(...)                         NpcRenderer:107-375
│       ├─ model.resetPose()
│       ├─ 组装 Molang.Ctx：animTime / lifeTime / moveSpeed / headPitch / health /
│       │   queries 手写 20 余项（is_sneaking…has_boots）/ roaming / phySicsOwner
│       ├─ EntitySettings.molVars 注入 queries（轮盘设置面板的 v.*）
│       ├─ Timelines.fire(anim, prevT, curT, ctx)          timeline_events 到点执行
│       ├─ applyAnimLayers: pre_parallel0-7 → 状态动画 → 控制器条件层 → parallel0-7   (:468)
│       ├─ applyArmorAnims（helmet/chestplate/… 自带盔甲骨骼动画）                      (:451)
│       └─ blendFrom 过渡：捕获新姿态 → 复位 → 重放旧动画 → 逐骨骼 lerp 0.1s          (:222-238)
│       ├─ applyHeadRotation(headYaw, headPitch)（±25° clamp 在 hook 侧）
│       ├─ poseStack: Y(180 - bodyYaw) → scale(sx,sy,sz) → 乘 width_scale/height_scale
│       ├─ getBuffer(ModelYsmRenderTypes.modelNoCull(tex))  一次取一个 VC
│       └─ for root in rootBones: renderBone(递归)                         :572-638
│           ├─ translate(mcPivot - parentPivot + animPos[3])   （根骨骼额外 +rootOffset）
│           ├─ mulPose(Z, Y, X) = restRot + animRot（bedrock x/y 取反）
│           ├─ scale(animScale)（某轴 0 = 骨骼隐藏）
│           ├─ for cube: drawCube                                     :647-736
│           │   ├─ .ysm 烘焙面：cullable → buildProjMV → 逐面 det<=0 剔除；预览期 previewCullOff
│           │   │   关剔除 + 零厚度薄片只留法线 -Z 面；显式法线优先，缺失才叉积
│           │   ├─ JSON 面 UV：mirror 时 WEST/EAST **换岛**（照官方 bakeFace 的镜像语义）
│           │   └─ 盒式 UV：vanilla 布局
│           └─ locator（*HandLocator）→ 捕获矩阵 → ItemRenderer.renderStatic 挂手持物品
└─ popPose()
```

### 3.2 OpenYSM 一帧的完整链路

```
CustomPlayerRenderer (extends LivingEntityRenderer<Player, PlayerModel>)   GeoReplacedEntityRenderer:41
└─ renderEntityWithTexture:84
    ├─ RenderLivingBridge.firePre(...)（对外 API 前置事件，可取消）
    ├─ t.processAnimation(partialTick) → AnimationEvent + EntityModelData
    │     （内部：AnimationProcessor.tickAnimation → controller runtime → 每骨骼 snapshot）
    ├─ dispatchedMat.set(pose)
    ├─ setupRotations（原版 + 睡床偏移 / 攀爬朝向 / 旋转攻击临时清 deathTime）      :150-175
    ├─ preRenderCallback → translate(0, 0.01, 0)
    ├─ renderWithBone(...)                                        IGeoRenderer:23
    │    ├─ renderEarly：renderCycle == INITIAL 时才 scale(width, height, width)    :42-46
    │    └─ renderLate（空实现，留给子类）
    ├─ [可选] 额外玩家层 render(...)（ExtraPlayerOverlay / 层渲染器列表）
    ├─ renderWithBoneAndRenderType(...)                           :29-37
    │    ├─ VC = bufferSource.getBuffer(renderType)（第二遍：贴图层/半透明层）
    │    ├─ animatable.resetAnimationState()
    │    └─ NativeModelRenderer.renderMesh(VC, pose.last(), GeoModel,
    │           matrixData, absPivotData, texIndex, partMask, light, overlay, rgba, tex)   :36-94
    │         ├─ OculusCompat.updatePBRState()
    │         ├─ GPU：GpuCapability 可用 + BoneSkinShader 编译成功 → GpuRenderPath.tryRender
    │         │      （每模型一个 GpuMesh + 骨骼矩阵走 uniform/SSBO，顶点只上传一次）
    │         ├─ Iris 阴影包在用且非预览 → IrisRenderPath.tryRender
    │         ├─ 原生：NativeLibLoader 已加载 → nativeRenderModel → JNI nComputeModelVertices
    │         └─ CPU：renderModel:96-175
    │               ├─ 每骨骼 calculateBoneMatrix(i) 缓存 Matrix4f[]           :177-249
    │               │   translate(pivotX - animTx, pivotY + animTy, pivotZ + animTz) *1/16
    │               │   rotateZ → rotateY → rotateX → scale(animS)
    │               │   scale 三轴全 0 → 骨架子孙整体不可见                  :213-215
    │               │   unk3==1 时把绝对 pivot 写进 stateBuffer（供 GPU 蒙皮）  :234-242
    │               │   translate(-pivot)，pivot 仅作旋转轴心
    │               └─ 每骨骼：globalMat = rootPose · localMat，projMat 再乘
    │                   cullable 面：投影后 det<=0 剔除                        :156-164
    │                   glow 骨骼：lightmap 换 pack(15,15)                     :152
    │                   共享顶点缓冲（每模型只算一次，多 pass 复用）
    └─ 层渲染：elytra / 背包 / 刀鞘 / TACZ 枪械 / 腰间 / 肩挂（AnimatedGeoModel 骨骼分组）
```

### 3.3 主干"同"在哪、"异"在哪

| 环节 | 做法 | 是否等价 |
|---|---|---|
| 几何烘焙 | 都烘焙成"每骨骼立方体 → 每面 4 顶点 + UV + 法线" | 同 |
| 骨骼矩阵 | 都是 `translate(+pivot) → rotateZ/Y/X → scale → translate(-pivot)` | **数学等价**（`NpcRenderer:583-613` ↔ `NativeModelRenderer:217-244`） |
| 活动轴心 | 都用 pivot 当旋转轴心，顶点是绝对模型坐标 | 同 |
| 逐面剔除 | 都用"投影后三顶点行列式 ≤ 0 剔除"，只对 `cullable` 立方体 | 同（我们多了预览期关闭剔除的补丁） |
| RenderType | 都是 `entityCutoutNoCull` 语义（cutout + 无剔除），半透明另走 translucent | 同 |
| 自发光 | 都不换 RenderType，只把该骨骼 lightmap 提到 15 | 同 |
| 矩阵组织 | 我们是 **PoseStack 递归 push/pop**，每骨骼都走一遍矩阵栈 | **不同**：他们是 `Matrix4f[]` 缓存 + 共享顶点缓冲，一条矩阵算一次多 pass 复用 |
| 每帧顶点生成 | 我们每帧现场 `vc.vertex(mat, x,y,z)` × 面 × 4 | 他们 GPU 只传一次顶点，之后只更新骨骼矩阵（native 时走 SIMD 批量写入） |
| Pass 数 | 1 遍（外加手持物品 `renderStatic`） | 2 遍（body + RenderType 层）+ 任意多 layer（披风/背包/枪） |
| 原版渲染器能力 | dispatcher 被 cancel → **没有** vanilla 的盔甲层 / 受击红闪（`OverlayTexture.NO_OVERLAY`，`quadBaked:900`）/ 隐身判定 / 发光轮廓 / 名牌 | 他们继承 `LivingEntityRenderer` → 这些全部原样保留（`:116` 已传 `packOverlayCoords(entity, hurtProgress)`、`:131` 名牌、`:180` `shouldShowName`） |

> 一句话：**几何与剔除的口径已经对齐，矩阵组织方式、pass 组织方式、原版渲染器能力的继承完全不同。**

---

## 4. 动画与 Molang：最大的实现落差

| 能力 | ModelYSM | OpenYSM |
|---|---|---|
| 采样插值 | 关键帧之间**只有线性** `Mth.lerp`（`BedrockAnimation:79-81`） | `LinearKeyFrame` / `CatmullRomKeyFrame` / `TransitionPoint` / `EasingType` 全套 |
| 骨骼混合 | 层"后应用覆盖前"，无权重 | `BoneTopLevelSnapshot` 快照 + `AnimationPoint` 多动画加权 |
| 过渡 | 状态切换 0.1s：**重放旧动画再 lerp**（`NpcRenderer:222-238`），成本 = 两次全量采样 | `TransitionKeyFrame` + controller 原生 `transitionLengthTicks`，只算一次 |
| 控制器 | `ModelControllers`（316 行）：状态字符串 + 条件表达式 mini 求值器 | `AnimationControllerRuntime/Instance/Composite` + `PredicateBasedController` + 完整 `AnimationControllerFile` |
| 事件关键帧 | 只有 `timeline_events`（Molang 脚本到点执行，`Timelines`) | `SoundKeyFrameExecutor` / `ParticleEventKeyFrame` / `InstructionKeyFrameExecutor` |
| Molang | 子集（`Molang` 527 行：算术、`??`、三元、少数 query/函数、`v.* / v.roaming.*`、弹簧物理） | 完整：`molang/parser`+`lexer`+`runtime` + `core/molang`（math 30+ 函数、query 绑定 40+、entity/item/block 变量体系、作用域绑定、临时变量池、字符串池） |
| 表达式执行时机 | 每帧按骨骼 × 通道 × 关键帧求值 | `preProcess/postProcess` 两趟，指令式表达式单独走 `execute(...)` |
| 音频 | 无 | OGG Vorbis / Opus 解码 + 原生解码器 + 可 seek 播放 |

**可感知后果**：带 `sound` / `particle_effects` 的模型在我们这边**静音且无特效**；大跨度关键帧（慢动作、甩发）因为只有线性插值会比官方"硬"；复杂 Molang（`query.armor_texture_slot`、`math.hermite_blend`、字符串比较、`loop`）会退化成常量或 false。

---

## 5. 功能矩阵

| 功能 | ModelYSM | OpenYSM | 说明 |
|---|---|---|---|
| 文件夹模型（`ysm.json`+`main.animation.json`+贴图） | ✅ | ✅ | 我们 `ModelLibrary.load` / 他们 `YSMFolderDeserializer` |
| `.ysm` 二进制直读 | ✅（`YsmDirectLoader`+`YSMBinaryDeserializer`） | ✅（`YSMBinaryDeserializer`） | 我们只读，不写 |
| `.ysm` 加密/zip 内嵌 | ✅（`YsmCrypt`/`XChaCha20`） | ✅ | 口径同源 |
| 多贴图切换 | ✅（`LoadedModel.textures` + `EntitySettings.textureIndex`） | ✅（`PlayerTextureScreen` 独立界面 + 索引同步） | 他们 UI 更完整 |
| 预览动画选择 | ✅ `preview_animation / gui / idle` | ✅ | |
| 模型搜索 | ✅ 单行 `EditBox` 名称过滤（`ModelPickerScreen:120`） | ✅ `@作者` / `#标签` 前缀 + `SearchSuggestions` 联想 + 分类 ALL/AUTH/STAR + 文件夹层级 + 分页记忆 | 差距明显 |
| 轮盘（表情/形态/设置） | ✅ 6 扇区 + 右侧设置面板 + 身体大小滑条（`ModelWheelScreen`） | ✅ `AnimationRouletteScreen`（651 行） | 都叫"轮盘"，实现完全不同 |
| 部件/形态切换 | ✅ `partBones` + `exclusiveParts` + 强制列表 | ✅ 骨骼名 + `v.Head_Eyes` 一变体选择器体系 | |
| 弹簧物理 | ✅ `Physics`（`ysm.second_order/first_order`） | ✅（原生/完整 Molang 侧） | |
| timeline_events | ✅ | ✅ | |
| 声音关键帧 | ❌ | ✅ | 最直观的功能缺口 |
| 粒子关键帧 | ❌ | ✅ | |
| 第一人称手臂 | ✅ `FpArmHook` + `FirstPersonRendererMixin` | ✅ `FirstPersonArmAnimationController` + `HandItemRenderer` | |
| 弹射物替换 | ✅ `ProjectileRenderHook`（模型自带子模型） | ✅ 独立 `ProjectileRenderer` + 能力 + 同步包 | |
| 载具替换 | ❌ | ✅ `VehicleRenderer` + `VehicleCapability` | |
| 盔甲/手持物品 | ✅ locator + `ItemRenderer.renderStatic`；盔甲仅"自带盔甲骨骼动画" | ✅ 骨骼分组 + 层渲染器（更接近原版） | |
| 披风/背包/刀鞘/TACZ | ❌ | ✅ 骨骼分组 + `SBackpackCompat` | |
| 模型上传到服务器 | ❌ | ✅ 三段式上传 + 审批反馈 | |
| 授权模型 / 星标收藏 | ❌ | ✅ `AuthModelsCapability` / `StarModelsCapability` | |
| 服务端下发模型 | ✅ 分块流式（24KB × 40 块/tick） | ✅ 上传/同步体系内 | |
| NPC 绑定 + 绑定同步 | ✅ `ModelBindingStore` + `NpcTracker` + `SyncNpcModelsS2C` + `/modelysm` | ❌（官方只作用于玩家/弹射物/载具） | **这是我们的独有能力** |
| 网络状态同步粒度 | 只同步"动作/绑定"，动画状态本地按 UUID 独立推进 | 289 行 `S2CSyncPlayerStatePacket` 全量状态 + `C2SSyncAnimationExpressionPacket` | 他们多人一致性强 |
| EMF/ETF/Oculus/OptiFine 兼容 | ❌ | ✅ | 装了光影包时我们没特殊路径 |
| 多平台（Fabric） | ❌ | ✅ | |
| 原生 JNI / GPU 蒙皮 | ❌ | ✅ | 高实体量时帧率差距主要来自这里 |

---

## 6. "一致程度"评估（按视觉/行为可感知度）

| 项目 | 一致度 | 依据 |
|---|---|---|
| 几何形状、UV 岛、mirror 语义 | ≈98% | 面 UV、镜像块换岛、显式法线都已按官方口径复刻 |
| 骨骼 pivot / 静止角 / Z→Y→X 顺序 | ≈97% | 数学等价；我们缺 `unk1/unk2` 类特殊标记语义 |
| 逐面剔除结果 | ≈95% | 同为投影行列式；我们预览期主动关剔除 + 薄片单面处理（保底手段） |
| 材质与光照（cutout + lightmap + glow） | ≈95% | RenderType 参数逐项对齐；缺实体 outline/受击 overlay |
| 姿态随时间变化（曲线形状） | ≈75% | 只有线性，无 CatmullRom / easing / 多动画加权 |
| Molang 驱动效果（形态、颜色、位移） | ≈60% | query/函数覆盖不足，复杂模型会走默认分支 |
| 事件表现（声音/粒子） | ≈0% | 未实现 |
| 多人一致性 | ≈70% | 我们状态本地推进，他们状态同步下发 |
| 兼容性（光影/EMF/背包/枪械） | ≈30% | 仅做了基础 cutout 双面 + 自建视锥 |

---

## 7. 若要继续向官方收敛，建议优先级

**P0（视觉/体验缺口，工作量中等）**
1. 音频：先不做原生解码，直接引 `javax.sound`/`org.lwjgl.stb` 或复制 `audio` 包，接 `SoundKeyFrameExecutor` 的触发点。
2. 关键帧曲线：给 `BedrockAnimation.Curve` 加 `CatmullRom` 分支（官方 `CatmullRomKeyFrame` 可直接照抄公式）。
3. Molang 补全：把 `Molang` 换成 `geo/core/molang` 的 parser+runtime（纯 Java，无原生依赖）。

**P1（性能与正确性）**
4. 骨骼矩阵缓存化：用 `Matrix4f[] + globalMat` 替代 PoseStack 递归（可直接照 `NativeModelRenderer:177-249`），顺带拿到多 pass 复用能力。
5. 每实体渲染器化：把 dispatcher 拦截改成"注册一个 `LivingEntityRenderer` 子类"，换回盔甲层/受击 overlay/隐身/轮廓/名牌。
6. 视锥与阴影：官方从不关剔除，只是给大 AABB；我们的 `frustumDecision` 需与 `setRenderShadow` 一起校准。

**P2（生态）**
7. 上传/授权/星标/搜索分类（`PlayerModelScreen` 那套 UI 可整体照搬结构）。
8. EMF/ETF/Oculus 兼容层、骨骼分组（披风/背包/枪械）。

---

## 8. 附：关键文件索引

**ModelYSM**
- `client/render/NpcRenderer.java:107-375` 渲染主入口；`:572-638` 骨骼递归；`:647-736` 立方体；`:860-872` 逐面剔除；`:876-905` 烘焙面顶点
- `client/render/BedrockAnimation.java:61-87` 线性采样；`:117-124` 循环折算
- `client/render/ModelYsmRenderTypes.java:41-56` cutout/nocull
- `client/anim/ModelControllers.java`（316）控制器；`client/molang/Molang.java`（527）子集；`client/Timelines.java`；`client/molang/Physics.java`
- `client/ModelLibrary.java:255-420` 加载；`:715-795` 几何解析；`:796-836` 控制器；`:837-940` 动画解析
- `mixin/client/EntityRenderDispatcherMixin.java:23-69`；`client/NpcRenderHook.java:19-153`

**OpenYSM**
- `geckolib3/geo/GeoReplacedEntityRenderer.java:41-196` 渲染器基类
- `geckolib3/geo/IGeoRenderer.java:23-79` pass 组织、RenderType、缩放入口
- `geckolib3/geo/NativeModelRenderer.java:36-94` 三后端分派；`:96-175` CPU 绘制；`:177-249` 骨骼矩阵 + stateBuffer
- `geckolib3/geo/animated/AnimatedGeoModel.java:21-188` matrixData 步长 12 / absPivot 步长 4 / 骨骼分组
- `geckolib3/core/processor/AnimationProcessor.java:76-306` 动画推进与表达式 pre/post
- `resource/YSMFolderDeserializer.java`（1138）、`YSMBinaryDeserializer.java`（857）、`YSMClientMapper.java`（996）
- `client/renderer/RendererManager.java:36-70` 渲染器装配；`ModelPreviewRenderer.java:260-359` 卡片/大预览
- `client/gui/PlayerModelScreen.java`（811）搜索分类分页
- `rip/ysm/gpu/GpuRenderPath.java`、`rip/ysm/gpu/BoneSkinShader.java`、`rip/ysm/gpu/IrisRenderPath.java`
