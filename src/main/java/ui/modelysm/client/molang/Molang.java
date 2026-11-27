package ui.modelysm.client.molang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

public final class Molang {
   private Molang() {
   }

   public static Expr parse(String src) {
      try {
         Parser p = new Parser(src);
         Expr e = p.parseScript();
         p.expectEnd();
         return e;
      } catch (Exception var3) {
         return (ctx) -> (double)0.0F;
      }
   }

   public static double eval(String src, Ctx ctx) {
      return parse(src).eval(ctx);
   }

   /** 官方 Interpolations.lerpYaw（MathHelper.wrapDegrees + normalizeYaw）的 double 版。 */
   private static double wrapDegrees(double degrees) {
      double d = degrees % (double) 360.0F;
      if (d >= (double) 180.0F) {
         d -= (double) 360.0F;
      }
      if (d < (double) -180.0F) {
         d += (double) 360.0F;
      }
      return d;
   }

   private static double normalizeYaw(double a, double b) {
      double diff = a - b;
      if (diff > (double) 180.0F || diff < (double) -180.0F) {
         diff = Math.copySign((double) 360.0F - Math.abs(diff), diff);
         return a + diff;
      }
      return b;
   }

   private static double lerpYaw(double a, double b, double position) {
      double a0 = wrapDegrees(a);
      double b0 = wrapDegrees(b);
      return a0 + (normalizeYaw(a0, b0) - a0) * position;
   }

   public static final class Ctx {
      public final Map<String, Double> queries = new HashMap<>();
      public double animTime;
      public double lifeTime;
      public double distanceMoved;
      public double moveSpeed;
      public double headPitch;
      public double headYaw;
      public double health = (double)1.0F;
      public double maxHealth = (double)1.0F;
      public Map<String, Double> boneGlow = new HashMap<>();
      public boolean lastQueryNull;
      public UUID physicsOwner;
      public double deltaTime = 0.05;
      public Map<String, Double> roaming;
      /** 官方 YsmModel 变量表（每实体持久，v./variable. 目标；timeline 脚本与 controller 共享） */
      public Map<String, Float> vars;

      public Double lookup(String name) {
         String lname = name.toLowerCase(Locale.ROOT);

         // 官方语义：v.* / variable.* 先取模型变量表（timeline/controller 赋值即持久），
         // 未定义返回 null（保留 "v.x ?? 默认" 的兜底链）
         if (lname.startsWith("v.") || lname.startsWith("variable.")) {
            String key = lname.startsWith("variable.") ? "v." + lname.substring("variable.".length()) : lname;
            if (this.vars != null) {
               Float fv = this.vars.get(key);
               if (fv != null) {
                  return (double)fv;
               }
            }
            if (key.startsWith("v.roaming.")) {
               Double rv = this.roaming != null ? (Double)this.roaming.get(key) : null;
               return rv != null ? rv : (double)0.0F;
            }
            Double qv = this.queries.get(key);
            return qv;
         }

         if (name.equals("anim_time")) {
            return this.animTime;
         } else if (name.equals("life_time")) {
            return this.lifeTime;
         } else if (!name.equals("modified_distance_moved") && !name.equals("distance_from_last_travel_point")) {
            if (!name.equals("modified_move_speed") && !name.equals("ground_speed")) {
               if (name.equals("delta_time")) {
                  // 官方 QueryBinding:62 = positionTracker.getTimeDelta()/20（秒），
                  // 之前恒返回 0.05（按 20FPS 写死）：90FPS 下弹簧每帧都被喂了 4~5 倍的步长，
                  // 二阶弹簧会发散/过冲，实机表现就是"头发抽一下就不动/抖成死板姿态"。
                  return this.deltaTime;
               } else if (name.equals("health")) {
                  return this.health;
               } else if (name.equals("max_health")) {
                  return this.maxHealth;
               } else {
                  Double d = (Double)this.queries.get(name);
                  if (d != null) {
                     return d;
                  } else if (!name.startsWith("v.roaming.") && !name.startsWith("variable.roaming.")) {
                     return !name.startsWith("v.") && !name.startsWith("variable.") ? null : null;
                  } else {
                     String rmKey = name.startsWith("variable.roaming.") ? "v.roaming." + name.substring("variable.roaming.".length()) : name;
                     Double rv = this.roaming != null ? (Double)this.roaming.get(rmKey) : null;
                     return rv != null ? rv : (double)0.0F;
                  }
               }
            } else {
               return this.moveSpeed;
            }
         } else {
            return this.distanceMoved;
         }
      }

