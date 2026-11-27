package ui.modelysm.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import ui.modelysm.ModelYSM;
import ui.modelysm.common.ModelBindingStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 绑定/解绑已全部移到客户端 V 键轮盘（绑定模型 / 解除绑定 两个扇区，走 EntityActionC2S），
 * 这里只保留调试用命令：
 * /modelysm list    - 列出可用模型（config/modelysm/models/ 下）
 * /modelysm info    - 查看准星指向实体的绑定
 * /modelysm reload  - 从磁盘重读 bindings.json 并广播同步（外部手改后免重启）
 */
public final class ModelYsmCommand {
    private ModelYsmCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("modelysm")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            ui.modelysm.common.ModelBindingStore.get().reload();
                            NpcTracker.broadcastSync();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§a已重读 bindings.json，共 " + ui.modelysm.common.ModelBindingStore.get().all().size()
                                            + " 条绑定"), false);
                            return 1;
                        }))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            List<String> models = new ArrayList<>(ui.modelysm.common.ModelFolderScanner.listModels());
                            if (models.isEmpty()) {
                                ctx.getSource().sendSuccess(() -> Component.literal("§e未找到任何模型，请将模型文件夹放入 config/modelysm/models/"), false);
                            } else {
                                ctx.getSource().sendSuccess(() -> Component.literal("§b可用模型(" + models.size() + "): §f" + String.join("§7, §f", models)), false);
                            }
                            return 1;
                        }))
                .then(Commands.literal("info")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            Entity npc = raycastTarget(p);
                            if (npc == null) {
                                p.sendSystemMessage(Component.literal("§c准星范围内(8格)没有可绑定的实体"));
                                return 0;
                            }
                            String m = ModelBindingStore.get().get(npc.getUUID());
                            p.sendSystemMessage(Component.literal("§bNPC " + npcName(npc) + " §7-> " + (m == null ? "§8未绑定" : "§e" + m)));
                            return 1;
                        })));
    }

    private static String npcName(Entity npc) {
        return npc.hasCustomName() ? npc.getCustomName().getString() : npc.getName().getString();
    }

    /** 准星射线找目标（8格内任意活体实体，不含自己） */
    private static Entity raycastTarget(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(8.0));
        AABB searchBox = player.getBoundingBox().expandTowards(player.getLookAngle().scale(8.0)).inflate(2.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, searchBox,
                e -> e instanceof net.minecraft.world.entity.LivingEntity && e != player && e.isPickable(), 64.0);
        return hit == null ? null : hit.getEntity();
    }
}
