package co.multiply.pathling;

import clojure.lang.*;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Navigation structures for Pathling.
 *
 * These types record the path to matching elements in a data structure,
 * enabling efficient targeted updates without re-scanning.
 *
 * All navigation types implement {@link Updatable} for efficient virtual dispatch
 * during the update phase.
 */
public final class Nav {
    private Nav() {} // Prevent instantiation

    /**
     * Sentinel value indicating an element should be removed from its parent collection.
     * Using a unique object ensures identity comparison works correctly.
     */
    public static final Object REMOVE = new Object() {
        @Override
        public String toString() {
            return "Nav.REMOVE";
        }
    };

    /**
     * Interface for applying updates through navigation structure.
     * Implemented by all Nav types for efficient virtual dispatch.
     */
    public sealed interface Updatable
        permits MapEditable, MapPersistent, MapStruct, VecEdit, VecPersistent,
                SeqNav, SetEdit, SetPersistent, Scalar {
        /**
         * Apply replacer to all locations identified by this navigation.
         *
         * @param data the data structure to update
         * @param r the replacer strategy
         * @return the updated data structure
         */
        Object applyUpdates(Object data, Replacer r);
    }

    // ========================================================================
    // Map entry navigation types (sealed for exhaustive pattern matching)
    // ========================================================================

    /** Navigation for map entries - distinguishes value-only, key-only, and both */
    public sealed interface KeyNav permits Val, Key, KeyVal {}

    /** Navigate into value only (key unchanged) */
    public record Val(Object key, Updatable child) implements KeyNav {}

    /** Transform key only (value unchanged) */
    public record Key(Object key) implements KeyNav {}

    /** Transform key and navigate into value */
    public record KeyVal(Object key, Updatable child) implements KeyNav {}

    // ========================================================================
    // Collection-level navigation types
    // ========================================================================

    /**
     * Navigation for editable maps (supports transients).
     * Can contain Val, Key, or KeyVal entries.
     * When hasKeyTransforms is false, all children are Val and single-pass update is used.
     */
    public record MapEditable(ArrayList<KeyNav> children, boolean terminal, boolean hasKeyTransforms) implements Updatable {
        @Override
        public Object applyUpdates(Object data, Replacer r) {
            IPersistentMap m = (IPersistentMap) data;
            Object updated;
            if (children != null) {
                ITransientMap tm = (ITransientMap) ((IEditableCollection) m).asTransient();
                int n = children.size();

                if (hasKeyTransforms) {
                    // Two-pass: keys may change, so remove all first then add back
                    for (int i = 0; i < n; i++) {
                        Object k = switch (children.get(i)) {
                            case Val v -> v.key();
                            case Key k2 -> k2.key();
                            case KeyVal kv -> kv.key();
                        };
                        tm = tm.without(k);
                    }
                    for (int i = 0; i < n; i++) {
                        switch (children.get(i)) {
                            case Val v -> {
                                Object value = v.child().applyUpdates(RT.get(m, v.key()), r);
                                if (value != REMOVE) {
                                    tm = tm.assoc(v.key(), value);
                                }
                            }
                            case Key k -> {
                                Object newK = r.replace(k.key());
                                if (newK != REMOVE) {
                                    tm = tm.assoc(newK, RT.get(m, k.key()));
                                }
                            }
                            case KeyVal kv -> {
                                Object newK = r.replace(kv.key());
                                Object value = kv.child().applyUpdates(RT.get(m, kv.key()), r);
                                if (newK != REMOVE && value != REMOVE) {
                                    tm = tm.assoc(newK, value);
                                }
                            }
                        }
                    }
                } else {
                    // Single-pass: all children are Val, keys don't change
                    for (int i = 0; i < n; i++) {
                        Val v = (Val) children.get(i);
                        Object value = v.child().applyUpdates(RT.get(m, v.key()), r);
                        if (value != REMOVE) {
                            tm = tm.assoc(v.key(), value);
                        } else {
                            tm = tm.without(v.key());
                        }
                    }
                }
                updated = withMeta(tm.persistent(), RT.meta(m));
            } else {
                updated = m;
            }
            return terminal ? r.replace(updated) : updated;
        }
    }

