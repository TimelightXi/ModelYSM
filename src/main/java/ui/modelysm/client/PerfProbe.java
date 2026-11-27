package ui.modelysm.client;

import net.minecraft.client.Minecraft;
import ui.modelysm.ModelYSM;

/**
 * 帧率探针：把「哪个模型 / 哪个界面 / 哪个时刻」和 fps 波动一起写进日志，前缀统一 {@code [FPS]}。
 *
 * <p>用法：渲染路径每画一只模型都会调 {@link #tick()}（内部 500ms 节流），
 * 所以只要屏幕上有我们的模型在画，就有采样；换模型时调 {@link #event(String)} 打一个事件标记，
 * 之后的采样会把「切换前后的 fps」和「掉帧幅度」写出来，方便直接把帧率波动和具体模型对上。
 */
public final class PerfProbe {
    private PerfProbe() {}

    private static long lastSample, lastHeartbeat, eventAt;
    private static int lastFps = -1, minFps = Integer.MAX_VALUE, maxFps = -1, baselineFps = -1;
    private static String pendingEvent;

    /** 打一个事件（换模型、换界面等），后续采样会标注"事件后 N ms 的 fps" */
    public static void event(String what) {
        Minecraft mc = Minecraft.getInstance();
        int fps = mc == null ? -1 : mc.getFps();
        pendingEvent = what;
        eventAt = System.currentTimeMillis();
        baselineFps = fps;
        ModelYSM.LOGGER.info("[FPS] 事件 {}（切换前 fps={}）", what, fps);
    }

    /** 采样入口（渲染路径每画一只模型调一次，内部按 500ms 节流） */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        long now = System.currentTimeMillis();
        int fps = mc.getFps();
        if (fps >= 0) {
            if (fps < minFps) minFps = fps;
            if (fps > maxFps) maxFps = fps;
        }
        if (lastSample == 0L) {
            lastSample = now;
            lastHeartbeat = now;
            return;
        }
        if (now - lastSample < 500L) return;
        lastSample = now;

        boolean changed = lastFps >= 0 && Math.abs(fps - lastFps) >= 3;
        boolean heartbeat = now - lastHeartbeat >= 5000L;
        boolean justSwitched = pendingEvent != null && now - eventAt <= 1500L;
        if (!changed && !heartbeat && !justSwitched) return;

        String scene = mc.screen == null ? "世界" : ("界面/" + mc.screen.getClass().getSimpleName());
        int[] c = ui.modelysm.client.render.NpcRenderer.perfCounters();
        ModelYSM.LOGGER.info("[FPS] fps={} 区间={}~{} {} 世界GPU={}/CPU={} 预览GPU={}/CPU={}{}",
                fps,
                minFps == Integer.MAX_VALUE ? fps : minFps,
                Math.max(maxFps, 0),
                scene, c[0], c[1], c[2], c[3],
                pendingEvent == null ? "" : (" 事件=" + pendingEvent));

        lastFps = fps;
        minFps = Integer.MAX_VALUE;
        maxFps = -1;
        if (heartbeat) lastHeartbeat = now;

        if (pendingEvent != null) {
            if (baselineFps >= 0 && fps <= baselineFps - 8) {
                ModelYSM.LOGGER.info("[FPS] ▼掉帧 {}(fps) → {}(fps)，事件后 {}ms，事件={}",
                        baselineFps, fps, now - eventAt, pendingEvent);
                pendingEvent = null;
            } else if (now - eventAt > 3000L) {
                pendingEvent = null;
            }
        }
    }
}
