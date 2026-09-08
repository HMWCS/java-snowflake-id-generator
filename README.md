# Snowflake ID Generator

This Java implementation of the Snowflake ID algorithm generates unique 64-bit IDs across distributed systems, optimized with CAS for high throughput and fast concurrent generation.

## Features

- **Distributed Unique ID Generation**: Generates unique IDs using data center and machine IDs, ensuring no duplicates across distributed systems.
- **Custom Epoch Support**: Allows setting a custom epoch start time.
- **Optimized Performance**: High throughput achieved through efficient synchronization using Compare-And-Swap (CAS), minimizing contention and ensuring fast, reliable concurrent ID generation.

## Strategies

- **Clock Backward Handling**: The program requires monotonically increasing timestamps to ensure ID uniqueness. If a clock rollback occurs (e.g., due to NTP), a 50 ms tolerance is allowed. Within this tolerance, the generator uses the last recorded timestamp and continues incrementing the sequence; if the rollback exceeds 50 ms, an exception is thrown.
- **Sequence Overflow Handling**: For sequence overflow within a single millisecond, the strategy is to wait for the next timestamp.

## ID Structure

| Component      | Bits  | Description                                             |
|----------------|-------|---------------------------------------------------------|
| Sign Bit       | 1     | Reserved for the sign (ensuring a positive ID).         |
| Timestamp      | 41    | Time in milliseconds since custom epoch.                |
| Data Center ID | 5     | Identifies the data center (0-31).                      |
| Machine ID     | 5     | Identifies the machine within the data center (0-31).   |
| Sequence       | 12    | Counter for IDs generated within the same millisecond.  |

## Configuration

The Snowflake ID generator ensures distributed uniqueness using data center and machine IDs, with the option to set a custom epoch start time.

### Examples

**With Default Epoch:**

```java
SnowflakeIdGenerator generator = new SnowflakeIdGenerator(9, 29);
long uniqueId = generator.nextId();
System.out.println("Generated ID: " + uniqueId);
```

**With Custom Epoch:**

```java
long customEpoch = 1680100000000L; // Custom epoch in milliseconds
SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 19, customEpoch);
long uniqueId = generator.nextId();
System.out.println("Generated ID: " + uniqueId);
```

## Installation

To install this library to your local Maven repository:

```bash
mvn clean install
```

## Running Tests

Run unit tests to verify ID generation and performance:

```bash
mvn test
```

## Maven Dependency

To use this library in your project, add the following to your `pom.xml`:

```xml
<dependency>
    <groupId>com.hmwcs</groupId>
    <artifactId>hmwcs-snowflake</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Test Results (M1 Pro Chip)

```
Single-threaded: 
Generated 50,000,000 unique IDs in 45.12 seconds.  
Throughput: 1,108,074.52 IDs/second

Multi-threaded: 
Generated 50,000,000 unique IDs concurrently in 21.30 seconds.  
Throughput: 2,347,386.96 IDs/second
```

## Reference

This implementation is based on Twitter's Snowflake algorithm. For more details, see the [Twitter Engineering Blog](https://blog.twitter.com/engineering/en_us/a/2010/announcing-snowflake).

## License

This project is licensed under the MIT License.
