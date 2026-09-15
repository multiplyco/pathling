package co.multiply.pathling;

import clojure.lang.*;
import java.util.Map;

/**
 * Scanner that collects matching values (including map keys) with transducer support.
 * Used for read-only find operations with key matching and transformation/filtering.
 *
 * Supports early termination via reduced values.
 * For the fast path without transducers, use ScannerMatchesKeys.
 */
public final class ScannerMatchesKeysXf {
    private ScannerMatchesKeysXf() {} // Prevent instantiation

    /**
     * Scan a data structure for values matching the predicate (including map keys),
     * with transducer support.
     *
     * @param obj  the data structure to scan
     * @param pred predicate function (Clojure IFn)
     * @param xf   transducer to apply to matches
     * @return vector of matching values (transformed by xf), or null if no matches
     */
    public static IPersistentVector matchesWhen(Object obj, IFn pred, IFn xf) {
        MatchReducer matches = new MatchReducer(xf);
        scanWhen(obj, matches, pred);
        return matches.complete();
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
            case Sequential s -> scanSeq(s, addMatch, pred);
            default -> scanScalar(obj, addMatch, pred);
        };
    }

    private static boolean scanMap(IPersistentMap m, IFn addMatch, IFn pred) {
        for (Object o : m) {
            Map.Entry<?,?> e = (Map.Entry<?,?>) o;
            Object k = e.getKey();
            Object v = e.getValue();

            if (scanWhen(v, addMatch, pred)) return true;
            if (RT.booleanCast(pred.invoke(k))) {
                if (RT.isReduced(addMatch.invoke(k))) return true;
            }
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

    private static boolean scanSeq(Object originalColl, IFn addMatch, IFn pred) {
        ISeq s = RT.seq(originalColl);
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
