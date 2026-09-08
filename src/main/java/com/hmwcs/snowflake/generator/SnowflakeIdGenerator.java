package com.hmwcs.snowflake.generator;

import com.hmwcs.snowflake.exception.ClockMovedBackwardsException;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static com.hmwcs.snowflake.config.SnowflakeConfig.*;

/**
 * Generates unique IDs using the Twitter Snowflake algorithm.
 *
 * <p>
 * Snowflake ID Structure (Twitter’s original):
 * </p>
 *
 * <ul>
 * <li>64 bits total:</li>
 * <li>Sign Bit (1 bit): Reserved for the sign, keeping the ID non-negative within the timestamp range.</li>
 * <li>Timestamp (41 bits): Represents the time in milliseconds since a custom epoch.</li>
 * <li>Data Center ID (5 bits): Identifies the data center.</li>
 * <li>Machine ID (5 bits): Identifies the specific machine within the data center.</li>
 * <li>Sequence Number (12 bits): A counter that increments for IDs generated within the same millisecond.</li>
 * </ul>
 *
 * <p>
 * For more information, see the original design details at
 * <a href="https://blog.twitter.com/engineering/en_us/a/2010/announcing-snowflake">Twitter Engineering Blog</a>.
 * </p>
 */
public class SnowflakeIdGenerator {
    private final int dataCenterId; // ID of the data center
    private final int machineId; // ID of the machine
    private final long epoch; // The epoch used for ID generation

    private final LongSupplier clock;

    // Unix milliseconds in the upper bits, sequence in the lowest 12 bits.
    // Arithmetic right shift preserves the initial timestamp sentinel of -1.
    private final AtomicLong atomicState = new AtomicLong(-1L << SEQUENCE_BITS);

    /**
     * Constructor to initialize the Snowflake ID generator with default epoch.
     *
     * @param dataCenterId The ID of the data center (0-31). Cannot be null.
     * @param machineId The ID of the machine (0-31). Cannot be null.
     * @throws IllegalArgumentException if the dataCenterId, machineId is out of range, or custom epoch is invalid.
     */
    public SnowflakeIdGenerator(int dataCenterId, int machineId) {
        this(dataCenterId, machineId, DEFAULT_EPOCH);
    }

    /**
     * Constructor to initialize the Snowflake ID generator with a custom epoch.
     *
     * @param dataCenterId The ID of the data center (0-31). Cannot be null.
     * @param machineId The ID of the machine (0-31). Cannot be null.
     * @param customEpoch The custom epoch to use for generating IDs
     * @throws IllegalArgumentException if the dataCenterId or machineId is out of range
     */
    public SnowflakeIdGenerator(int dataCenterId, int machineId, long customEpoch) {
        this(dataCenterId, machineId, customEpoch, () -> Instant.now().toEpochMilli());
    }

    // Package-private clock injection keeps boundary tests deterministic.
    SnowflakeIdGenerator(int dataCenterId, int machineId, long customEpoch, LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (customEpoch > currentTimeMillis())
            throw new IllegalArgumentException("Custom epoch cannot be in the future");
        if (customEpoch < 0)
            throw new IllegalArgumentException("Custom epoch cannot be before the Unix epoch (1970-01-01)");
        if (dataCenterId > MAX_DATA_CENTER_ID || dataCenterId < 0)
            throw new IllegalArgumentException(
                    String.format("Data Center ID (%d) must be between 0 and %d", dataCenterId, MAX_DATA_CENTER_ID));
        if (machineId > MAX_MACHINE_ID || machineId < 0)
            throw new IllegalArgumentException(
                    String.format("Machine ID (%d) must be between 0 and %d", machineId, MAX_MACHINE_ID));

        this.dataCenterId = dataCenterId;
        this.machineId = machineId;
        epoch = customEpoch;
    }

    /**
     * Generates a unique Snowflake ID.
     *
     * @return A unique 64-bit Snowflake ID
     * @throws ClockMovedBackwardsException if the system clock moves backwards
     */
    public long nextId() {
        while (true) {
            long currentState = atomicState.get();
            long lastTimestamp = currentState >> SEQUENCE_BITS;
            int sequence = (int) (currentState & SEQUENCE_MASK);

            long timestamp = currentTimeMillis();
            checkClockTolerance(lastTimestamp, timestamp);
            if (timestamp < lastTimestamp) timestamp = lastTimestamp;

            if (timestamp == lastTimestamp) {
                sequence = (sequence + 1) & SEQUENCE_MASK;
                if (sequence == 0) timestamp = waitNextMillis(timestamp);
            } else sequence = 0;
            long newState = (timestamp << SEQUENCE_BITS) | sequence;

            if (atomicState.compareAndSet(currentState, newState))
                return generateId(timestamp, sequence);
            // If CAS fails, another thread has updated the state; retry
            Thread.onSpinWait();
        }
    }

    /**
     * Constructs a unique Snowflake ID based on the timestamp, data center ID, machine ID, and sequence number.
     *
     * <p>
     * The generated ID is composed by left-shifting the timestamp and combining it with the data center ID, machine ID,
     * and sequence number using bitwise OR operations.
     * </p>
     *
     * @param timestamp The current timestamp in milliseconds
     * @param sequence The sequence number for the current millisecond
     * @return A unique 64-bit Snowflake ID
     */
    private long generateId(long timestamp, int sequence) {
        return ((timestamp - epoch) << TIMESTAMP_LEFT_SHIFT) |
                ((long) dataCenterId << DATA_CENTER_ID_SHIFT) |
                ((long) machineId << MACHINE_ID_SHIFT) |
                (sequence & SEQUENCE_MASK);
    }

    /**
     * Returns the current time in milliseconds.
     *
     * @return Current time in milliseconds since Unix epoch
     */
    private long currentTimeMillis() {
        return clock.getAsLong();
    }

    /**
     * Waits for the next millisecond if the clock has not advanced.
     *
     * @param lastTimestamp The timestamp of the last ID generated
     * @return The current timestamp
     * @throws ClockMovedBackwardsException if the system clock moves backwards beyond the tolerance limit
     */
    private long waitNextMillis(long lastTimestamp) {
        long timestamp;
        do {
            Thread.onSpinWait();
            timestamp = currentTimeMillis();
            checkClockTolerance(lastTimestamp, timestamp);
        } while (timestamp <= lastTimestamp);

        return timestamp;
    }

    /**
     * Ensures the system clock has not moved backwards beyond the allowed tolerance.
     *
     * <p>
     * If the clock moves backwards beyond the defined tolerance, this method will throw an exception.
     * </p>
     *
     * @param lastTimestamp The timestamp of the last ID generated
     * @param currentTimestamp The current system timestamp
     * @throws ClockMovedBackwardsException if the clock moves backwards beyond the tolerance limit
     */
    private void checkClockTolerance(long lastTimestamp, long currentTimestamp) {
        if (currentTimestamp + CLOCK_BACKWARD_TOLERANCE < lastTimestamp)
            throw new ClockMovedBackwardsException(lastTimestamp, currentTimestamp);
    }
}
