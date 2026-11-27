package ui.modelysm.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ui.modelysm.ModelYSM;
import ui.modelysm.common.ModelFolderScanner;
import ui.modelysm.network.Net;
import ui.modelysm.network.packets.ModelChunkS2C;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端模型流式分发（官方 YSM 模型分块下发同思路）：
 *  - 客户端缺模型 → ModelRequestC2S → 入队
 *  - ServerTick 每玩家每 tick 限量发包（24KB/块 × 40 块/tick ≈ 19MB/s 上限，受网络带宽实际约束）
 *  - 仅支持 .ysm 二进制文件（文件夹模型需玩家手动安装）
 */
public final class ModelTransferManager {
    private static final Logger LOGGER = LogManager.getLogger(ModelYSM.MODID);
    private static final int CHUNK_SIZE = 24 * 1024;
    private static final int CHUNKS_PER_TICK = 40;

    private record Job(String modelId, byte[] data, int next) {}

    /** 每玩家的待发送队列 */
    private static final Map<UUID, Deque<Job>> QUEUES = new ConcurrentHashMap<>();
    /** 同玩家同模型在传/失败冷却（避免重复入队与请求风暴） */
    private static final Map<String, Long> COOLDOWN = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 10_000;

    private ModelTransferManager() {}

    public static void request(ServerPlayer player, String modelId) {
        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();
        Long last = COOLDOWN.get(uuid + "/" + modelId);
        if (last != null && now - last < COOLDOWN_MS) return;

        Path file = ModelFolderScanner.modelsRoot().resolve(modelId + ".ysm");
        if (!Files.isRegularFile(file)) {
            COOLDOWN.put(uuid + "/" + modelId, now);
            // 明确应答"不存在"（total=0），客户端标记不可用
            Net.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new ModelChunkS2C(modelId, 0, 0, new byte[0]));
            return;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            LOGGER.warn("[ModelYSM] read model file failed {}: {}", modelId, e.getMessage());
            COOLDOWN.put(uuid + "/" + modelId, now);
            Net.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new ModelChunkS2C(modelId, 0, 0, new byte[0]));
            return;
        }
        int total = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        LOGGER.info("[ModelYSM] start model transfer '{}' → {} ({} KB, {} chunks)",
                modelId, player.getName().getString(), data.length / 1024, total);
        COOLDOWN.put(uuid + "/" + modelId, now + 1); // 传输期间标记（完成由入队量控制）
        Deque<Job> q = QUEUES.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        // 已有同模型在传 → 跳过
        for (Job j : q) {
            if (j.modelId().equals(modelId)) return;
        }
        q.add(new Job(modelId, data, 0));
    }

    /** ServerTick 驱动（NpcTracker.onServerTick 调用） */
    public static void tick() {
        for (Map.Entry<UUID, Deque<Job>> e : QUEUES.entrySet()) {
            Deque<Job> q = e.getValue();
            if (q.isEmpty()) {
                QUEUES.remove(e.getKey());
                continue;
            }
            ServerPlayer player = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()
                    .getPlayerList().getPlayer(e.getKey());
            if (player == null) { // 掉线：丢弃
                q.clear();
                continue;
            }
            int budget = CHUNKS_PER_TICK;
            while (budget-- > 0 && !q.isEmpty()) {
                Job head = q.peek();
                int from = head.next();
                int end = Math.min(head.data().length, from + CHUNK_SIZE);
                byte[] chunk = new byte[end - from];
                System.arraycopy(head.data(), from, chunk, 0, chunk.length);
                Net.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new ModelChunkS2C(head.modelId(), from / CHUNK_SIZE, (head.data().length + CHUNK_SIZE - 1) / CHUNK_SIZE, chunk));
                if (end >= head.data().length) {
                    q.poll();
                    LOGGER.info("[ModelYSM] model transfer '{}' → {} done",
                            head.modelId(), player.getName().getString());
                } else {
                    q.poll();
                    q.addFirst(new Job(head.modelId(), head.data(), end));
                }
            }
        }
    }
}
