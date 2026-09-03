# Value Class Flattening Analysis for EntityKey

## Layout Analysis Results (JDK 28 fastdebug)

| Value class | Fields | Payload | +null marker | Rounded | ≤ MAX_ATOMIC(8)? | NULLABLE_ATOMIC_FLAT? |
|---|---|---|---|---|---|---|
| `EntityKeyWithPersister` | 2 oops (identifier + persister) | 8 bytes | 9 bytes | 16 | ❌ | **NO** |
| `EntityKeyImpl` | 1 oop (identifier) | 4 bytes | 5 bytes | 8 | ✅ | **YES** |

## Heap Impact (from user's Epsilon GC test, before Priority 1 fix)

| Class | Instances | Size each | Total |
|---|---|---|---|
| `EntityKeyWithPersister` | 408,166 | 24 bytes | 9.8 MB |
| `EntityKeyMap$Node` | 189,432 | 40 bytes | 7.6 MB |
| **Combined** | | | **17.4 MB** |

## Priority 1: Eliminate EntityKeyWithPersister from the hot path (IMPLEMENTED)

### Problem
`EntityKeyWithPersister` (identifier + persister, 2 oops = 8 bytes payload) was created by
`generateEntityKey()` on every entity lookup. At 2 oops it is too large for NULLABLE_ATOMIC_FLAT
(9 bytes rounded to 16 > MAX_ATOMIC_OP_SIZE of 8), so the JVM cannot flatten it in arrays or
scalarize it as aggressively.

### Solution implemented
Two-pronged approach:

1. **`PersistenceContext` methods now accept `(EntityPersister, EntityKey)`** as separate parameters.
   Every method that previously relied on `key.getPersister()` now receives the persister explicitly.
   The `StatefulPersistenceContext` implementation uses this persister directly for `EntityKeyMap`
   operations. This decouples the map operations from the key carrying a persister.

2. **`generateEntityKey()` currently still returns `EntityKeyWithPersister`** because many downstream
   call sites (event listeners, bytecode enhancement interceptors, batch fetch queue, etc.) still
   call `key.getPersister()`, `key.getEntityName()`, and `key.isBatchLoadable()` on the key. These
   would all need individual fixes before `generateEntityKey()` can return bare `EntityKeyImpl`.

### Path to full EntityKeyImpl on hot path
To fully eliminate `EntityKeyWithPersister` from the hot path, the remaining ~40 call sites that
call `.getPersister()` / `.getEntityName()` / `.isBatchLoadable()` on EntityKey variables (especially
`keyToLoad` in `DefaultLoadEventListener`, `key` in `BatchFetchQueue`, `entityKey` in
`EnhancementAsProxyLazinessInterceptor`, etc.) must be refactored to use the persister from the
surrounding context. Once done, `generateEntityKey()` can return `EntityKey.of(id)` = `EntityKeyImpl`.

### Backward compatibility
- `EntityKeyWithPersister` is **retained** for keys from `generateEntityKey()`,
  `EntityKeyMap.Node.toEntityKey()`, and `EntityKey.of(id, persister)`.
- The `EntityKey` interface still defines `getPersister()`, `getEntityName()`, and `isBatchLoadable()`
  as **default methods** — they work on `EntityKeyWithPersister` and
  throw `UnsupportedOperationException` on bare `EntityKeyImpl`.

### Current net effect
The PersistenceContext SPI is ready to accept bare `EntityKeyImpl` with a separate persister.
Once the remaining ~40 downstream `.getPersister()` calls are refactored, `generateEntityKey()`
can switch to returning `EntityKeyImpl` (1 oop, flattenable), eliminating the 408k × 24 bytes.

## Priority 2: Reduce EntityKeyMap$Node allocations (DEFERRED)

### Problem
Each `EntityKeyMap.Node` is 40 bytes with 7 fields:
- `int hash` (4 bytes)
- `String rootEntityName` (4 bytes compressed oop)
- `Object identifier` (4 bytes)
- `Object changesetId` (4 bytes, usually null)
- `EntityPersister persister` (4 bytes)
- `V value` (4 bytes)
- `Node next` (4 bytes)
= 28 bytes payload + 16 byte header ≈ 40 bytes (with alignment)

189k instances × 40 bytes = 7.6 MB.

### Option A: Remove fields from Node

Remove `changesetId` (null for 99%+ of entities) and `persister` (derivable from `rootEntityName`
via `MetamodelMapping`). This saves 8 bytes per Node → 32 bytes/node, saving ~1.5 MB at 189k nodes.

**Trade-off**: Temporal keys need a separate overlay map. Persister lookup on read adds CPU cost.

### Option B: Open addressing hash map

Replace chaining (linked list of Nodes) with open addressing (linear/quadratic probing). Eliminates
the `Node` class entirely — keys and values stored in parallel arrays:

```
int[] hashes          — 4 bytes/entry
String[] entityNames  — 4 bytes/entry (compressed oop)
Object[] identifiers  — 4 bytes/entry (compressed oop)  
Object[] values       — 4 bytes/entry (compressed oop)
```

Total: ~16 bytes/entry vs 40 bytes/Node = **60% reduction**.

With value class arrays (if `EntityKeyImpl` is flattenable), `identifiers` could be a flat array
at 8 bytes/element (NULLABLE_ATOMIC_FLAT), but this doesn't save vs 4-byte compressed oops.

**Trade-off**: Open addressing is more complex (tombstone-based deletion, different resize strategy,
load factor constraints). Probe chains can degrade under high load.

### Option C: Hybrid — inline key fields into EntityHolderImpl

Since every `EntityKeyMap<EntityHolderImpl>` Node maps to exactly one `EntityHolderImpl`, we could
store the key-part fields (`hash`, `rootEntityName`, `identifier`) directly inside `EntityHolderImpl`
and use the holder array itself as the hash map storage. This eliminates the separate Node allocation.

Per-entry storage: `EntityHolderImpl` already has ~48 bytes. Adding `hash(4) + rootEntityName(4) +
identifier(4) + next(4)` = 16 bytes → 64 bytes total, vs current 40 (Node) + 48 (Holder) = 88 bytes.
Saves ~24 bytes/entry = **~4.5 MB at 189k entries**.

**Trade-off**: Tightly couples the hash map with the holder class. Only works for the
`entitiesByKey` map, not for `entitySnapshotsByKey` or other EntityKeyMap usages.
