---
name: value-class-diagnostics
description: Diagnose JEP 401 value class flattening, allocation, and scalarization behaviour in Valhalla-enabled JDKs. Use when investigating value class layout, flat array storage, allocation profiling gaps, or C2 escape analysis of value types. Requires a Valhalla/JDK 28+ build.
---

# Value Class Diagnostics

Diagnose how the JVM handles value classes (JEP 401): object layout, field/array flattening, allocation behaviour, C2 scalarization, and interaction with JFR/JVMTI allocation events.

## When to use

- Investigating whether a value class is being flattened in arrays or fields
- Understanding why allocation profiling (JFR or async-profiler) shows unexpected results for value types
- Checking if C2 is eliminating value class allocations via escape analysis
- Debugging flat array read/write behaviour
- Verifying whether C2 scalarisation works across method call chains

## Prerequisites

- A Valhalla-enabled JDK (JDK 28+ with `--enable-preview`)
- For C2 diagnostics: a **fastdebug** JDK build (has `develop` flags like `PrintEscapeAnalysis`)
- For allocation profiling: `jfr` command (bundled with JDK) and optionally async-profiler

## Critical: `--enable-preview` changes flag defaults

**All flattening flags default to `true` ONLY when `--enable-preview` is passed.** Without it, `UseArrayFlattening` and all sub-flags are `false`. Always check flag values with `--enable-preview`:

```bash
java --enable-preview -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions \
  -XX:+PrintFlagsFinal -version 2>&1 | grep -E "UseArrayFlat|UseFieldFlat|NullFree|Nullable"
```

## When is `new ValueClass[N]` flat?

A regular `new ValueClass[N]` creates a **nullable** array. Whether it is flat depends on whether the value class has a `NULLABLE_ATOMIC_FLAT` layout, which requires:

```
round_up_power_of_2(payload_bytes + 1_null_marker_byte) ≤ MAX_ATOMIC_OP_SIZE
```

`MAX_ATOMIC_OP_SIZE` is `sizeof(uint64_t)` = 8 bytes on aarch64 and x86-64.

| Value class | Payload | +null marker | Rounded | ≤ 8? | `new VC[N]` flat? |
|---|---|---|---|---|---|
| `SmallVP { int x }` | 4 bytes | 5 bytes | 8 | ✅ | YES |
| `LargeVP { int x, y }` | 8 bytes | 9 bytes | 16 | ❌ | NO |
| `TinyVP { short x, byte y }` | 3 bytes | 4 bytes | 4 | ✅ | YES |

For value classes too large for nullable atomic flat, a **null-restricted** array is needed:

```java
import jdk.internal.value.ValueClass;
LargeVP[] flat = (LargeVP[]) ValueClass.newNullRestrictedAtomicArray(
        LargeVP.class, N, new LargeVP(0, 0));
```

The null-free layout has no null marker, so `NULL_FREE_ATOMIC_FLAT` only needs the payload to fit (e.g. 8 bytes for 2 ints ≤ 8 → OK).

The JDK source that decides this is in `fieldLayoutBuilder.cpp`:

```cpp
int nullable_atomic_size = round_up_power_of_2(new_raw_size);
if (nullable_atomic_size <= (int)MAX_ATOMIC_OP_SIZE) {
    _nullable_atomic_layout_size_in_bytes = nullable_atomic_size;
}
```

## The opposite-allocation pattern

Flat and non-flat arrays have opposite allocation behaviour:

```
    ┌─────────────────┬───────────────────┬──────────────────┐
    │                 │ FLAT array        │ NON-FLAT array   │
    ├─────────────────┼───────────────────┼──────────────────┤
    │ Write (aastore) │ Few/no allocs     │ Allocs (new+oop) │
    │                 │ (scalarised→flat) │                  │
    ├─────────────────┼───────────────────┼──────────────────┤
    │ Read (aaload)   │ ALLOCS (buffering)│ No allocs        │
    │                 │ (flat→heap copy)  │ (returns oop)    │
    └─────────────────┴───────────────────┴──────────────────┘
```