      public double query(String name) {
         Double v = this.lookup(name);
         return v == null ? (double)0.0F : v;
      }
   }

   private static final class Parser {
      private final String s;
      private int pos;

      Parser(String s) {
         this.s = s;
      }

      void expectEnd() {
         this.skipWs();
         if (this.pos < this.s.length()) {
            throw new IllegalArgumentException("unexpected: " + this.s.substring(this.pos));
         }
      }

      private char peek() {
         this.skipWs();
         return this.pos < this.s.length() ? this.s.charAt(this.pos) : '\u0000';
      }

      private char peekRaw() {
         return this.pos < this.s.length() ? this.s.charAt(this.pos) : '\u0000';
      }

      private String readStringLit() {
         char q = this.s.charAt(this.pos++);

         int st;
         for(st = this.pos; this.pos < this.s.length() && this.s.charAt(this.pos) != q; ++this.pos) {
         }

         String out = this.s.substring(st, this.pos);
         if (this.pos < this.s.length()) {
            ++this.pos;
         }

         return out;
      }

      private void skipWs() {
         while(this.pos < this.s.length() && (this.s.charAt(this.pos) == ' ' || this.s.charAt(this.pos) == '\t')) {
            ++this.pos;
         }

      }

      private boolean eat(String token) {
         this.skipWs();
         if (this.s.startsWith(token, this.pos)) {
            this.pos += token.length();
            return true;
         } else {
            return false;
         }
      }

      Expr parseExpression() {
         return this.parseTernary();
      }

      Expr parseScript() {
         List<Expr> stmts = new ArrayList();
         stmts.add(this.parseStatement());

         while(this.peek() == ';') {
            ++this.pos;
            this.skipWs();
            if (this.pos >= this.s.length()) {
               break;
            }

            stmts.add(this.parseStatement());
         }

         return (ctx) -> {
            double val = (double)0.0F;

            for(Expr st : stmts) {
               val = st.eval(ctx);
            }

            return val;
         };
      }

      Expr parseStatement() {
         this.skipWs();
         if (this.s.startsWith("return", this.pos) && this.isWordBoundary(6)) {
            this.pos += 6;
            return this.parseExpression();
         } else {
            Expr assign = this.tryParseAssign();
            return assign != null ? assign : this.parseExpression();
         }
      }

      private Expr tryParseAssign() {
         this.skipWs();
         int prefix = this.s.regionMatches(true, this.pos, "v.", 0, 2) ? 2 : (this.s.regionMatches(true, this.pos, "variable.", 0, 9) ? 9 : -1);
         if (prefix < 0) {
            return null;
         } else {
            int nameStart = this.pos + prefix;

            int i;
            for(i = nameStart; i < this.s.length() && (Character.isLetterOrDigit(this.s.charAt(i)) || this.s.charAt(i) == '_' || this.s.charAt(i) == '.'); ++i) {
            }

            if (i == nameStart) {
               return null;
            } else {
               int j;
               for(j = i; j < this.s.length() && (this.s.charAt(j) == ' ' || this.s.charAt(j) == '\t'); ++j) {
               }

               if (j < this.s.length() && this.s.charAt(j) == '=') {
                  if (j + 1 < this.s.length()) {
                     char n2 = this.s.charAt(j + 1);
                     if (n2 == '=' || n2 == '<' || n2 == '>') {
                        return null;
                     }
                  }

                  String var10000 = this.s.substring(nameStart, i);
                  String key = "v." + var10000.toLowerCase(Locale.ROOT);
                  this.pos = j + 1;
                  Expr rhs = this.parseExpression();
                  return (ctx) -> {
                     double val = rhs.eval(ctx);
                     // 官方语义：变量赋值进模型的持久变量表（跨帧/跨层可见），
                     // 没有持久表时才退化成帧内 queries（GUI 静态预览）
                     if (ctx.vars != null) {
                        ctx.vars.put(key, (float)val);
                     } else {
                        ctx.queries.put(key, val);
                     }
                     if (ctx.roaming != null && key.startsWith("v.roaming.")) {
                        ctx.roaming.put(key, val);
                     }

                     return val;
                  };
               } else {
                  return null;
               }
            }
         }
      }

