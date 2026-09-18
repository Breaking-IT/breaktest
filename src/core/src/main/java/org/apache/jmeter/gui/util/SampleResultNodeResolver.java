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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
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

    // Neither retained results nor this cache should keep a closed test plan alive.
    private static final Map<SampleResult, WeakReference<JMeterTreeNode>> NAVIGATION_TARGETS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SampleResultNodeResolver() {
    }

    /** Resolves a navigation target, falling back to the nearest resolvable result ancestor. */
    public static JMeterTreeNode findForNavigation(SampleResult sampleResult) {
        for (SampleResult current = sampleResult; current != null; current = current.getParent()) {
            JMeterTreeNode node = navigationTarget(current);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    /** Binds displayed results before later edits can invalidate their recorded names and paths. */
    public static void rememberNavigationTargets(SampleResult sampleResult) {
        navigationTarget(sampleResult);
        for (SampleResult child : sampleResult.getSubResults()) {
            rememberNavigationTargets(child);
        }
    }

    private static JMeterTreeNode navigationTarget(SampleResult sampleResult) {
        WeakReference<JMeterTreeNode> remembered = NAVIGATION_TARGETS.get(sampleResult);
        if (remembered != null) {
            JMeterTreeNode node = remembered.get();
            GuiPackage gui = GuiPackage.getInstance();
            // A deleted node must not redirect to a different sampler with the same name.
            return node != null && gui != null && node.getRoot() == gui.getTreeModel().getRoot()
                    ? node : null;
        }
        JMeterTreeNode node = find(sampleResult);
        if (node != null) {
            NAVIGATION_TARGETS.put(sampleResult, new WeakReference<>(node));
        }
        return node;
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

    /** Resolves a recorded execution path, including paths expanded through module controllers. */
    public static JMeterTreeNode findBySourcePath(List<SampleResult.TestElementPathEntry> sourcePath) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return null;
        }
        return findBySourcePath((JMeterTreeNode) guiPackage.getTreeModel().getRoot(), sourcePath);
    }

    private static JMeterTreeNode findBySourcePath(
            JMeterTreeNode root, List<SampleResult.TestElementPathEntry> sourcePath) {
        if (sourcePath.isEmpty()) {
            return null;
        }
        JMeterTreeNode direct = resolvePathSuffix(root, sourcePath, 0, false);
        if (direct != null) {
            return direct;
        }
        for (int i = 0; i < sourcePath.size(); i++) {
            if (MODULE_CONTROLLER_CLASS.equals(sourcePath.get(i).className())) {
                for (int start = i + 1; start < sourcePath.size(); start++) {
                    JMeterTreeNode fragmentNode = resolvePathSuffix(root, sourcePath, start, true);
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
            JMeterTreeNode root, List<SampleResult.TestElementPathEntry> sourcePath, int start,
            boolean includeDisabledTarget) {
        // Module controllers can explicitly execute a disabled target or a test fragment.
        JMeterTreeNode current = findDescendant(root, sourcePath.get(start), includeDisabledTarget);
        for (SampleResult.TestElementPathEntry pathEntry : sourcePath.subList(start + 1, sourcePath.size())) {
            current = findChild(current, pathEntry, false);
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
            JMeterTreeNode parent, SampleResult.TestElementPathEntry pathEntry, boolean includeDisabled) {
        JMeterTreeNode child = findChild(parent, pathEntry, includeDisabled);
        if (child != null) {
            return child;
        }
        Enumeration<?> children = parent.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode next = (JMeterTreeNode) children.nextElement();
            if (!includeDisabled && !next.isEnabled()) {
                continue;
            }
            JMeterTreeNode descendant = findDescendant(next, pathEntry, includeDisabled);
            if (descendant != null) {
                return descendant;
            }
        }
        return null;
    }

    private static JMeterTreeNode findChild(
            JMeterTreeNode parent, SampleResult.TestElementPathEntry pathEntry, boolean includeDisabled) {
        if (parent == null) {
            return null;
        }
        int occurrence = 0;
        Enumeration<?> children = parent.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode) children.nextElement();
            // Execution paths count occurrences after disabled elements have been removed.
            if (!includeDisabled && !child.isEnabled()) {
                continue;
            }
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
