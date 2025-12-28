package co.multiply.pathling;

import clojure.lang.*;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Scanner that collects matching values with transducer support.
 * Used for read-only find operations with transformation/filtering.
 *
 * Supports early termination via reduced values.
 * For the fast path without transducers, use ScannerMatches.
 */
public final class ScannerMatchesXf {
    private ScannerMatchesXf() {} // Prevent instantiation

    /**
     * Scan a data structure for values matching the predicate, with transducer support.
     *
     * @param obj  the data structure to scan
     * @param pred predicate function (Clojure IFn)
     * @param xf   transducer to apply to matches
     * @return vector of matching values (transformed by xf), or null if no matches
     */
    public static IPersistentVector matchesWhen(Object obj, IFn pred, IFn xf) {
        ArrayList<Object> matches = new ArrayList<>();

        // Create base rf with completing arity
        IFn baseRf = new AFn() {
            @Override
            public Object invoke(Object acc) {
                return acc; // completing
            }
            @Override
            public Object invoke(Object acc, Object x) {
                @SuppressWarnings("unchecked")
                ArrayList<Object> list = (ArrayList<Object>) acc;
                list.add(x);
                return acc;
            }
        };
        IFn rf = (IFn) xf.invoke(baseRf);

        IFn addMatch = new AFn() {
            @Override
            public Object invoke(Object x) {
                return rf.invoke(matches, x);
            }
        };

        scanWhen(obj, addMatch, pred);
        rf.invoke(matches); // completing

        return matches.isEmpty() ? null : PersistentVector.create(matches);
    }

    // ========================================================================
    // Internal scanning implementation
    // ========================================================================

    private static boolean scanWhen(Object obj, IFn addMatch, IFn pred) {
        return switch (obj) {
            case null -> scanScalar(null, addMatch, pred);
            case IPersistentMap m -> scanMap(m, addMatch, pred);
            case IPersistentVector v -> scanVector(v, addMatch, pred);
            case IPersistentSet s -> scanSet(s, addMatch, pred);
            case ISeq s -> scanSeq(s, addMatch, pred);
            case Sequential s -> scanSeq(RT.seq(s), addMatch, pred);
            default -> scanScalar(obj, addMatch, pred);
        };
    }

    private static boolean scanMap(IPersistentMap m, IFn addMatch, IFn pred) {
        Iterator<?> iter = (m instanceof IMapIterable mi) ? mi.valIterator() : RT.iter(RT.vals(m));
        while (iter.hasNext()) {
            if (scanWhen(iter.next(), addMatch, pred)) return true;
        }
        if (RT.booleanCast(pred.invoke(m))) {
            return RT.isReduced(addMatch.invoke(m));
        }
        return false;
    }

    private static boolean scanVector(IPersistentVector v, IFn addMatch, IFn pred) {
        int count = v.count();
        for (int i = 0; i < count; i++) {
            if (scanWhen(v.nth(i), addMatch, pred)) return true;
        }
        if (RT.booleanCast(pred.invoke(v))) {
            return RT.isReduced(addMatch.invoke(v));
        }
        return false;
    }

    private static boolean scanSet(IPersistentSet s, IFn addMatch, IFn pred) {
        for (Object elem : (Iterable<?>) s) {
            if (scanWhen(elem, addMatch, pred)) return true;
        }
        if (RT.booleanCast(pred.invoke(s))) {
            return RT.isReduced(addMatch.invoke(s));
        }
        return false;
    }

    private static boolean scanSeq(ISeq s, IFn addMatch, IFn pred) {
        if (s == null) {
            return scanScalar(null, addMatch, pred);
        }
        Object originalColl = s;
        while (s != null) {
            if (scanWhen(s.first(), addMatch, pred)) return true;
            s = s.next();
        }
        if (RT.booleanCast(pred.invoke(originalColl))) {
            return RT.isReduced(addMatch.invoke(originalColl));
        }
        return false;
    }

    private static boolean scanScalar(Object obj, IFn addMatch, IFn pred) {
        if (RT.booleanCast(pred.invoke(obj))) {
            return RT.isReduced(addMatch.invoke(obj));
        }
        return false;
    }
}
