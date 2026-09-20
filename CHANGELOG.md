# Changelog

## Unreleased — resumable stable spatial query cursors and structural version validation

### New capability

Adds paged spatial queries with an opaque, serializable **resume token**. A
single traversal of the tree can be paused between pages and resumed later,
including from another process that holds an identical (logically equal)
tree. The public entry points are:

- `RTree.structureVersion()` — deterministic 64-bit content fingerprint of
  the tree (`StructureVersion`), lazily computed and memoized (the tree is
  immutable).
- `RTree.searchPage(...)` — first-page/resume overloads for
  `Rectangle`, `Point`, `Circle`, `Line`, a general `SearchQuery`, and a
  general query plus `Sort` (`pageSize` must be positive).
- `SearchQuery` — stable description of a spatial query: an `id`, an ordered
  string `parameters` map that fully describes built-in predicates, a
  conservative `nodeCondition`, and an optional leaf-level `entryFilter`.
  Factories: `intersects(Rectangle|Circle|Line)`, `within(Rectangle,
  maxDistance)`, `all()`, and `custom(id, condition[, filter])` for
  predicates that cannot be expressed as built-ins.
- `Sort.none()` (default, the existing depth-first order) and
  `Sort.ascendingDistance(Rectangle)` (stable; ties broken by DFS ordinal).
- `Page` — `entries()`, `nextResumeToken()` (`Optional`, absent when the
  traversal is exhausted), `hasNext()`.
- `ResumeToken` — `toBytes()` and URL-safe Base64 `encode()` / `parse(...)`.

### Implementation choices

- **DFS engine is a line-by-line port of the existing `Backpressure` stack
  discipline** (`CursorSearch` + the existing `NodePosition` and
  `ImmutableStack`). It was deliberately written alongside `Backpressure`
  rather than reusing the Rx `Subscriber` path so the continuation can be
  captured and rehydrated without changing the hot search path used by
  `search(...)`. Consequently the concatenation of DFS pages is, item by
  item, exactly `RTree.search(...)` output for every page size.
- **Continuation encoding.** The DFS stack is serialized as a root-to-leaf
  list of frames (`CursorPath`): each frame is the child index in its parent
  (`-1` for the root) and the node's internal cursor. While a child is on
  the stack the parent cursor equals that child's index (the parent is only
  advanced when the child is popped), which is what makes a single child
  index per frame sufficient to rehydrate exact node identity without
  hashing individual nodes. Rehydration validates every index/cursor range
  and the parent/child consistency, so a token forged against a different
  shape cannot address the wrong node.
- **Structural version** (`StructureVersion`) folds node kind, child counts
  and child order, every node MBR, every leaf entry geometry kind, precision
  tag and coordinates, and the entry value `hashCode()` into a streaming
  FNV-1a 64-bit hash. No object identity is used, so the value is
  deterministic across processes and across the `DEFAULT` (object graph) and
  `SINGLE_ARRAY` (FlatBuffers) node implementations. Float and double
  geometries that compare equal are intentionally distinguished.
- **Sorted paging** recomputes the deterministic total order
  `(ascending distance, DFS ordinal)` per page; the ordinal tie-break makes
  the order total, stable for duplicate geometries, and identical across
  structurally equal trees. Its continuation is an ordinal offset. This is
  bounded by the full match set (same order of work as the non-paged
  `nearest`) rather than streaming, because distance order is not
  depth-first; the token still binds tree version, predicate and sort.
- **Token layout**: magic bytes, format version, payload
  (structure version, query id, parameter map, sort identity, mode, emitted
  count, DFS frames or sorted offset) and a CRC32 over the payload. Decoding
  checks magic/version/length/crc and payload self-consistency before any
  tree is touched; tree/query/sort validation happens before a single entry
  is returned.
- **Fail closed**: structural mismatch, different query id, different
  predicate parameters, different sort (incl. mixing sorted/unsorted),
  corrupt/truncated/garbage tokens, or an inconsistent traversal frame all
  raise `ResumeTokenException` with diagnostic context (token vs current
  fingerprint, offending index/count, crc values, etc.). No partial page is
  ever returned from a rejected token.