    /**
     * Navigation for non-editable maps (no transient support).
     * Can contain Val, Key, or KeyVal entries.
     * When hasKeyTransforms is false, all children are Val and single-pass update is used.
     */
    public record MapPersistent(ArrayList<KeyNav> children, boolean terminal, boolean hasKeyTransforms) implements Updatable {
        @Override
        public Object applyUpdates(Object data, Replacer r) {
            IPersistentMap m = (IPersistentMap) data;
            Object updated;
            if (children != null) {
                IPersistentMap result = m;
                int n = children.size();

                if (hasKeyTransforms) {
                    // Two-pass: keys may change, so remove all first then add back
                    for (int i = 0; i < n; i++) {
                        Object k = switch (children.get(i)) {
                            case Val v -> v.key();
                            case Key k2 -> k2.key();
                            case KeyVal kv -> kv.key();
                        };
                        result = result.without(k);
                    }
                    for (int i = 0; i < n; i++) {
                        switch (children.get(i)) {
                            case Val v -> {
                                Object value = v.child().applyUpdates(RT.get(m, v.key()), r);
                                if (value != REMOVE) {
                                    result = result.assoc(v.key(), value);
                                }
                            }
                            case Key k -> {
                                Object newK = r.replace(k.key());
                                if (newK != REMOVE) {
                                    result = result.assoc(newK, RT.get(m, k.key()));
                                }
                            }
                            case KeyVal kv -> {
                                Object newK = r.replace(kv.key());
                                Object value = kv.child().applyUpdates(RT.get(m, kv.key()), r);
                                if (newK != REMOVE && value != REMOVE) {
                                    result = result.assoc(newK, value);
                                }
                            }
                        }
                    }
                } else {
                    // Single-pass: all children are Val, keys don't change
                    for (int i = 0; i < n; i++) {
                        Val v = (Val) children.get(i);
                        Object value = v.child().applyUpdates(RT.get(m, v.key()), r);
                        if (value != REMOVE) {
                            result = result.assoc(v.key(), value);
                        } else {
                            result = result.without(v.key());
                        }
                    }
                }
                // Persistent operations preserve metadata, no need to restore
                updated = result;
            } else {
                updated = m;
            }
            return terminal ? r.replace(updated) : updated;
        }
    }

    /**
     * Navigation for struct maps (fixed keys, no dissoc, no transients).
     * Only contains Val entries since struct maps have fixed keys.
     */
    public record MapStruct(ArrayList<Val> children, boolean terminal) implements Updatable {
        @Override
        public Object applyUpdates(Object data, Replacer r) {
            IPersistentMap m = (IPersistentMap) data;
            Object updated;
            if (children != null) {
                IPersistentMap result = m;
                int n = children.size();
                for (int i = 0; i < n; i++) {
                    Val v = children.get(i);
                    Object value = v.child().applyUpdates(RT.get(m, v.key()), r);
                    // Struct maps don't support dissoc, so REMOVE becomes assoc nil
                    result = result.assoc(v.key(), value == REMOVE ? null : value);
                }
                updated = result;
            } else {
                updated = m;
            }
            return terminal ? r.replace(updated) : updated;
        }
    }

    /**
     * Navigation for editable vectors (supports transients).
     */
    public record VecEdit(ArrayList<Pos> children, boolean terminal) implements Updatable {
        @Override
        public Object applyUpdates(Object data, Replacer r) {
            IPersistentVector v = (IPersistentVector) data;
            Object updated;
            if (children != null) {
                ITransientVector tv = (ITransientVector) ((IEditableCollection) v).asTransient();
                ArrayList<Integer> removals = null;
                int n = children.size();
                for (int i = 0; i < n; i++) {
                    Pos p = children.get(i);
                    Object result = p.child().applyUpdates(v.nth(p.index()), r);
                    if (result == REMOVE) {
                        if (removals == null) removals = new ArrayList<>();
                        removals.add(p.index());
                    } else {
                        tv = tv.assocN(p.index(), result);
                    }
                }
                updated = VecRemover.removeIndices((IPersistentVector) tv.persistent(), removals, RT.meta(v));
            } else {
                updated = v;
            }
            return terminal ? r.replace(updated) : updated;
        }
    }

