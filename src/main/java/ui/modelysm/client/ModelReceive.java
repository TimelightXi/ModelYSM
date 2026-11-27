package ui.modelysm.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ui.modelysm.ModelYSM;
import ui.modelysm.common.ModelFolderScanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端接收服务端下发的模型分块（ModelChunkS2C）：
 *  - 内存重组 → 全量落盘 config/modelysm/models/<id>.ysm
 *  - 校验长度 → ModelLibrary 缓存失效（下次渲染自动加载新模型）
 *  - 请求去重/失败冷却
 */
public final class ModelReceive {
    private static final Logger LOGGER = LogManager.getLogger(ModelYSM.MODID);

    private static class Transfer {
        final int total;
        final byte[] data;
        int received = 0;

        Transfer(int total, byte[] data) { this.total = total; this.data = data; }
    }

    private static final Map<UUID, Transfer> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<String, Long> UNAVAILABLE = new ConcurrentHashMap<>();
    private static final Map<String, Long> REQUESTED = new ConcurrentHashMap<>();
    private static final long RETRY_MS = 15_000;

    private ModelReceive() {}

    public static void receiveChunk(String modelId, int seq, int total, byte[] data) {
        if (total <= 0) {
            // 服务端应答：模型不存在
            UNAVAILABLE.put(modelId, System.currentTimeMillis());
            REQUESTED.remove(modelId);
            LOGGER.info("[ModelYSM] server reports model '{}' unavailable for download", modelId);
            return;
        }
        UUID xferId = new UUID(0, modelId.hashCode() * 31L + total);
        Transfer t = ACTIVE.get(xferId);
        if (t == null) {
            t = new Transfer(total, new byte[total * 24 * 1024]);
            ACTIVE.put(xferId, t);
        }
        int off = seq * 24 * 1024;
        if (off + data.length > t.data.length) {
            LOGGER.warn("[ModelYSM] chunk overflow for {} (seq={}, len={})", modelId, seq, data.length);
            return;
        }
        System.arraycopy(data, 0, t.data, off, data.length);
        t.received += data.length;
        if (seq == total - 1 || t.received >= t.data.length) {
            finish(modelId, t);
            ACTIVE.remove(xferId);
        }
    }

    private static void finish(String modelId, Transfer t) {
        try {
            Path dir = ModelFolderScanner.modelsRoot();
            Files.createDirectories(dir);
            Path target = dir.resolve(modelId + ".ysm");
            // 原子写：临时文件再替换（截齐实际接收长度：末块可能不足整块）
            Path tmp = dir.resolve(modelId + ".ysm.downloading");
            byte[] exact = t.received == t.data.length ? t.data : java.util.Arrays.copyOf(t.data, t.received);
            Files.write(tmp, exact);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            ModelLibrary.invalidate(modelId);
            REQUESTED.remove(modelId);
            LOGGER.info("[ModelYSM] model '{}' downloaded ({} KB) → {}",
                    modelId, t.received / 1024, target);
        } catch (IOException e) {
            LOGGER.warn("[ModelYSM] save downloaded model failed {}: {}", modelId, e.getMessage());
            REQUESTED.remove(modelId);
        }
        ACTIVE.clear();
    }

    /** 收到全量同步后调用：检查缺失模型并请求下载（去重+冷却） */
    public static void requestMissing(java.util.Collection<String> boundModels) {
        long now = System.currentTimeMillis();
        for (String id : boundModels) {
            if (id == null || id.isBlank()) continue;
            if (ModelLibrary.get(id) != null) continue; // 已加载
            Long un = UNAVAILABLE.get(id);
            if (un != null && now - un < RETRY_MS) continue;   // 服务端明确没有
            Long rq = REQUESTED.get(id);
            if (rq != null && now - rq < RETRY_MS) continue;   // 已在请求
            REQUESTED.put(id, now);
            ui.modelysm.network.Net.CHANNEL.sendToServer(new ui.modelysm.network.packets.ModelRequestC2S(id));
            LOGGER.info("[ModelYSM] requesting model '{}' from server", id);
        }
    }
}
