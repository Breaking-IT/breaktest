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

package org.apache.jmeter.gui.action;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;

final class AiTaskWorkspace {
    private AiTaskWorkspace() {
    }

    static File unsavedPlanDirectory() {
        try {
            // Keep generated fixtures for inspection. Do not inherit the installation's source repository.
            return Files.createTempDirectory("breaktest-ai-task-").toFile();
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot create AI task workspace", ex);
        }
    }

    static boolean hasNoSamplers(JMeterTreeNode node) {
        if (node == null) {
            return true;
        }
        var children = node.depthFirstEnumeration();
        while (children.hasMoreElements()) {
            if (children.nextElement() instanceof JMeterTreeNode child && child.getTestElement() instanceof Sampler) {
                return false;
            }
        }
        return true;
    }
}
