package co.multiply.pathling;

/**
 * Strategy interface for replacing values during update-paths traversal.
 *
 * Implementations provide different replacement strategies:
 * - {@link FunctionReplacer}: applies a function to each matched value
 * - {@link ListReplacer}: returns sequential values from a pre-computed list
 *
 * This abstraction allows update-paths to use a single code path regardless
 * of replacement strategy.
 */
public sealed interface Replacer permits FunctionReplacer, ListReplacer {
    /**
     * Compute the replacement for a matched value.
     *
     * @param v the original matched value
     * @return the replacement value (may be {@link Nav#REMOVE} to delete)
     */
    Object replace(Object v);
}
