# EntityKey Refactoring — Design Decision Record

## Problem Statement

`org.hibernate.engine.spi.EntityKey` was a `sealed` class with **three fields**:

```java
public sealed class EntityKey implements Serializable permits TemporalEntityKey {
    private final Object identifier;
    private final int hashCode;
    private final EntityPersister persister;
}
```

The objective was to make `EntityKey` **final** with a **single field `Object identifier`**
(the raw entity id, not a wrapper), while minimising disruption to the rest of the
codebase (~121 files reference `EntityKey`).

### Why this matters

`EntityKey` instances are created at very high frequency during session operations.
Reducing the footprint from three fields (identifier + persister reference + cached
hashCode) to a single field has measurable memory and GC benefits in large
persistence contexts.

---

## Constraints

1. The `EntityKeyImpl` class must be `final` and contain **exactly one field**:
   `Object identifier`.
2. The `identifier` must be the **raw entity id** (e.g. a `Long`, `String`, or
   composite-id object) — not a wrapper bundling other information.
3. The refactoring should cause the **least possible code disruption**.

---

## Solutions Considered

### 1. EntityKey as interface + final impl, two-level persistence context maps

**Idea:** Make `EntityKey` an interface with only `getIdentifier()`. The
persistence context maps change from `Map<EntityKey, …>` to
`Map<String/*rootEntityName*/, Map<EntityKey, …>>` so that entity-type
discrimination happens at the outer map level, and `EntityKey.equals/hashCode`
only needs the identifier.

**Why rejected:** Two-level maps complicate iteration, change public SPI return
types (`Map<EntityKey, EntityHolder>`), fragment lookup logic, and hurt cache
locality. Every `PersistenceContext` method that takes `EntityKey` would need
a second `EntityPersister` parameter — massive SPI churn (~35 method
signature changes).

### 2. Custom HashMap implementation (EntityKeyMap) — **chosen**

**Idea:** Instead of restructuring the maps, keep a **flat** hash map but move
the entity-type discrimination **into the map's `Node`**. Each `Node` stores
`rootEntityName`, `identifier`, `changesetId`, and `persister` as its key-part
fields. The map computes hash and equality from the first three fields
internally, so the `EntityKey` object itself doesn't need to carry entity-type
information.

**Trade-offs:**
- ✅ `EntityKeyImpl` truly has one field
- ✅ Single flat map — same cache locality as `HashMap<EntityKey, …>`
- ✅ Entity-type discrimination is fully handled by the map's `Node`
- ✅ Reconstructed keys from iteration carry persister (via `Node.toEntityKey()`)
- ⚠️ Custom map code to maintain (~650 lines, closely follows HashMap)
- ⚠️ Tree-bin support omitted (linked lists only) — acceptable for entity keys

### 3. Wrapping java.util.HashMap with an internal composite key (rejected)

**Idea:** `EntityKeyMap<V>` wraps a `HashMap<CompositeKey, V>` where
`CompositeKey` is a private record `(rootEntityName, identifier, changesetId)`.

**Why rejected:** A wrapper allocates a temporary `CompositeKey` for every
`get()` call. More importantly, the user explicitly asked for a
**reimplementation** of HashMap internals, not a wrapper.

### 4. Storing entity name as a second field on EntityKeyImpl (rejected)

Violates the single-field constraint.

### 5. Encoding entity type in the identifier object (rejected)

**Why rejected:** The identifier must be the **raw entity id**, not a wrapper.

---

## Final Solution

### Architecture

```
EntityKey (interface)
├── EntityKeyImpl                    — final, 1 field (Object identifier)
├── TemporalEntityKey                — final, 2 fields (identifier + changesetId)
├── EntityKeyWithPersister           — final, 2 fields (identifier + persister)  [convenience]
└── TemporalEntityKeyWithPersister   — final, 3 fields                          [convenience]

EntityKeyMap<V>   — custom HashMap reimpl, Nodes store entity-type metadata
EntityKeySet      — Set wrapper backed by EntityKeyMap
```

**`EntityKeyImpl`** satisfies the primary objective: `final`, single
`Object identifier` field.

**`EntityKeyWithPersister`** is a package-private convenience implementation
returned by `session.generateEntityKey(id, persister)`. It carries the
persister so that existing call sites using `entityKey.getPersister()` and
`entityKey.getEntityName()` continue to work without PersistenceContext SPI
changes.

### EntityKeyMap — what is custom vs java.util.HashMap

Every custom section is marked with `// --- CUSTOM ---` comments in the source:

| Aspect | java.util.HashMap | EntityKeyMap |
|--------|-------------------|-------------|
| **Node key fields** | `final K key` | `rootEntityName`, `identifier`, `changesetId`, `persister` |
| **Hash function** | `hash(key)` → `key.hashCode()` then bit-spread | `hash(rootEntityName, identifier, changesetId)` — combines all three then same bit-spread |
| **Equality check** | `key.equals(k)` inline | `keysEqual(node, hash, rootEntityName, identifier, changesetId)` |
| **Public API** | `get(Object)`, `put(K,V)` | `get(EntityPersister, EntityKey)`, `put(EntityPersister, EntityKey, V)` |
| **Node.toEntityKey()** | N/A | Reconstructs `EntityKeyWithPersister` from stored fields |
| **Tree bins** | Full red-black tree (~500 lines) | Not implemented (linked lists only) |
| **Serialization** | Java Serializable | Not supported (handled externally) |
| **Map interface** | Implements `java.util.Map` | Does not (API requires EntityPersister) |

Everything else — resize, power-of-two buckets, load factor, linked-list split,
fail-fast iterators, `modCount`, `values()`, `entrySet()` — is **identical**.

### Maps migrated to EntityKeyMap

| Location | Old type | New type |
|----------|----------|----------|
| `StatefulPersistenceContext.entitiesByKey` | `HashMap<EntityKey, EntityHolderImpl>` | `EntityKeyMap<EntityHolderImpl>` |
| `StatefulPersistenceContext.entitySnapshotsByKey` | `HashMap<EntityKey, Object>` | `EntityKeyMap<Object>` |
| `StatefulPersistenceContext.nullifiableEntityKeys` | `HashSet<EntityKey>` | `EntityKeySet` |
| `StatefulPersistenceContext.deletedUnloadedEntityKeys` | `HashSet<EntityKey>` | `EntityKeySet` |
| `BatchFetchQueue.subselectsByEntityKey` | `Map<EntityKey, SubselectFetch>` | `EntityKeyMap<SubselectFetch>` |
| `SubselectFetch.resultingEntityKeys` | `Set<EntityKey>` | `EntityKeySet` |
| `BatchEntitySelectFetchInitializer.toBatchLoad` | `HashMap<EntityKey, List<…>>` | `EntityKeyMap<List<…>>` |
| `BatchEntityInsideEmbeddableSelectFetchInitializer.toBatchLoad` | `HashMap<EntityKey, List<…>>` | `EntityKeyMap<List<…>>` |
| `BatchInitializeEntitySelectFetchInitializer.toBatchLoad` | `HashSet<EntityKey>` | `EntityKeySet` |
| `PersistenceContext` interface return types | `Map<EntityKey, …>` | `EntityKeyMap<…>` |

### Maps intentionally NOT migrated

| Location | Reason |
|----------|--------|
| `AuditWorkQueue.entries` | Uses `LinkedHashMap` for insertion-order preservation (audit log ordering). EntityKeyMap doesn't support ordered iteration. Keys carry persisters from `generateEntityKey()`, so HashMap equality works correctly. |
| `BatchFetchQueue.batchLoadableEntityKeys` | Inner `LinkedHashSet<EntityKey>` is already segregated by entity name (outer `Map<String, …>`), so identifier-only equality is safe. Order matters for batch fetch. |

---

## Files

### New files

| File | Description |
|------|-------------|
| `EntityKeyImpl.java` | `final` class, single `Object identifier` field |
| `EntityKeyMap.java` | Custom HashMap reimplementation with entity-type-aware Node |
| `EntityKeySet.java` | Set wrapper backed by EntityKeyMap |
| `EntityKeyWithPersister.java` | Convenience key carrying persister reference |
| `TemporalEntityKeyWithPersister.java` | Temporal variant with persister |
| `design-docs/entity-key-refactoring.md` | This document |

### Modified files

| File | Change |
|------|--------|
| `EntityKey.java` | Rewritten: sealed class → interface |
| `TemporalEntityKey.java` | Rewritten: extends sealed class → standalone final impl |
| `PersistenceContext.java` | Return types changed to `EntityKeyMap` |
| `StatefulPersistenceContext.java` | All EntityKey maps migrated to EntityKeyMap/EntityKeySet |
| `BatchFetchQueue.java` | `subselectsByEntityKey` → `EntityKeyMap` |
| `SubselectFetch.java` | `resultingEntityKeys` → `EntityKeySet` |
| `EntityPrinter.java` | Updated for `EntityKeyMap.Entry` |
| `SessionStatisticsImpl.java` | Updated for `EntityKeyMap` |
| `BatchEntitySelectFetchInitializer.java` | `toBatchLoad` → `EntityKeyMap` |
| `BatchEntityInsideEmbeddableSelectFetchInitializer.java` | `toBatchLoad` → `EntityKeyMap` |
| `BatchInitializeEntitySelectFetchInitializer.java` | `toBatchLoad` → `EntityKeySet` |
| `AbstractSharedSessionContract.java` | `generateEntityKey()` uses `EntityKey.of(id, persister)` |
| Various other files | `new EntityKey(…)` → `EntityKey.of(…)` |
