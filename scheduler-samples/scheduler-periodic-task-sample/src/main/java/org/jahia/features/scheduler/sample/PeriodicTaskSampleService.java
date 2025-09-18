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
package org.jahia.features.scheduler.sample;

import org.jahia.features.scheduler.api.Scheduled;
import org.jahia.features.scheduler.api.Timeout;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Jerome Blanchard
 */
@Component(service = PeriodicTaskSampleService.class, immediate = true)
public class PeriodicTaskSampleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PeriodicTaskSampleService.class);

    private int everyCpt = 0;
    private int cronCpt = 0;
    private int timeoutCpt = 0;

    public int getEveryCpt() {
        return everyCpt;
    }

    public int getCronCpt() {
        return cronCpt;
    }

    public int getTimeoutCpt() {
        return timeoutCpt;
    }

    @Scheduled(every = "5s")
    public void executeEvery5Seconds() {
        LOGGER.info("Executing task every 5 seconds");
        everyCpt++;
    }

    @Scheduled(cron = "*/5 * * * * ?")
    public void executeOnCronSchedule() {
        LOGGER.info("Executing task on cron schedule (every 5 seconds)");
        cronCpt++;
    }

    @Scheduled(cron = "*/5 * * * * ?", timeout = 10)
    public void executeOnCronScheduleTimeout() {
        LOGGER.info("Executing task (with timeout 10s and duration 15s) on cron schedule (every 5 seconds)");
        try {
            LOGGER.info("Simulating long running task that will timeout");
            Thread.sleep(15000); // Sleep for 15 seconds to trigger timeout
        } catch (InterruptedException e) {
            LOGGER.info("Task was interrupted due to timeout");
        }
    }

    @Timeout
    public void handleTimeout(String identity) {
        LOGGER.warn("Timeout occurred! Executing timeout handler for task with identity: {}", identity);
        timeoutCpt++;
    }

}
