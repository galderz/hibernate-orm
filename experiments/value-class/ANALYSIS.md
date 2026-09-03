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

## Priority 2: Eliminate EntityKeyMap$Node allocations (IMPLEMENTED)

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

### Solution: Open-addressing hash map (`EntityKeyOpenMap`)

Replaced the chaining-based `EntityKeyMap` (which allocates a `Node` object per entry) with
`EntityKeyOpenMap` using **linear probing** and **parallel arrays**:

```
int[] hashes               — 4 bytes/entry
String[] rootEntityNames   — 4 bytes/entry (compressed oop)
Object[] identifiers       — 4 bytes/entry (compressed oop)
Object[] changesetIds      — 4 bytes/entry (compressed oop, usually null)
EntityPersister[] persisters — 4 bytes/entry (compressed oop)
Object[] values            — 4 bytes/entry (compressed oop)
```

Total: ~24 bytes/entry in arrays (no per-entry object header).
At 75% load factor: ~32 bytes/slot amortised.

vs `EntityKeyMap.Node`: 40 bytes per Node object (16-byte header + 24 bytes payload).

**Key design decisions:**
- **Linear probing** for collision resolution (good cache locality).
- **Tombstone-based deletion** (`TOMBSTONE` sentinel in `rootEntityNames`) to maintain
  probe chain integrity.
- **Automatic tombstone cleanup**: when tombstones exceed 25% of table, triggers resize
  to rehash and eliminate them.
- **Same public API** as `EntityKeyMap` — drop-in replacement.
- **`EntityKeyOpenSet`** wraps `EntityKeyOpenMap<Object>` for set usage.

**Expected impact**: Eliminates 189k × 40 bytes = **7.6 MB** of `Node` object allocations.

### Other options considered but not implemented

**Option A: Remove fields from Node** — saves ~1.5 MB but still allocates Node objects.

**Option C: Inline key fields into EntityHolderImpl** — saves ~4.5 MB but tightly couples
hash map with the holder class. Only works for `entitiesByKey`, not other maps.