      private Expr parseTernary() {
         Expr cond = this.parseLogicOr();
         if (this.s.startsWith("??", this.pos)) {
            this.pos += 2;
            Expr right = this.parseTernary();
            return (ctx) -> {
               double lv = cond.eval(ctx);
               return ctx.lastQueryNull ? right.eval(ctx) : lv;
            };
         } else if (this.peek() == '?') {
            ++this.pos;
            Expr a = this.parseExpression();
            if (this.peek() == ':') {
               ++this.pos;
               Expr b = this.parseExpression();
               return (ctx) -> cond.eval(ctx) != (double)0.0F ? a.eval(ctx) : b.eval(ctx);
            } else {
               return cond;
            }
         } else {
            return cond;
         }
      }

      private Expr parseLogicOr() {
         Expr left = this.parseLogicAnd();

         while(true) {
            boolean isOr;
            if (this.s.startsWith("||", this.pos)) {
               this.pos += 2;
               isOr = true;
            } else if (this.s.startsWith("or", this.pos) && this.isWordBoundary(2)) {
               this.pos += 2;
               isOr = true;
            } else if (this.s.startsWith("&&", this.pos)) {
               this.pos += 2;
               isOr = false;
            } else {
               if (!this.s.startsWith("and", this.pos) || !this.isWordBoundary(3)) {
                  return left;
               }

               this.pos += 3;
               isOr = false;
            }

            Expr right = this.parseLogicAnd();
            final Expr l = left;
            final Expr r = right;
            left = isOr
                  ? (ctx) -> l.eval(ctx) == (double)0.0F && r.eval(ctx) == (double)0.0F ? (double)0.0F : (double)1.0F
                  : (ctx) -> l.eval(ctx) != (double)0.0F && r.eval(ctx) != (double)0.0F ? (double)1.0F : (double)0.0F;
         }
      }

      private boolean isWordBoundary(int len) {
         char next = this.pos + len < this.s.length() ? this.s.charAt(this.pos + len) : 32;
         return !Character.isLetterOrDigit(next) && next != '_';
      }

      private Expr parseLogicAnd() {
         Expr left = this.parseCompare();

         while (this.peek() == '&' && this.pos + 1 < this.s.length() && this.s.charAt(this.pos + 1) == '&') {
            this.pos += 2;
            final Expr l = left;
            final Expr r = this.parseCompare();
            left = (ctx) -> l.eval(ctx) != (double)0.0F && r.eval(ctx) != (double)0.0F ? (double)1.0F : (double)0.0F;
         }

         return left;
      }

      private Expr parseCompare() {
         Expr left = this.parseAdditive();

         while(this.s.startsWith("<=", this.pos) || this.s.startsWith(">=", this.pos) || this.s.startsWith("==", this.pos) || this.s.startsWith("!=", this.pos) || this.peek() == '<' || this.peek() == '>') {
            String op = this.s.startsWith("<=", this.pos) ? "<=" : (this.s.startsWith(">=", this.pos) ? ">=" : (this.s.startsWith("==", this.pos) ? "==" : (this.s.startsWith("!=", this.pos) ? "!=" : String.valueOf(this.s.charAt(this.pos)))));
            this.pos += op.length();
            Expr right = this.parseAdditive();
            final Expr l = left;
            final Expr r = right;
            switch (op) {
               case "<=":
                  left = (ctx) -> l.eval(ctx) <= r.eval(ctx) ? (double)1.0F : (double)0.0F;
                  break;
               case ">=":
                  left = (ctx) -> l.eval(ctx) >= r.eval(ctx) ? (double)1.0F : (double)0.0F;
                  break;
               case "==":
                  left = (ctx) -> l.eval(ctx) == r.eval(ctx) ? (double)1.0F : (double)0.0F;
                  break;
               case "!=":
                  left = (ctx) -> l.eval(ctx) != r.eval(ctx) ? (double)1.0F : (double)0.0F;
                  break;
               case "<":
                  left = (ctx) -> l.eval(ctx) < r.eval(ctx) ? (double)1.0F : (double)0.0F;
                  break;
               default:
                  left = (ctx) -> l.eval(ctx) > r.eval(ctx) ? (double)1.0F : (double)0.0F;
            }
         }

         return left;
      }

