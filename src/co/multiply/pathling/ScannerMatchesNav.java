package co.multiply.pathling;

import clojure.lang.*;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * High-performance scanner for Pathling using Java 21+ pattern matching.
 *
 * Uses pattern switch for type dispatch with specialized methods for each
 * concrete map type to eliminate runtime type checks in hot loops.
 */
public final class ScannerMatchesNav {
    private ScannerMatchesNav() {} // Prevent instantiation

    /**
     * One callback per scan, shared by all hash maps in that scan. The reduction
     * accumulator owns each map's child list, so nested maps need no mutable
     * callback stack and predicates can safely start independent scans.
     */
    private static final class HashMapReducer extends AFn {
        private final ArrayList<Object> matches;
        private final IFn pred;

        private HashMapReducer(ArrayList<Object> matches, IFn pred) {
            this.matches = matches;
            this.pred = pred;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object children, Object key, Object value) {
            Nav.Updatable nav = pathWhenInternal(value, matches, pred, this);
            if (nav == null) return children;

            ArrayList<Nav.KeyNav> childNavs = (ArrayList<Nav.KeyNav>) children;
            if (childNavs == null) childNavs = new ArrayList<>();
            childNavs.add(new Nav.Val(key, nav));
            return childNavs;
        }
    }

    /**
     * Scan a data structure for values matching the predicate.
     *
     * @param obj     the data structure to scan
     * @param matches ArrayList to accumulate matching values (mutated)
     * @param pred    predicate function (Clojure IFn)
     * @return navigation structure, or null if no matches
     */
    public static Object pathWhen(Object obj, ArrayList<Object> matches, IFn pred) {
        return pathWhenInternal(obj, matches, pred, new HashMapReducer(matches, pred));
    }

    private static Nav.Updatable pathWhenInternal(Object obj, ArrayList<Object> matches, IFn pred, HashMapReducer hashMaps) {
        return switch (obj) {
            case null -> pathScalar(null, matches, pred);
            case PersistentStructMap m -> pathMapStruct(m, matches, pred, hashMaps);
            case PersistentHashMap m -> pathHashMap(m, matches, pred, hashMaps);
            case PersistentArrayMap m -> pathArrayMap(m, matches, pred, hashMaps);
            case IPersistentMap m -> pathMapOther(m, matches, pred, hashMaps);
            case PersistentVector v -> pathPersistentVector(v, matches, pred, hashMaps);
            case IPersistentVector v -> pathVectorOther(v, matches, pred, hashMaps);
            case PersistentHashSet s -> pathHashSet(s, matches, pred, hashMaps);
            case IPersistentSet s -> pathSetOther(s, matches, pred, hashMaps);
            case ISeq s -> pathSeq(s, matches, pred, hashMaps);
            case Sequential s -> pathSeq(s, matches, pred, hashMaps);
            default -> pathScalar(obj, matches, pred);
        };
    }

    // ========================================================================
    // Map scanning - specialized for each concrete type
    // ========================================================================

    /**
     * Scan PersistentHashMap through its native key/value reduction.
     * Always returns MapEditable (HashMap is IEditableCollection).
     */
    @SuppressWarnings("unchecked")
    private static Nav.Updatable pathHashMap(PersistentHashMap m, ArrayList<Object> matches, IFn pred, HashMapReducer hashMaps) {
        ArrayList<Nav.KeyNav> childNavs = (ArrayList<Nav.KeyNav>) m.kvreduce(hashMaps, null);

        boolean predRes = RT.booleanCast(pred.invoke(m));
        if (predRes) matches.add(m);

        if (childNavs != null || predRes) {
            return new Nav.MapEditable(childNavs, predRes, false);
        }
        return null;
    }

    /**
     * Scan PersistentArrayMap using keyIterator() to avoid MapEntry allocation.
     * Always returns MapEditable (ArrayMap is IEditableCollection).
     */
    private static Nav.Updatable pathArrayMap(PersistentArrayMap m, ArrayList<Object> matches, IFn pred, HashMapReducer hashMaps) {
        ArrayList<Nav.KeyNav> childNavs = null;

        Iterator<?> iter = m.keyIterator();
        while (iter.hasNext()) {
            Object k = iter.next();
            Object v = m.valAt(k);

            Nav.Updatable nav = pathWhenInternal(v, matches, pred, hashMaps);
            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Val(k, nav));
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(m));
        if (predRes) matches.add(m);

