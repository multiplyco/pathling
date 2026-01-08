# Changelog

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