package co.multiply.pathling;

import clojure.lang.*;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Scanner that collects matching values without building navigation structure.
 * Used for read-only find operations.
 *
 * This is the fast path with direct ArrayList access - no transducer support.
 * For transducer support, use ScannerMatchesXf.
 */
public final class ScannerMatches {
    private ScannerMatches() {} // Prevent instantiation

    /**
     * Scan a data structure for values matching the predicate.
     *
     * @param obj  the data structure to scan
     * @param pred predicate function (Clojure IFn)
     * @return vector of matching values, or null if no matches
     */
    public static IPersistentVector matchesWhen(Object obj, IFn pred) {
        ArrayList<Object> matches = new ArrayList<>();
        scanWhen(obj, matches, pred);
        return matches.isEmpty() ? null : PersistentVector.create(matches);
    }

    private static void scanWhen(Object obj, ArrayList<Object> matches, IFn pred) {
        switch (obj) {
            case null -> scanScalar(null, matches, pred);
            case IPersistentMap m -> scanMap(m, matches, pred);
            case IPersistentVector v -> scanVector(v, matches, pred);
            case IPersistentSet s -> scanSet(s, matches, pred);
            case ISeq s -> scanSeq(s, matches, pred);
            case Sequential s -> scanSeq(RT.seq(s), matches, pred);
            default -> scanScalar(obj, matches, pred);
        }
    }

    private static void scanMap(IPersistentMap m, ArrayList<Object> matches, IFn pred) {
        Iterator<?> iter = (m instanceof IMapIterable mi) ? mi.valIterator() : RT.iter(RT.vals(m));
        while (iter.hasNext()) {
            scanWhen(iter.next(), matches, pred);
        }
        if (RT.booleanCast(pred.invoke(m))) {
            matches.add(m);
        }
    }

    private static void scanVector(IPersistentVector v, ArrayList<Object> matches, IFn pred) {
        int count = v.count();
        for (int i = 0; i < count; i++) {
            scanWhen(v.nth(i), matches, pred);
        }
        if (RT.booleanCast(pred.invoke(v))) {
            matches.add(v);
        }
    }

    private static void scanSet(IPersistentSet s, ArrayList<Object> matches, IFn pred) {
        for (Object elem : (Iterable<?>) s) {
            scanWhen(elem, matches, pred);
        }
        if (RT.booleanCast(pred.invoke(s))) {
            matches.add(s);
        }
    }

    private static void scanSeq(ISeq s, ArrayList<Object> matches, IFn pred) {
        if (s == null) {
            scanScalar(null, matches, pred);
            return;
        }
        Object originalColl = s;
        while (s != null) {
            scanWhen(s.first(), matches, pred);
            s = s.next();
        }
        if (RT.booleanCast(pred.invoke(originalColl))) {
            matches.add(originalColl);
        }
    }

    private static void scanScalar(Object obj, ArrayList<Object> matches, IFn pred) {
        if (RT.booleanCast(pred.invoke(obj))) {
            matches.add(obj);
        }
    }
}
