package ui.modelysm.client.anim;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import ui.modelysm.client.render.BedrockAnimation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 官方 animation_controllers 状态机（条件动画层）：
 * 数据（Ctrl/CState/Trans）+ 条件表达式求值器 + 实体控制态分类。
 *
 * 官方对照：ControllerPredicateBuiltins（ctrl.* 谓词）+ InnerClassify（手持物分类）
 * + classify()（实体运动状态选择）。表达式支持 {} 分组、&& || !、比较、
 * ctrl.hold/use/swing('hand',':分类'|'$id'|'#tag')、q.all_animations_finished 等。
 *
 * 已知取舍：onEntry/onExit 脚本（v.bow_charge=1 之类）未执行；
 * blend_transition 曲线未实现（状态切换为硬切）；兼容模组查询（parcool/slashblade/
 * carryon/tac_hold_gun）恒为空值。
 */
public final class ModelControllers {
    private ModelControllers() {}

    // ================= 数据 =================

    public static final class Ctrl {
        public String key;                 // "player.post_main" 等
        public String initial = "default";
        public final Map<String, CState> states = new java.util.LinkedHashMap<>();
    }

    public static final class CState {
        public String name;
        public final List<String> animations = new ArrayList<>(); // 动画名
        public final List<Trans> transitions = new ArrayList<>(); // 有序：先命中先走
        /** 官方 on_entry：进入状态时执行一次的 Molang 脚本（可赋值 v.*） */
        public final List<String> onEntry = new ArrayList<>();
        /** 官方 on_exit：离开状态时执行一次的 Molang 脚本（可赋值 v.*） */
        public final List<String> onExit = new ArrayList<>();
    }

    public static final class Trans {
        public String target;
        public String expr;
    }

    /** 渲染用：某 controller 当前激活的状态 + 状态内时间 */
    public static final class ActiveLayer {
        public final String key;
        public final CState state;
        public final float stateTime;
        public ActiveLayer(String key, CState state, float stateTime) {
            this.key = key;
            this.state = state;
            this.stateTime = stateTime;
        }
    }

    // ================= 求值上下文 =================

    /** 一次 update 帧的实体快照（NpcRenderHook 填充） */
    public static final class EvalCtx {
        public LivingEntity entity;
        public float speedPerSec;                 // 平滑水平速度
        public float verticalSpeedPerSec;         // 垂直速度（格/秒，正=上升）
        public boolean running;                   // 迟滞跑步标志
        public Map<String, Float> molVars;        // 用户变量（缺 v.* 视为 0）
        public List<BedrockAnimation> stateAnims; // 当前 controller 状态的动画
        public float stateTime;                   // 状态内时间（秒）
        public boolean allFinished;
        public boolean anyFinished;
        public String selectedControl;            // classify() 结果
    }

    // ================= 实体控制态分类（官方 classify 同序） =================

    public static String selectedControl(LivingEntity e, float speedPerSec) {
        // 官方 classify = AnimationRegister 同一行为表（Behaviors 已逐项对齐）
        return ui.modelysm.client.Behaviors.detectMain(e, speedPerSec, false);
    }

    // ================= 手持物分类（官方 InnerClassify 同序） =================

