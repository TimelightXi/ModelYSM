package ui.modelysm.client.molang.rt.parser.ast;

import ui.modelysm.client.molang.core.util.StringPool;
import org.jetbrains.annotations.NotNull;

public class StructAccessExpression implements Expression {

    private final Expression left;

    private final int path;

    public StructAccessExpression(Expression expression, String path) {
        this.left = expression;
        this.path = StringPool.computeIfAbsent(path);
    }

    @Override
    public <R> R visit(@NotNull ExpressionVisitor<R> visitor) {
        return visitor.visitStruct(this);
    }

    public Expression left() {
        return left;
    }

    public int path() {
        return path;
    }
}