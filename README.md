# ModelYSM

YSM（Yes Steve Model）格式模型的 Minecraft Forge 渲染器：服务端把 YSM 模型绑定到 Citizens 等插件创建的 NPC，客户端用自己的 Bedrock 几何 / 动画渲染器把这些 NPC 画出来。

A YSM (Yes Steve Model) renderer for Minecraft 1.20.1 Forge — bind YSM models to Citizens NPCs on the server, render them client-side with our own Bedrock geometry/animation pipeline and GPU skinning.

---

## 特性

- **YSM 模型直读**：支持 `config/modelysm/models/` 下的标准 Bedrock 工程目录（`main.json` + `main.animation.json` + 贴图），也支持直接丢 `.ysm` 文件，由内置导入器解密/解包成标准目录后加载。
- **Bedrock 渲染管线**：自带几何解析、骨骼层级、镶面/UV 处理，Molang 表达式引擎驱动动画与物理。不依赖 GeckoLib，不依赖官方 YSM 模组。
- **GPU 蒙皮**：世界与 GUI 预览都走 GPU 蒙皮（自建着色器 + VBO），高面数模型不再把主线程压垮；内部带 `[PERF]` / `[FPS]` 采样日志用于定位掉帧。
- **NPC 绑定**：服务端识别 Citizens 等插件创建的玩家类 NPC，按实体 UUID 绑定模型，并同步给装有本模组的客户端。
- **客户端界面**：模型列表、选中预览、V 键绑定轮盘、官方抽卡轮盘复刻等。
- **细节钩子**：第一人称手臂、投掷物（箭/雪球等）也走同一套模型渲染。
- **软依赖**：Citizens 不是硬依赖，没装也能渲染普通实体/玩家。

## 运行环境

| 项 | 值 |
| --- | --- |
| Minecraft | 1.20.1 |
| 加载器 | Forge 47.x（编译基线 47.4.10，客户端 47.4.20 实测可用） |
| Java | 17 |
| 端 | 客户端 + 服务端（服务端负责绑定与同步，客户端负责渲染） |

## 安装

1. 把构建出的 `modelysm-*.jar` 放进 `mods/`。
2. 单人游戏放客户端即可；联机时服务端也要放同一份（否则绑定关系同步不到客户端）。
3. 首次启动会自动在 `config/modelysm/` 释放示例模型（`default`、`alex`、`steve`、`default_boy`、`wine_fox`）。

## 模型目录

```
config/modelysm/models/
├── 我的模型/                 # 文件夹形式：子文件夹名 = 模型 id
│   ├── main.json             # Bedrock 几何
│   ├── main.animation.json   # 动画
│   └── xxx.png               # 贴图（可多张）
└── 某个模型.ysm              # 也可直接丢 .ysm，后台自动导入
```

- 目录形式与 `.ysm` 形式可以混放，同名以文件夹为准。
- `.ysm` 导入时**原文件不会被移动或删除**，解密产物落在 `models/<ysm 文件名>/`；导入工作目录固定在系统临时目录（避开非 ASCII 路径问题）。

## 使用

- **绑定模型**：客户端按 `V` 打开绑定轮盘，准星指向实体后选择模型即可（服务端校验权限并落盘）。
- **查看/刷新**：`/modelysm list` 列出可用模型，`/modelysm info` 看准星实体的绑定，`/modelysm reload` 手改 `bindings.json` 后热重载并广播（需要 OP，权限 2）。
- **绑定表**：服务端 `config/modelysm/bindings.json`，格式为 `uuid → 模型 id + 实体设置`，可直接编辑后用 `/modelysm reload` 生效。

## 架构

```
客户端                                    服务端
├── ModelLibrary        扫描/加载模型      ├── NpcTracker       跟踪 NPC、同步映射
├── BedrockModel        几何 + 骨骼        ├── ModelBindingStore 绑定表持久化
├── BedrockAnimation    动画时间轴         ├── ModelYsmCommand  /modelysm 命令
├── Molang / Physics    表达式 + 物理     └── ModelTransferManager 模型分块下发
├── NpcRenderer         拦截实体渲染
├── WorldGpuRenderer    GPU 蒙皮（世界）
├── PreviewFboRenderer  离屏 FBO 预览
└── GUI（列表/预览/轮盘）
```

网络层只走少量自定义包：客户端握手、模型分块请求/下发、NPC 模型同步、实体动作（绑定/解绑/设置）。

## 性能说明

- 世界 NPC 与 GUI 预览都是 GPU 蒙皮，单次绘制约 0.1ms 量级；满场景 58–60fps（Oculus + 光影环境下实测）。
- 日志里的 `[PERF]`（5 秒一次）与 `[FPS]`（500ms 采样）可用于定位是哪一环节掉帧，`▼掉帧` 标记对应换模型瞬间。
- 高面数模型（数万面 / 数百骨骼）也不建议一个场景里堆十几个，蒙皮开销仍随实例数线性增长。

## 构建

```bash
# 需要 JDK 17
./gradlew build
# Windows
gradlew.bat build
```

产物：`build/libs/modelysm-<version>.jar`。开发调试可用 `./gradlew runClient` / `runServer`。

## 目录结构

```
src/main/java/ui/modelysm/
├── ModelYSM.java              模组入口
├── client/                    渲染、模型加载、Molang、GUI、钩子
│   ├── render/                Bedrock 几何/动画、GPU 蒙皮、离屏预览
│   ├── molang/                Molang 词法/语法/求值 + 物理
│   ├── ysm/                   YSM 二进制解析（zstd / 加密）
│   └── gui/                   模型列表、选择器、轮盘
├── common/                    绑定存储、模型目录扫描、.ysm 导入
├── network/                   自定义包与处理器
├── server/                    NPC 跟踪、绑定表、命令
└── mixin/                     渲染分发与第一人称手臂钩子
docs/                          设计笔记（不参与编译）
tools/                         调试脚本（导出/扫描辅助）
```

## 已知限制

- 只做了 1.20.1 + Forge；其他版本未适配。
- 模型格式跟随 YSM 生态，个别模型的特殊材质类型（如部分发光/描边写法）可能表现不同。
- 服务端只负责绑定关系，不做服务端渲染；纯服务端环境下不产生视觉效果。

## 致谢与声明

- YSM（Yes Steve Model）模型格式与示例模型版权归其原作者，本仓库仅提供渲染/绑定实现，不分发他人模型资源。
- `docs/ModelYSM-vs-OpenYSM-架构与渲染管线对比.md` 为架构设计笔记，不参与编译、不进入构建产物。
- `.ysm` 导入参考了 OpenYSM 的 YSMParser 实现思路。

## 许可证

MIT License，见根目录 `LICENSE`。可自由使用、修改、分发与商用，保留版权声明即可。
