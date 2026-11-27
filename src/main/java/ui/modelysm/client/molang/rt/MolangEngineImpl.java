package ui.modelysm.client.molang.rt;

import ui.modelysm.client.molang.rt.parser.MolangParser;
import ui.modelysm.client.molang.rt.parser.ast.Expression;
import ui.modelysm.client.molang.rt.runtime.binding.ObjectBinding;

import java.io.IOException;
import java.io.Reader;
import java.util.List;

public final class MolangEngineImpl implements MolangEngine {

    private final ObjectBinding bindings;

    MolangEngineImpl(ObjectBinding bindings) {
        this.bindings = bindings;
    }

    @Override
    public List<Expression> parse(Reader reader) throws IOException {
        return MolangParser.parser(reader, this.bindings).parseAll();
    }
}