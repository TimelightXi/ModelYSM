package ui.modelysm.client.molang;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.Mth;

public final class Physics {
   private static final Map<UUID, Map<String, Spring>> MANAGERS = new ConcurrentHashMap<>();

   private Physics() {
   }

   public static void update(UUID owner, float timeStep) {
      Map<String, Spring> m = MANAGERS.get(owner);
      if (m != null) {
         for (Spring s : m.values()) {
            s.update(timeStep);
         }
      }
   }

   public static double secondOrder(UUID owner, String name, double input, double f, double z, double r) {
      return get(owner, name, input, false, f, z, r).setArgs(input, f, z, r);
   }

   public static double firstOrder(UUID owner, String name, double input, double f) {
      return get(owner, name, input, true, f, 0.0d, 0.0d).setArgs(input, f, 0.0d, 0.0d);
   }

   private static Spring get(UUID owner, String name, double input, boolean first, double f, double z, double r) {
      return MANAGERS.computeIfAbsent(owner, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(name, k -> first ? new FirstOrder(input, f) : new SecondOrder(input, f, z, r));
   }

   public static void cleanup(Set<UUID> valid) {
      MANAGERS.keySet().removeIf(k -> !valid.contains(k));
   }

   public static void clear(UUID owner) {
      MANAGERS.remove(owner);
   }

   private static final class FirstOrder implements Spring {
      private float input;
      private float response;
      private float lastSimulation;

      FirstOrder(double input, double response) {
         this.input = (float) input;
         this.response = (float) response;
      }

      public void update(float timeStep) {
         float k = Math.max(1.0e-6f, this.response);
         this.lastSimulation = (1.0f - timeStep / k) * this.lastSimulation + timeStep / k * this.input;
      }

      public float setArgs(double input, double a, double b, double c) {
         this.input = (float) input;
         this.response = (float) a;
         return this.lastSimulation;
      }
   }

   private static final class SecondOrder implements Spring {
      private float arg0;
      private float arg1;
      private float arg2;
      private float arg3;
      private float inputFunction;
      private float lastSimulation;
      private float lastSimulationDot;

      SecondOrder(double input, double frequency, double coefficient, double response) {
         this.arg0 = (float) input;
         this.arg1 = Mth.clamp((float) frequency, 0.0f, 5.0f);
         this.arg2 = Mth.clamp((float) coefficient, 0.0f, 1.0f);
         this.arg3 = (float) response;
      }

      public void update(float timeStep) {
         float input = this.arg0;
         float frequency = Mth.clamp(this.arg1, 0.0f, 5.0f);
         float coefficient = Mth.clamp(this.arg2, 0.0f, 1.0f);
         float response = this.arg3;
         float k1 = coefficient / (float) Math.PI / frequency;
         float k2 = 1.0f / (((float) Math.PI * 2f) * frequency) / (((float) Math.PI * 2f) * frequency);
         float k3 = response * coefficient / 2.0f / (float) Math.PI / frequency;
         float inputFunctionDot = (input - this.inputFunction) / Math.max(1.0e-6f, timeStep);
         this.inputFunction = input;
         float maxTimeStep = (float) Math.sqrt((double) (4.0f * k2 + k1 * k1)) - k1;
         if (maxTimeStep <= 0.0f) {
            maxTimeStep = 1.0e-4f;
         }

         int cycleTime = (int) Math.ceil((double) (timeStep / maxTimeStep));
         timeStep /= (float) Math.max(1, cycleTime);
         float simDot = this.lastSimulationDot;

         float sim;
         for (sim = this.lastSimulation; cycleTime > 0; --cycleTime) {
            sim += timeStep * simDot;
            simDot += timeStep * (k3 * inputFunctionDot + input - sim - k1 * simDot) / k2;
         }

         this.lastSimulation = sim;
         this.lastSimulationDot = simDot;
      }

      public float setArgs(double input, double a, double b, double c) {
         this.arg0 = (float) input;
         this.arg1 = Mth.clamp((float) a, 0.0f, 5.0f);
         this.arg2 = Mth.clamp((float) b, 0.0f, 1.0f);
         this.arg3 = (float) c;
         return this.lastSimulation;
      }
   }

   private interface Spring {
      void update(float timeStep);

      float setArgs(double input, double a, double b, double c);
   }
}
