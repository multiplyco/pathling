package co.multiply.pathling;

import java.util.List;

/**
 * Replacer that returns values sequentially from a pre-computed list.
 *
 * The list is traversed in order, with each call to {@link #replace(Object)}
 * returning the next element. The input value is ignored since replacements
 * are determined by position, not by the original value.
 *
 * This enables efficient bulk updates where replacements are computed
 * externally (e.g., in parallel) and collected into a list that matches
 * the traversal order of path-when.
 *
 * Thread-safety: NOT thread-safe. Each instance should be used by a single
 * thread for a single update-paths traversal.
 */
public final class ListReplacer implements Replacer {
    private final List<?> replacements;
    private int idx = 0;

    public ListReplacer(List<?> replacements) {
        this.replacements = replacements;
    }

    @Override
    public Object replace(Object v) {
        return replacements.get(idx++);
    }
}
