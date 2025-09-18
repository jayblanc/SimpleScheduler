/*
 * Copyright (C) 2002-2025 Jahia Solutions Group SA. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jahia.features.scheduler.core.internal;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import org.jahia.features.scheduler.api.InvalidExpressionException;
import org.jahia.features.scheduler.api.Scheduled;
import org.jahia.features.scheduler.api.SchedulerService;
import org.jahia.features.scheduler.api.TaskNotFoundException;
import org.jahia.features.scheduler.api.Timeout;
import org.osgi.framework.*;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;

@Component(
        service = SchedulerService.class,
        immediate = true
)
public class SchedulerServiceImpl implements SchedulerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerServiceImpl.class);

    private ScheduledExecutorService executor;
    private ServiceTracker<Object, Object> serviceTracker;
    private BundleContext bundleContext;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final Map<Object, Set<String>> serviceTasksMap = new ConcurrentHashMap<>();

    @Activate
    public void activate(BundleContext context) {
        LOGGER.info("Activating SchedulerService");
        this.bundleContext = context;
        this.executor = Executors.newScheduledThreadPool(10);
        this.startServiceTracker();
    }

    @Deactivate
    public void deactivate() {
        LOGGER.info("Deactivating SchedulerService");
        if (serviceTracker != null) {
            serviceTracker.close();
        }
        if (executor != null) {
            executor.shutdown();
        }
        scheduledTasks.clear();
    }

    @Override
    public String schedule(String name, Runnable task, String cronExpression) throws InvalidExpressionException {
        LOGGER.info("Scheduling task with cron expression: {}", cronExpression);
        String taskId = UUID.randomUUID().toString();
        CronParser parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
        Cron cron;
        try {
            cron = parser.parse(cronExpression);
            cron.validate();
        } catch (IllegalArgumentException e) {
            throw new InvalidExpressionException("Invalid cron expression: " + cronExpression, e);
        }
        ScheduledFuture<?> future = scheduleNextExecution(task, cron, taskId, name);
        scheduledTasks.put(taskId, future);
        return taskId;
    }

    @Override
    public String scheduleWithFixedDelay(String name, Runnable task, String durationExpression) throws InvalidExpressionException {
        LOGGER.info("Scheduling task with fixed delay: {}", durationExpression);
        String taskId = UUID.randomUUID().toString();
        Duration duration = parseDuration(durationExpression);
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(task, 0, duration.toMillis(), TimeUnit.MILLISECONDS);
        scheduledTasks.put(taskId, future);
        return taskId;
    }

    @Override
    public List<String> list() {
        LOGGER.debug("Listing all scheduled tasks");
        return new ArrayList<>(scheduledTasks.keySet());
    }

    @Override
    public void cancel(String taskId) throws TaskNotFoundException {
        LOGGER.info("Cancelling task with ID: {}", taskId);
        if (!scheduledTasks.containsKey(taskId)) {
            throw new TaskNotFoundException(taskId);
        }

        ScheduledFuture<?> future = scheduledTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public TaskBuilder newTask() {
        return new TaskBuilder(this);
    }

    private void startServiceTracker() {
        try {
            String filterString = "(&(" + Constants.OBJECTCLASS + "=*)(!(" + Constants.OBJECTCLASS + "=" + SchedulerService.class.getName() + ")))";
            Filter filter = bundleContext.createFilter(filterString);
            if ( filter != null ) {
                serviceTracker = new ServiceTracker<>(bundleContext, filter, new ServiceTrackerCustomizer<Object, Object>() {
                    @Override public synchronized Object addingService(ServiceReference<Object> reference) {
                        LOGGER.info("New service detected: {}", reference);
                        return handleServiceAdded(reference);
                    }

                    @Override public synchronized void modifiedService(ServiceReference<Object> reference, Object service) {
                        LOGGER.info("Service modified: {}", reference);
                        handleServiceRemoved(service);
                        handleServiceAdded(reference);
                    }

                    @Override public synchronized void removedService(ServiceReference<Object> reference, Object service) {
                        LOGGER.info("Service removed: {}", reference);
                        handleServiceRemoved(service);
                    }
                });
                serviceTracker.open(true);
                LOGGER.info("Service tracker started with filter: {}", filterString);
            }
        } catch (InvalidSyntaxException e) {
            LOGGER.error("Failed to create service tracker filter", e);
        }
    }

    private Object handleServiceAdded(ServiceReference<Object> reference) {
        Object service = bundleContext.getService(reference);
        if (service != null) {
            try {
                LOGGER.info("Processing service {} for scheduled tasks", service.getClass());
                processService(service);
            } catch (InvalidExpressionException e) {
                LOGGER.error("Failed to process service {} for scheduled tasks", service.getClass(), e);
            }
        }
        return service;
    }

    private void handleServiceRemoved(Object service) {
        Set<String> taskIds = serviceTasksMap.remove(service);
        if (taskIds != null) {
            for (String taskId : taskIds) {
                // if tasks is running let it finish (maybe with a timeout)...
                try {
                    LOGGER.info("Cancelling task with ID {} for removed service {}", taskId, service.getClass());
                    cancel(taskId);
                } catch (TaskNotFoundException e) {
                    LOGGER.warn("Task with ID {} not found during service {} removal", taskId, service.getClass(), e);
                }
            }
        }
    }

    protected void processService(Object service) throws InvalidExpressionException {
        Class<?> serviceClass = service.getClass();
        Set<String> taskIds = new HashSet<>();

        Method timeoutMethod = findTimeoutMethod(serviceClass);
        if (timeoutMethod != null) {
            LOGGER.info("Found timeout method {} for service {}", timeoutMethod.getName(), serviceClass.getSimpleName());
        }

        for (Method method : serviceClass.getMethods()) {
            Scheduled annotation = method.getAnnotation(Scheduled.class);
            if (annotation != null) {
                String taskId = scheduleMethodCall(service, method, annotation, timeoutMethod);
                if (taskId != null) {
                    taskIds.add(taskId);
                }
            }
        }

        if (!taskIds.isEmpty()) {
            serviceTasksMap.put(service, taskIds);
        }
    }

    private Method findTimeoutMethod(Class<?> serviceClass) {
        for (Method method : serviceClass.getMethods()) {
            if (method.getAnnotation(Timeout.class) != null) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length == 0 || (paramTypes.length == 1 && paramTypes[0] == String.class)) {
                    return method;
                } else {
                    LOGGER.warn("Timeout method {} has invalid signature. Expected no parameters or one String parameter.", method.getName());
                }
            }
        }
        return null;
    }

    private String scheduleMethodCall(Object service, Method method, Scheduled annotation, Method timeoutMethod) throws InvalidExpressionException {
        String every = annotation.every();
        String cron = annotation.cron();
        String name = annotation.name();
        int timeout = annotation.timeout();
        TimeUnit timeoutUnit = annotation.timeoutUnit();
        String taskId = null;

        if (name.isEmpty()) {
            name = service.getClass().getSimpleName() + "." + method.getName();
        }

        Runnable taskWithTimeout = createTaskWithTimeout(service, method, timeoutMethod, timeout, timeoutUnit, name);

        if (!every.isEmpty()) {
            taskId = scheduleWithFixedDelay(name, taskWithTimeout, every);
            LOGGER.info("Scheduled method {}.{} with fixed delay '{}' and task name '{}'", service.getClass().getName(), method.getName(), every, name);
        } else if (!cron.isEmpty()) {
            taskId = schedule(name, taskWithTimeout, cron);
            LOGGER.info("Scheduled method {}.{} with cron expression '{}' and task name '{}'", service.getClass().getName(), method.getName(), cron, name);
        }

        return taskId;
    }

    private Runnable createTaskWithTimeout(Object service, Method method, Method timeoutMethod, int timeout, TimeUnit timeoutUnit, String name) {
        return () -> {
            Future<Void> methodExecution = executor.submit(() -> {
                try {
                    LOGGER.info("Invoking scheduled method {}.{} with task name {}", service.getClass().getName(), method.getName(), name);
                    method.invoke(service);
                } catch (Exception e) {
                    LOGGER.error("Failed to invoke scheduled method for task with name {}", name, e);
                }
                return null;
            });

            try {
                methodExecution.get(timeout, timeoutUnit);
            } catch (TimeoutException e) {
                LOGGER.warn("Method execution timed out for task with name {}, calling timeout handler", name);
                methodExecution.cancel(true);

                if (timeoutMethod != null) {
                    try {
                        Class<?>[] paramTypes = timeoutMethod.getParameterTypes();
                        if (paramTypes.length == 0) {
                            LOGGER.info("Invoking timeout method {} (no parameters) for task with name '{}'", timeoutMethod.getName(), name);
                            timeoutMethod.invoke(service);
                        } else if (paramTypes.length == 1 && paramTypes[0] == String.class) {
                            LOGGER.info("Invoking timeout method {} for task with name '{}'", timeoutMethod.getName(), name);
                            timeoutMethod.invoke(service, name);
                        }
                    } catch (Exception timeoutException) {
                        LOGGER.error("Failed to invoke timeout method for task with name {}", name, timeoutException);
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                LOGGER.error("Error during method execution for task with name {}", name, e);
            }
        };
    }

    protected Duration parseDuration(String durationExpression) throws InvalidExpressionException {
        try {
            if (durationExpression.matches("^\\d+$")) {
                return Duration.ofSeconds(Long.parseLong(durationExpression));
            } else if (durationExpression.endsWith("ms")) {
                String value = durationExpression.substring(0, durationExpression.length() - 2);
                return Duration.ofMillis(Long.parseLong(value));
            } else if (durationExpression.matches("^\\d+[hms]$")) {
                return Duration.parse("PT" + durationExpression);
            } else if (durationExpression.endsWith("d")) {
                String value = durationExpression.substring(0, durationExpression.length() - 1);
                return Duration.parse("P" + value + "D");
            } else {
                return Duration.parse(durationExpression);
            }
        } catch (Exception e) {
            throw new InvalidExpressionException("Invalid duration format: " + durationExpression, e);
        }
    }

    private ScheduledFuture<?> scheduleNextExecution(Runnable task, Cron cron, String taskId, String taskName) {
        LOGGER.info("Calculating next execution time for task: [{}]{} and Cron: {}", taskId, taskName, cron.asString());
        ZonedDateTime now = ZonedDateTime.now();
        ExecutionTime executionTime = ExecutionTime.forCron(cron);
        Optional<Duration> durationOpt = executionTime.timeToNextExecution(now);
        if (durationOpt.isEmpty()) {
            LOGGER.error("Unable to calculate next execution time for cron expression");
            return null;
        }
        Duration duration = durationOpt.get();
        LOGGER.info("Duration to next execution (ms): {}", duration.toMillis());
        return executor.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.error("Unexpected error during task execution", e);
            } finally {
                ScheduledFuture<?> nextExecution = scheduleNextExecution(task, cron, taskId, taskName);
                if (nextExecution != null && !scheduledTasks.isEmpty() && scheduledTasks.containsKey(taskId)) {
                    scheduledTasks.put(taskId, nextExecution);
                }
            }
        }, duration.toMillis(), TimeUnit.MILLISECONDS);
    }

}
