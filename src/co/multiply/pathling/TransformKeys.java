package co.multiply.pathling;

import clojure.lang.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

/**
 * Transform values (including map keys) matching a predicate in nested data structures.
 * Scans and applies updates in a single pass without collecting matches.
 */
public final class TransformKeys {
    private TransformKeys() {} // Prevent instantiation

    /**
     * Transform all values matching a predicate in a data structure, including map keys.
     *
     * @param obj  the data structure to transform
     * @param pred predicate function (Clojure IFn)
     * @param tf   transform function (Clojure IFn)
     * @return transformed data structure, or obj unchanged if no matches
     */
    public static Object transformWhen(Object obj, IFn pred, IFn tf) {
        Object nav = navWhen(obj, pred);
        if (nav == null) {
            return obj;
        }
        return ((Nav.Updatable) nav).applyUpdates(obj, new FunctionReplacer(tf));
    }

    // ========================================================================
    // Internal navigation building
    // ========================================================================

    private static Object navWhen(Object obj, IFn pred) {
        return switch (obj) {
            case null -> navScalar(null, pred);
            case PersistentStructMap m -> navMapStruct(m, pred);
            case PersistentHashMap m -> navHashMap(m, pred);
            case PersistentArrayMap m -> navArrayMap(m, pred);
            case IPersistentMap m -> navMapOther(m, pred);
            case PersistentVector v -> navPersistentVector(v, pred);
            case IPersistentVector v -> navVectorOther(v, pred);
            case PersistentHashSet s -> navHashSet(s, pred);
            case IPersistentSet s -> navSetOther(s, pred);
            case ISeq s -> navSeq(s, pred);
            case Sequential s -> navSeq(RT.seq(s), pred);
            default -> navScalar(obj, pred);
        };
    }

    // ========================================================================
    // Map scanning
    // ========================================================================

    private static Object navHashMap(PersistentHashMap m, IFn pred) {
        ArrayList<Nav.KeyNav> childNavs = null;
        boolean hasKeyTransforms = false;

        for (Object o : m) {
            Map.Entry<?,?> e = (Map.Entry<?,?>) o;
            Object k = e.getKey();
            Object v = e.getValue();

            Nav.Updatable nav = (Nav.Updatable) navWhen(v, pred);
            boolean termK = RT.booleanCast(pred.invoke(k));

            if (termK) {
                hasKeyTransforms = true;
            }

            if (nav != null || termK) {
                if (childNavs == null) childNavs = new ArrayList<>();
                if (nav != null && termK) {
                    childNavs.add(new Nav.KeyVal(k, nav));
                } else if (nav != null) {
                    childNavs.add(new Nav.Val(k, nav));
                } else {
                    childNavs.add(new Nav.Key(k));
                }
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(m));
        if (childNavs != null || predRes) {
            return new Nav.MapEditable(childNavs, predRes, hasKeyTransforms);
        }
        return null;
    }

    private static Object navArrayMap(PersistentArrayMap m, IFn pred) {
        ArrayList<Nav.KeyNav> childNavs = null;
        boolean hasKeyTransforms = false;

        Iterator<?> iter = m.keyIterator();
        while (iter.hasNext()) {
            Object k = iter.next();
            Object v = m.valAt(k);

            Nav.Updatable nav = (Nav.Updatable) navWhen(v, pred);
            boolean termK = RT.booleanCast(pred.invoke(k));

            if (termK) {
                hasKeyTransforms = true;
            }

            if (nav != null || termK) {
                if (childNavs == null) childNavs = new ArrayList<>();
                if (nav != null && termK) {
                    childNavs.add(new Nav.KeyVal(k, nav));
                } else if (nav != null) {
                    childNavs.add(new Nav.Val(k, nav));
                } else {
                    childNavs.add(new Nav.Key(k));
                }
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(m));
        if (childNavs != null || predRes) {
            return new Nav.MapEditable(childNavs, predRes, hasKeyTransforms);
        }
        return null;
    }

    private static Object navMapOther(IPersistentMap m, IFn pred) {
        ArrayList<Nav.KeyNav> childNavs = null;
        boolean hasKeyTransforms = false;

        Iterator<?> iter = (m instanceof IMapIterable mi) ? mi.keyIterator() : RT.iter(RT.keys(m));
        while (iter.hasNext()) {
            Object k = iter.next();
            Object v = m.valAt(k);

            Nav.Updatable nav = (Nav.Updatable) navWhen(v, pred);
            boolean termK = RT.booleanCast(pred.invoke(k));

            if (termK) {
                hasKeyTransforms = true;
            }

            if (nav != null || termK) {
                if (childNavs == null) childNavs = new ArrayList<>();
                if (nav != null && termK) {
                    childNavs.add(new Nav.KeyVal(k, nav));
                } else if (nav != null) {
                    childNavs.add(new Nav.Val(k, nav));
                } else {
                    childNavs.add(new Nav.Key(k));
                }
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(m));
        if (childNavs != null || predRes) {
            return new Nav.MapPersistent(childNavs, predRes, hasKeyTransforms);
        }
        return null;
    }

    private static Object navMapStruct(PersistentStructMap m, IFn pred) {
        ArrayList<Nav.Val> childNavs = null;

        Iterator<?> iter = RT.iter(RT.keys(m));
        while (iter.hasNext()) {
            Object k = iter.next();
            Object v = m.valAt(k);

            Nav.Updatable nav = (Nav.Updatable) navWhen(v, pred);
            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Val(k, nav));
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(m));
        if (childNavs != null || predRes) {
            return new Nav.MapStruct(childNavs, predRes);
        }
        return null;
    }

    // ========================================================================
    // Vector scanning
    // ========================================================================

    private static Object navPersistentVector(PersistentVector v, IFn pred) {
        ArrayList<Nav.Pos> childNavs = null;

        int count = v.count();
        for (int i = 0; i < count; i++) {
            Nav.Updatable nav = (Nav.Updatable) navWhen(v.nth(i), pred);
            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Pos(i, nav));
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(v));
        if (childNavs != null || predRes) {
            return new Nav.VecEdit(childNavs, predRes);
        }
        return null;
    }

    private static Object navVectorOther(IPersistentVector v, IFn pred) {
        ArrayList<Nav.Pos> childNavs = null;

        int count = v.count();
        for (int i = 0; i < count; i++) {
            Nav.Updatable nav = (Nav.Updatable) navWhen(v.nth(i), pred);
            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Pos(i, nav));
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(v));
        if (childNavs != null || predRes) {
            return new Nav.VecPersistent(childNavs, predRes);
        }
        return null;
    }

