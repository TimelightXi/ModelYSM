package ui.modelysm.client.molang.rt.runtime;

import ui.modelysm.ModelYSM;
import ui.modelysm.client.molang.rt.parser.ast.Expression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface ExecutionContext<TEntity> {
    TEntity entity();

    @Nullable
    Object eval(@NotNull Expression expression2);

    @Nullable
    Object evalAll(@NotNull Iterable<Expression> iterable, boolean z);

    @Nullable
    default Object evalSafe(@NotNull Expression expression2) {
        try {
            return eval(expression2);
        } catch (Exception e) {
            ModelYSM.LOGGER.debug("Failed to evaluate molang expression.", e);
            return null;
        }
    }

    @Nullable
    default Object evalAllSafe(@NotNull Iterable<Expression> iterable, boolean z) {
        try {
            return evalAll(iterable, z);
        } catch (Exception e) {
            ModelYSM.LOGGER.debug("Failed to evaluate molang expression.", e);
            return null;
        }
    }
}