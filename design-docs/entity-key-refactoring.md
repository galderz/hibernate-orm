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

**Prompt adjustment:** This was the first plan proposed. The user rejected the
two-level map because it complicates iteration, changes public SPI return types
(`Map<EntityKey, EntityHolder>`), and fragments lookup logic across two levels.

**Trade-offs:**
- ✅ `EntityKeyImpl` truly has one field
- ❌ Every `PersistenceContext` method that takes `EntityKey` needs a second
  `EntityPersister` parameter — massive SPI churn
- ❌ Two-level maps hurt cache locality and complicate serialisation
- ❌ ~35 methods on `PersistenceContext` interface would change signature

### 2. Custom HashMap implementation (EntityKeyMap)

**Idea:** Instead of restructuring the maps, keep a **flat** hash map but move
the entity-type discrimination **into the map's `Node`**. Each `Node` stores
`rootEntityName`, `identifier`, and `changesetId` as its key-part fields. The
map computes hash and equality from all three fields internally, so the
`EntityKey` object itself doesn't need to carry entity-type information.

**Prompt adjustment:** This was suggested as an alternative to two-level maps.
The user approved this direction and asked for a reimplementation based on
`java.util.HashMap` (not a wrapper), with clear markings of what is custom
versus reused.

**Trade-offs:**
- ✅ `EntityKeyImpl` truly has one field
- ✅ Single flat map — same cache locality as `HashMap<EntityKey, …>`
- ✅ Entity-type discrimination is fully handled by the map's `Node`
- ⚠️ Custom map code to maintain (~600 lines, but closely follows HashMap)
- ⚠️ Tree-bin support omitted (linked lists only) — acceptable for entity keys
  where collisions are extremely rare

### 3. Wrapping java.util.HashMap with an internal composite key (rejected)

**Idea:** `EntityKeyMap<V>` wraps a `HashMap<CompositeKey, V>` where
`CompositeKey` is a private record `(rootEntityName, identifier, changesetId)`.

**Why rejected:** The user explicitly asked for a **reimplementation** of
HashMap internals, not a wrapper. A wrapper also allocates a temporary
`CompositeKey` for every `get()` call, though JIT escape analysis would likely
eliminate this.

### 4. Storing entity name as a second field on EntityKeyImpl (rejected)

**Idea:** Give `EntityKeyImpl` two fields: `identifier` and `entityName`.

**Why rejected:** Violates the single-field constraint.

### 5. Encoding entity type in the identifier object (rejected)

**Idea:** Wrap the real id in a record that includes the entity name, so the
single `Object identifier` field is actually a composite.

**Why rejected:** The user explicitly stated the identifier must be the **raw
entity id**, not a wrapper.

---

## Final Solution

A hybrid approach combining solutions 1 and 2:

### Architecture

```
EntityKey (interface)
├── EntityKeyImpl           — final, 1 field (Object identifier)
├── TemporalEntityKey       — final, 2 fields (identifier + changesetId)
├── EntityKeyWithPersister  — final, 2 fields (identifier + persister)  [convenience]
└── TemporalEntityKeyWithPersister — final, 3 fields                   [convenience]
```

**`EntityKeyImpl`** satisfies the primary objective: `final`, single
`Object identifier` field.

**`EntityKeyWithPersister`** is a package-private convenience implementation
returned by `session.generateEntityKey(id, persister)`. It carries the
persister so that existing call sites using `entityKey.getPersister()` and
`entityKey.getEntityName()` continue to work without SPI changes. It is a
**short-lived lookup key** — the persistence context map (`EntityKeyMap`)
does not rely on the persister being present on the key.

**`EntityKeyMap<V>`** is a reimplementation of `java.util.HashMap` with a
custom `Node` that stores entity-type metadata:

```java
static class Node<V> {
    final int hash;
    final String rootEntityName;   // ← replaces "K key"
    final Object identifier;       // ← replaces "K key"
    final @Nullable Object changesetId; // ← replaces "K key"
    V value;
    Node<V> next;
}
```

### What is custom in EntityKeyMap vs java.util.HashMap