    // ========================================================================
    // Set scanning
    // ========================================================================

    private static Object navHashSet(PersistentHashSet s, IFn pred) {
        ArrayList<Nav.Mem> childNavs = null;

        for (Object elem : (Iterable<?>) s) {
            Nav.Updatable nav = (Nav.Updatable) navWhen(elem, pred);
            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Mem(elem, nav));
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(s));
        if (childNavs != null || predRes) {
            return new Nav.SetEdit(childNavs, predRes);
        }
        return null;
    }

    private static Object navSetOther(IPersistentSet s, IFn pred) {
        ArrayList<Nav.Mem> childNavs = null;

        for (Object elem : (Iterable<?>) s) {
            Nav.Updatable nav = (Nav.Updatable) navWhen(elem, pred);
            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Mem(elem, nav));
            }
        }

        boolean predRes = RT.booleanCast(pred.invoke(s));
        if (childNavs != null || predRes) {
            return new Nav.SetPersistent(childNavs, predRes);
        }
        return null;
    }

    // ========================================================================
    // Sequential scanning
    // ========================================================================

    private static Object navSeq(ISeq s, IFn pred) {
        if (s == null) return navScalar(null, pred);

        Object originalColl = s;
        int idx = 0;
        ArrayList<Nav.Pos> childNavs = null;

        while (s != null) {
            Nav.Updatable nav = (Nav.Updatable) navWhen(s.first(), pred);
            if (nav != null) {
                if (childNavs == null) childNavs = new ArrayList<>();
                childNavs.add(new Nav.Pos(idx, nav));
            }
            idx++;
            s = s.next();
        }

        boolean predRes = RT.booleanCast(pred.invoke(originalColl));
        if (childNavs != null || predRes) {
            return new Nav.SeqNav(childNavs, predRes, idx);
        }
        return null;
    }

    // ========================================================================
    // Scalar scanning
    // ========================================================================

    private static Object navScalar(Object obj, IFn pred) {
        if (RT.booleanCast(pred.invoke(obj))) {
            return Nav.Scalar.INSTANCE;
        }
        return null;
    }
}
