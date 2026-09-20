# Changelog

## Unreleased — Resumable stable spatial query cursor with structural version validation

### Added

- `RTree.searchPage(Rectangle, int)` — returns the first `SearchPage` of a
  stable depth-first search; traversal order is identical to the existing
  one-shot `RTree.search(Rectangle)`.
- `RTree.searchPage(Rectangle, ResumeToken)` — resumes a paged search from a
  previously issued token, including from another process or against another
  tree instance with identical structure and content.
- `SearchPage` — one page of entries plus an optional `ResumeToken` for the
  next page (`entries()`, `nextToken()`, `hasNext()`).
- `ResumeToken` — serializable (`java.io.Serializable`) cursor state binding:
  the tree structural version, a fingerprint of the geometry predicate, the
  traversal order (sort parameters), the page size, and the current DFS stack
  as a path of child indices from the root.
- `RTree.structuralVersion()` — 64-bit content-derived fingerprint of tree
  structure and geometry content (lazily computed, cached).
- `ResumeTokenException` — fail-closed error carrying full diagnostic context
  (expected vs actual versions, predicates, order, path, node arity).

### Implementation choices

- **Content-derived structural version instead of a mutation counter.** The
  version is a deterministic FNV-1a 64-bit fingerprint over tree size,
  max/min children, node hierarchy shape, node mbrs, and entry geometry
  types + mbrs (entry *values* are deliberately excluded; the token binds
  structure, not payloads). Because it is derived from content rather than
  instance identity, a token remains valid across a FlatBuffers round-trip
  and across identically built trees in another process, while any add,
  delete or node split changes it with probability 1 - 2^-64. A mutation
  counter would have forced all deserialized trees to restart at version 0
  and would have broken the cross-process resume story.
- **The pager reuses the exact stack discipline of `Backpressure`**
  (`NodePosition` frames on an `ImmutableStack`), so page concatenation is
  item-for-item identical to the one-shot search by construction, not by
  coincidence. The token path is the list of frame positions from root to
  current node; resume rebuilds the stack by descending child indices with
  bounds validation at every level.
- **Stack normalization at page boundaries.** After filling a page, exhausted
  frames (position == node count) are collapsed exactly as the traversal loop
  would do on its next iterations, so a page boundary falling exactly on the
  end of the traversal does not issue a superfluous token / empty final page.
- **Fail closed on every mismatch.** Modified tree, different predicate
  rectangle, unsupported order, corrupt/impossible DFS path, or a token
  presented against an empty tree all throw `ResumeTokenException` before any
  result is emitted. Only `DFS_PREORDER` (the existing deterministic search
  order) is resumable; distance-ordered (`nearest`) cursors are rejected via
  the order binding rather than silently reordered.

### Coverage gaps closed

- The pre-existing backpressure search could stop early but had no way to
  resume from the same position, and there was no notion of a tree structural
  version; tokens now cover both.
- New tests (`SearchPageTest`, `SerializerFlatBuffersResumeTokenTest`) cover:
  page sizes {1,2,3,4,5,7,8,16,64,100,10000,100000} concatenated equal to
  one-shot search for both R* and quadratic splitters; empty tree; 50
  duplicate geometries at one point; R*-split trees (maxChildren=4, 1000
  entries); exact page boundary at end of traversal; Java serialization
  round-trip of a token; resume on an identically built second tree instance;
  FlatBuffers round-trip under both `DEFAULT` and `SINGLE_ARRAY` internal
  structures; and fail-closed behaviour for add/delete/size-preserving
  modification, changed predicate, unsupported order, and corrupted paths.

### Degenerate protections for adjacent semantics

- No existing API signature, default, ordering or boundary behaviour was
  changed; all pre-existing tests pass unmodified.
- `pageSize <= 0` is rejected with `IllegalArgumentException` (mirroring the
  backpressure contract that a non-positive request yields nothing).
- Empty tree yields one empty page with no token; a token can therefore never
  exist for an empty tree, and a forged one is rejected.

### Most dangerous counterexample and its regression test

**Counterexample:** delete one entry and add a different one — the tree size
is unchanged. Any version scheme based on size (or on entry count alone)
would accept the stale token, and the DFS stack path would then be replayed
over a *different* node hierarchy, silently duplicating or skipping entries
with no error. This is the worst case because it produces plausible-looking,
wrong results instead of failing. The content-derived structural fingerprint
changes under any such mutation. Regression test:
`SearchPageTest.testTokenFailsClosedWhenTreeModifiedButSizeUnchanged`.

Runner-up: a page boundary landing exactly on the end of a leaf (top stack
frame position == node count). Without stack normalization this issues a
token that resumes into an empty final page; with a corrupt path of this
shape, resume must collapse frames before continuing. Regression tests:
`SearchPageTest.testExactPageBoundaryAtEndOfSearch`,
`SearchPageTest.testTokenFailsClosedOnCorruptPath`.
