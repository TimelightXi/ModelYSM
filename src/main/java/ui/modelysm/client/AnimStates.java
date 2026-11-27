package ui.modelysm.client;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 每实体动画运行状态（客户端） */
public final class AnimStates {
    private AnimStates() {}

    public static final class State {
        public float animTime;        // 当前动画累计秒数
        public String currentAnim;    // 当前动画名（null=未开始）
        public float lastDt = 0.05f;  // 上一帧渲染间隔（秒），弹簧物理/插值用
        public double lastX, lastY, lastZ;
        public boolean hasLastPos;
        public float speedPerSec;     // 平滑后水平速度（格/秒）
        public long lastFrameNanos = System.nanoTime();
        public boolean wasHurt;       // 上一帧 hurtTime>0（检测受击上升沿）
        public float hurtTimer;       // attacked 受击动画剩余时长
        public boolean running;       // 跑步状态迟滞（防阈值抖动）
        public boolean walking;       // 走路状态迟滞
        // ---- 官方 0.1s 过渡插值（main 状态层切换时旧→新姿态线性混合）----
        public String prevAnim;       // 过渡起点动画（null=无过渡）
        public float prevAnimTime;    // 切换瞬间旧动画的时间快照（冻结采样）
        public float transLeft;       // 过渡剩余秒数（<=0 表示无过渡）
        // ---- 官方 animation_controllers 状态机运行时 ----
        public final Map<String, String> ctrlState = new java.util.HashMap<>();   // controller key → 当前状态名
        public final Map<String, Float> ctrlTime = new java.util.HashMap<>();     // controller key → 状态内时间（秒）
        // ---- 官方行为 overlay 层运行时（hold/use/swing/passenger）----
        public float swingLayerTime;  // swing 层累计秒（挥动上升沿清零，PLAY_ONCE）
        public boolean wasSwing;      // 上一帧 swinging（检测上升沿）
        public float useLayerTime;    // use 层累计秒（使用中累计，结束清零，LOOP）
        public float holdLayerTime;   // hold 层累计秒（LOOP）
        public String lastHoldMain;   // 主手 hold 动画名（切换时清零时间，官方换物品 indicateReload）
        public String lastHoldOff;    // 副手 hold 动画名
        public float passengerTime;   // 被骑层累计秒（LOOP）
        // ---- 官方 roaming 变量表（每实体持久；时间轴脚本赋值，动画表达式读取）----
        // GUI 开关在客户端线程写、渲染线程读，用并发表避免结构竞争
        public final Map<String, Double> roaming = new java.util.concurrent.ConcurrentHashMap<>();
        // ---- 官方模型变量表（每实体持久；timeline 脚本 / controller on_entry·on_exit / 动画表达式共享）----
        public final Map<String, Float> vars = new java.util.concurrent.ConcurrentHashMap<>();
        /** 由 GUI 设置面板写入的变量键（es.molVars 投影到 vars 的那部分）。
         *  用来做"重置/取消勾选后清理"：服务端 molVars 里消失的键必须从 vars 删掉，
         *  否则重置设置清空 molVars 后，vars 仍留着旧值 → 模型位置/大小回不到默认（实测复现）。 */
        public final Set<String> userVarKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
        // ---- 时间轴事件游标（main 状态动画的 timeline_events 到点执行一次）----
        public String evAnim;         // 游标所属动画名
        public float evTime;          // 上一帧事件检查时间（秒）
        /** 常驻层（pre_parallel / parallel / idle 基底）各自 timeline 的上次触发时间。
         *  官方每层是独立的 AnimationTracker，timeline 自己跑；YSM 的尾巴/头发物理就写在
         *  parallel3（弹簧常数，0.5s 循环）和 parallel4（每 0.01s 积一次，等于每帧）的 timeline 里，
         *  只给主状态动画触发 timeline 会导致弹簧常数与积分脚本永不执行 → 尾巴一动不动。 */
        public final Map<String, Float> layerEvTime = new java.util.concurrent.ConcurrentHashMap<>();
        public String consumedExtra;  // 已播完的官方"额外动画"（回落状态机用）

        /** 从头重播当前动画（GUI 再次点击同一动画、额外动画二次触发时调用） */
        public void restartAnimation() {
            animTime = 0f;
            currentAnim = null;
            prevAnim = null;
            transLeft = 0f;
            evAnim = null;
            evTime = 0f;
            consumedExtra = null;
        }
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    public static State get(UUID entity) {
        return STATES.computeIfAbsent(entity, k -> new State());
    }

    public static void cleanup(Set<UUID> validUuids) {
        // 只清理"已不在任何绑定中"且长时间没更新的状态；v1 简化为按绑定集合清理
        STATES.keySet().removeIf(k -> !validUuids.contains(k) || validUuids.isEmpty());
        if (validUuids.isEmpty()) STATES.clear();
        else STATES.keySet().retainAll(new HashSet<>(validUuids));
        // 弹簧物理实例同步清理
        ui.modelysm.client.molang.Physics.cleanup(validUuids);
    }
}