      private Expr parseAdditive() {
         Expr left = this.parseMultiplicative();

         while(true) {
            char c = this.peek();
            if (c != '+' && c != '-') {
               return left;
            }

            ++this.pos;
            Expr right = this.parseMultiplicative();
            final Expr l = left;
            final Expr r = right;
            left = c == '+' ? (ctx) -> l.eval(ctx) + r.eval(ctx) : (ctx) -> l.eval(ctx) - r.eval(ctx);
         }
      }

      private Expr parseMultiplicative() {
         Expr left = this.parseUnary();

         while(true) {
            char c = this.peek();
            if (c != '*' && c != '/' && c != '%') {
               return left;
            }

            ++this.pos;
            Expr right = this.parseUnary();
            final Expr l = left;
            final Expr r = right;
            left = c == '*' ? (ctx) -> l.eval(ctx) * r.eval(ctx) : (c == '/' ? (ctx) -> {
               double d = r.eval(ctx);
               return d == (double)0.0F ? (double)0.0F : l.eval(ctx) / d;
            } : (ctx) -> {
               double d = r.eval(ctx);
               return d == (double)0.0F ? (double)0.0F : l.eval(ctx) % d;
            });
         }
      }

      private Expr parseUnary() {
         this.skipWs();
         if (this.peek() == '-') {
            ++this.pos;
            Expr e = this.parseUnary();
            return (ctx) -> -e.eval(ctx);
         } else if (this.peek() == '+') {
            ++this.pos;
            return this.parseUnary();
         } else if (this.peek() == '!') {
            ++this.pos;
            Expr e = this.parseUnary();
            return (ctx) -> e.eval(ctx) == (double)0.0F ? (double)1.0F : (double)0.0F;
         } else {
            return this.parsePrimary();
         }
      }

      private Expr parsePrimary() {
         this.skipWs();
         char c = this.peek();
         if (c == '(') {
            ++this.pos;
            Expr e = this.parseExpression();
            if (this.peek() == ')') {
               ++this.pos;
            }

            return e;
         } else if (c == 0) {
            throw new IllegalArgumentException("unexpected end");
         } else if (c != '\'' && c != '"') {
            if (Character.isDigit(c) || c == '.' && this.pos + 1 < this.s.length() && Character.isDigit(this.s.charAt(this.pos + 1))) {
               int start;
               for(start = this.pos; this.pos < this.s.length() && (Character.isDigit(this.s.charAt(this.pos)) || this.s.charAt(this.pos) == '.'); ++this.pos) {
               }

               double v = Double.parseDouble(this.s.substring(start, this.pos));
               return (ctx) -> v;
            } else {
               int start;
               for(start = this.pos; this.pos < this.s.length() && (Character.isLetterOrDigit(this.s.charAt(this.pos)) || this.s.charAt(this.pos) == '_' || this.s.charAt(this.pos) == '.'); ++this.pos) {
               }

               String name = this.s.substring(start, this.pos);
               if (name.isEmpty()) {
                  throw new IllegalArgumentException("bad token at " + this.pos);
               } else {
                  return this.variableOrCall(name);
               }
            }
         } else {
            this.readStringLit();
            return (ctx) -> (double)0.0F;
         }
      }

