# Value Class Flattening Analysis for EntityKey

## Layout Analysis Results (JDK 28 fastdebug)

| Value class | Fields | Payload | +null marker | Rounded | ≤ MAX_ATOMIC(8)? | NULLABLE_ATOMIC_FLAT? |
|---|---|---|---|---|---|---|
| `EntityKeyWithPersister` | 2 oops (identifier + persister) | 8 bytes | 9 bytes | 16 | ❌ | **NO** |
| `EntityKeyImpl` | 1 oop (identifier) | 4 bytes | 5 bytes | 8 | ✅ | **YES** |
| `LongKey` (hypothetical) | 1 long | 8 bytes | 9 bytes | 16 | ❌ | NO |

**Key finding**: `EntityKeyWithPersister` at 2 oops is **too large for nullable atomic flattening** on the hot path.
Reducing to 1 oop (`EntityKeyImpl`) makes it flattenable, enabling C2 to scalarize it across method calls via
`InlineTypePassFieldsAsArgs=true`.

## Heap Impact (from user's Epsilon GC test)

| Class | Instances | Size each | Total |
|---|---|---|---|
| `EntityKeyWithPersister` | 408,166 | 24 bytes | 9.8 MB |
| `EntityKeyMap$Node` | 189,432 | 40 bytes | 7.6 MB |
| **Combined** | | | **17.4 MB** |

## Priority 1: Eliminate EntityKeyWithPersister allocations

### Problem
`EntityKeyWithPersister` (identifier + persister, 2 oops) is created by `generateEntityKey()` on every entity
lookup. It's too large for NULLABLE_ATOMIC_FLAT, so C2 cannot fully eliminate its heap allocation when the object
escapes to a callee (e.g., a `PersistenceContext` method).

### Solution: Pass persister as a separate parameter

Change the `PersistenceContext` and `EntityKeyMap` methods to accept `(EntityPersister persister, EntityKey key)`
instead of relying on `key.getPersister()`. Then:

1. `generateEntityKey()` returns `EntityKeyImpl` (1 oop → flattenable value class)
2. `EntityKeyWithPersister` is eliminated from hot paths
3. Callers pass the persister alongside the key (they already have it)

**API changes needed:**
```java
// PersistenceContext interface — add persister parameter
EntityHolder getEntityHolder(EntityPersister persister, EntityKey key);
EntityHolder addEntityHolder(EntityPersister persister, EntityKey key, Object entity);
Object getEntity(EntityPersister persister, EntityKey key);
// etc.
```

**Estimated allocation savings**: ~408k × 24 bytes = **9.8 MB eliminated per session lifecycle**.
With C2 scalarization, `EntityKeyImpl` (as a value class) would never reach the heap.

### Call site impact
~57 call sites need the persister parameter. All already have the persister available in scope
(they used it to call `generateEntityKey(id, persister)` in the first place).

## Priority 2: Reduce EntityKeyMap$Node allocations

### Problem
Each `EntityKeyMap.Node` is 40 bytes with 7 fields:
- `int hash` (4 bytes)
- `String rootEntityName` (4 bytes, compressed oop)
- `Object identifier` (4 bytes)
- `Object changesetId` (4 bytes, usually null)
- `EntityPersister persister` (4 bytes)
- `V value` (4 bytes)
- `Node next` (4 bytes)
= 28 bytes payload + 16 byte header = 44 → aligned to 48? (or 40 with compressed oops)

### Possible improvements

**Option A: Remove fields from Node**
- Remove `changesetId` — for non-temporal entities (vast majority), this is always null. Could use a separate
  "temporal overlay" map for the rare temporal case.
- Remove `persister` from Node — it can be looked up from `rootEntityName` via the MetamodelMapping when needed
  (trade CPU for memory).
- Minimal Node: `hash(4) + rootEntityName(4) + identifier(4) + value(4) + next(4)` = 20 + 16 header = 36 → 40 aligned.
  Saves ~0 bytes (still 40). Would need to remove one more field (e.g., store rootEntityName hash instead of String ref) to get to 32.

**Option B: Open addressing hash map**
- Eliminate `Node` objects entirely — store keys and values in parallel arrays.
- `Object[] identifiers`, `String[] rootEntityNames`, `Object[] values`, `int[] hashes`
- No `next` pointer (open addressing uses probing).
- Trades Node allocations for array space. With value class arrays, identifiers could be flat.
- Complex to implement (tombstones for delete, different resize strategy).

**Option C: Combined key-value flat storage (speculative)**
- If EntityKeyImpl is a flattenable value class (1 oop, ≤ 8 bytes), an array `EntityKeyImpl[]` is flat.
- An open-addressing map with `EntityKeyImpl[]` as the key array would store identifiers inline with no per-entry allocation.
- Combined with a separate `Object[] values` array (for the non-value-type value), this eliminates Nodes entirely.
- Still needs `String[] rootEntityNames` and `int[] hashes` for entity-type discrimination.
- Net per-entry storage: 8 (flat key) + 4 (rootEntityName oop) + 4 (value oop) + 4 (hash) = 20 bytes vs current 40 bytes in Node.

## C2 Escape Analysis Results

Both 1-oop and 2-oop value classes show `Scalar` (eliminated) for the value class itself.
The bottleneck is the `Integer.valueOf()` / identifier boxing, which creates `NotScalar` InlineType nodes.
In real Hibernate usage, identifiers are pre-existing objects (not boxed in a loop), so this is not a concern.

The real win comes from **eliminating the EntityKeyWithPersister class entirely**, not from better scalarization —
because in the actual Hibernate code, the key escapes to PersistenceContext methods (passes across call boundaries),
and C2 may not inline those deeply enough to scalarize the key. With a 1-oop value class, even if it escapes,
the allocation is smaller (16 bytes vs 24 bytes) and more likely to be scalarized by C2's InlineTypePassFieldsAsArgs.
