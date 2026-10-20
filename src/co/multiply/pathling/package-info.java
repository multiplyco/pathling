/**
 * Internal JVM implementation of Pathling.
 *
 * <h2>Public API boundary</h2>
 * <p>The supported API is the documented Clojure/ClojureScript surface, including
 * the documented accumulator helpers. No Java type or member in this package is
 * a supported public API or extension point, regardless of Java visibility.
 * Classes, interfaces, records, constructors and methods may be renamed,
 * reorganized or replaced without preserving Java source or binary compatibility.
 *
 * <p>The {@code :nav} returned by {@code path-when} is opaque. Callers may retain
 * it and pass it unchanged to {@code update-paths} with the scanned input. They
 * must not depend on its concrete type, fields or representation, construct or
 * mutate its internals, or assume a supported serialization format. In contrast,
 * the raw match accumulator is intentionally mutable through the documented
 * accumulator operations; that does not expose navigation internals as API.
 *
 * <h2>Execution families</h2>
 * <p>The Clojure entry points select an execution family and key-matching variant
 * before recursive traversal. Each family performs only the work its operation
 * requires; specialized loops are intentional, not a duplication problem to
 * resolve by default with a common walker or additional per-node strategies.
 *
 * <table>
 * <caption>Scanner families and their responsibilities</caption>
 * <thead>
 * <tr><th scope="col">Values</th><th scope="col">Including keys</th>
 *     <th scope="col">Responsibility</th></tr>
 * </thead>
 * <tbody>
 * <tr><td>{@link co.multiply.pathling.ScannerMatches}</td>
 *     <td>{@link co.multiply.pathling.ScannerMatchesKeys}</td>
 *     <td>Collect matches for plain {@code find-when}; build no navigation.</td></tr>
 * <tr><td>{@link co.multiply.pathling.ScannerMatchesXf}</td>
 *     <td>{@link co.multiply.pathling.ScannerMatchesKeysXf}</td>
 *     <td>Transduce matches for {@code find-when}, supporting early termination.</td></tr>
 * <tr><td>{@link co.multiply.pathling.ScannerMatchesNav}</td>
 *     <td>{@link co.multiply.pathling.ScannerMatchesNavKeys}</td>
 *     <td>Collect matches and build navigation for {@code path-when}.</td></tr>
 * <tr><td>{@link co.multiply.pathling.Transform}</td>
 *     <td>{@link co.multiply.pathling.TransformKeys}</td>
 *     <td>Build navigation without collecting matches, then apply updates for
 *         {@code transform-when}.</td></tr>
 * </tbody>
 * </table>
 *
 * <p>Despite their names, {@code Transform} and {@code TransformKeys} contain
 * navigation-only scanners plus a small update wrapper. They use the same
 * navigation/update machinery as {@code path-when} and {@code update-paths};
 * they are not separate implementations of collection reconstruction.
 *
 * <h2>Dependencies and ownership</h2>
 * <p>Scanners inspect the original input and build collection-specific
 * {@link co.multiply.pathling.Nav} nodes where needed. Those nodes own the
 * reconstruction algorithms and recursively apply child navigation.
 * {@link co.multiply.pathling.Replacer} supplies a replacement at each match:
 * {@link co.multiply.pathling.FunctionReplacer} invokes a function, while
 * {@link co.multiply.pathling.ListReplacer} consumes prepared replacements in
 * order. Replacement dispatch therefore follows matches, not every inspected
 * value in the original scan.
 *
 * <p>Child lists belong to the scanner that builds them and must not be mutated
 * after navigation is returned. Navigation is reused with its scanned input;
 * rescan after changing the navigated structure. Reduction state belongs to one
 * transducing scan, and a positional replacement cursor belongs to one update.
 * Keep those reducer and cursor instances scoped to their invocation. Reusing a
 * raw replacement collection does not mean reusing its consumption cursor.
 *
 * <h2>Shared contracts when changing a specialization</h2>
 * <ul>
 * <li>Predicates inspect original values and collections. Empty collections have
 *     no child elements; a real {@code null} element remains a distinct visit.</li>
 * <li>Visit children before their collection. With key matching, visit an entry's
 *     value subtree before testing its key as a whole value, then test the map
 *     after its entries.</li>
 * <li>Replacement consumption must follow match order, including when keys
 *     collide or replacements remove elements. A parent replacement callback
 *     receives the collection with child updates already applied.</li>
 * <li>A null child list denotes no matching descendants. Positional navigation
 *     is in ascending index order; retained navigation follows matches and
 *     their ancestors.</li>
 * <li>Transducing scans stop on reduced state, propagate reduction state, and
 *     complete once. Completion preserves {@code find-when}'s vector-or-nil
 *     contract.</li>
 * </ul>
 *
 * <p>When a traversal rule changes, consider every affected family and both key
 * variants, and exercise the applicable shared conformance cases. Keep analogous
 * collection handlers organized consistently so those comparisons remain easy.
 * Validate specialization using correctness checks plus timing, allocation and
 * variability measurements; code deduplication is a secondary concern.
 */
package co.multiply.pathling;
