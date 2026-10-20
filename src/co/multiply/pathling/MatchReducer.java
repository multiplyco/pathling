package co.multiply.pathling;

import clojure.lang.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-scan transduction state. A transducer may replace or wrap the accumulator,
 * so every step's result must reach the next step (or completion when reduced).
 */
final class MatchReducer extends AFn {
    private static final IFn COLLECT = new AFn() {
        @Override
        public Object invoke() {
            return new ArrayList<Object>();
        }

        @Override
        public Object invoke(Object acc) {
            return acc;
        }

        @Override
        public Object invoke(Object acc, Object value) {
            @SuppressWarnings("unchecked")
            ArrayList<Object> matches = (ArrayList<Object>) acc;
            matches.add(value);
            return acc;
        }
    };

    private final IFn rf;
    private Object state = new ArrayList<Object>();

    MatchReducer(IFn xf) {
        rf = (IFn) xf.invoke(COLLECT);
    }

    @Override
    public Object invoke(Object value) {
        state = rf.invoke(state, value);
        return state;
    }

    IPersistentVector complete() {
        Object acc = RT.isReduced(state) ? ((IDeref) state).deref() : state;
        Object result = rf.invoke(acc);
        // Preserve find-when's vector-or-nil API, including alternate completion results.
        IPersistentVector matches = switch (result) {
            case null -> null;
            case IPersistentVector v -> v;
            case List<?> list -> list.isEmpty() ? null : PersistentVector.create(list);
            case Iterable<?> iterable -> PersistentVector.create(iterable);
            case Seqable seqable -> PersistentVector.create(seqable.seq());
            case Object[] array -> PersistentVector.create(array);
            default -> throw new IllegalArgumentException(
                "find-when transducer must complete to a collection or nil");
        };
        return matches == null || matches.count() == 0 ? null : matches;
    }
}