    /**
     * Navigation for non-editable vectors (subvec, etc).
     * Uses ArrayList to avoid intermediate vector allocations from assocN.
     */
    public record VecPersistent(ArrayList<Pos> children, boolean terminal) implements Updatable {
        @Override
        public Object applyUpdates(Object data, Replacer r) {
            IPersistentVector v = (IPersistentVector) data;
            Object updated;
            if (children != null) {
                int count = v.count();
                ArrayList<Object> list = new ArrayList<>(count);
                int n = children.size();
                int prevEnd = 0;

                for (int i = 0; i < n; i++) {
                    Pos p = children.get(i);
                    int idx = p.index();

                    // Copy unchanged elements from prevEnd to idx
                    for (int j = prevEnd; j < idx; j++) {
                        list.add(v.nth(j));
                    }

                    // Process this child
                    Object value = p.child().applyUpdates(v.nth(idx), r);
                    if (value != REMOVE) {
                        list.add(value);
                    }

                    prevEnd = idx + 1;
                }

                // Copy remaining elements
                for (int j = prevEnd; j < count; j++) {
                    list.add(v.nth(j));
                }

                // Convert to vector with original metadata
                updated = PersistentVector.create(list).withMeta(RT.meta(v));
            } else {
                updated = v;
            }
            return terminal ? r.replace(updated) : updated;
        }
    }

    /**
     * Navigation for lists and other sequential collections.
     * Uses ArrayList for O(1) indexed updates, avoiding intermediate PersistentVector.
     */
    public record SeqNav(ArrayList<Pos> children, boolean terminal, int length) implements Updatable {
        @Override
        public Object applyUpdates(Object data, Replacer r) {
            // Materialize to ArrayList for O(1) indexed access
            ArrayList<Object> list;
            if (data instanceof Collection<?> coll) {
                list = new ArrayList<>(coll);
            } else {
                // Fallback: preallocate using known length
                list = new ArrayList<>(length);
                for (ISeq s = RT.seq(data); s != null; s = s.next()) {
                    list.add(s.first());
                }
            }

            Object updated;
            if (children != null) {
                ArrayList<Integer> removals = null;
                int n = children.size();

                for (int i = 0; i < n; i++) {
                    Pos p = children.get(i);
                    Object result = p.child().applyUpdates(list.get(p.index()), r);
                    if (result == REMOVE) {
                        if (removals == null) removals = new ArrayList<>();
                        removals.add(p.index());
                    } else {
                        list.set(p.index(), result);
                    }
                }

                // Build filtered list if removals, otherwise use list directly
                ArrayList<Object> filtered;
                if (removals != null) {
                    filtered = new ArrayList<>(list.size() - removals.size());
                    int removePtr = 0;
                    for (int i = 0; i < list.size(); i++) {
                        if (removePtr < removals.size() && removals.get(removePtr) == i) {
                            removePtr++;
                        } else {
                            filtered.add(list.get(i));
                        }
                    }
                } else {
                    filtered = list;
                }

                // Cons in reverse to build PersistentList
                IPersistentList result = PersistentList.EMPTY;
                for (int i = filtered.size() - 1; i >= 0; i--) {
                    result = (IPersistentList) result.cons(filtered.get(i));
                }
                updated = withMeta(result, RT.meta(data));
            } else {
                updated = data;
            }
            return terminal ? r.replace(updated) : updated;
        }
    }

    /**
     * Navigation for editable sets (supports transients).
     */
    public record SetEdit(ArrayList<Mem> children, boolean terminal) implements Updatable {
        @Override
        public Object applyUpdates(Object data, Replacer r) {
            IPersistentSet set = (IPersistentSet) data;
            Object updated;
            if (children != null) {
                ITransientSet ts = (ITransientSet) ((IEditableCollection) set).asTransient();
                // First remove all members that will be transformed
                int n = children.size();
                for (int i = 0; i < n; i++) {
                    ts = ts.disjoin(children.get(i).member());
                }
                // Then add back transformed values
                for (int i = 0; i < n; i++) {
                    Mem m = children.get(i);
                    Object result = m.child().applyUpdates(m.member(), r);
                    if (result != REMOVE) {
                        ts = (ITransientSet) ts.conj(result);
                    }
                }
                updated = withMeta(ts.persistent(), RT.meta(set));
            } else {
                updated = set;
            }
            return terminal ? r.replace(updated) : updated;
        }
    }