| Aspect | java.util.HashMap | EntityKeyMap |
|--------|-------------------|-------------|
| **Node key fields** | `final K key` | Three fields: `rootEntityName`, `identifier`, `changesetId` |
| **Hash function** | `hash(key)` → `key.hashCode()` then bit-spread | `hash(rootEntityName, identifier, changesetId)` — combines all three then same bit-spread |
| **Equality check** | `key.equals(k)` inline | `keysEqual(node, hash, rootEntityName, identifier, changesetId)` comparing all three fields |
| **Public API** | `get(Object)`, `put(K,V)` | `get(EntityPersister, EntityKey)`, `put(EntityPersister, EntityKey, V)` |
| **Tree bins** | Full red-black tree (~500 lines) | Not implemented (linked lists only) |
| **Serialization** | Java Serializable | Not supported (handled externally) |
| **Map interface** | Implements `java.util.Map` | Does not (API requires EntityPersister) |

Everything else — resize strategy, power-of-two buckets, load factor, linked-list
split on resize, fail-fast iterators, `modCount` tracking, `values()` collection
view — is **identical** to `java.util.HashMap`.

### Why tree bins are omitted

HashMap converts bins to red-black trees when a single bucket accumulates 8+
entries. This protects against hash-flooding attacks or pathological hash
distributions. For `EntityKeyMap`:

- Keys are `(rootEntityName, identifier)` pairs. Even with the same identifier
  hash, different root entity names will differ, making collisions within a
  bucket extremely rare in practice.
- The tree bin code is ~500 lines of intricate red-black tree logic that
  extends `LinkedHashMap.Entry` (which we can't reuse outside the JDK).
- Omitting it keeps the implementation simple and auditable.

### Call-site changes

All `new EntityKey(id, persister)` call sites changed to `EntityKey.of(id, persister)`.
All `new TemporalEntityKey(id, persister, csId)` changed to
`EntityKey.of(id, persister, csId)`. Only ~14 call sites total, plus 2 test files.

The `PersistenceContext` interface is **unchanged** — no method signatures were
modified. This is possible because `generateEntityKey()` returns an
`EntityKeyWithPersister` that satisfies the existing `getPersister()` /
`getEntityName()` / `isBatchLoadable()` calls.

### Future work

- **Adopt `EntityKeyMap`** in `StatefulPersistenceContext` for `entitiesByKey`,
  `entitySnapshotsByKey`, and the `HashSet<EntityKey>` fields (nullifiable keys,
  deleted unloaded keys). This will fully decouple the persistence context from
  `EntityKeyWithPersister`, making `EntityKeyImpl` the dominant implementation.
- **Migrate callers** of `entityKey.getPersister()` to use
  `EntityHolder.getDescriptor()` or the persister from the surrounding context,
  so that `EntityKeyWithPersister` can eventually be removed.
- **Add tree-bin support** to `EntityKeyMap` if profiling shows bucket chains
  exceeding 8 entries in practice.

---

## Files

### New files

| File | Description |
|------|-------------|
| `EntityKeyImpl.java` | `final` class, single `Object identifier` field |
| `EntityKeyMap.java` | Custom HashMap reimplementation with entity-type-aware Node |
| `EntityKeyWithPersister.java` | Convenience key carrying persister reference |
| `TemporalEntityKeyWithPersister.java` | Temporal variant with persister |
| `design-docs/entity-key-refactoring.md` | This document |

### Modified files

| File | Change |
|------|--------|
| `EntityKey.java` | Rewritten: sealed class → interface |
| `TemporalEntityKey.java` | Rewritten: extends sealed class → standalone final impl |
| `AbstractSharedSessionContract.java` | `generateEntityKey()` uses `EntityKey.of(id, persister)` |
| `EntityEntryImpl.java` | `EntityKey.of(id, persister)` |
| `EntityInitializerImpl.java` | `EntityKey.of(id, persister)` |
| 6 other files | `new EntityKey(…)` → `EntityKey.of(…)` |
| 2 test files | Same pattern |