- **Backwards compatibility**: only new methods/classes were added; existing
  `search(...)`, `entries()`, `nearest(...)` signatures, their default
  ordering, defaults and boundary behavior are untouched. `Sort.none()`
  preserves the exact current DFS order.

### Gap in the previous coverage

There was no notion of a resumable position: `OnSubscribeSearch`/
`Backpressure` hold the continuation in a volatile stack private to a live
Rx `Subscriber`, which cannot be externalized, recreated in another process,
or validated against a changed tree. Paging clients could only re-run the
whole query (duplicates/order dependent on a changing tree) or invent
offset schemes based on values, which break with duplicate geometries and
with tree mutations. Nothing asserted that partial traversal, when resumed,
reproduced the one-shot sequence exactly, and nothing tied a continuation
to the tree structure or predicate.

### Regression tests added

- `CursorPagingTest` (35 tests), `CursorFuzzTest` (3 property tests),
  `StructureVersionTest` (9), `ResumeTokenTest` (9).
- For every page size from `1` to `matches+1`, page concatenation equals the
  one-shot `search(...)` result item by item (grid, random quadratic, R*);
  same for `within`, circle and line intersection, and `all()`.
- Failure recovery: token used on a tree modified by add and by delete, with
  a different rectangle predicate, different query kind (`within` vs
  `intersects`), different custom-query id, different sort anchor, and
  sorted↔unsorted mix-ups all throw `ResumeTokenException`; bit-flips over
  every payload byte, truncated tokens, garbage strings and magic/version
  mutations are rejected with diagnostics.
- Boundaries: empty tree (DFS and sorted), no-match window, a last page that
  exactly fills the budget, `pageSize` validation, and invalid arguments.
- Duplicate geometries preserve all entries and stable DFS/tie order.
- R*-split multi-level trees (incl. 300 entries and fuzz across
  `maxChildren` 3/4/8, counts 0..90, 5 windows, both splitters).
- FlatBuffers round-trip: equal structural version, equal one-shot result,
  and a token encoded to a string on the original tree resumed on the
  `SINGLE_ARRAY` restored tree (DFS and sorted).
- Mid-stream resume with *changing* page sizes stays exactly correct.

### Adjacent-semantics degradation guards

- The old Rx search path (`OnSubscribeSearch`, `Backpressure`,
  `searchWithoutBackpressure`) is unchanged; `BackpressureTest`,
  `OnSubscribeSearchTest`, `RTreeTest` and all 311 pre-existing tests still
  pass unchanged (367 total now).
- Default ordering is preserved: `Sort.none()` walks exactly the
  `Backpressure` order; this is asserted against the observable result
  rather than against a newly defined order.
- New geometry predicates reuse the same pruning/refinement logic as the
  existing `search` overloads (circle/line prune on the MBR then refine on
  the exact geometry), so cursor results match the established methods.

### Most dangerous counterexample and its regression

The most dangerous failure for **R-tree traversal / resumable cursors /
structural versions** is a continuation frame that addresses the *wrong*
node after a split or child reorder: the token still deserializes and the
indexes stay in range, but the resumed DFS silently emits duplicates, skips
entries or reorders them — a corruption that is invisible unless pages are
stitched back together and compared, item by item, with a one-shot traversal
on the exact post-split shape. A close second is accepting the token against
a structurally modified tree (fingerprint mismatch ignored).

The direct regressions are:

- `CursorFuzzTest.testDfsPagingFuzzManyShapesAndPageSizes` — both splitters,
  `maxChildren` 3/4/8 (forcing splits/reinserts), multiple shapes and five
  windows, every page size 1..1000, asserting exact item-by-item equality
  with the one-shot observable result (this is the wrong-node-after-split
  detector).
- `CursorFuzzTest.testResumingMidStreamWithDifferentPageSizesStaysCorrect` —
  non-aligned page boundaries across an R*-split tree.
- `CursorPagingTest.testResumeAfterTreeModificationFailsClosed` and
  `testResumeAfterDeleteFailsClosed` — the fingerprint-bind detector.
