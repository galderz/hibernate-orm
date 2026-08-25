/**
 * Experiment: Check value class layout and flattening for EntityKey variants.
 *
 * Goal 1: Make EntityKeyWithPersister small enough for NULLABLE_ATOMIC_FLAT.
 *   - Current: 2 oop fields (identifier + persister) = 8 bytes payload
 *   - Need: payload + null_marker ≤ 8 → payload ≤ 7 bytes
 *   - 2 compressed oops = 8 bytes → TOO BIG for nullable atomic flat
 *   - Solution: reduce to 1 oop field → 4 bytes + 1 null marker = 5 → rounds to 8 ✓
 *
 * Goal 2: Explore if EntityKeyMap$Node can be flattened somehow.
 *
 * Run with:
 *   $JAVA_28_HOME/bin/java --enable-preview \
 *     -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions \
 *     -XX:+PrintInlineLayout -XX:+PrintFlatArrayLayout \
 *     --add-opens java.base/jdk.internal.value=ALL-UNNAMED \
 *     --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
 *     EntityKeyLayoutTest 2>&1 | grep -A 30 'EntityKey\|TwoOop\|OneOop\|MapNode'
 */
public class EntityKeyLayoutTest {

    // Simulates current EntityKeyWithPersister: 2 oop fields
    static value class TwoOopKey {
        Object identifier;
        Object persister;
        TwoOopKey(Object id, Object p) { this.identifier = id; this.persister = p; }
    }

    // Proposed: 1 oop field only (identifier)
    static value class OneOopKey {
        Object identifier;
        OneOopKey(Object id) { this.identifier = id; }
    }

    // Proposed: identifier as long (unboxed primitive ID)
    static value class LongKey {
        long identifier;
        LongKey(long id) { this.identifier = id; }
    }

    // Simulates EntityKeyMap.Node fields (not a value class, just for size comparison)
    // hash(int) + rootEntityName(oop) + identifier(oop) + changesetId(oop) + persister(oop) + value(oop) + next(oop)
    static class MapNode {
        int hash;
        String rootEntityName;
        Object identifier;
        Object changesetId;
        Object persister;
        Object value;
        MapNode next;
    }

    // What if we inline the key parts into the Node and remove persister/changesetId?
    // Minimal node: hash(int) + rootEntityName(oop) + identifier(oop) + value(oop) + next(oop)
    static class MinimalNode {
        int hash;
        String rootEntityName;
        Object identifier;
        Object value;
        MinimalNode next;
    }

    public static void main(String[] args) throws Exception {
        var unsafe = jdk.internal.misc.Unsafe.getUnsafe();

        // Force class loading to trigger PrintInlineLayout
        var k1 = new TwoOopKey(1L, "persister");
        var k2 = new OneOopKey(1L);
        var k3 = new LongKey(1L);

        // Check array sizes
        TwoOopKey[] twoArr = new TwoOopKey[100];
        OneOopKey[] oneArr = new OneOopKey[100];
        LongKey[] longArr = new LongKey[100];

        long twoSize = unsafe.getObjectSize(twoArr);
        long oneSize = unsafe.getObjectSize(oneArr);
        long longSize = unsafe.getObjectSize(longArr);

        System.out.println("=== Array sizes (100 elements) ===");
        System.out.println("TwoOopKey[100]:  " + twoSize + " bytes (" + (twoSize - 16) / 100.0 + " bytes/elem)");
        System.out.println("OneOopKey[100]:  " + oneSize + " bytes (" + (oneSize - 16) / 100.0 + " bytes/elem)");
        System.out.println("LongKey[100]:    " + longSize + " bytes (" + (longSize - 16) / 100.0 + " bytes/elem)");

        // Check if arrays are flat
        var vcClass = Class.forName("jdk.internal.value.ValueClass");
        var isFlatMethod = vcClass.getMethod("isFlatArray", Object.class);
        System.out.println("\n=== Flat array status ===");
        System.out.println("TwoOopKey[] flat: " + isFlatMethod.invoke(null, twoArr));
        System.out.println("OneOopKey[] flat: " + isFlatMethod.invoke(null, oneArr));
        System.out.println("LongKey[] flat:   " + isFlatMethod.invoke(null, longArr));

        // Check instance sizes
        System.out.println("\n=== Instance sizes ===");
        System.out.println("TwoOopKey instance: " + unsafe.getObjectSize(k1) + " bytes");
        System.out.println("OneOopKey instance: " + unsafe.getObjectSize(k2) + " bytes");
        System.out.println("LongKey instance:   " + unsafe.getObjectSize(k3) + " bytes");
        System.out.println("MapNode instance:   " + unsafe.getObjectSize(new MapNode()) + " bytes");
        System.out.println("MinimalNode inst:   " + unsafe.getObjectSize(new MinimalNode()) + " bytes");

        // Fill and read to trigger compilation
        for (int i = 0; i < 100; i++) {
            oneArr[i] = new OneOopKey((long) i);
            longArr[i] = new LongKey(i);
        }

        System.out.println("\n=== Conclusion ===");
        System.out.println("TwoOopKey (current EntityKeyWithPersister): 2 oops = 8 bytes payload");
        System.out.println("  + null marker = 9 bytes → round_up_pow2 = 16 > MAX_ATOMIC_OP_SIZE(8) → NOT flat");
        System.out.println("OneOopKey (proposed): 1 oop = 4 bytes payload");
        System.out.println("  + null marker = 5 bytes → round_up_pow2 = 8 ≤ MAX_ATOMIC_OP_SIZE(8) → FLAT!");
        System.out.println("LongKey (primitive ID): 8 bytes payload");
        System.out.println("  + null marker = 9 bytes → round_up_pow2 = 16 > MAX_ATOMIC_OP_SIZE(8) → NOT flat");
    }
}
