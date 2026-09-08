package com.hmwcs.snowflake.generator;

import org.junit.jupiter.api.Test;
import com.hmwcs.snowflake.exception.ClockMovedBackwardsException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.hmwcs.snowflake.config.SnowflakeConfig.*;

import static org.junit.jupiter.api.Assertions.*;

class SnowflakeIdGeneratorTest {
    @Test
    void generatesIncreasingIds() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(9, 29);
        long previous = generator.nextId();
        for (int i = 0; i < 10_000; i++) {
            long id = generator.nextId();
            assertTrue(id > previous);
            previous = id;
        }
    }

    @Test
    void generatesUniqueIdsConcurrently() throws Exception {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(9, 29);
        assertConcurrentUnique(generator::nextId, 8, 25_000);
    }

    @Test
    void concurrentAssertionsReachTestThread() {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> assertConcurrentUnique(() -> 42L, 2, 2));
        assertInstanceOf(AssertionError.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("Duplicate ID"));
    }

    @Test
    void concurrentGeneratorExceptionsReachTestThread() {
        IllegalStateException expected = new IllegalStateException("generator failed");
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> assertConcurrentUnique(() -> { throw expected; }, 2, 2));
        assertSame(expected, failure.getCause());
    }

    @Test
    void encodesTimestampNodesAndSequence() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(9, 29, 123L, now::get);
        long first = generator.nextId();
        assertEquals(999_877L, first >>> TIMESTAMP_LEFT_SHIFT);
        assertEquals(9, (first >>> DATA_CENTER_ID_SHIFT) & MAX_DATA_CENTER_ID);
        assertEquals(29, (first >>> MACHINE_ID_SHIFT) & MAX_MACHINE_ID);
        assertEquals(0, first & SEQUENCE_MASK);
        assertEquals(first + 1, generator.nextId());
        now.incrementAndGet();
        assertEquals(0, generator.nextId() & SEQUENCE_MASK);
    }

    @Test
    void initializesAtUnixEpoch() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0, 0, 0L, () -> 0L);
        assertEquals(0L, generator.nextId());
        assertEquals(1L, generator.nextId());
    }

    @Test
    void toleratesRollbackThroughFiftyMilliseconds() {
        for (long rollback : new long[]{1, 49, 50}) {
            AtomicLong now = new AtomicLong(1_000_000L);
            SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0, 0, 0L, now::get);
            long first = generator.nextId();
            now.addAndGet(-rollback);
            assertEquals(first + 1, generator.nextId());
            now.set(1_000_001L);
            assertEquals(1_000_001L << TIMESTAMP_LEFT_SHIFT, generator.nextId());
        }
    }

    @Test
    void rejectsLargerRollbackWithoutConsumingSequence() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0, 0, 0L, now::get);
        long first = generator.nextId();
        now.addAndGet(-51);
        assertThrows(ClockMovedBackwardsException.class, generator::nextId);
        now.set(1_000_000L);
        assertEquals(first + 1, generator.nextId());
    }

    @Test
    void waitsForNextMillisecondAfterSequenceExhaustion() {
        AtomicInteger reads = new AtomicInteger();
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0, 0, 0L,
                () -> reads.incrementAndGet() <= 4098 ? 1_000_000L : 1_000_001L);
        for (int sequence = 0; sequence <= SEQUENCE_MASK; sequence++)
            assertEquals((1_000_000L << TIMESTAMP_LEFT_SHIFT) | sequence, generator.nextId());
        assertEquals(1_000_001L << TIMESTAMP_LEFT_SHIFT, generator.nextId());
    }

    @Test
    void checksRollbackWhileWaitingForSequenceReset() {
        AtomicInteger reads = new AtomicInteger();
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0, 0, 0L,
                () -> reads.incrementAndGet() <= 4098 ? 1_000_000L : 999_949L);
        for (int i = 0; i <= SEQUENCE_MASK; i++) generator.nextId();
        assertThrows(ClockMovedBackwardsException.class, generator::nextId);
    }

    @Test
    void toleratesSmallRollbackWhileWaitingForSequenceReset() {
        AtomicInteger reads = new AtomicInteger();
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0, 0, 0L, () -> {
            int read = reads.incrementAndGet();
            if (read <= 4098) return 1_000_000L;
            return read == 4099 ? 999_950L : 1_000_001L;
        });
        for (int i = 0; i <= SEQUENCE_MASK; i++) generator.nextId();
        assertEquals(1_000_001L << TIMESTAMP_LEFT_SHIFT, generator.nextId());
    }

    @Test
    void validatesConstructorArguments() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(32, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(0, 32));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(0, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new SnowflakeIdGenerator(0, 0, 101L, () -> 100L));
        assertDoesNotThrow(() -> new SnowflakeIdGenerator(31, 31, 100L, () -> 100L));
    }

    private void assertConcurrentUnique(LongSupplier generator, int threads, int count) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int j = 0; j < count; j++) {
                        long id = generator.getAsLong();
                        assertTrue(ids.add(id), () -> "Duplicate ID detected: " + id);
                    }
                    return null;
                }));
            }
            start.countDown();
            executor.shutdown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (Future<?> future : futures)
                future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertEquals((long) threads * count, ids.size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Workers did not terminate");
        }
    }
}