        if (childNavs != null || predRes) {
            return new Nav.MapEditable(childNavs, predRes, false);
        }
        return null;
    }

    /**
     * Scan other map types (TreeMap, etc) using keyIterator().
     * Always returns MapPersistent (these types are not IEditableCollection).
     */
    private static Nav.Updatable pathMapOther(IPersistentMap m, ArrayList<Object> matches, IFn pred, HashMapReducer hashMaps) {
        ArrayList<Nav.KeyNav> childNavs = null;

        Iterator<?> iter = (m instanceof IMapIterable mi) ? mi.keyIterator() : RT.iter(RT.keys(m));
        while (iter.hasNext()) {
            Object k = iter.next();
            Object v = m.valAt(k);

            Nav.Updatable nav = pathWhenInternal(v, matches, pred, hashMaps);
            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Val(k, nav));
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(m));
        if (predRes) matches.add(m);

        if (childNavs != null || predRes) {
            return new Nav.MapPersistent(childNavs, predRes, false);
        }
        return null;
    }

    /**
     * Scan struct maps. Keys are fixed, never transform keys.
     * Always returns MapStruct.
     */
    private static Nav.Updatable pathMapStruct(PersistentStructMap m, ArrayList<Object> matches, IFn pred, HashMapReducer hashMaps) {
        Iterator<?> iter = RT.iter(RT.keys(m));
        ArrayList<Nav.Val> childNavs = null;

        while (iter.hasNext()) {
            Object k = iter.next();
            Object v = m.valAt(k);
            Nav.Updatable nav = pathWhenInternal(v, matches, pred, hashMaps);

            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Val(k, nav));
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(m));
        if (predRes) matches.add(m);

        if (childNavs != null || predRes) {
            return new Nav.MapStruct(childNavs, predRes);
        }
        return null;
    }

    // ========================================================================
    // Vector scanning - specialized for concrete types
    // ========================================================================

    /**
     * Scan PersistentVector. Always returns VecEdit (PersistentVector is IEditableCollection).
     */
    private static Nav.Updatable pathPersistentVector(PersistentVector v, ArrayList<Object> matches, IFn pred, HashMapReducer hashMaps) {
        int count = v.count();
        ArrayList<Nav.Pos> childNavs = null;

        for (int i = 0; i < count; i++) {
            Object elem = v.nth(i);
            Nav.Updatable nav = pathWhenInternal(elem, matches, pred, hashMaps);
            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Pos(i, nav));
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(v));
        if (predRes) matches.add(v);

        if (childNavs != null || predRes) {
            return new Nav.VecEdit(childNavs, predRes);
        }
        return null;
    }

    /**
     * Scan other vector types (SubVector, etc). Always returns VecPersistent.
     */
    private static Nav.Updatable pathVectorOther(IPersistentVector v, ArrayList<Object> matches, IFn pred, HashMapReducer hashMaps) {
        int count = v.count();
        ArrayList<Nav.Pos> childNavs = null;

        for (int i = 0; i < count; i++) {
            Object elem = v.nth(i);
            Nav.Updatable nav = pathWhenInternal(elem, matches, pred, hashMaps);
            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Pos(i, nav));
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(v));
        if (predRes) matches.add(v);

        if (childNavs != null || predRes) {
            return new Nav.VecPersistent(childNavs, predRes);
        }
        return null;
    }

    // ========================================================================
    // Set scanning - specialized for concrete types
    // ========================================================================

    /**
     * Scan PersistentHashSet. Always returns SetEdit (PersistentHashSet is IEditableCollection).
     */
    private static Nav.Updatable pathHashSet(PersistentHashSet s, ArrayList<Object> matches, IFn pred, HashMapReducer hashMaps) {
        Iterator<?> iter = RT.iter(s);
        ArrayList<Nav.Mem> childNavs = null;

        while (iter.hasNext()) {
            Object elem = iter.next();
            Nav.Updatable nav = pathWhenInternal(elem, matches, pred, hashMaps);
            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Mem(elem, nav));
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(s));
        if (predRes) matches.add(s);

        if (childNavs != null || predRes) {
            return new Nav.SetEdit(childNavs, predRes);
        }
        return null;
    }

    /**
     * Scan other set types (PersistentTreeSet, etc). Always returns SetPersistent.
     */
    private static Nav.Updatable pathSetOther(IPersistentSet s, ArrayList<Object> matches, IFn pred, HashMapReducer hashMaps) {
        Iterator<?> iter = RT.iter(s);
        ArrayList<Nav.Mem> childNavs = null;

        while (iter.hasNext()) {
            Object elem = iter.next();
            Nav.Updatable nav = pathWhenInternal(elem, matches, pred, hashMaps);
            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Mem(elem, nav));
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(s));
        if (predRes) matches.add(s);

        if (childNavs != null || predRes) {
            return new Nav.SetPersistent(childNavs, predRes);
        }
        return null;
    }

    // ========================================================================
    // Sequential scanning (lists, lazy seqs, etc.)
    // ========================================================================

    private static Nav.Updatable pathSeq(Object originalColl, ArrayList<Object> matches, IFn pred, HashMapReducer hashMaps) {
        ISeq s = RT.seq(originalColl);
        int idx = 0;
        ArrayList<Nav.Pos> childNavs = null;

        while (s != null) {
            Object elem = s.first();
            Nav.Updatable nav = pathWhenInternal(elem, matches, pred, hashMaps);
            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Pos(idx, nav));
            }
            idx++;
            s = s.next();
        }

        boolean predRes = RT.booleanCast(pred.invoke(originalColl));
        if (predRes) matches.add(originalColl);

        if (childNavs != null || predRes) {
            return new Nav.SeqNav(childNavs, predRes, idx);
        }
        return null;
    }

    // ========================================================================
    // Scalar scanning
    // ========================================================================

    private static Nav.Updatable pathScalar(Object obj, ArrayList<Object> matches, IFn pred) {
        if (RT.booleanCast(pred.invoke(obj))) {
            matches.add(obj);
            return Nav.Scalar.INSTANCE;
        }
        return null;
    }

}
