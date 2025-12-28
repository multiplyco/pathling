package co.multiply.pathling;

import clojure.lang.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

/**
 * High-performance scanner for Pathling using Java 21+ pattern matching.
 *
 * Uses pattern switch for type dispatch with specialized methods for each
 * concrete map type to eliminate runtime type checks in hot loops.
 */
public final class ScannerMatchesNav {
    private ScannerMatchesNav() {} // Prevent instantiation

    /**
     * Scan a data structure for values matching the predicate.
     *
     * @param obj     the data structure to scan
     * @param matches ArrayList to accumulate matching values (mutated)
     * @param pred    predicate function (Clojure IFn)
     * @return navigation structure, or null if no matches
     */
    public static Object pathWhen(Object obj, ArrayList<Object> matches, IFn pred) {
        return switch (obj) {
            case null -> pathScalar(null, matches, pred);
            case PersistentStructMap m -> pathMapStruct(m, matches, pred);
            case PersistentHashMap m -> pathHashMap(m, matches, pred);
            case PersistentArrayMap m -> pathArrayMap(m, matches, pred);
            case IPersistentMap m -> pathMapOther(m, matches, pred);
            case PersistentVector v -> pathPersistentVector(v, matches, pred);
            case IPersistentVector v -> pathVectorOther(v, matches, pred);
            case PersistentHashSet s -> pathHashSet(s, matches, pred);
            case IPersistentSet s -> pathSetOther(s, matches, pred);
            case ISeq s -> pathSeq(s, matches, pred);
            case Sequential s -> pathSeq(RT.seq(s), matches, pred);
            default -> pathScalar(obj, matches, pred);
        };
    }

    // ========================================================================
    // Map scanning - specialized for each concrete type
    // ========================================================================

    /**
     * Scan PersistentHashMap using iterator() for key+value together.
     * Always returns MapEditable (HashMap is IEditableCollection).
     */
    private static Object pathHashMap(PersistentHashMap m, ArrayList<Object> matches, IFn pred) {
        ArrayList<Nav.KeyNav> childNavs = null;

        for (Object o : m) {
            Map.Entry<?,?> e = (Map.Entry<?,?>) o;
            Object k = e.getKey();
            Object v = e.getValue();

            Nav.Updatable nav = (Nav.Updatable) pathWhen(v, matches, pred);
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
     * Scan PersistentArrayMap using keyIterator() to avoid MapEntry allocation.
     * Always returns MapEditable (ArrayMap is IEditableCollection).
     */
    private static Object pathArrayMap(PersistentArrayMap m, ArrayList<Object> matches, IFn pred) {
        ArrayList<Nav.KeyNav> childNavs = null;

        Iterator<?> iter = m.keyIterator();
        while (iter.hasNext()) {
            Object k = iter.next();
            Object v = m.valAt(k);

            Nav.Updatable nav = (Nav.Updatable) pathWhen(v, matches, pred);
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
    private static Object pathMapOther(IPersistentMap m, ArrayList<Object> matches, IFn pred) {
        ArrayList<Nav.KeyNav> childNavs = null;

        Iterator<?> iter = (m instanceof IMapIterable mi) ? mi.keyIterator() : RT.iter(RT.keys(m));
        while (iter.hasNext()) {
            Object k = iter.next();
            Object v = m.valAt(k);

            Nav.Updatable nav = (Nav.Updatable) pathWhen(v, matches, pred);
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
    private static Object pathMapStruct(PersistentStructMap m, ArrayList<Object> matches, IFn pred) {
        Iterator<?> iter = RT.iter(RT.keys(m));
        ArrayList<Nav.Val> childNavs = null;

        while (iter.hasNext()) {
            Object k = iter.next();
            Object v = m.valAt(k);
            Nav.Updatable nav = (Nav.Updatable) pathWhen(v, matches, pred);

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
    private static Object pathPersistentVector(PersistentVector v, ArrayList<Object> matches, IFn pred) {
        int count = v.count();
        ArrayList<Nav.Pos> childNavs = null;

        for (int i = 0; i < count; i++) {
            Object elem = v.nth(i);
            Nav.Updatable nav = (Nav.Updatable) pathWhen(elem, matches, pred);
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
    private static Object pathVectorOther(IPersistentVector v, ArrayList<Object> matches, IFn pred) {
        int count = v.count();
        ArrayList<Nav.Pos> childNavs = null;

        for (int i = 0; i < count; i++) {
            Object elem = v.nth(i);
            Nav.Updatable nav = (Nav.Updatable) pathWhen(elem, matches, pred);
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
    private static Object pathHashSet(PersistentHashSet s, ArrayList<Object> matches, IFn pred) {
        Iterator<?> iter = RT.iter(s);
        ArrayList<Nav.Mem> childNavs = null;

        while (iter.hasNext()) {
            Object elem = iter.next();
            Nav.Updatable nav = (Nav.Updatable) pathWhen(elem, matches, pred);
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
    private static Object pathSetOther(IPersistentSet s, ArrayList<Object> matches, IFn pred) {
        Iterator<?> iter = RT.iter(s);
        ArrayList<Nav.Mem> childNavs = null;

        while (iter.hasNext()) {
            Object elem = iter.next();
            Nav.Updatable nav = (Nav.Updatable) pathWhen(elem, matches, pred);
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

    private static Object pathSeq(ISeq s, ArrayList<Object> matches, IFn pred) {
        if (s == null) return pathScalar(null, matches, pred);

        Object originalColl = s;
        int idx = 0;
        ArrayList<Nav.Pos> childNavs = null;

        while (s != null) {
            Object elem = s.first();
            Nav.Updatable nav = (Nav.Updatable) pathWhen(elem, matches, pred);
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

    private static Object pathScalar(Object obj, ArrayList<Object> matches, IFn pred) {
        if (RT.booleanCast(pred.invoke(obj))) {
            matches.add(obj);
            return Nav.Scalar.INSTANCE;
        }
        return null;
    }

}