**Why flat writes have few/no allocs**: C2 scalarises the `new` — extracts the fields and copies them directly into the flat array element memory via `aastore`. No heap object is ever created. C2 escape analysis shows `Scalar=N, NotScalar=0`.

**Why flat reads have allocs**: `aaload` from a flat array must copy the inline data into a heap-buffered oop. Each unique field-value combination triggers a buffer allocation. C2 shows `Scalar=N, NotScalar=N` (some eliminated, some kept).

**Why non-flat reads have zero allocs**: A non-flat array stores compressed oop references. `aaload` returns the existing heap object. No allocation. C2 shows zero Allocate nodes.

**Reading into a typed non-volatile field does NOT reduce flat-read allocations**: The buffering happens at the `aaload` level. Whether the target is `volatile Object sink` or `LargeVP typedSink` doesn't matter — the oop must still be materialised from flat storage.

## C2 scalarisation across method chains

C2 is extremely aggressive at inlining value class methods because `InlineTypePassFieldsAsArgs=true` passes value fields in registers across method boundaries. Even a 4-deep call chain like:

```java
LargeVP v = createValue(i);   // separate method
processAndStore(c, v);         // → doStore(c, v) → Container.add(v)
```

...gets fully inlined and scalarised (`Scalar=3, NotScalar=0`), producing the same few allocation events as a simple flat write.

To defeat this for benchmarking, you need: methods exceeding `MaxInlineSize`/`FreqInlineSize`, polymorphic dispatch, separate classloaders, or JNI boundaries.

## Diagnostic flags reference

### Layout & flattening (require `-XX:+UnlockDiagnosticVMOptions`)

| Flag | What it shows |
|------|---------------|
| `-XX:+PrintInlineLayout` | Per-class layout: field offsets, null marker, instance size, flat payload sizes for each layout kind (BUFFERED, NULL_FREE_ATOMIC_FLAT, NULLABLE_ATOMIC_FLAT, etc.) |
| `-XX:+PrintFlatArrayLayout` | Lists all flat array klasses created at runtime, with layout kind and element size |
| `-XX:+UseArrayFlattening` | Enable/disable array flattening (default: `true` with `--enable-preview`) |
| `-XX:+UseFieldFlattening` | Enable/disable field flattening (default: `true` with `--enable-preview`) |
| `-XX:+UseNullFreeAtomicValueFlattening` | Null-free atomic flat arrays — requires `-XX:+UnlockExperimentalVMOptions` |
| `-XX:+UseNullableAtomicValueFlattening` | Nullable atomic flat arrays |
| `-XX:FlatArrayElementMaxOops` | Maximum oop fields allowed in a flat array element (default: 4) |
| `-XX:FlatteningBudget` | Maximum bytes budget for flattening (default: 1024) — requires `-XX:+UnlockExperimentalVMOptions` |

### C2 escape analysis (require fastdebug build)

| Flag | What it shows |
|------|---------------|
| `-XX:+PrintEscapeAnalysis` | Per-Allocate node: `Scalar` (eliminated) vs `NotScalar` (kept on heap), with C2 IR node info and BCI |
| `-XX:+PrintEliminateAllocations` | `++++ Eliminated: NNN Allocate` confirmations |
| `-XX:+EliminateAllocations` | Enable/disable allocation elimination (default: `true`) |
| `-XX:+DoEscapeAnalysis` | Enable/disable escape analysis (default: `true`) |
| `-XX:InlineTypePassFieldsAsArgs` | Whether value types are passed as scalarised fields in calls (default: `true`) — key to cross-method scalarisation |
| `-XX:InlineTypeReturnedAsFields` | Whether value types are returned as scalarised fields (default: `true`) |

### Compilation & inlining

| Flag | What it shows |
|------|---------------|
| `-XX:+PrintCompilation` | Which methods were compiled, at what tier, OSR vs regular |
| `-Xlog:jit+inlining=debug` | Inlining decisions — shows whether value class methods were inlined and why. Use `:file=inlining.log:tags,level` to log to file |
| `-Xlog:valuetypes=trace` | Value-type-specific runtime logging |

