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

package org.apache.jmeter.gui.util;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.AbstractThreadGroup;

/** Resolves runtime sample results back to their source nodes in the editable test plan. */
public final class SampleResultNodeResolver {

    private static final String MODULE_CONTROLLER_CLASS = "org.apache.jmeter.control.ModuleController"; // $NON-NLS-1$
    private static final Pattern JMETER_THREAD_NAME = Pattern.compile("(.+) \\d+-\\d+$"); // $NON-NLS-1$

    private SampleResultNodeResolver() {
    }

    public static JMeterTreeNode find(SampleResult sampleResult) {
        if (sampleResult == null) {
            return null;
        }
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return null;
        }
        JMeterTreeNode root = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
        JMeterTreeNode sourceNode = findBySourcePath(root, sampleResult.getSourceTestElementPath());
        return sourceNode == null ? findByRuntimeIdentity(root, sampleResult) : sourceNode;
    }

    private static JMeterTreeNode findBySourcePath(
            JMeterTreeNode root, List<SampleResult.TestElementPathEntry> sourcePath) {
        if (sourcePath.isEmpty()) {
            return null;
        }
        JMeterTreeNode direct = resolvePathSuffix(root, sourcePath, 0);
        if (direct != null) {
            return direct;
        }
        for (int i = 0; i < sourcePath.size(); i++) {
            if (MODULE_CONTROLLER_CLASS.equals(sourcePath.get(i).className())) {
                for (int start = i + 1; start < sourcePath.size(); start++) {
                    JMeterTreeNode fragmentNode = resolvePathSuffix(root, sourcePath, start);
                    if (fragmentNode != null) {
                        return fragmentNode;
                    }
                }
                break;
            }
        }
        return null;
    }

    private static JMeterTreeNode resolvePathSuffix(
            JMeterTreeNode root, List<SampleResult.TestElementPathEntry> sourcePath, int start) {
        JMeterTreeNode current = findDescendant(root, sourcePath.get(start));
        for (SampleResult.TestElementPathEntry pathEntry : sourcePath.subList(start + 1, sourcePath.size())) {
            current = findChild(current, pathEntry);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static JMeterTreeNode findByRuntimeIdentity(JMeterTreeNode root, SampleResult sampleResult) {
        List<JMeterTreeNode> candidates = new ArrayList<>();
        collectNamedSamplers(root, sampleResult.getSampleLabel(), candidates);
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        String resultThreadGroup = threadGroupName(sampleResult.getThreadName());
        List<JMeterTreeNode> matchingThreadGroup = candidates.stream()
                .filter(candidate -> resultThreadGroup.equals(threadGroupName(candidate)))
                .toList();
        return matchingThreadGroup.size() == 1 ? matchingThreadGroup.get(0) : null;
    }

    private static void collectNamedSamplers(
            JMeterTreeNode parent, String sampleLabel, List<JMeterTreeNode> candidates) {
        if (parent.getTestElement() instanceof Sampler && Objects.equals(parent.getName(), sampleLabel)) {
            candidates.add(parent);
        }
        Enumeration<?> children = parent.children();
        while (children.hasMoreElements()) {
            collectNamedSamplers((JMeterTreeNode) children.nextElement(), sampleLabel, candidates);
        }
    }

    private static String threadGroupName(String threadName) {
        if (threadName == null || threadName.isEmpty()) {
            return ""; // $NON-NLS-1$
        }
        Matcher matcher = JMETER_THREAD_NAME.matcher(threadName);
        return matcher.matches() ? matcher.group(1) : threadName;
    }

    private static String threadGroupName(JMeterTreeNode node) {
        return node.getPathToThreadGroup().stream()
                .map(JMeterTreeNode::getTestElement)
                .filter(AbstractThreadGroup.class::isInstance)
                .map(AbstractThreadGroup.class::cast)
                .map(AbstractThreadGroup::getName)
                .findFirst()
                .orElse(""); // $NON-NLS-1$
    }

    private static JMeterTreeNode findDescendant(
            JMeterTreeNode parent, SampleResult.TestElementPathEntry pathEntry) {
        JMeterTreeNode child = findChild(parent, pathEntry);
        if (child != null) {
            return child;
        }
        Enumeration<?> children = parent.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode descendant = findDescendant((JMeterTreeNode) children.nextElement(), pathEntry);
            if (descendant != null) {
                return descendant;
            }
        }
        return null;
    }

    private static JMeterTreeNode findChild(
            JMeterTreeNode parent, SampleResult.TestElementPathEntry pathEntry) {
        if (parent == null) {
            return null;
        }
        int occurrence = 0;
        Enumeration<?> children = parent.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode) children.nextElement();
            Object userObject = child.getUserObject();
            if (userObject != null
                    && userObject.getClass().getName().equals(pathEntry.className())
                    && Objects.equals(child.getName(), pathEntry.name())) {
                if (occurrence == pathEntry.occurrence()) {
                    return child;
                }
                occurrence++;
            }
        }
        return null;
    }
}
