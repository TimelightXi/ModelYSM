package ui.modelysm.client.render;

import ui.modelysm.client.molang.Molang;

/**
 * 官方 timeline_events 执行器（YSM/Bedrock 动画的"到点脚本"）。
 *
 * <p>格式约定：事件挂在某条动画的某个时间点上，播放跨过该时间点时执行一次；
 * 循环动画每个循环都会再执行一次（官方同语义）。典型用途：
 * <ul>
 *   <li>换装/穿脱：extra0 的 {@code v.roaming.a=1-v.roaming.b; v.roaming.b=v.roaming.a;}
 *       → 每次播放翻一次值，稠密依赖 roaming 持久表才有效；</li>
 *   <li>头发/披风弹簧的每帧积分（parallel 系列的 len=0.01 动画）。</li>
 * </ul>
 */
public final class Timelines {
    private Timelines() {}

    /**
     * 执行 (prev, cur] 区间内跨过的全部事件。
     *
     * @param prev 上一帧的播放时间（秒）；<=0 表示从头开始
     * @param cur  当前播放时间（秒）
     */
    public static void fire(BedrockAnimation anim, float prev, float cur, Molang.Ctx ctx) {
        if (anim == null || anim.timelineEvents.isEmpty() || ctx == null) return;
        float len = anim.length > 0 && Float.isFinite(anim.length) ? anim.length : 0f;
        if (len <= 0) len = 1.0f; // 未标时长的动画按 1s 折算，避免除零
        if (!anim.loop && cur > len) cur = len; // 一次性动画只在第一遍触发

        if (!Float.isFinite(cur) || !Float.isFinite(prev)) return;
        if (cur <= prev) return; // 摆锤回绕/时间未推进：无事可做
        if (cur - prev > len) prev = cur - len; // 掉帧/切后台太久：只补最近一个循环

        int c0 = (int) Math.floor(prev / len);
        int c1 = (int) Math.floor(cur / len);
        if (c1 - c0 > 2) c0 = c1 - 2; // 兜底：最多补 3 个循环，防长时间卡顿后一次性跑几千次

        for (int c = c0; c <= c1; c++) {
            float base = c * len;
            for (BedrockAnimation.TimelineEvent ev : anim.timelineEvents) {
                float at = base + ev.t;
                if (at > prev && at <= cur) {
                    for (Molang.Expr e : ev.exprs) {
                        try {
                            e.eval(ctx);
                        } catch (Throwable ignored) {
                            // 单条脚本失败不能中断整帧渲染（官方也是逐条容错）
                        }
                    }
                }
            }
        }
    }
}
