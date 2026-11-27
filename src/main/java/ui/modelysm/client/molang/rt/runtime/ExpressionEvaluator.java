package ui.modelysm.client.molang.rt.runtime;

import ui.modelysm.client.molang.rt.parser.ast.Expression;
import ui.modelysm.client.molang.rt.runtime.binding.ObjectBinding;
import ui.modelysm.client.molang.rt.runtime.binding.ValueConversions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface ExpressionEvaluator<TEntity> extends ExecutionContext<TEntity> {
    @NotNull
    static <TEntity> ExpressionEvaluator<TEntity> evaluator(@Nullable TEntity entity) {
        return new ExpressionEvaluatorImpl<>(entity);
    }

    @NotNull
    static ExpressionEvaluator evaluator() {
        return evaluator(ObjectBinding.EMPTY);
    }

    default float evalAsFloat(@NotNull Expression expression) {
        return ValueConversions.asFloat(eval(expression));
    }

    default boolean evalAsBoolean(@NotNull Expression expression) {
        return ValueConversions.asBoolean(eval(expression));
    }
}