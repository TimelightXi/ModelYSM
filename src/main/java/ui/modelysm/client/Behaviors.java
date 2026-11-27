package ui.modelysm.client;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import ui.modelysm.client.anim.ModelControllers;
import ui.modelysm.client.render.BedrockAnimation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 官方行为绑定表：官方 YSM 按"玩家行为"驱动 player.main 等层动画
 * （官方源码 AnimationRegister.registerAnimationState 逐项对应），
 * ModelYSM 适配生物 NPC —— 每帧检测 NPC 此刻行为，对照本表一一绑定动画。
 *
 * 玩家独有、NPC 无触发接口的行为不写检测逻辑，但在本表保留条目
 * （菜单可见、标注不可适配原因）；NPC 默认行为 = 官方兜底 "idle"（静态站立）。
 */
public final class Behaviors {
    private Behaviors() {}

    // ================= 官方 main 状态（AnimationRegister 同序同优先级） =================

    /** 单个官方行为条目 */
    public static final class Entry {
        public final String id;          // 官方动画名（death/sleep/...）
        public final String cn;          // 中文名（菜单显示）
        public final int priority;       // 官方优先级（HIGHEST=0）
        public final boolean npcSupport; // NPC 有触发接口（true=可适配）
        public final String note;        // 适配说明 / 不可适配原因
        public Entry(String id, String cn, int priority, boolean npcSupport, String note) {
            this.id = id; this.cn = cn; this.priority = priority;
            this.npcSupport = npcSupport; this.note = note;
        }
    }

    /** 官方行为清单（顺序=官方优先级序，菜单与报告共用） */
    public static final List<Entry> TABLE = List.of(
        new Entry("death",           "死亡",              0, true,  "isDeadOrDying，PLAY_ONCE"),
        new Entry("riptide",         "激流冲锋",          0, false, "接口存在(isAutoSpinAttack)，原版生物无三叉戟触发路径"),
        new Entry("sleep",           "睡觉",              0, true,  "isSleeping（村民等可触发）"),
        new Entry("swim",            "游泳(冲刺)",        0, true,  "isSwimming"),
        new Entry("climb",           "爬行(移动)",        0, true,  "Pose.SWIMMING 且移动（1格高空间爬行）"),
        new Entry("climbing",        "爬行(静止)",        0, true,  "Pose.SWIMMING"),
        new Entry("ladder_up",       "梯子·上",           0, true,  "onClimbable 且垂直速度>0"),
        new Entry("ladder_stillness","梯子·停",           0, true,  "onClimbable 且垂直速度=0"),
        new Entry("ladder_down",     "梯子·下",           0, true,  "onClimbable 且垂直速度<0"),
        new Entry("fly",             "飞行",              1, true,  "玩家=abilities.flying；NPC 近似适配=飞行型生物(FlyingMoveControl)腾空"),
        new Entry("elytra_fly",      "鞘翅滑翔",          1, false, "接口存在(isFallFlying)，NPC 无法装备鞘翅触发"),
        new Entry("swim_stand",      "水中站立",          2, true,  "isInWater 且离地"),
        new Entry("attacked",        "受击",              2, true,  "hurtTime>0，PLAY_ONCE"),
        new Entry("jump",            "跳跃/腾空",         2, true,  "离地且不在水中"),
        new Entry("sneak",           "潜行(移动)",        2, true,  "isCrouching 且移动"),
        new Entry("sneaking",        "潜行(静止)",        2, true,  "isCrouching"),
        new Entry("run",             "奔跑",              3, true,  "官方=isSprinting；NPC 多数生物不置位 → 近似适配=速度阈值迟滞"),
        new Entry("walk",            "行走",              3, true,  "地面水平移动"),
        new Entry("idle",            "静态站立(默认)",    4, true,  "兜底：以上全部不满足时播 idle——NPC 默认行为")
    );

    // ================= main 状态检测（官方优先级序，返回官方动画名） =================

    /** 官方 MIN_SPEED（limbSwingAmount 阈值）；NPC 侧以平滑格/秒近似 */
    private static final float MIN_SPEED = 0.25f;

    /**
     * 检测 NPC 当前 main 行为（官方 19 状态优先级序）。
     * @return 官方动画名（模型是否拥有该动画由调用方 pick 决定）
     */
    public static String detectMain(LivingEntity e, float speedPerSec, boolean runningLatch) {
        java.util.List<String> all = detectAll(e, speedPerSec, runningLatch);
        return all.isEmpty() ? "idle" : all.get(0);
    }

