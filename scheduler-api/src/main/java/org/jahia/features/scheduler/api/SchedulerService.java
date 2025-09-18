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
package org.jahia.features.scheduler.api;

import java.util.List;
import java.util.UUID;

public interface SchedulerService {

    String schedule(String name, Runnable task, String cronExpression) throws InvalidExpressionException;

    String scheduleWithFixedDelay(String name, Runnable task, String durationExpression) throws InvalidExpressionException;

    List<String> list();

    void cancel(String taskId) throws TaskNotFoundException;

    TaskBuilder newTask();

    class TaskBuilder {

        private final SchedulerService service;
        private String name;
        private Runnable task;
        private String cronExpression;
        private String durationExpression;

        public TaskBuilder(SchedulerService service) {
            this.service = service;
        }

        public TaskBuilder name(String name) {
            this.name = name;
            return this;
        }

        public TaskBuilder task(Runnable task) {
            this.task = task;
            return this;
        }

        public TaskBuilder cron(String cronExpression) {
            this.cronExpression = cronExpression;
            return this;
        }

        public TaskBuilder every(String durationExpression) {
            this.durationExpression = durationExpression;
            return this;
        }

        public String schedule() throws InvalidExpressionException {
            if (task == null) {
                throw new IllegalStateException("Task (Runnable) must be provided");
            }
            if (name == null || name.isEmpty()) {
                name = "Task-" + UUID.randomUUID();
            }
            if (cronExpression != null && !cronExpression.isEmpty()) {
                return service.schedule(name, task, cronExpression);
            } else if (durationExpression != null && !durationExpression.isEmpty()) {
                return service.scheduleWithFixedDelay(name, task, durationExpression);
            } else {
                throw new IllegalStateException("Either cron expression or duration expression must be provided");
            }
        }
    }
}
