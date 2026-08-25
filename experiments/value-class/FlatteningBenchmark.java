/**
 * Benchmark: Demonstrates the allocation difference between a 2-oop value class
 * (NOT flattenable) vs a 1-oop value class (flattenable as NULLABLE_ATOMIC_FLAT).
 *
 * Simulates the PersistenceContext hot path: create entity keys and look them up
 * in a HashMap-like structure.
 *
 * Run with Epsilon GC to see true heap impact:
 *   $JAVA_28_HOME/bin/java --enable-preview \
 *     -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC \
 *     -XX:+UnlockDiagnosticVMOptions \
 *     -XX:+PrintInlineLayout -XX:+PrintFlatArrayLayout \
 *     -Xmx256m -Xms256m \
 *     -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=bench.hprof \
 *     FlatteningBenchmark <before|after> 2>&1
 *
 * With PrintEscapeAnalysis (fastdebug JDK):
 *   Add: -XX:+PrintEscapeAnalysis -XX:+PrintEliminateAllocations
 */
public class FlatteningBenchmark {

    // ---- BEFORE: 2 oop fields, NOT flattenable ----
    static value class EntityKeyBefore {
        Object identifier;
        Object persister; // simulates persister reference
        EntityKeyBefore(Object id, Object p) {
            this.identifier = id;
            this.persister = p;
        }
    }

    // ---- AFTER: 1 oop field, IS flattenable as NULLABLE_ATOMIC_FLAT ----
    static value class EntityKeyAfter {
        Object identifier;
        EntityKeyAfter(Object id) {
            this.identifier = id;
        }
    }

    static final Object PERSISTER = "MockPersister"; // simulates EntityPersister
    static final int N = 500_000;
    static final int WARMUP = 50;

    // Simulates: generateEntityKey + persistence context lookup
    // BEFORE: creates EntityKeyBefore(id, persister) then uses it
    static long runBefore() {
        long sum = 0;
        for (int iter = 0; iter < WARMUP; iter++) {
            for (int i = 0; i < N; i++) {
                var key = new EntityKeyBefore(Integer.valueOf(i), PERSISTER);
                // Simulate using the key for a lookup (hash + equals)
                sum += key.identifier.hashCode();
                sum += key.persister.hashCode();
            }
        }
        return sum;
    }

    // AFTER: creates EntityKeyAfter(id) + passes persister separately
    static long runAfter() {
        long sum = 0;
        for (int iter = 0; iter < WARMUP; iter++) {
            for (int i = 0; i < N; i++) {
                var key = new EntityKeyAfter(Integer.valueOf(i));
                // Simulate using the key + separate persister for a lookup
                sum += key.identifier.hashCode();
                sum += PERSISTER.hashCode(); // persister passed separately
            }
        }
        return sum;
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "after";

        System.out.println("Mode: " + mode);
        System.out.println("Iterations: " + WARMUP + " x " + N + " = " + (long) WARMUP * N + " key creations");

        long result;
        long start = System.nanoTime();
        if ("before".equals(mode)) {
            result = runBefore();
        } else {
            result = runAfter();
        }
        long elapsed = System.nanoTime() - start;

        System.out.println("Result: " + result + " (prevent dead code elimination)");
        System.out.printf("Elapsed: %.2f ms%n", elapsed / 1e6);
        System.out.println("Done. Check heap dump or JFR for allocation analysis.");

        // Force GC reporting (or OOME with Epsilon)
        if (Boolean.getBoolean("forceOOM")) {
            System.out.println("Forcing OOM for heap dump...");
            @SuppressWarnings("unused")
            byte[] fill = new byte[Integer.MAX_VALUE];
        }
    }
}
