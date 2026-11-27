package ui.modelysm.client.molang.rt.runtime;

import ui.modelysm.client.molang.rt.parser.ast.Expression;
import ui.modelysm.client.molang.rt.parser.ast.StringExpression;
import ui.modelysm.client.molang.rt.runtime.binding.ValueConversions;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@FunctionalInterface
public interface Function {
    /**
     * Executes this function with the given arguments.
     *
     * @param context   The execution context
     * @param arguments The arguments
     * @return The function result
     * @since 3.0.0
     */
    @Nullable Object evaluate(final @NotNull ExecutionContext<?> context, final @NotNull ArgumentCollection arguments);

    default boolean validateArgumentSize(int size) {
        return true;
    }

    ArgumentCollection EMPTY_ARGUMENT = new ArgumentCollection(new ArrayList());

    Function NOOP = (executionContext, argumentCollection) -> null;

    class ArgumentCollection {

        private final List<Expression> arguments;

        public ArgumentCollection(List<Expression> arguments) {
            this.arguments = arguments;
        }

        public int size() {
            return this.arguments.size();
        }

        @Nullable
        public String getAsString(@NotNull ExecutionContext<?> ctx, final int index) {
            return ValueConversions.asString(ctx.evalSafe(this.arguments.get(index)));
        }

        public int getStringId(@NotNull ExecutionContext<?> ctx, final int index) {
            return ValueConversions.asStringId(ctx.evalSafe(this.arguments.get(index)));
        }

        public double getAsDouble(@NotNull ExecutionContext<?> ctx, final int index) {
            return ValueConversions.asDouble(ctx.evalSafe(this.arguments.get(index)));
        }

        public int getAsInt(@NotNull ExecutionContext<?> ctx, final int index) {
            return ValueConversions.asInt(ctx.evalSafe(this.arguments.get(index)));
        }

        public float getAsFloat(@NotNull ExecutionContext<?> ctx, final int index) {
            return ValueConversions.asFloat(ctx.evalSafe(this.arguments.get(index)));
        }

        public boolean getAsBoolean(@NotNull ExecutionContext<?> ctx, final int index) {
            return ValueConversions.asBoolean(ctx.evalSafe(this.arguments.get(index)));
        }

        @Nullable
        public Object getValue(@NotNull ExecutionContext<?> ctx, final int index) {
            return ctx.evalSafe(this.arguments.get(index));
        }

        public Expression getExpression(int i) {
            return this.arguments.get(i);
        }
    }
}