      private Expr variableOrCall(String name) {
         name = name.toLowerCase(Locale.ROOT);
         if (!name.equals("ysm.bone_glow") && !name.equals("bone_glow")) {
            if (!name.equals("second_order") && !name.equals("first_order")) {
               if (this.peek() != '(') {
                  if (!name.startsWith("q.") && !name.startsWith("query.")) {
                     if (!name.startsWith("v.") && !name.startsWith("variable.")) {
                        if (name.equals("math.pi")) {
                           return (ctx) -> Math.PI;
                        } else if (name.equals("math.euler")) {
                           return (ctx) -> Math.E;
                        } else if (name.startsWith("ysm.")) {
                           String k = name.substring(4);
                           return (ctx) -> {
                              Double d = (Double)ctx.queries.get("ysm." + k);
                              if (d == null) {
                                 d = (Double)ctx.queries.get(k);
                              }

                              if (d != null) {
                                 return d;
                              } else {
                                 double var10000;
                                 switch (k) {
                                    case "head_pitch" -> var10000 = ctx.headPitch;
                                    case "head_yaw" -> var10000 = ctx.headYaw;
                                    case "food_level" -> var10000 = (double)20.0F;
                                    case "is_close_eyes" -> var10000 = (double)0.0F;
                                    default -> var10000 = (double)0.0F;
                                 }

                                 return var10000;
                              }
                           };
                        } else {
                           return (ctx) -> (double)0.0F;
                        }
                     } else {
                        String v = name.startsWith("v.") ? name.substring(2) : name.substring(9);
                        return (ctx) -> {
                           Double d = ctx.lookup("v." + v);
                           ctx.lastQueryNull = d == null;
                           return d == null ? (double)0.0F : d;
                        };
                     }
                  } else {
                     String q = name.startsWith("q.") ? name.substring(2) : name.substring(6);
                     return (ctx) -> {
                        Double d = ctx.lookup(q);
                        ctx.lastQueryNull = d == null;
                        return d == null ? (double)0.0F : d;
                     };
                  }
               } else {
                  ++this.pos;
                  List<Expr> args = new ArrayList();
                  if (this.peek() != ')') {
                     args.add(this.parseExpression());

                     while(this.peek() == ',') {
                        ++this.pos;
                        args.add(this.parseExpression());
                     }
                  }

                  if (this.peek() == ')') {
                     ++this.pos;
                  }

                  return this.functionExpr(name, args);
               }
            } else if (this.peek() != '(') {
               return (ctx) -> (double)0.0F;
            } else {
               ++this.pos;
               this.skipWs();
               String springName = "";
               if (this.peek() != '\'' && this.peek() != '"') {
                  int st;
                  for(st = this.pos; this.pos < this.s.length() && this.s.charAt(this.pos) != ',' && this.s.charAt(this.pos) != ')'; ++this.pos) {
                  }

                  springName = this.s.substring(st, this.pos).trim();
               } else {
                  springName = this.readStringLit();
               }

               List<Expr> args = new ArrayList();
               if (this.peek() == ',') {
                  ++this.pos;
                  args.add(this.parseExpression());

                  while(this.peek() == ',') {
                     ++this.pos;
                     args.add(this.parseExpression());
                  }
               }

               if (this.peek() == ')') {
                  ++this.pos;
               }

               boolean first = name.equals("first_order");
               final String spring = springName;
               Expr input = !args.isEmpty() ? (Expr)args.get(0) : (ctx) -> (double)0.0F;
               Expr f = args.size() > 1 ? (Expr)args.get(1) : (ctx) -> (double)0.0F;
               Expr z = args.size() > 2 ? (Expr)args.get(2) : (ctx) -> (double)0.0F;
               Expr r = args.size() > 3 ? (Expr)args.get(3) : (ctx) -> (double)0.0F;
               return (ctx) -> {
                  if (ctx.physicsOwner != null && !spring.isEmpty()) {
                     return first ? Physics.firstOrder(ctx.physicsOwner, spring, input.eval(ctx), f.eval(ctx)) : Physics.secondOrder(ctx.physicsOwner, spring, input.eval(ctx), f.eval(ctx), z.eval(ctx), r.eval(ctx));
                  } else {
                     return input.eval(ctx);
                  }
               };
            }
         } else if (this.peek() != '(') {
            return (ctx) -> (double)0.0F;
         } else {
            ++this.pos;
            this.skipWs();
            String boneName = "";
            if (this.peek() != '\'' && this.peek() != '"') {
               int st;
               for(st = this.pos; this.pos < this.s.length() && this.s.charAt(this.pos) != ',' && this.s.charAt(this.pos) != ')'; ++this.pos) {
               }

               boneName = this.s.substring(st, this.pos).trim();
            } else {
               boneName = this.readStringLit();
            }

            this.skipWs();
            Expr val;
            if (this.peek() == ',') {
               ++this.pos;
               val = this.parseExpression();
            } else {
               val = (ctx) -> (double)0.0F;
            }

            if (this.peek() == ')') {
               ++this.pos;
            }

            final String bone = boneName;
            final Expr valF = val;
            return (ctx) -> {
               if (!bone.isEmpty()) {
                  ctx.boneGlow.put(bone, valF.eval(ctx));
               }

               return (double)0.0F;
            };
         }
      }

