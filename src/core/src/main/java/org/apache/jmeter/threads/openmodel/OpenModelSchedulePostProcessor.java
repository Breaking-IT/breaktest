/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.threads.openmodel;

import org.apache.jmeter.save.LoadedTreePostProcessor;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;

import com.google.auto.service.AutoService;

/** Migrates legacy open-model schedules before a loaded JMX reaches the GUI or engine. */
@AutoService(LoadedTreePostProcessor.class)
public class OpenModelSchedulePostProcessor implements LoadedTreePostProcessor {
    @Override
    public void process(HashTree loadedTree) {
        for (Object element : loadedTree.list()) {
            if (element instanceof ThreadGroup group) {
                String schedule = group.getOpenModelSchedule();
                String migrated = OpenModelScheduleMigration.migrateOpenModelSchedule(schedule);
                if (!schedule.equals(migrated)) {
                    group.setOpenModelSchedule(migrated);
                }
            } else if (element instanceof OpenModelThreadGroup group) {
                String schedule = group.getScheduleString();
                String migrated = OpenModelScheduleMigration.migrateOpenModelSchedule(schedule);
                if (!schedule.equals(migrated)) {
                    group.setScheduleString(migrated);
                }
            }
            HashTree children = loadedTree.getTree(element);
            if (children != null) {
                process(children);
            }
        }
    }
}
