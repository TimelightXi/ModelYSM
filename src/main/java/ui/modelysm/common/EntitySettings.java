package ui.modelysm.common;

import java.util.HashSet;

/**
 * 单个实体的模型设置（服务端存储 + 全量同步到客户端）。
 * 对应 YSM GUI 的：动画调控、模型大小（整体缩放滑条）、骨骼显隐。
 */
public class EntitySettings {
    public String model;                                   // 绑定的模型 id
    public float scale = 1.0f;                             // 整体大小（0.1~3.0，滑条调控）
    public String anim;                                    // 强制播放的动画名（null=状态机自动）
    public HashSet<String> hiddenBones = new HashSet<>();  // 隐藏的骨骼名（子骨骼一并隐藏）
    /** 强制形态：GUI 选择的部件骨骼名（null=自动，交由动画/Molang 控制形态） */
    public String forcedPart;
    /**
     * 模型配置表单的 molang 变量（v.xxx → 值，官方轮盘 ⚙ checkbox/radio/range 写入）。
     * key 为完整变量名（如 "v.roaming.umbrella"），渲染时注入 Molang ctx.queries。
     * 服务端权威存储，随 SyncNpcModelsS2C 全量同步。
     */
    public java.util.HashMap<String, Float> molVars = new java.util.HashMap<>();
    /** 贴图索引（0=默认第一张；对应 LoadedModel.textures，官方多套皮肤切换） */
    public int textureIndex = 0;

    public EntitySettings() {}

    public EntitySettings(String model) {
        this.model = model;
    }

    /** 恢复默认（保留模型绑定） */
    public void reset() {
        scale = 1.0f;
        anim = null;
        hiddenBones.clear();
        forcedPart = null;
        molVars.clear();
        textureIndex = 0;
    }
}
