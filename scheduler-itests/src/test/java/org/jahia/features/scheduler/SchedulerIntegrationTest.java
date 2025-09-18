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
package org.jahia.features.scheduler;

import org.apache.karaf.itests.KarafTestSupport;
import org.jahia.features.scheduler.api.SchedulerService;
import org.jahia.features.scheduler.sample.PeriodicTaskSampleService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.*;
import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.debugConfiguration;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.editConfigurationFilePut;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class SchedulerIntegrationTest extends KarafTestSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerIntegrationTest.class);

    @Inject
    private BundleContext bundleContext;

    @Configuration
    public Option[] config() {
        List<Option> options = new LinkedList<>();
        options.add(editConfigurationFilePut("etc/org.ops4j.pax.logging.cfg","log4j2.logger.scheduler.name","org.jahia.features.scheduler"));
        options.add(editConfigurationFilePut("etc/org.ops4j.pax.logging.cfg","log4j2.logger.scheduler.level","INFO"));
        options.add(editConfigurationFilePut("etc/org.ops4j.pax.logging.cfg","log4j2.logger.scheduler-sample.name","org.jahia.features.scheduler.sample"));
        options.add(editConfigurationFilePut("etc/org.ops4j.pax.logging.cfg","log4j2.logger.scheduler-sample.level","DEBUG"));
        String karafDebug = System.getProperty("it.karaf.debug");
        if (karafDebug != null) {
            LOGGER.warn("Found system Karaf Debug system property, activating configuration: {}", karafDebug);
            String port = "5006";
            boolean hold = true;
            if (karafDebug.trim().isEmpty()) {
                String[] debugOptions = karafDebug.split(",");
                for (String debugOption : debugOptions) {
                    String[] debugOptionParts = debugOption.split(":");
                    if ("hold".equals(debugOptionParts[0])) {
                        hold = Boolean.parseBoolean(debugOptionParts[1].trim());
                    }
                    if ("port".equals(debugOptionParts[0])) {
                        port = debugOptionParts[1].trim();
                    }
                }
            }
            options.add(0, debugConfiguration(port, hold));
        }
        return Stream.concat(Stream.of(super.config()), options.stream()).toArray(Option[]::new);
    }

    @Test
    public void testSchedulerService() throws Exception {
        LOGGER.info("Testing scheduler service");

        // Install SimpleScheduler feature
        addFeaturesRepository(maven("org.jahia.features", "scheduler-features").type("xml").classifier("features").version("1.0.0-SNAPSHOT").getURL());
        installAndAssertFeature("simple-scheduler");

        // Check that bundle exists and retrieve the service
        assertSchedulerBundleExists();
        ServiceReference<?> schedulerServiceRef = bundleContext.getServiceReference(SchedulerService.class.getName());
        assertNotNull("SchedulerService is NOT available", schedulerServiceRef);
        SchedulerService schedulerService = (SchedulerService) bundleContext.getService(schedulerServiceRef);
        assertNotNull("Unable to get SchedulerService reference", schedulerService);
        LOGGER.info("1. Scheduler task list size {}", schedulerService.list().size());
        assertEquals("Scheduler should not have any task at this point", 0, schedulerService.list().size());

        // Test a programmatic job scheduling
        final CountDownLatch latch = new CountDownLatch(3);
        String taskid = schedulerService.newTask().name("Test.task.cron").task(latch::countDown).every("500ms").schedule();
        assertTrue("Task id " + taskid + " not found in scheduler", schedulerService.list().contains(taskid));
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        LOGGER.info("2. Scheduler task list size {}", schedulerService.list().size());
        assertEquals("Scheduler should have one task at this point", 1, schedulerService.list().size());

        // Install a sample bundle with a service having a method annotated with @Scheduled
        Bundle periodicTaskBundle = bundleContext.installBundle(maven("org.jahia.features", "scheduler-periodic-task-sample").type("jar").version("1.0.0-SNAPSHOT").getURL());
        assertNotNull("Unable to install periodic task bundle", periodicTaskBundle);
        periodicTaskBundle.start();
        LOGGER.info("Bundle scheduler-periodic-task-sample installed. State: {}", getBundleStateAsString(periodicTaskBundle.getState()));
        assertEquals("Bundle is not is ACTIVE state", Bundle.ACTIVE, periodicTaskBundle.getState());
        ServiceReference<?> periodicTaskServiceRef = bundleContext.getServiceReference("org.jahia.features.scheduler.sample.PeriodicTaskSampleService");
        assertNotNull("Sample service is not available", periodicTaskServiceRef);
        PeriodicTaskSampleService periodicTasksService = (PeriodicTaskSampleService) bundleContext.getService(periodicTaskServiceRef);
        assertNotNull("Unable to get a ", periodicTasksService);
        LOGGER.info("Scheduled every 5s executed {} times", periodicTasksService.getEveryCpt());
        LOGGER.info("Scheduled cron executed {} times", periodicTasksService.getCronCpt());
        LOGGER.info("Timeout called {} times", periodicTasksService.getTimeoutCpt());

        // Wait for the OSGI framework to detect new service and methods to be processed for task creation
        LOGGER.info("Waiting for ServiceTracker to detect annotated methods...");
        long startTime = System.currentTimeMillis();
        while (schedulerService.list().size() < 3 && (System.currentTimeMillis() - startTime < 10000)) {
            LOGGER.info("Current task count: {}", schedulerService.list().size());
            Thread.sleep(500);
        }

        // Check that the tasks declared in the sample service are now in the scheduler task list
        LOGGER.info("3. Scheduler task list size {}", schedulerService.list().size());
        assertEquals("Scheduler should have 4 tasks at this point", 4, schedulerService.list().size());

        // Check task execution
        LOGGER.info("4. Wait for cron method to be called...");
        int executionCount = 0;
        startTime = System.currentTimeMillis();
        while (executionCount == 0 && (System.currentTimeMillis() - startTime < 20000)) {
            executionCount = periodicTasksService.getCronCpt();
            Thread.sleep(500);
        }
        assertTrue("Nothing happened during 20s", executionCount > 0);
        LOGGER.info("Scheduled every 5s executed {} times", periodicTasksService.getEveryCpt());
        assertTrue("Scheduled every 5s should have been executed at least 1 times", periodicTasksService.getEveryCpt() >= 1);
        LOGGER.info("Scheduled cron executed {} times", periodicTasksService.getCronCpt());
        assertTrue("Scheduled cron should have been executed at least 1 times", periodicTasksService.getCronCpt() >= 1);
        LOGGER.info("Timeout called {} times", periodicTasksService.getTimeoutCpt());
        assertEquals("Timeout should have NOT been called yet", 0, periodicTasksService.getTimeoutCpt());

        //Wait for timeout to occur
        LOGGER.info("5. Waiting for timeout to occurred...");
        startTime = System.currentTimeMillis();
        while (periodicTasksService.getTimeoutCpt() == 0 && (System.currentTimeMillis() - startTime < 20000)) {
            Thread.sleep(500);
        }
        assertTrue("Timeout handler should have been executed at least once", periodicTasksService.getTimeoutCpt() >= 1);
        LOGGER.info("Timeout called {} times", periodicTasksService.getTimeoutCpt());

        // Try to uninstall the bundle and check the task does not exists in the scheduler (or is stopped)
        LOGGER.info("6. Uninstall the sample bundle ...");
        bundleContext.ungetService(periodicTaskServiceRef);
        periodicTaskBundle.stop();
        periodicTaskBundle.uninstall();
        LOGGER.info("Sample Bundle uninstalled, wait a little bit and check scheduler tasks status...");
        Thread.sleep(500);

        // Check that the tasks declared in the sample service are now in the scheduler task list
        LOGGER.info("7. Scheduler task list size {}", schedulerService.list().size());
        assertEquals("Scheduler should have 1 tasks at this point", 1, schedulerService.list().size());

        bundleContext.ungetService(schedulerServiceRef);
    }

    private void assertSchedulerBundleExists() {
        boolean bundleFound = false;
        for (Bundle bundle : bundleContext.getBundles()) {
            if (bundle.getSymbolicName().contains("scheduler-core")) {
                LOGGER.info("Bundle scheduler-core found. State: {}", getBundleStateAsString(bundle.getState()));
                bundleFound = true;
            }
        }
        if (!bundleFound) {
            LOGGER.error("Bundle scheduler-core NOT found!");
        }
        assertTrue("Bundle scheduler-core not found", bundleFound);
    }

    private String getBundleStateAsString(int state) {
        return switch (state) {
            case Bundle.UNINSTALLED -> "UNINSTALLED";
            case Bundle.INSTALLED -> "INSTALLED";
            case Bundle.RESOLVED -> "RESOLVED";
            case Bundle.STARTING -> "STARTING";
            case Bundle.STOPPING -> "STOPPING";
            case Bundle.ACTIVE -> "ACTIVE";
            default -> "UNKNOWN (" + state + ")";
        };
    }
}
