# Changelog

## 0.2.1 - 2025-01-30

### Fixed

- Fix ClojureScript build failure due to stale reference to renamed `util` namespace.

## 0.2.0 - 2025-01-30

### Added

- `update-paths` now accepts a vector or accumulator of replacements in addition to a function. Values are applied
  sequentially in depth-first order matching `path-when`'s traversal.
- `:raw-matches` option for `path-when` returns the internal mutable accumulator (`ArrayList` on JVM, JS array on
  ClojureScript) instead of converting to a vector. Enables zero-allocation update patterns by mutating matches
  in-place and passing the same accumulator to `update-paths`.
- `acc-set!` macro in `co.multiply.pathling.accumulator` for setting values at an index in accumulators.

### Changed

- Internal: Introduced `Replacer` interface (JVM) and `IReplacer` protocol (ClojureScript) to abstract over
  function-based and collection-based replacement strategies.

## 0.1.8 - 2025-01-08

- Remove leftover ClojureScript dependency.

## 0.1.7 - 2025-01-02

Initial release.

- `path-when` - find all values matching predicate with navigation structure
- `find-when` - find values without navigation (more efficient for read-only)
- `transform-when` - find and transform in one step
- `update-paths` - apply function to locations identified by navigation
- `REMOVE` - sentinel for removing elements during transformation
- `:include-keys` option for matching/transforming map keys