      private Expr functionExpr(String name, List<Expr> args) {
         String n = name.startsWith("math.") ? name.substring(5) : name;
         Expr a0 = args.isEmpty() ? (ctx) -> (double)0.0F : (Expr)args.get(0);
         Expr a1 = args.size() > 1 ? (Expr)args.get(1) : (ctx) -> (double)0.0F;
         Expr a2 = args.size() > 2 ? (Expr)args.get(2) : (ctx) -> (double)0.0F;
         Expr var10000;
         switch (n) {
            case "sin":
               var10000 = (ctx) -> Math.sin(Math.toRadians(a0.eval(ctx)));
               break;
            case "cos":
               var10000 = (ctx) -> Math.cos(Math.toRadians(a0.eval(ctx)));
               break;
            case "asin":
               var10000 = (ctx) -> Math.toDegrees(Math.asin(a0.eval(ctx)));
               break;
            case "acos":
               var10000 = (ctx) -> Math.toDegrees(Math.acos(a0.eval(ctx)));
               break;
            case "atan":
               var10000 = (ctx) -> Math.toDegrees(Math.atan(a0.eval(ctx)));
               break;
            case "atan2":
               var10000 = (ctx) -> Math.toDegrees(Math.atan2(a0.eval(ctx), a1.eval(ctx)));
               break;
            case "abs":
               var10000 = (ctx) -> Math.abs(a0.eval(ctx));
               break;
            case "floor":
               var10000 = (ctx) -> Math.floor(a0.eval(ctx));
               break;
            case "ceil":
               var10000 = (ctx) -> Math.ceil(a0.eval(ctx));
               break;
            case "trunc":
               var10000 = (ctx) -> {
                  double v = a0.eval(ctx);
                  return v < (double)0.0F ? Math.ceil(v) : Math.floor(v);
               };
               break;
            case "round":
               var10000 = (ctx) -> (double)Math.round(a0.eval(ctx));
               break;
            case "sqrt":
               var10000 = (ctx) -> Math.sqrt(a0.eval(ctx));
               break;
            case "pow":
               var10000 = (ctx) -> Math.pow(a0.eval(ctx), a1.eval(ctx));
               break;
            case "exp":
               var10000 = (ctx) -> Math.exp(a0.eval(ctx));
               break;
            case "ln":
               var10000 = (ctx) -> Math.log(a0.eval(ctx));
               break;
            case "min_angle":
               var10000 = (ctx) -> {
                  double angle = a0.eval(ctx) % (double)360.0F;
                  if (angle >= (double)180.0F) {
                     angle -= (double)360.0F;
                  } else if (angle < (double)-180.0F) {
                     angle += (double)360.0F;
                  }
                  return angle;
               };
               break;
            case "hermite_blend":
               var10000 = (ctx) -> {
                  double m = Math.ceil(a0.eval(ctx));
                  return Math.floor((double)3.0F * m * m - (double)2.0F * m * m * m);
               };
               break;
            case "lerp":
               var10000 = (ctx) -> {
                  double a = a0.eval(ctx);
                  double b = a1.eval(ctx);
                  return a + (b - a) * a2.eval(ctx);
               };
               break;
            case "lerp_rot":
               var10000 = (ctx) -> lerpYaw(a0.eval(ctx), a1.eval(ctx), a2.eval(ctx));
               break;
            case "mod":
               var10000 = (ctx) -> {
                  double d = a1.eval(ctx);
                  if (d == (double)0.0F) {
                     return (double)0.0F;
                  } else {
                     return a0.eval(ctx) % d;
                  }
               };
               break;
            case "clamp":
               var10000 = (ctx) -> Math.max(a1.eval(ctx), Math.min(a2.eval(ctx), a0.eval(ctx)));
               break;
            case "min":
               var10000 = (ctx) -> {
                  double m = a0.eval(ctx);

                  for(Expr e : args) {
                     m = Math.min(m, e.eval(ctx));
                  }

                  return m;
               };
               break;
            case "max":
               var10000 = (ctx) -> {
                  double m = a0.eval(ctx);

                  for(Expr e : args) {
                     m = Math.max(m, e.eval(ctx));
                  }

                  return m;
               };
               break;
            case "random":
               var10000 = (ctx) -> {
                  if (args.isEmpty()) {
                     return (double) ThreadLocalRandom.current().nextFloat();
                  }
                  if (args.size() == 1) {
                     return (double) (ThreadLocalRandom.current().nextFloat() * a0.eval(ctx));
                  }
                  double min = a0.eval(ctx);
                  double range = a1.eval(ctx);
                  if (min > range) {
                     double temp = min;
                     min = range;
                     range = temp - range;
                  } else {
                     range -= min;
                  }
                  return min + (double) ThreadLocalRandom.current().nextFloat() * range;
               };
               break;
            case "random_integer":
               var10000 = (ctx) -> {
                  int min = args.isEmpty() ? 0 : (int) a0.eval(ctx);
                  int range = args.size() < 2 ? 0 : (int) a1.eval(ctx);
                  if (min > range) {
                     int temp = min;
                     min = range;
                     range = temp - range;
                  } else {
                     range -= min;
                  }
                  if (range <= 0) {
                     return (double) min;
                  }
                  return (double) (min + ThreadLocalRandom.current().nextInt(range));
               };
               break;
            case "die_roll":
               var10000 = (ctx) -> {
                  int count = args.isEmpty() ? 0 : (int) a0.eval(ctx);
                  double min = args.size() < 2 ? (double) 0.0F : a1.eval(ctx);
                  double range = args.size() < 3 ? (double) 0.0F : a2.eval(ctx);
                  if (min > range) {
                     double temp = min;
                     min = range;
                     range = temp - range;
                  } else {
                     range -= min;
                  }
                  double total = (double) 0.0F;
                  ThreadLocalRandom rnd = ThreadLocalRandom.current();
                  while (count-- > 0) {
                     total += min + (double) rnd.nextFloat() * range;
                  }
                  return total;
               };
               break;
            case "die_roll_integer":
               var10000 = (ctx) -> {
                  int count = args.isEmpty() ? 0 : (int) Math.round(a0.eval(ctx));
                  int min = args.size() < 2 ? 0 : (int) a1.eval(ctx);
                  int range = args.size() < 3 ? 0 : (int) a2.eval(ctx);
                  if (min > range) {
                     int temp = min;
                     min = range;
                     range = temp - range;
                  } else {
                     range -= min;
                  }
                  int total = 0;
                  ThreadLocalRandom rnd = ThreadLocalRandom.current();
                  while (count-- > 0 && range > 0) {
                     total += min + rnd.nextInt(range);
                  }
                  return (double) total;
               };
               break;
            default:
               var10000 = (ctx) -> (double)0.0F;
         }

         return var10000;
      }
   }

   public interface Expr {
      double eval(Ctx var1);
   }
}
