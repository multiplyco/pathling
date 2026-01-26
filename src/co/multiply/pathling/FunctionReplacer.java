package co.multiply.pathling;

import clojure.lang.IFn;

/**
 * Replacer that applies a function to each matched value.
 * This is the traditional update-paths behavior.
 */
public final class FunctionReplacer implements Replacer {
    private final IFn f;

    public FunctionReplacer(IFn f) {
        this.f = f;
    }

    @Override
    public Object replace(Object v) {
        return f.invoke(v);
    }
}