    /** filter 语义：':xxx'=分类匹配（sword/bow/shield/...）；'$ns:id'=注册名；'#ns:tag'=物品标签 */
    public static boolean holdMatch(LivingEntity e, InteractionHand hand, String filter) {
        ItemStack s = e.getItemInHand(hand);
        if (s.isEmpty()) return false;
        if (filter == null || filter.isEmpty()) return false;
        if (filter.charAt(0) == '$') {
            String id = filter.substring(1);
            Item item = s.getItem();
            return net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item) != null
                    && net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item).toString().equals(id);
        }
        if (filter.charAt(0) == '#') {
            try {
                var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                        new net.minecraft.resources.ResourceLocation(filter.substring(1)));
                return s.is(tag);
            } catch (Exception ex) {
                return false;
            }
        }
        String f = filter.charAt(0) == ':' ? filter.substring(1) : filter;
        return classify(s).equals(f);
    }

    /** 官方 InnerClassify.getClassify 同序 */
    public static String classify(ItemStack s) {
        Item i = s.getItem();
        if (i instanceof SwordItem) return "sword";
        if (i instanceof AxeItem) return "axe";
        if (i instanceof PickaxeItem) return "pickaxe";
        if (i instanceof ShovelItem) return "shovel";
        if (i instanceof HoeItem) return "hoe";
        if (i instanceof ShieldItem) return "shield";
        if (i instanceof CrossbowItem) return "crossbow";
        if (i instanceof BowItem) return "bow";
        if (i instanceof FishingRodItem) return "fishing_rod";
        if (i instanceof TridentItem) return "spear";
        if (i instanceof ThrowablePotionItem) return "throwable_potion";
        return "";
    }

    // ================= 条件表达式求值器 =================

    // 值（数值或字符串）
    private record Val(double num, String str, boolean isStr) {
        static Val of(double d) { return new Val(d, "", false); }
        static Val of(String s) { return new Val(0, s, true); }
        boolean bool() { return isStr ? !"false".equals(str) && !str.isEmpty() : num != 0; }
        double numOrZero() { return isStr ? 0 : num; }
    }

    private static final java.util.Set<String> ALWAYS_EMPTY_STR = java.util.Set.of(
            "ctrl.parcool_state", "ctrl.slashblade_animation", "ctrl.carryon_is_princess");
    private static final java.util.Set<String> ALWAYS_FALSE = java.util.Set.of(
            "ctrl.tac_hold_gun", "ysm.offhand_charged_crossbow");

    /** 求值入口。语法错误/未知函数一律返回 false（保守：停在当前状态） */
    public static boolean eval(String expr, EvalCtx c) {
        if (expr == null || expr.isBlank()) return false;
        try {
            Parser p = new Parser(expr, c);
            boolean v = p.parseOr();
            return v;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 官方 on_entry / on_exit 脚本执行器（多语句，用 ';' 分隔）。
     * <p>支持 {@code v.x = 表达式;} / {@code variable.x = 表达式;}，写入持久变量表；
     * 其余语句按条件表达式求值（只判真假，无副作用）。逐条容错，单条失败不影响其它语句。
     */
    public static void execScript(String script, EvalCtx c) {
        if (script == null || script.isBlank() || c == null) return;
        for (String raw : script.split(";")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            int eq = -1;
            for (int k = 0; k < s.length(); k++) {
                if (s.charAt(k) != '=') continue;
                char prev = k > 0 ? s.charAt(k - 1) : '\0';
                char next = k + 1 < s.length() ? s.charAt(k + 1) : '\0';
                if (prev == '=' || prev == '!' || prev == '>' || prev == '<' || next == '=') continue;
                eq = k;
                break;
            }
            if (eq < 0) { eval(s, c); continue; }
            String lhs = s.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
            if (!lhs.startsWith("v.") && !lhs.startsWith("variable.")) continue;
            String key = lhs.startsWith("variable.") ? "v." + lhs.substring("variable.".length()) : lhs;
            try {
                double val = new Parser(s.substring(eq + 1).trim(), c).parseArith().num;
                if (c.molVars != null) c.molVars.put(key, (float) val);
            } catch (Exception ignored) {
                // 单条脚本失败不能中断状态机
            }
        }
    }

    private static final class Parser {
        final String s;
        final EvalCtx c;
        int i = 0;

        Parser(String s, EvalCtx c) { this.s = s; this.c = c; }

        // or := and ('||' and)*
        boolean parseOr() {
            boolean v = parseAnd();
            skipWs();
            while (peek2("||")) { i += 2; v = parseAnd() || v; skipWs(); }
            return v;
        }

        boolean parseAnd() {
            boolean v = parseUnary();
            skipWs();
            while (peek2("&&")) { i += 2; v = parseUnary() && v; skipWs(); }
            return v;
        }

        boolean parseUnary() {
            skipWs();
            if (peek("!") && !peek2("!=")) { i++; return !parseUnary(); }
            Val v = parseCmp();
            return v.bool();
        }

        // 算术（官方脚本/表达式里的 + - * / 与一元负号）
        Val parseArith() {
            Val a = parseTerm();
            skipWs();
            while (true) {
                if (peek("+")) { i++; a = Val.of(a.numOrZero() + parseTerm().numOrZero()); }
                else if (peek("-")) { i++; a = Val.of(a.numOrZero() - parseTerm().numOrZero()); }
                else break;
                skipWs();
            }
            return a;
        }

        Val parseTerm() {
            Val a = parseUnaryNum();
            skipWs();
            while (true) {
                if (peek("*")) { i++; a = Val.of(a.numOrZero() * parseUnaryNum().numOrZero()); }
                else if (peek("/")) { i++; Val b = parseUnaryNum(); double d = b.numOrZero(); a = Val.of(d == 0 ? 0 : a.numOrZero() / d); }
                else break;
                skipWs();
            }
            return a;
        }

        Val parseUnaryNum() {
            skipWs();
            if (peek("-") && i + 1 < s.length() && !Character.isDigit(s.charAt(i + 1))) { i++; return Val.of(-parseUnaryNum().numOrZero()); }
            if (peek("+") && i + 1 < s.length() && !Character.isDigit(s.charAt(i + 1))) { i++; return parseUnaryNum(); }
            return parsePrimary();
        }

        // 比较（同返回 Val 以支持嵌套字符串比较链）
        Val parseCmp() {
            Val a = parseArith();
            skipWs();
            String op = null;
            for (String o : new String[]{"==", "!=", ">=", "<=", ">", "<"}) {
                if (peek(o)) { op = o; i += o.length(); break; }
            }
            if (op == null) return a;
            Val b = parsePrimary();
            if (a.isStr || b.isStr) {
                String x = a.isStr ? a.str : fmt(a.numOrZero());
                String y = b.isStr ? b.str : fmt(b.numOrZero());
                return Val.of(switch (op) {
                    case "==" -> x.equals(y);
                    case "!=" -> !x.equals(y);
                    case ">" -> x.compareTo(y) > 0;
                    case "<" -> x.compareTo(y) < 0;
                    case ">=" -> x.compareTo(y) >= 0;
                    default -> x.compareTo(y) <= 0;
                } ? 1 : 0);
            }
            double x = a.num, y = b.num;
            return Val.of(switch (op) {
                case "==" -> x == y;
                case "!=" -> x != y;
                case ">" -> x > y;
                case "<" -> x < y;
                case ">=" -> x >= y;
                default -> x <= y;
            } ? 1 : 0);
        }

        private String fmt(double d) {
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }

        Val parsePrimary() {
            skipWs();
            char ch = i < s.length() ? s.charAt(i) : '\0';
            if (ch == '(' || ch == '{') { i++; Val v = parseGroupVal(); skipWs(); if (i < s.length() && (s.charAt(i) == ')' || s.charAt(i) == '}')) i++; return v; }
            if (ch == '\'' || ch == '"') { return Val.of(readString()); }
            if (ch == '-' || ch == '+' || Character.isDigit(ch)) { return Val.of(readNumber()); }
            if (Character.isLetter(ch) || ch == '_') {
                String name = readIdent();
                skipWs();
                if (i < s.length() && s.charAt(i) == '(') {
                    i++;
                    List<String> args = new ArrayList<>();
                    skipWs();
                    if (i < s.length() && s.charAt(i) != ')') {
                        args.add(readArgRaw());
                        skipWs();
                        while (i < s.length() && s.charAt(i) == ',') { i++; args.add(readArgRaw()); skipWs(); }
                    }
                    if (i < s.length() && s.charAt(i) == ')') i++;
                    return callFunc(name, args);
                }
                return identValue(name);
            }
            return Val.of(0);
        }

        Val parseGroupVal() {
            // 组内是完整布尔表达式，但按 Val 返回（0/1）
            return Val.of(parseOr() ? 1 : 0);
        }

        Val callFunc(String name, List<String> args) {
            switch (name) {
                case "ctrl.idle" -> { return Val.of("idle".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.walk" -> { return Val.of("walk".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.run" -> { return Val.of("run".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.jump" -> { return Val.of("jump".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.fly" -> { return Val.of("fly".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.elytra_fly" -> { return Val.of("elytra_fly".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.sneak" -> { return Val.of("sneak".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.sneaking" -> { return Val.of("sneaking".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.swim" -> { return Val.of("swim".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.swim_stand" -> { return Val.of("swim_stand".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.sleep" -> { return Val.of("sleep".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.attacked" -> { return Val.of("attacked".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.death" -> { return Val.of("death".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.ladder_up" -> { return Val.of("ladder_up".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.ladder_down" -> { return Val.of("ladder_down".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.ladder_stillness" -> { return Val.of("ladder_stillness".equals(c.selectedControl) ? 1 : 0); }
                case "ctrl.hold", "ctrl.use", "ctrl.swing" -> {
                    if (c.entity == null || args.size() < 2) return Val.of(0);
                    InteractionHand hand = "offhand".equals(unquote(args.get(0))) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                    String filter = unquote(args.get(1));
                    boolean match = holdMatch(c.entity, hand, filter);
                    if ("ctrl.hold".equals(name)) return Val.of(match ? 1 : 0);
                    if ("ctrl.use".equals(name)) {
                        return Val.of(match && c.entity.isUsingItem()
                                && c.entity.getUsedItemHand() == hand ? 1 : 0);
                    }
                    // ctrl.swing：挥手且手持有匹配物
                    return Val.of(match && c.entity.swinging ? 1 : 0);
                }
                case "q.all_animations_finished" -> { return Val.of(c.allFinished ? 1 : 0); }
                case "q.any_animation_finished" -> { return Val.of(c.anyFinished ? 1 : 0); }
                // 官方 math 内建（脚本里用来随机挑 idle 变体：math.random_integer(0, 4)）
                case "math.random_integer", "math.random_integer_l" -> {
                    int a = (int) Math.round(numArg(args, 0));
                    int b = (int) Math.round(numArg(args, 1));
                    if (a > b) { int t = a; a = b; b = t; }
                    return Val.of(a + java.util.concurrent.ThreadLocalRandom.current().nextInt(b - a + 1));
                }
                case "math.random" -> { return Val.of(java.util.concurrent.ThreadLocalRandom.current().nextDouble()); }
                case "math.round" -> { return Val.of(Math.round(numArg(args, 0))); }
                case "math.floor" -> { return Val.of(Math.floor(numArg(args, 0))); }
                case "math.ceil" -> { return Val.of(Math.ceil(numArg(args, 0))); }
                case "math.abs" -> { return Val.of(Math.abs(numArg(args, 0))); }
                case "math.min" -> { return Val.of(Math.min(numArg(args, 0), numArg(args, 1))); }
                case "math.max" -> { return Val.of(Math.max(numArg(args, 0), numArg(args, 1))); }
                case "math.mod" -> { double d = numArg(args, 1); return Val.of(d == 0 ? 0 : numArg(args, 0) % d); }
                default -> {
                    if (ALWAYS_FALSE.contains(name)) return Val.of(0);
                    if (ALWAYS_EMPTY_STR.contains(name)) return Val.of("");
                    return Val.of(0);
                }
            }
        }

        Val identValue(String name) {
            // 官方把 ctrl.idle / ctrl.playing_extra_animation 注册成"无参函数"，
            // Molang 允许不带括号调用（控制器 JSON 里就写 ctrl.idle），这里等价分派
            if (name.startsWith("ctrl.") || name.equals("q.all_animations_finished") || name.equals("q.any_animation_finished")) {
                return callFunc(name, java.util.Collections.emptyList());
            }
            switch (name) {
                case "q.is_on_ground" -> { return Val.of(c.entity != null && c.entity.onGround() ? 1 : 0); }
                case "q.is_in_water" -> { return Val.of(c.entity != null && c.entity.isInWater() ? 1 : 0); }
                case "q.ground_speed", "query.ground_speed" -> { return Val.of(c.speedPerSec); }
                case "q.vertical_speed", "query.vertical_speed" -> { return Val.of(c.verticalSpeedPerSec); }
                case "ysm.input_vertical", "ysm.input_horizontal" -> { return Val.of(0); }
                case "true" -> { return Val.of(1); }
                case "false" -> { return Val.of(0); }
                default -> {
                    // v.xxx / variable.xxx：用户变量，缺省 0（大小写不敏感，官方模型有 V.CBody 写法）
                    if (c.molVars != null) {
                        String n = name.toLowerCase(java.util.Locale.ROOT);
                        Float f = c.molVars.get(n);
                        if (f == null && n.startsWith("variable.")) f = c.molVars.get("v." + n.substring(9));
                        if (f == null) f = c.molVars.get("v." + n);
                        if (f != null) return Val.of(f);
                    }
                    return Val.of(0);
                }
            }
        }

        void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        boolean peek(String t) { return s.regionMatches(i, t, 0, t.length()); }
        boolean peek2(String t) { return peek(t); }

        /** 读一个参数原文（到顶层 ',' 或 ')' 为止，保留引号/括号内的内容） */
        String readArgRaw() {
            skipWs();
            StringBuilder sb = new StringBuilder();
            int depth = 0;
            char q = 0;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (q != 0) { sb.append(ch); if (ch == q) q = 0; i++; continue; }
                if (ch == '\'' || ch == '"') { q = ch; sb.append(ch); i++; continue; }
                if (ch == '(') { depth++; }
                else if (ch == ')') { if (depth == 0) break; depth--; }
                else if (ch == ',' && depth == 0) break;
                sb.append(ch); i++;
            }
            return sb.toString().trim();
        }

        /** 参数求数：数字/表达式/变量；字符串字面量按 0 处理 */
        double numArg(List<String> args, int idx) {
            if (idx >= args.size()) return 0;
            String a = args.get(idx);
            if (a.isEmpty() || a.charAt(0) == '\'' || a.charAt(0) == '"') return 0;
            try {
                return new Parser(a, c).parseArith().numOrZero();
            } catch (Exception ex) {
                return 0;
            }
        }

        /** 去掉字符串字面量的引号（ctrl.hold/use/swing 的 filter 形参） */
        static String unquote(String a) {
            if (a == null || a.length() < 2) return a == null ? "" : a;
            char ch = a.charAt(0);
            if ((ch == '\'' || ch == '"') && a.charAt(a.length() - 1) == ch) return a.substring(1, a.length() - 1);
            return a;
        }

        String readString() {
            if (i >= s.length()) return "";
            char q = s.charAt(i++);
            StringBuilder sb = new StringBuilder();
            while (i < s.length() && s.charAt(i) != q) sb.append(s.charAt(i++));
            if (i < s.length()) i++;
            return sb.toString();
        }

        double readNumber() {
            int st = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            return Double.parseDouble(s.substring(st, i));
        }

        String readIdent() {
            int st = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) i++;
            return s.substring(st, i);
        }
    }
}
