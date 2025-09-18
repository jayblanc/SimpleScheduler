# SimpleScheduler

A powerful and flexible OSGi-based task scheduler for Apache Karaf with annotation-driven simplicity and programmatic API support.

## Features

- **Annotation-driven scheduling** - Schedule tasks with simple `@Scheduled` annotations
- **Programmatic API** - Full control over task scheduling through the `SchedulerService`
- **Cron expressions** - Support for Quartz-style cron expressions
- **Fixed delay scheduling** - Schedule tasks with fixed delays using human-readable durations
- **Timeout handling** - Built-in timeout support with `@Timeout` annotation
- **OSGi integration** - Seamless integration with OSGi services and lifecycle
- **Apache Karaf ready** - Packaged as Karaf features for easy deployment

## Quick Start

### 1. Installation

Deploy the scheduler feature in your Karaf container:

```bash
# Add the features repository
feature:repo-add mvn:org.jahia.features/scheduler-features/1.0.0-SNAPSHOT/xml/features

# Install the scheduler
feature:install simple-scheduler
```

### 2. Annotation-Based Scheduling (Recommended)

The simplest way to schedule tasks is using annotations. Just annotate methods in your OSGi components:

```java
@Component(service = MyService.class, immediate = true)
public class MyService {

    @Scheduled(every = "30s")
    public void performMaintenance() {
        // This method runs every 30 seconds
        System.out.println("Performing maintenance...");
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyCleanup() {
        // This method runs daily at 2 AM
        System.out.println("Running daily cleanup...");
    }

    @Scheduled(cron = "*/10 * * * * ?", timeout = 5)
    public void quickTask() {
        // This method runs every 10 seconds with 5-second timeout
        try {
            Thread.sleep(7000); // Simulates work that might timeout
        } catch (InterruptedException e) {
            // Handle interruption due to timeout
        }
    }

    @Timeout
    public void handleTimeout(String taskIdentity) {
        // This method is called when any scheduled method times out
        System.out.println("Task " + taskIdentity + " timed out!");
    }
}
```

**That's it!** The scheduler automatically discovers your annotated methods when your OSGi component is activated and removes them when deactivated.

## Annotation Reference

### @Scheduled

Schedule methods to run automatically:

```java
@Scheduled(
    every = "5m",           // Run every 5 minutes (alternative to cron)
    cron = "0 */5 * * * ?", // Quartz cron expression (alternative to every)
    name = "my-task",       // Optional: custom task name
    timeout = 30,           // Optional: timeout in seconds (default: 30)
    timeoutUnit = TimeUnit.SECONDS // Optional: timeout unit (default: SECONDS)
)
```

**Duration formats** (for `every` parameter):
- `500ms` - 500 milliseconds
- `30s` - 30 seconds  
- `5m` - 5 minutes
- `2h` - 2 hours
- `1d` - 1 day

**Cron expressions** support Quartz format:
- `0 0 12 * * ?` - Daily at noon
- `0 15 10 ? * MON-FRI` - 10:15 AM on weekdays
- `0 0/5 14 * * ?` - Every 5 minutes starting at 2 PM

### @Timeout

Handle timeout events for long-running scheduled tasks:

```java
@Timeout
public void onTimeout(String taskIdentity) {
    // Called when any @Scheduled method in this component times out
    logger.warn("Task {} exceeded timeout", taskIdentity);
}
```

## Programmatic API

For advanced use cases, use the `SchedulerService` directly:

```java
@Component
public class MyAdvancedService {

    @Reference
    private SchedulerService schedulerService;

    public void scheduleCustomTask() {
        // Schedule with cron expression
        String taskId = schedulerService.schedule(
            "my-cron-task",
            () -> System.out.println("Cron task executed"),
            "0 */2 * * * ?"  // Every 2 minutes
        );

        // Schedule with fixed delay
        String taskId2 = schedulerService.scheduleWithFixedDelay(
            "my-delay-task",
            () -> System.out.println("Delay task executed"),
            "1m"  // Every minute
        );

        // Using the fluent builder API
        String taskId3 = schedulerService.newTask()
            .name("builder-task")
            .task(() -> System.out.println("Builder task executed"))
            .every("30s")
            .schedule();

        // List all scheduled tasks
        List<String> allTasks = schedulerService.list();
        System.out.println("Active tasks: " + allTasks);

        // Cancel a specific task
        schedulerService.cancel(taskId);
    }
}
```

### Builder API

The fluent builder provides a clean way to create tasks:

```java
schedulerService.newTask()
    .name("optional-name")          // Optional: defaults to auto-generated UUID
    .task(() -> doSomething())      // Required: the Runnable to execute
    .cron("0 0 * * * ?")           // Either cron expression...
    .every("5m")                   // ...or fixed delay (not both)
    .schedule();                   // Returns the task ID
```

## Architecture

The scheduler consists of several modules:

- **scheduler-api** - Public API with annotations and service interfaces
- **scheduler-core** - Implementation with OSGi service tracking and cron parsing
- **scheduler-features** - Karaf feature definitions for easy deployment
- **scheduler-samples** - Example implementations and usage patterns
- **scheduler-itests** - Integration tests running in Karaf

## Advanced Features

### Automatic Service Discovery

The scheduler uses OSGi ServiceTracker to automatically:
- Detect new services with `@Scheduled` methods
- Register their scheduled tasks
- Clean up tasks when services are unregistered
- Handle service lifecycle transparently

### Timeout Management

Tasks can specify timeout values. When a task exceeds its timeout:
1. The task thread is interrupted
2. Any `@Timeout` annotated method in the same component is called
3. The task continues on its normal schedule

### Thread Safety

- All operations are thread-safe
- Uses `ScheduledExecutorService` with configurable thread pool
- Concurrent task execution is supported
- Safe service lifecycle management

## Building from Source

```bash
# Clone the repository
git clone <repository-url>
cd myScheduler

# Build with Maven
mvn clean install

# Run integration tests
mvn clean verify -Pintegration-tests
```

## Requirements

- Java 17+
- Apache Karaf 4.5+
- OSGi framework with Declarative Services (SCR)

## Dependencies

The scheduler uses these key libraries:
- **cron-utils** (9.2.1) - Cron expression parsing and validation
- **Apache Karaf** (4.5.0) - OSGi runtime and features
- **OSGi Declarative Services** - Component lifecycle management

## License

Licensed under the Apache License, Version 2.0. See LICENSE file for details.

## Examples

Check the `scheduler-samples` module for complete working examples:

- **scheduler-periodic-task-sample** - Demonstrates annotation usage with various scheduling patterns

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for your changes
4. Ensure all tests pass
5. Submit a pull request

## Support

For issues and questions:
- Check the integration tests for usage examples
- Review the sample implementations
- Create an issue in the project repository