    /**
     * 按官方优先级序返回当前成立的全部行为（首项=官方语义行为；
     * 模型缺该动画时可依次向后回退，如缺 run 回退 walk）。
     */
    public static java.util.List<String> detectAll(LivingEntity e, float speedPerSec, boolean runningLatch) {
        java.util.List<String> out = new ArrayList<>(6);
        if (e.isDeadOrDying()) { out.add("death"); return out; }
        if (e.isAutoSpinAttack()) out.add("riptide");
        if (e.isSleeping()) { out.add("sleep"); return out; }
        if (e.isSwimming()) { out.add("swim"); return out; }
        if (e.getPose() == Pose.SWIMMING) {
            out.add(speedPerSec > MIN_SPEED ? "climb" : "climbing");
            return out;
        }
        if (e.onClimbable()) {
            float vy = verticalSpeed(e);
            out.add(vy > 0.01f ? "ladder_up" : (vy >= -0.01f ? "ladder_stillness" : "ladder_down"));
            return out;
        }
        // fly：玩家=abilities.flying；NPC 启发式=飞行型生物腾空（近似适配，官方无此接口）
        if (e instanceof net.minecraft.world.entity.player.Player p && p.getAbilities().flying) {
            out.add("fly");
            return out;
        }
        boolean flyingMob = isFlyingMob(e);
        if (flyingMob) out.add("fly");
        if (e.isFallFlying()) out.add("elytra_fly");
        boolean inWaterAir = e.isInWater() && !e.onGround();
        if (inWaterAir) out.add("swim_stand");
        if (e.hurtTime > 0) out.add("attacked");
        if (!e.onGround() && !e.isInWater() && !flyingMob) out.add("jump");
        boolean moving = speedPerSec > MIN_SPEED;
        boolean crouch = e.onGround() && e.isCrouching();
        if (crouch && moving) out.add("sneak");
        if (crouch) out.add("sneaking");
        // 官方 run = onGround && isSprinting；NPC 侧加速度迟滞 latch（多数生物不置位 sprinting）
        if (e.onGround() && !inWaterAir && (e.isSprinting() || runningLatch)) out.add("run");
        if (e.onGround() && !inWaterAir && moving) out.add("walk");
        return out;
    }

    /** 飞行型生物判定：原版飞行生物统一用 FlyingMoveControl */
    private static boolean isFlyingMob(LivingEntity e) {
        return e instanceof net.minecraft.world.entity.Mob m && m.getMoveControl() instanceof FlyingMoveControl;
    }

    private static float verticalSpeed(LivingEntity e) {
        return 20f * (float) (e.getY() - e.yo);
    }

    // ================= vehicle 层（官方 VehiclePredicate 同序） =================

    /**
     * 骑乘检测。官方 main 在骑乘时直接 STOP，vehicle 层接管。
     * @return 官方动画名（ride_pig / ride / boat / sit）
     */
    public static String detectVehicle(LivingEntity e) {
        Entity v = e.getVehicle();
        if (v == null || !v.isAlive()) return null;
        if (v instanceof Pig) return "ride_pig";
        if (v instanceof Saddleable) return "ride";
        if (v instanceof Boat) return "boat";
        return "sit";
    }

    // ================= hold 层（官方 ConditionalHold 语义：id → tag → 分类/动作） =================

    /** 模型的 hold 动画名集合（按前缀预分类；per-modelId 缓存） */
    private static final class HoldSet {
        final Map<String, String> byId = new LinkedHashMap<>();      // "minecraft:bow" → 完整动画名
        final List<Map.Entry<String, String>> byTag = new ArrayList<>();
        final Map<String, String> byExtra = new LinkedHashMap<>();   // "sword"/"EAT"/"charged_crossbow" → 动画名
        final boolean hasEmpty;
        HoldSet(Map<String, BedrockAnimation> anims, String prefix) {
            boolean empty = false;
            for (String name : anims.keySet()) {
                if (!name.startsWith(prefix)) continue;
                String rest = name.substring(prefix.length());
                if (rest.startsWith("$")) {
                    byId.put(rest.substring(1), name);
                } else if (rest.startsWith("#")) {
                    byTag.add(Map.entry(rest.substring(1), name));
                } else if (rest.isEmpty()) {
                    // 裸名（如 hold_mainhand）：官方视为无条件持物动画，extra 优先级最低，
                    // 映射为哨兵键 ""（任何持物状态兜底命中）
                    byExtra.putIfAbsent("", name);
                } else {
                    byExtra.put(rest, name);
                    if (rest.equals("empty")) empty = true;
                }
            }
            hasEmpty = empty;
        }
    }

    private static final Map<String, HoldSet> HOLD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static HoldSet holdSet(ModelLibrary.LoadedModel loaded, String prefix) {
        return HOLD_CACHE.computeIfAbsent(loaded.id, k -> new HoldSet(loaded.animations, prefix));
    }

