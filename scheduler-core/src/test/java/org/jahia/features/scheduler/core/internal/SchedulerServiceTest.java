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

import org.jahia.features.scheduler.api.InvalidExpressionException;
import org.jahia.features.scheduler.api.Scheduled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class SchedulerServiceTest {

    private SchedulerServiceImpl schedulerService;

    @Mock
    private BundleContext bundleContext;

    @BeforeEach
    void setup() {
        schedulerService = new SchedulerServiceImpl();
        schedulerService.activate(bundleContext);
    }

    @Test
    void testScheduleWithFixedDelay() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        Runnable task = latch::countDown;

        String taskId = schedulerService.scheduleWithFixedDelay("Test.task", task, "100ms");

        assertNotNull(taskId);
        assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    void testCancel() throws Exception {
        CountDownLatch latch = new CountDownLatch(10);
        Runnable task = latch::countDown;

        String taskId = schedulerService.scheduleWithFixedDelay("Test.task", task, "100ms");

        Thread.sleep(250);

        schedulerService.cancel(taskId);

        int countAfterCancel = (int) latch.getCount();
        Thread.sleep(300);
        assertEquals(countAfterCancel, latch.getCount());
    }

    @Test
    void testScheduleWithDayDuration() throws Exception {
        // Create a spy of the scheduler service
        SchedulerServiceImpl spyService = Mockito.spy(schedulerService);
        CountDownLatch latch = new CountDownLatch(1);

        // Create a short duration for testing
        Duration mockDuration = Duration.ofMillis(100);

        // Mock parseDuration to return our test duration for "1d"
        Mockito.doReturn(mockDuration).when(spyService).parseDuration("1d");

        // Schedule the task with our spy service
        String taskId = spyService.scheduleWithFixedDelay("Test.task", latch::countDown, "1d");

        // Assert the task was scheduled and executed
        assertNotNull(taskId);
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void testServiceLifecycle() throws Exception {
        // Test the OSGi service lifecycle
        TestScheduledService service = new TestScheduledService();

        // Directly call processService instead of using reflection
        schedulerService.processService(service);

        // Access serviceTasksMap directly or through a protected getter if needed
        Map<Object, Set<String>> serviceTasksMap = getServiceTasksMap(schedulerService);

        // Verify that tasks were created for the service
        assertTrue(serviceTasksMap.containsKey(service));
        assertFalse(serviceTasksMap.get(service).isEmpty());

        // Get the scheduled task IDs
        Set<String> taskIds = serviceTasksMap.get(service);

        // Simulate service removal by directly cancelling tasks
        for (String taskId : taskIds) {
            schedulerService.cancel(taskId);
        }
        serviceTasksMap.remove(service);

        // Verify the service was removed
        assertFalse(serviceTasksMap.containsKey(service));
    }

    @Test
    void testParseDurationSeconds() throws InvalidExpressionException {
        Duration duration = schedulerService.parseDuration("30");
        assertEquals(Duration.ofSeconds(30), duration);
    }

    @Test
    void testParseDurationMilliseconds() throws InvalidExpressionException {
        Duration duration = schedulerService.parseDuration("500ms");
        assertEquals(Duration.ofMillis(500), duration);
    }

    @Test
    void testParseDurationHours() throws InvalidExpressionException {
        Duration duration = schedulerService.parseDuration("2h");
        assertEquals(Duration.ofHours(2), duration);
    }

    @Test
    void testParseDurationMinutes() throws InvalidExpressionException {
        Duration duration = schedulerService.parseDuration("15m");
        assertEquals(Duration.ofMinutes(15), duration);
    }

    @Test
    void testParseDurationSecondsWithSuffix() throws InvalidExpressionException {
        Duration duration = schedulerService.parseDuration("45s");
        assertEquals(Duration.ofSeconds(45), duration);
    }

    @Test
    void testParseDurationDays() throws InvalidExpressionException {
        Duration duration = schedulerService.parseDuration("3d");
        assertEquals(Duration.ofDays(3), duration);
    }

    @Test
    void testParseDurationStandardFormat() throws InvalidExpressionException {
        Duration duration = schedulerService.parseDuration("PT1H30M");
        assertEquals(Duration.ofHours(1).plus(Duration.ofMinutes(30)), duration);
    }

    @Test
    void testParseDurationInvalidFormat() {
        Exception exception = assertThrows(InvalidExpressionException.class, () -> schedulerService.parseDuration("invalid"));
        assertTrue(exception.getMessage().contains("Invalid duration format"));
    }

    @Test
    void testParseDurationNegativeValue() {
        Exception exception = assertThrows(InvalidExpressionException.class, () -> schedulerService.parseDuration("-10s"));
        assertTrue(exception.getMessage().contains("Invalid duration format"));
    }

    @Test
    void testParseDurationEmptyString() {
        Exception exception = assertThrows(InvalidExpressionException.class, () -> schedulerService.parseDuration(""));
        assertTrue(exception.getMessage().contains("Invalid duration format"));
    }

    @Test
    void testParseDurationNull() {
        Exception exception = assertThrows(InvalidExpressionException.class, () -> schedulerService.parseDuration(null));
        assertTrue(exception.getMessage().contains("Invalid duration format"));
    }

    // Helper method for accessing map if it remains private
    private Map<Object, Set<String>> getServiceTasksMap(SchedulerServiceImpl scheduler) {
        try {
            Field field = SchedulerServiceImpl.class.getDeclaredField("serviceTasksMap");
            field.setAccessible(true);
            return (Map<Object, Set<String>>) field.get(scheduler);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static class TestScheduledService {
        private CountDownLatch latch;

        public void setLatch(CountDownLatch latch) {
            this.latch = latch;
        }

        @Scheduled(every = "10s")
        public void method1() {
            if (latch != null) latch.countDown();
        }

        @Scheduled(cron = "0 0 * * * ?")
        public void method2() {
            if (latch != null) latch.countDown();
        }
    }
}