## Workflow: Check if a value class is flattened in an array

### Step 1: Check the layout

```bash
java --enable-preview -cp $CLASSPATH \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:+PrintInlineLayout -XX:+PrintFlatArrayLayout \
  YourMainClass 2>&1 | grep -A 20 'YourValueClass'
```

Look for `NULLABLE_ATOMIC_FLAT` (for `new VC[N]`) and `NULL_FREE_ATOMIC_FLAT` (for null-restricted arrays). `-/-` means that layout is not available.

Also check `PrintFlatArrayLayout` output for:

```
Flat Type Array: [LYourValueClass;
 - layout kind: NULLABLE_ATOMIC_FLAT   ← or NULL_FREE_ATOMIC_FLAT
 - element size 8 aligned layout size 8
```

If your class doesn't appear → no flat array was created for it.

### Step 2: Check array type at runtime

```java
import jdk.internal.value.ValueClass;
ValueClass.isFlatArray(arr)  // true = flat, false = reference-based
```

Requires `--add-opens java.base/jdk.internal.value=ALL-UNNAMED`.

### Step 3: Verify via shallow size

```java
import jdk.internal.misc.Unsafe;
long size = Unsafe.getUnsafe().getObjectSize(arr);
long bytesPerElement = (size - 16) / arr.length;
// Flat:     ≥ payload bytes/element (e.g. 8 for two ints)
// Non-flat:  4 bytes/element (compressed oop reference)
```

### Step 4: Check C2 allocation elimination (fastdebug JDK)

```bash
$JAVA_DBG --enable-preview -cp $CLASSPATH \
  -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions \
  -XX:+PrintEscapeAnalysis -XX:+PrintEliminateAllocations \
  -XX:+PrintCompilation \
  -Xlog:jit+inlining=debug:file=inlining.log:tags,level \
  YourMainClass 2>&1 | grep -E 'YourMethod|Scalar|NotScalar|Eliminated'
```

**Interpreting output:**

- `Scalar ... YourValueClass ... YourMethod @ bci:NN` → allocation eliminated
- `NotScalar (Object is referenced by node) ... InlineType` → value must be heap-allocated
- `++++ Eliminated: NNN Allocate` → confirmed elimination
- No Scalar/NotScalar lines → zero Allocate nodes (fully scalarised or no allocation path)

Check the inlining log to see whether methods were inlined:

```bash
grep "YourMethod\|createValue\|processAndStore" inlining.log
```

### Step 5: Compare with flattening disabled

```bash
java --enable-preview -cp $CLASSPATH \
  -XX:+UnlockDiagnosticVMOptions -XX:-UseArrayFlattening \
  -XX:StartFlightRecording="jdk.ObjectAllocationInNewTLAB#enabled=true,filename=no_flat.jfr,dumponexit=true" \
  YourMainClass
```

### Step 6: Epsilon GC heap dump (ground truth for heap impact)

Epsilon GC never collects — every heap allocation persists. This reveals the true heap impact independent of profiling sampling:

```bash
java --enable-preview -cp $CLASSPATH \
  -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC \
  -Xmx48m -Xms48m \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=scenario.hprof \
  YourMainClass
```

If the scenario doesn't naturally OOME, force one at the end to trigger the dump (e.g. allocate `new byte[Integer.MAX_VALUE]`).

Analyse the dump:

```bash
java -jar jol-cli.jar heapdump-stats scenario.hprof
```

**Interpreting results:**

- **OOME + ~1.2M+ objects**: real heap allocations at scale (flat reads, non-flat writes, boxing)
- **No OOME + ~100k objects**: warmup allocations only — C2 scalarises the hot loop, stopping heap allocations. The ~100k objects come from interpreter / C1 compilation before C2 kicks in
- **No OOME + 0 objects**: zero heap allocations (non-flat reads return existing oops)

Note: JFR alloc events (ObjectAllocationInNewTLAB) are always zero under Epsilon GC because it uses a single bump-pointer allocator with no TLAB refills. The heap dump object count is the authoritative source.