    /**
     * Navigation for non-editable sets (sorted-set, etc).
     */
    public record SetPersistent(ArrayList<Mem> children, boolean terminal) implements Updatable {
        @Override
        public Object applyUpdates(Object data, Replacer r) {
            IPersistentSet set = (IPersistentSet) data;
            Object updated;
            if (children != null) {
                // First remove all members that will be transformed
                IPersistentSet result = set;
                int n = children.size();
                for (int i = 0; i < n; i++) {
                    result = result.disjoin(children.get(i).member());
                }
                // Then add back transformed values
                for (int i = 0; i < n; i++) {
                    Mem m = children.get(i);
                    Object value = m.child().applyUpdates(m.member(), r);
                    if (value != REMOVE) {
                        result = (IPersistentSet) result.cons(value);
                    }
                }
                // Persistent operations preserve metadata, no need to restore
                updated = result;
            } else {
                updated = set;
            }
            return terminal ? r.replace(updated) : updated;
        }
    }

    // ========================================================================
    // Element-level navigation types (used within collections)
    // ========================================================================

    /**
     * Navigation for positional elements (vectors, lists).
     */
    public record Pos(int index, Updatable child) {}

    /**
     * Navigation for set members.
     */
    public record Mem(Object member, Updatable child) {}

    // ========================================================================
    // Terminal navigation
    // ========================================================================

    /**
     * Singleton representing a terminal match (scalar that matched predicate).
     */
    public record Scalar() implements Updatable {
        public static final Scalar INSTANCE = new Scalar();

        @Override
        public Object applyUpdates(Object data, Replacer r) {
            return r.replace(data);
        }

        @Override
        public String toString() {
            return "Nav.SCALAR";
        }
    }

    /** For backwards compatibility */
    public static final Updatable SCALAR = Scalar.INSTANCE;

    // ========================================================================
    // Helper methods
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static <T> T withMeta(Object obj, IPersistentMap meta) {
        if (meta != null && obj instanceof IObj o) {
            return (T) o.withMeta(meta);
        }
        return (T) obj;
    }

    /**
     * Helper class for removing indices from vectors efficiently.
     */
    static final class VecRemover {
        /**
         * Remove elements at specified indices from a vector.
         * Indices must be sorted ascending and unique.
         * Uses direct array adoption for ≤32 elements, transient for larger.
         */
        static IPersistentVector removeIndices(IPersistentVector v, ArrayList<Integer> indices, IPersistentMap meta) {
            if (indices == null || indices.isEmpty()) {
                return meta != null ? (IPersistentVector) ((IObj) v).withMeta(meta) : v;
            }

            int end = v.count();
            int finalSize = end - indices.size();
            IPersistentVector finalResult;

            if (finalSize <= 32) {
                // Direct array adoption - no intermediate allocations
                Object[] arr = new Object[finalSize];
                int pos = 0;
                int prevEnd = 0;

                for (int removeIdx : indices) {
                    for (int j = prevEnd; j < removeIdx; j++) {
                        arr[pos++] = v.nth(j);
                    }
                    prevEnd = removeIdx + 1;
                }
                for (int j = prevEnd; j < end; j++) {
                    arr[pos++] = v.nth(j);
                }

                finalResult = PersistentVector.adopt(arr);
            } else {
                // Transient for larger vectors
                ITransientVector tv = PersistentVector.EMPTY.asTransient();
                int prevEnd = 0;

                for (int removeIdx : indices) {
                    for (int j = prevEnd; j < removeIdx; j++) {
                        tv = (ITransientVector) tv.conj(v.nth(j));
                    }
                    prevEnd = removeIdx + 1;
                }
                for (int j = prevEnd; j < end; j++) {
                    tv = (ITransientVector) tv.conj(v.nth(j));
                }

                finalResult = (IPersistentVector) tv.persistent();
            }

            return meta != null ? (IPersistentVector) ((IObj) finalResult).withMeta(meta) : finalResult;
        }
    }
}
