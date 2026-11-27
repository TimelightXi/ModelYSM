package ui.modelysm.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ui.modelysm.ModelYSM;
import ui.modelysm.common.ModelBindingStore;
import ui.modelysm.network.Net;
import ui.modelysm.network.packets.SyncNpcModelsS2C;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端 NPC 追踪：
 *  - 识别 Citizens 等 NPC（类名前缀 net.citizensnpcs 的 Player 实体）
 *  - 维护"已安装客户端模组"的玩家列表，绑定变化/实体追踪时推送全量映射
 */
@Mod.EventBusSubscriber(modid = ModelYSM.MODID)
public final class NpcTracker {
    private static final Logger LOGGER = LogManager.getLogger(ModelYSM.MODID);

    /** NPC 实体类名前缀（Citizens）。可按需扩展其它假人插件 */
    private static final String[] NPC_CLASS_PREFIXES = {"net.citizensnpcs"};

    private static final Set<UUID> knownNpcs = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<UUID> moddedClients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<UUID, ui.modelysm.common.EntitySettings> EMPTY = Collections.emptyMap();

    private NpcTracker() {}

    public static boolean isNpc(Entity entity) {
        if (!(entity instanceof Player)) return false;
        String name = entity.getClass().getName();
        for (String p : NPC_CLASS_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    public static void onClientHello(ServerPlayer player) {
        moddedClients.add(player.getUUID());
        sendSyncTo(player);
        LOGGER.debug("[ModelYSM] client hello from {}, pushed {} bindings",
                player.getName().getString(), ModelBindingStore.get().all().size());
    }

    private static void sendSyncTo(ServerPlayer player) {
        Map<UUID, ui.modelysm.common.EntitySettings> snapshot =
                ModelBindingStore.get().all().isEmpty() ? EMPTY : ModelBindingStore.get().all();
        Net.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncNpcModelsS2C(snapshot));
    }

    /** 向所有已装客户端模组的在线玩家推送全量映射 */
    public static void broadcastSync() {
        for (ServerPlayer p : net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers()) {
            if (moddedClients.contains(p.getUUID())) {
                sendSyncTo(p);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (isNpc(event.getEntity())) {
            knownNpcs.add(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        knownNpcs.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity().level().isClientSide()) return;
        Entity target = event.getTarget();
        if (event.getEntity() instanceof ServerPlayer observer && moddedClients.contains(observer.getUUID())) {
            // 任何被绑定的实体（NPC/怪物/动物等）开始被观察时推送映射
            if (ModelBindingStore.get().all().containsKey(target.getUUID())) {
                sendSyncTo(observer);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        moddedClients.remove(event.getEntity().getUUID());
    }

    /** 绑定变化后调用 */
    public static void onBindingsChanged() {
        broadcastSync();
    }

    public static Set<UUID> knownNpcs() {
        return knownNpcs;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // 模型流式分发队列（客户端请求下载 → 分块下发）
        ui.modelysm.server.ModelTransferManager.tick();
    }
}