### Step 7: Interpreter-only with tiny TLABs (maximum events)

```bash
java --enable-preview -cp $CLASSPATH -Xint \
  -XX:+UnlockDiagnosticVMOptions -XX:TLABSize=4k -XX:-ResizeTLAB \
  -XX:StartFlightRecording="jdk.ObjectAllocationInNewTLAB#enabled=true,jdk.ObjectAllocationOutsideTLAB#enabled=true,filename=max_events.jfr,dumponexit=true" \
  YourMainClass
```

This removes JIT optimisation and maximises TLAB refill events, showing the raw allocation pattern.

## Workflow: Profile value class allocations

### With JDK built-in JFR

```bash
java --enable-preview -cp $CLASSPATH \
  -XX:StartFlightRecording="jdk.ObjectAllocationInNewTLAB#enabled=true,jdk.ObjectAllocationOutsideTLAB#enabled=true,filename=alloc.jfr,dumponexit=true" \
  YourMainClass
```

### With async-profiler

```bash
# Default mode (JVMTI SampledObjectAlloc — needs can_support_value_objects fix on ≤ 4.5):
java --enable-preview -cp $CLASSPATH \
  -agentpath:/path/to/libasyncProfiler.so=start,event=alloc,file=alloc.jfr \
  YourMainClass

# tlab mode (breakpoint trap — works without fix):
java --enable-preview -cp $CLASSPATH \
  -agentpath:/path/to/libasyncProfiler.so=start,event=alloc,tlab,file=alloc.jfr \
  YourMainClass
```

### Inspect results

```bash
# By class
jfr print --events jdk.ObjectAllocationInNewTLAB alloc.jfr \
  | grep 'objectClass = ' | sed 's/.*objectClass = //' | sort | uniq -c | sort -rn

# By method (stack-based attribution)
for s in myWriteMethod myReadMethod; do
    c=$(jfr print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB alloc.jfr \
        | grep -c "$s" || true)
    printf "  %-25s %s samples\n" "$s" "$c"
done
```

## Known behaviours

1. **Flat vs non-flat arrays have opposite allocation patterns** (see table above). Allocation events appear on flat reads (buffering) and non-flat writes (new+oop), not the reverse.

2. **`new ValueClass[N]` is flat only for small value classes** where `round_up_power_of_2(payload + null_marker) ≤ MAX_ATOMIC_OP_SIZE` (8 bytes). For larger value classes, use `ValueClass.newNullRestrictedAtomicArray()`.

3. **async-profiler default mode misses value class allocations** unless patched to request `can_support_value_objects` JVMTI capability. Workaround: use `tlab` mode.

4. **C2 scalarises value class allocations very aggressively**, even across multi-method call chains, because `InlineTypePassFieldsAsArgs=true` passes fields in registers. A 4-deep call chain can be fully inlined and scalarised.

5. **Reading into a typed non-volatile field does NOT reduce flat-read allocations.** The buffering from flat storage to heap oop happens at `aaload` time, regardless of the sink type.

6. **Value objects with identical field values may share identity**: `arr[0] == arr[1]` can be `true` when both elements have the same field values.

7. **Non-flat array reads always show zero allocation events** — even in interpreter-only mode — because they return existing heap oops with no new allocation.

8. **In interpreter-only mode** (`-Xint`), no scalarisation occurs, so flat writes produce the same number of events as non-flat writes. Flat reads still produce events (buffering still required). Non-flat reads still produce zero events.

9. **Epsilon GC heap dumps confirm the C2 EA picture.** Flat-write scenarios show ~111k objects (warmup only, no OOME), while flat-read and non-flat-write scenarios show ~1.2M+ objects (OOME, heap exhausted). Non-flat reads show 0 value class objects on the heap. The ~111k warmup objects represent allocations from the interpreter and C1/C3 tiers before C2 compiles with escape analysis.

10. **JFR alloc events are always zero under Epsilon GC** because it uses a single bump-pointer allocator with no TLAB refills. Use heap dumps, not JFR, as the ground truth for allocation behaviour under Epsilon.
