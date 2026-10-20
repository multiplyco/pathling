# Possible development: skip traversal work for known shapes

Status: proposal recorded on 2026-10-19; deferred until after the current
correctness, performance and documentation branch. No API or implementation is
committed to by this note.

## Motivation

Callers sometimes know the logical shape of their data. For a restricted Hiccup
tree, vectors contain document children while an optional attributes map is
atomic. Walking through that map's values does unnecessary work and may find
values outside the intended document traversal. XML represented as maps has a
related need: its logical children live under `:content`.

Borrow the idea of declaring a tree's structure from zippers while retaining
Pathling's collect-then-update workflow. Cursor navigation (up, down, siblings)
and arbitrary node reconstruction are separate possible features.

## First increment: control descent

Explore a public constructor for a reusable traversal policy. Illustrative API:

```clojure
(def vector-tree
  (p/traversal {:descend? vector?}))

(let [{:keys [matches nav]}
      (p/path-when document element? {:traversal vector-tree})]
  (p/update-paths document nav (rewrite-elements matches)))
```

The match predicate and descent policy answer different questions. A collection
rejected by `:descend?` is still a visited value and can match, but its contents
are not visited. For this vector-only example, attributes maps, tags and text
are leaves. Lists and lazy sequences are also atomic; this is a restricted
vector-tree policy, not a definition of every Hiccup dialect. A matched node
does not itself stop traversal when descent is allowed.

Retain original keys and indices in navigation, using the existing reconstruction
machinery. The helper holds policy, not an input document or mutable per-scan
state. Navigation retains the information required by `update-paths`; callers
should not need to supply the traversal again. Existing rules about using
navigation with the scanned input still apply.

Keep the default specialized traversal and select the custom implementation at
the public API boundary. Measure callback and allocation overhead rather than
assuming work skipping always wins.

## Construction API and bundled helpers

Make the construction API the foundation. Begin with documented examples;
later bundled helpers should be ordinary definitions using that same API.
`vector-tree` has a narrow, explicit meaning. A helper named `hiccup` needs an
agreed policy for attributes, sequences of children, fragments and component
forms before it can promise broader support.

## Later increment: select child locations

Descent control alone cannot express following only an XML element's `:content`
or visiting only a Hiccup vector's suffix after its tag and optional attributes.
Explore child selection expressed as keys and index ranges. That preserves
physical locations for targeted updates and can exclude a child entirely,
whereas treating it atomically still offers it to the match predicate.

A generic zipper-style `branch?` / `children` / `make-node` interface could support
custom node types later. Returning children without their locations requires
additional reconstruction information and may enumerate unchanged siblings.
It is not necessary for the initial work-skipping feature.

## Questions and validation before implementation

- Settle how descent policy interacts with `:include-keys`, unsupported collection
  types and root values; avoid silently conflicting traversal options.
- Decide the initial API scope across `path-when`, `find-when` (including early
  termination), `transform-when`, Clojure and ClojureScript.
- Preserve original-value predicates, child-before-parent updates, match order,
  metadata, removal behavior and positional replacement correspondence.
- Verify rejected subtrees are never inspected, while their root can still match.
  Include empty branches, attributes containing otherwise matching nested values,
  overlapping parent/child matches, reentrant predicates and shared-helper scans.
- Compare ordinary traversal and the custom policy on both useful skipping cases
  and controls with little to skip. Check elapsed time and allocation, and verify
  the ordinary default path has no new per-node policy overhead.

Prototype the vector-only case first; use XML and more complete Hiccup examples
to evaluate whether child selection warrants a second increment.