    /**
     * 持物层动画检测（官方 ConditionalHold.doTest 同序：空手→empty；id→tag→分类/UseAnim→裸名兜底）。
     * @return 动画名（模型无匹配则 null）
     */
    public static String detectHold(LivingEntity e, InteractionHand hand, ModelLibrary.LoadedModel loaded) {
        String prefix = hand == InteractionHand.MAIN_HAND ? "hold_mainhand:" : "hold_offhand:";
        ItemStack s = e.getItemInHand(hand);
        HoldSet set = holdSet(loaded, prefix);
        if (s.isEmpty()) return set.hasEmpty ? prefix + "empty" : null;
        // id 精确匹配
        var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem());
        if (key != null) {
            String an = set.byId.get(key.toString());
            if (an != null) return an;
        }
        // tag 匹配
        for (var t : set.byTag) {
            try {
                var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                        new net.minecraft.resources.ResourceLocation(t.getKey()));
                if (s.is(tag)) return t.getValue();
            } catch (Exception ignored) {}
        }
        // 官方特例：装填完毕的弩
        if (s.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(s)) {
            String an = set.byExtra.get("charged_crossbow");
            if (an != null) return an;
        }
        // 内置分类（sword/axe/.../spear/throwable_potion，官方 InnerClassify 同序）
        String cls = ModelControllers.classify(s);
        if (!cls.isEmpty()) {
            String an = set.byExtra.get(cls);
            if (an != null) return an;
        }
        // UseAnim（eat/drink/block/bow/spear/crossbow/spyglass/toot_horn/brush）
        UseAnim ua = s.getUseAnimation();
        if (ua != UseAnim.NONE) {
            String an = set.byExtra.get(ua.name().toLowerCase(Locale.US));
            if (an != null) return an;
        }
        // 裸名兜底（hold_mainhand 无后缀）
        return set.byExtra.get("");
    }

    // ================= use 层（官方 UsePredicate：使用中物品的手播 use_mainhand/offhand） =================

    /** 使用层动画检测（id/tag/分类/UseAnim 细分 → 裸名），LOOP */
    public static String detectUse(LivingEntity e, InteractionHand hand, ModelLibrary.LoadedModel loaded) {
        if (!e.isUsingItem() || e.isSleeping() || e.getUsedItemHand() != hand) return null;
        return detectHoldLike(e, hand, loaded, "use_");
    }

    private static String detectHoldLike(LivingEntity e, InteractionHand hand,
                                         ModelLibrary.LoadedModel loaded, String base) {
        String prefix = base + (hand == InteractionHand.MAIN_HAND ? "mainhand:" : "offhand:");
        ItemStack s = e.getItemInHand(hand);
        HoldSet set = holdSet(loaded, prefix);
        var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem());
        if (key != null) {
            String an = set.byId.get(key.toString());
            if (an != null) return an;
        }
        for (var t : set.byTag) {
            try {
                var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                        new net.minecraft.resources.ResourceLocation(t.getKey()));
                if (s.is(tag)) return t.getValue();
            } catch (Exception ignored) {}
        }
        String cls = ModelControllers.classify(s);
        if (!cls.isEmpty()) {
            String an = set.byExtra.get(cls);
            if (an != null) return an;
        }
        UseAnim ua = s.getUseAnimation();
        if (ua != UseAnim.NONE) {
            String an = set.byExtra.get(ua.name().toLowerCase(Locale.US));
            if (an != null) return an;
        }
        return set.byExtra.get("");
    }

    // ================= swing 层（官方 SwingPredicate：挥手播 swing_hand/offhand，PLAY_ONCE） =================

    /** @return "swing_hand" / "swing_offhand"（模型拥有才生效，由调用方 pick） */
    public static String detectSwing(LivingEntity e, ModelLibrary.LoadedModel loaded) {
        if (!e.swinging || e.isSleeping()) return null;
        return e.swingingArm == InteractionHand.MAIN_HAND ? "swing_hand" : "swing_offhand";
    }

    // ================= passenger 层（官方 ConditionalPassenger：NPC 被骑，id/tag 匹配） =================

    /** 被骑层动画检测（passenger$<实体id> / passenger#<实体tag>）。原版 NPC 极少被骑，模型带动画才生效 */
    public static String detectPassenger(LivingEntity e, ModelLibrary.LoadedModel loaded) {
        Entity p = e.getFirstPassenger();
        if (p == null || !p.isAlive()) return null;
        var pkey = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(p.getType());
        if (pkey != null) {
            String name = "passenger$" + pkey;
            if (loaded.animations.containsKey(name)) return name;
        }
        for (String name : loaded.animations.keySet()) {
            if (!name.startsWith("passenger#")) continue;
            try {
                var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE,
                        new net.minecraft.resources.ResourceLocation(name.substring("passenger#".length())));
                if (p.getType().is(tag)) return name;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** 模型是否含指定动画 */
    public static boolean has(ModelLibrary.LoadedModel loaded, String name) {
        return name != null && loaded.animations.containsKey(name);
    }
}
