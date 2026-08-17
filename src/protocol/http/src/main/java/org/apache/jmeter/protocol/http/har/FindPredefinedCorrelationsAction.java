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

package org.apache.jmeter.protocol.http.har;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.MenuElement;
import javax.swing.SwingWorker;
import javax.swing.tree.TreeNode;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.control.gui.ParallelControllerGui;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.AbstractActionWithNoRunningTest;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Command;
import org.apache.jmeter.gui.plugin.MenuCreator;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver.RecordedExchange;
import org.apache.jmeter.protocol.http.har.HarEntry.NameValue;
import org.apache.jmeter.protocol.http.har.HarEntry.PostData;
import org.apache.jmeter.protocol.http.har.HarPredefinedCorrelation.Rule;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.StringUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

/** Tries selected predefined correlation rules and applies reviewed matches. */
@AutoService({
        Command.class,
        MenuCreator.class
})
public final class FindPredefinedCorrelationsAction extends AbstractActionWithNoRunningTest implements MenuCreator {

    private static final Logger LOG = LoggerFactory.getLogger(FindPredefinedCorrelationsAction.class);
    private static final Set<String> COMMANDS = Set.of(ActionNames.FIND_PREDEFINED_CORRELATIONS);

    @Override
    public void doActionAfterCheck(ActionEvent event) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            return;
        }
        List<JMeterTreeNode> testPlans = gui.getTreeModel().getNodesOfType(TestPlan.class);
        JMeterTreeNode testPlanNode = testPlans.isEmpty() ? null : testPlans.get(0);
        TestElement testPlan = testPlanNode == null ? null : testPlanNode.getTestElement();
        HarCorrelationRulesPanel rulesPanel = new HarCorrelationRulesPanel(
                HarCorrelationRuleCatalog.rulesFor(testPlan),
                HarCorrelationRuleCatalog.customRuleIds(testPlan),
                rule -> editCustomRule(gui, testPlanNode, rule));
        rulesPanel.setPreferredSize(new Dimension(850, 500));
        Object[] options = {
                JMeterUtils.getResString("try_predefined_correlations_try"),
                JMeterUtils.getResString("close")
        };
        int result = JOptionPane.showOptionDialog(
                gui.getMainFrame(), rulesPanel,
                JMeterUtils.getResString(ActionNames.FIND_PREDEFINED_CORRELATIONS),
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (result != 0) {
            return;
        }
        List<Rule> selectedRules = rulesPanel.getSelectedRules();
        if (selectedRules.isEmpty()) {
            JMeterUtils.reportInfoToUser(
                    JMeterUtils.getResString("try_predefined_correlations_no_groups"),
                    JMeterUtils.getResString(ActionNames.FIND_PREDEFINED_CORRELATIONS));
            return;
        }
        List<ThreadGroupChoice> choices = recordedThreadGroups(gui);
        if (choices.isEmpty()) {
            JMeterUtils.reportErrorToUser(
                    JMeterUtils.getResString("find_predefined_correlations_no_recording"),
                    JMeterUtils.getResString(ActionNames.FIND_PREDEFINED_CORRELATIONS));
            return;
        }
        ThreadGroupChoice choice = chooseThreadGroup(gui, choices);
        if (choice == null) {
            return;
        }

        Path testPlanFile = StringUtilities.isEmpty(gui.getTestPlanFile())
                ? null : Path.of(gui.getTestPlanFile());
        gui.getMainFrame().showLoadingOverlay(
                JMeterUtils.getResString("find_predefined_correlations_searching"));
        SwingWorker<ScanResult, Void> worker = new SwingWorker<>() {
            @Override
            protected ScanResult doInBackground() {
                return scan(choice.node(), testPlanFile, selectedRules);
            }

            @Override
            protected void done() {
                gui.getMainFrame().hideLoadingOverlay();
                try {
                    ScanResult scan = get();
                    reviewAndApply(gui, choice, scan);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    LOG.warn("Unable to try predefined correlations", ex.getCause());
                    JMeterUtils.reportErrorToUser(
                            String.valueOf(ex.getCause().getMessage()),
                            JMeterUtils.getResString(ActionNames.FIND_PREDEFINED_CORRELATIONS));
                }
            }
        };
        worker.execute();
    }

    private static Rule editCustomRule(GuiPackage gui, JMeterTreeNode testPlanNode, Rule rule) {
        if (testPlanNode == null) {
            return null;
        }
        HarCorrelationRulesPanel.RuleEditorPanel editor =
                new HarCorrelationRulesPanel.RuleEditorPanel(rule);
        Rule updated;
        while (true) {
            int result = JOptionPane.showConfirmDialog(
                    gui.getMainFrame(), editor,
                    JMeterUtils.getResString("try_predefined_correlations_edit_title"),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }
            updated = editor.updatedRule(rule);
            try {
                HarCorrelationRuleCatalog.storeCustomRulesEverywhere(
                        testPlanNode.getTestElement(), List.of(updated));
                break;
            } catch (IOException ex) {
                JMeterUtils.reportErrorToUser(
                        ex.getMessage(), JMeterUtils.getResString("try_predefined_correlations_edit_title"));
            }
        }
        gui.getTreeModel().nodeChanged(testPlanNode);
        gui.setDirty(true);
        JMeterUtils.reportInfoToUser(
                JMeterUtils.getResString("try_predefined_correlations_custom_saved"),
                JMeterUtils.getResString("try_predefined_correlations_edit_title"));
        return updated;
    }

    private static void reviewAndApply(GuiPackage gui, ThreadGroupChoice choice, ScanResult scan) {
        if (scan.correlations().isEmpty()) {
            String message = MessageFormat.format(
                    JMeterUtils.getResString("find_predefined_correlations_none"), choice.label());
            if (scan.unavailableCount() > 0) {
                message += "\n" + MessageFormat.format(
                        JMeterUtils.getResString("find_predefined_correlations_incomplete"), scan.unavailableCount());
            }
            JMeterUtils.reportInfoToUser(message,
                    JMeterUtils.getResString(ActionNames.FIND_PREDEFINED_CORRELATIONS));
            return;
        }

        HarCorrelationMatchesPanel matchesPanel = new HarCorrelationMatchesPanel();
        matchesPanel.setCorrelations(scan.correlations());
        JScrollPane scroll = new JScrollPane(matchesPanel);
        scroll.setPreferredSize(new Dimension(720, 360));
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel(MessageFormat.format(
                JMeterUtils.getResString("find_predefined_correlations_review"), choice.label())),
                BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        if (scan.unavailableCount() > 0) {
            JLabel warning = new JLabel(MessageFormat.format(
                    JMeterUtils.getResString("find_predefined_correlations_incomplete"), scan.unavailableCount()));
            warning.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
            panel.add(warning, BorderLayout.SOUTH);
        }

        int result = JOptionPane.showConfirmDialog(
                gui.getMainFrame(), panel,
                JMeterUtils.getResString(ActionNames.FIND_PREDEFINED_CORRELATIONS),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        List<HarPredefinedCorrelation> selected = matchesPanel.getSelectedCorrelations();
        if (selected.isEmpty()) {
            return;
        }
        if (!confirmParallelControllerSplits(gui, selected, scan.nodesByEntryIndex())) {
            return;
        }
        ApplyResult applied = apply(gui, selected, scan.nodesByEntryIndex());
        String message = MessageFormat.format(
                JMeterUtils.getResString("find_predefined_correlations_applied"),
                applied.extractorCount(), applied.replacementCount(), choice.label());
        if (applied.movedRequestCount() > 0) {
            message += "\n" + MessageFormat.format(
                    JMeterUtils.getResString("find_predefined_correlations_split_applied"),
                    applied.movedRequestCount());
        }
        if (applied.skippedCount() > 0) {
            message += "\n" + MessageFormat.format(
                    JMeterUtils.getResString("find_predefined_correlations_split_skipped"),
                    applied.skippedCount());
        }
        JMeterUtils.reportInfoToUser(message,
                JMeterUtils.getResString(ActionNames.FIND_PREDEFINED_CORRELATIONS));
    }

    /**
     * Warns that consumers sharing a Parallel Controller with their extractor have to move into a
     * new controller, since everything in one Parallel Controller starts at the same time.
     */
    private static boolean confirmParallelControllerSplits(GuiPackage gui,
            List<HarPredefinedCorrelation> correlations, Map<Integer, JMeterTreeNode> nodesByEntryIndex) {
        int consumerCount = plannedSplits(correlations, nodesByEntryIndex).values().stream()
                .mapToInt(Set::size)
                .sum();
        if (consumerCount == 0) {
            return true;
        }
        int answer = JOptionPane.showConfirmDialog(
                gui.getMainFrame(),
                MessageFormat.format(
                        JMeterUtils.getResString("find_predefined_correlations_split_confirm"), consumerCount),
                JMeterUtils.getResString(ActionNames.FIND_PREDEFINED_CORRELATIONS),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        return answer == JOptionPane.OK_OPTION;
    }

    static ScanResult scan(JMeterTreeNode threadGroupNode, Path testPlanFile) {
        return scan(threadGroupNode, testPlanFile, HarCorrelationRuleCatalog.rulesFor(threadGroupNode));
    }

    static ScanResult scan(JMeterTreeNode threadGroupNode, Path testPlanFile, List<Rule> rules) {
        List<HarEntry> entries = new ArrayList<>();
        Map<Integer, JMeterTreeNode> nodesByEntryIndex = new LinkedHashMap<>();
        int unavailableCount = 0;
        int entryIndex = 0;
        Enumeration<TreeNode> nodes = threadGroupNode.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            TreeNode candidate = nodes.nextElement();
            if (!(candidate instanceof JMeterTreeNode node)
                    || !(node.getTestElement() instanceof HTTPSamplerBase sampler)) {
                continue;
            }
            RecordedHarExchangeResolver.Resolution resolution =
                    RecordedHarExchangeResolver.resolveFor(node, testPlanFile);
            if (resolution.exchange().isEmpty() && hasRecordingMetadata(sampler)) {
                unavailableCount++;
            }
            RecordedExchange exchange = resolution.exchange().orElse(null);
            entries.add(toHarEntry(sampler,
                    exchange == null ? "" : exchange.response(),
                    exchange == null ? "" : exchange.responseBody(), entryIndex));
            nodesByEntryIndex.put(entryIndex, node);
            entryIndex++;
        }
        return new ScanResult(
                HarPredefinedCorrelation.find(entries, rules),
                Map.copyOf(nodesByEntryIndex), unavailableCount);
    }

    /**
     * The consumers that start in parallel with the extractor they depend on, keyed by the Parallel
     * Controller that has to be split and expressed as the controller's own child that has to move.
     *
     * <p>The controller to split is the pair's lowest common ancestor when that ancestor is a
     * Parallel Controller: its two branches are exactly what runs at the same time. A deeper
     * controller cannot help, and a shallower one holds both of them in one branch. Nested
     * controllers therefore need several rounds - splitting the inner one makes the outer one the
     * common ancestor, which the next round splits.
     */
    static Map<JMeterTreeNode, Set<JMeterTreeNode>> plannedSplits(
            List<HarPredefinedCorrelation> correlations, Map<Integer, JMeterTreeNode> nodesByEntryIndex) {
        Map<JMeterTreeNode, Set<JMeterTreeNode>> movesByController = new LinkedHashMap<>();
        for (HarPredefinedCorrelation correlation : correlations) {
            JMeterTreeNode sourceNode = nodesByEntryIndex.get(correlation.getSourceEntryIndex());
            for (HarPredefinedCorrelation.Replacement replacement : correlation.getReplacements()) {
                JMeterTreeNode targetNode = nodesByEntryIndex.get(replacement.getTargetEntryIndex());
                JMeterTreeNode controller = concurrentAncestor(sourceNode, targetNode);
                if (controller == null) {
                    continue;
                }
                movesByController.computeIfAbsent(controller, ignored -> new LinkedHashSet<>())
                        .add(childOf(controller, targetNode));
            }
        }
        return movesByController;
    }

    /**
     * Moves every planned consumer into a new Parallel Controller placed right after the one it
     * shared with its extractor, repeating until nothing runs in parallel with its extractor any
     * more. Chains need one round per link: a consumer can be the extractor of the next
     * correlation, and both land in the same new controller.
     *
     * @return the consumers that could not be separated, so their replacements can be left out
     *         rather than written into a plan where the variable is not set yet
     */
    static SplitResult splitParallelControllers(GuiPackage gui,
            List<HarPredefinedCorrelation> correlations, Map<Integer, JMeterTreeNode> nodesByEntryIndex) {
        // A node can move more than once - separating a nested controller moves it out of the
        // inner controller first and out of the outer one next - so count nodes, not moves.
        Set<JMeterTreeNode> movedNodes = new LinkedHashSet<>();
        Map<JMeterTreeNode, Set<JMeterTreeNode>> splits =
                plannedSplits(correlations, nodesByEntryIndex);
        // A pair needs one round per Parallel Controller it is nested in, and every round makes
        // progress, so this is a safety net rather than the real stopping condition.
        int maxRounds = 1 + correlations.stream()
                .flatMap(correlation -> correlation.getReplacements().stream())
                .mapToInt(replacement -> 1 + parallelAncestorCount(
                        nodesByEntryIndex.get(replacement.getTargetEntryIndex())))
                .sum();
        for (int round = 0; round < maxRounds && !splits.isEmpty(); round++) {
            boolean progressed = false;
            for (Map.Entry<JMeterTreeNode, Set<JMeterTreeNode>> split : splits.entrySet()) {
                if (splitParallelController(gui, split.getKey(), split.getValue())) {
                    movedNodes.addAll(split.getValue());
                    progressed = true;
                }
            }
            if (!progressed) {
                // No progress, so repeating would loop forever on the same pairs.
                break;
            }
            splits = plannedSplits(correlations, nodesByEntryIndex);
        }
        if (!splits.isEmpty()) {
            LOG.warn("Unable to separate {} Parallel Controller(s) from the extractors they depend on",
                    splits.size());
        }
        return new SplitResult(movedNodes.size(), unresolvedTargets(splits));
    }

    private static Set<JMeterTreeNode> unresolvedTargets(
            Map<JMeterTreeNode, Set<JMeterTreeNode>> splits) {
        Set<JMeterTreeNode> unresolved = new LinkedHashSet<>();
        for (Map.Entry<JMeterTreeNode, Set<JMeterTreeNode>> split : splits.entrySet()) {
            for (JMeterTreeNode movedChild : split.getValue()) {
                Enumeration<TreeNode> nodes = movedChild.preorderEnumeration();
                while (nodes.hasMoreElements()) {
                    if (nodes.nextElement() instanceof JMeterTreeNode node) {
                        unresolved.add(node);
                    }
                }
            }
        }
        return unresolved;
    }

    private static boolean splitParallelController(
            GuiPackage gui, JMeterTreeNode controller, Set<JMeterTreeNode> movedChildren) {
        if (!(controller.getParent() instanceof JMeterTreeNode parent)
                || movedChildren.isEmpty() || movedChildren.size() >= controller.getChildCount()) {
            return false;
        }
        ParallelController original = (ParallelController) controller.getTestElement();
        ParallelController followUp = new ParallelController();
        followUp.setProperty(TestElement.GUI_CLASS, ParallelControllerGui.class.getName());
        followUp.setName(MessageFormat.format(
                JMeterUtils.getResString("find_predefined_correlations_split_name"), original.getName()));
        // Keep the string form: the limit can be a variable or function, which reading it as an
        // integer would freeze into whatever it evaluates to right now.
        followUp.setMaxParallel(original.getMaxParallelString());
        followUp.setEnabled(original.isEnabled());

        JMeterTreeNode followUpNode = new JMeterTreeNode(followUp, gui == null ? null : gui.getTreeModel());
        parent.insert(followUpNode, parent.getIndex(controller) + 1);
        // Move them in the order they had in the controller: correlations are discovered per
        // source, which says nothing about tree order, and the order decides which requests a
        // bounded parallelism starts first.
        List<JMeterTreeNode> orderedChildren = new ArrayList<>(movedChildren);
        orderedChildren.sort(Comparator.comparingInt(controller::getIndex));
        for (JMeterTreeNode movedChild : orderedChildren) {
            controller.remove(movedChild);
            followUpNode.add(movedChild);
        }
        unwrapSingleRequest(parent, followUpNode);
        unwrapSingleRequest(parent, controller);
        if (gui != null) {
            gui.getTreeModel().nodeStructureChanged(parent);
        }
        return true;
    }

    /**
     * Replaces a Parallel Controller that holds a single request with that request, since there is
     * nothing left to run in parallel. A disabled controller is kept: it is what stops its request
     * from running, and lifting the request into the enabled parent would start executing it.
     */
    private static void unwrapSingleRequest(JMeterTreeNode parent, JMeterTreeNode controller) {
        if (controller.getChildCount() != 1 || !controller.isEnabled()) {
            return;
        }
        JMeterTreeNode onlyChild = (JMeterTreeNode) controller.getChildAt(0);
        int index = parent.getIndex(controller);
        controller.remove(onlyChild);
        parent.remove(controller);
        parent.insert(onlyChild, index);
    }

    /**
     * The Parallel Controller that starts both nodes at the same time, or null when one runs after
     * the other. That is their lowest common ancestor, and only when it is a Parallel Controller:
     * any other container runs its children in order.
     */
    private static JMeterTreeNode concurrentAncestor(JMeterTreeNode source, JMeterTreeNode target) {
        if (source == null || target == null || source == target) {
            return null;
        }
        List<JMeterTreeNode> sourceAncestors = ancestors(source);
        List<JMeterTreeNode> targetAncestors = ancestors(target);
        JMeterTreeNode lowestCommon = null;
        for (int i = 0; i < Math.min(sourceAncestors.size(), targetAncestors.size()); i++) {
            if (sourceAncestors.get(i) != targetAncestors.get(i)) {
                break;
            }
            lowestCommon = sourceAncestors.get(i);
        }
        return lowestCommon != null && lowestCommon.getTestElement() instanceof ParallelController
                ? lowestCommon : null;
    }

    private static int parallelAncestorCount(JMeterTreeNode node) {
        int count = 0;
        JMeterTreeNode current = node;
        while (current != null) {
            if (current.getTestElement() instanceof ParallelController) {
                count++;
            }
            current = current.getParent() instanceof JMeterTreeNode parent ? parent : null;
        }
        return count;
    }

    /** The node's ancestors from the root down to the node itself. */
    private static List<JMeterTreeNode> ancestors(JMeterTreeNode node) {
        List<JMeterTreeNode> path = new ArrayList<>();
        for (TreeNode pathNode : node.getPath()) {
            if (pathNode instanceof JMeterTreeNode jmeterNode) {
                path.add(jmeterNode);
            }
        }
        return path;
    }

    /** The ancestor of the node that is a direct child of the controller, or the node itself. */
    private static JMeterTreeNode childOf(JMeterTreeNode controller, JMeterTreeNode node) {
        JMeterTreeNode current = node;
        while (current != null && current.getParent() != controller) {
            current = current.getParent() instanceof JMeterTreeNode parent ? parent : null;
        }
        return current;
    }

    record SplitResult(int movedRequests, Set<JMeterTreeNode> unseparableTargets) {
    }

    static HarEntry toHarEntry(
            HTTPSamplerBase sampler, String recordedResponse, String recordedResponseBody, int entryIndex) {
        HarEntry entry = new HarEntry();
        entry.setOriginalIndex(entryIndex);
        entry.setStartMs(entryIndex);
        entry.setEndMs(entryIndex);
        entry.setMethod(sampler.getMethod());
        entry.setUrl(samplerUrl(sampler));
        entry.setServerIpAddress("recorded");
        entry.setHasPositiveTiming(true);
        entry.setResponseContentText(recordedResponseBody);
        addResponseHeaders(entry, recordedResponse);
        for (org.apache.jmeter.protocol.http.control.Header header : sampler.getNativeHeaderList()) {
            entry.getRequestHeaders().add(new NameValue(header.getName(), header.getValue()));
        }
        addRequestArguments(entry, sampler);
        return entry;
    }

    private static String samplerUrl(HTTPSamplerBase sampler) {
        try {
            return sampler.getUrl().toString();
        } catch (MalformedURLException ex) {
            String recordedUrl = sampler.getPropertyAsString(RecordedHarExchangeResolver.HAR_REQUEST_URL);
            if (StringUtilities.isNotEmpty(recordedUrl)) {
                return recordedUrl;
            }
            return sampler.getProtocol() + "://" + sampler.getDomain() + sampler.getPath();
        }
    }

    private static void addResponseHeaders(HarEntry entry, String recordedResponse) {
        String[] lines = recordedResponse.split("\\R");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                break;
            }
            int separator = lines[i].indexOf(':', 1);
            if (separator > 0) {
                int valueStart = separator + 1;
                while (valueStart < lines[i].length() && Character.isWhitespace(lines[i].charAt(valueStart))) {
                    valueStart++;
                }
                entry.getResponseHeaders().add(
                        new NameValue(lines[i].substring(0, separator), lines[i].substring(valueStart)));
            }
        }
    }

    private static void addRequestArguments(HarEntry entry, HTTPSamplerBase sampler) {
        Arguments arguments = sampler.getArguments();
        if (arguments == null) {
            return;
        }
        boolean queryMethod = "GET".equalsIgnoreCase(sampler.getMethod())
                || "DELETE".equalsIgnoreCase(sampler.getMethod())
                || "OPTIONS".equalsIgnoreCase(sampler.getMethod());
        if (queryMethod) {
            for (JMeterProperty property : arguments) {
                HTTPArgument argument = (HTTPArgument) property.getObjectValue();
                entry.getQueryString().add(new NameValue(argument.getName(), argument.getValue()));
            }
            return;
        }
        if (sampler.getSendParameterValuesAsPostBody()) {
            StringBuilder body = new StringBuilder();
            for (JMeterProperty property : arguments) {
                HTTPArgument argument = (HTTPArgument) property.getObjectValue();
                body.append(argument.getValue());
            }
            entry.setPostData(new PostData("", body.toString(), List.of()));
            return;
        }
        List<NameValue> parameters = new ArrayList<>();
        for (JMeterProperty property : arguments) {
            HTTPArgument argument = (HTTPArgument) property.getObjectValue();
            parameters.add(new NameValue(argument.getName(), argument.getValue()));
        }
        entry.setPostData(new PostData("", "", parameters));
    }

    private static ApplyResult apply(GuiPackage gui, List<HarPredefinedCorrelation> correlations,
            Map<Integer, JMeterTreeNode> nodesByEntryIndex) {
        int extractorCount = 0;
        int replacementCount = 0;
        int skippedCount = 0;
        SplitResult split;
        gui.updateCurrentNode();
        gui.beginUndoTransaction();
        try {
            split = splitParallelControllers(gui, correlations, nodesByEntryIndex);
            for (HarPredefinedCorrelation correlation : correlations) {
                JMeterTreeNode sourceNode = nodesByEntryIndex.get(correlation.getSourceEntryIndex());
                boolean used = correlation.getReplacements().stream().anyMatch(replacement -> !split
                        .unseparableTargets()
                        .contains(nodesByEntryIndex.get(replacement.getTargetEntryIndex())));
                if (used && sourceNode != null && !hasExtractor(sourceNode, correlation.getVariableName())) {
                    try {
                        gui.getTreeModel().addComponent(
                                HarPredefinedCorrelation.buildExtractor(correlation), sourceNode);
                        extractorCount++;
                    } catch (IllegalUserActionException ex) {
                        throw new IllegalStateException("Unable to add predefined extractor", ex);
                    }
                }
            }
            for (HarPredefinedCorrelation correlation : correlations) {
                for (HarPredefinedCorrelation.Replacement replacement : correlation.getReplacements()) {
                    JMeterTreeNode targetNode = nodesByEntryIndex.get(replacement.getTargetEntryIndex());
                    if (targetNode == null || !(targetNode.getTestElement() instanceof HTTPSamplerBase sampler)) {
                        continue;
                    }
                    if (split.unseparableTargets().contains(targetNode)) {
                        // Still starts together with its extractor, so the variable would be unset.
                        skippedCount++;
                        continue;
                    }
                    int replaced = applyReplacement(sampler, correlation, replacement);
                    if (replaced > 0) {
                        replacementCount += replaced;
                        gui.getTreeModel().nodeChanged(targetNode);
                    }
                }
            }
        } finally {
            gui.endUndoTransaction();
        }
        gui.refreshCurrentGui();
        gui.getMainFrame().repaint();
        return new ApplyResult(extractorCount, replacementCount, split.movedRequests(), skippedCount);
    }

    static int applyReplacement(HTTPSamplerBase sampler, HarPredefinedCorrelation correlation,
            HarPredefinedCorrelation.Replacement replacement) {
        int replacementCount = 0;
        String variableReference = "${" + correlation.getVariableName() + "}";
        for (String variant : HarPredefinedCorrelation.replacementVariants(correlation, replacement)) {
            try {
                replacementCount += sampler.replace(Pattern.quote(variant), variableReference, true);
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to replace predefined correlation value", ex);
            }
        }
        return replacementCount;
    }

    private static boolean hasExtractor(JMeterTreeNode samplerNode, String variableName) {
        for (int i = 0; i < samplerNode.getChildCount(); i++) {
            TestElement child = ((JMeterTreeNode) samplerNode.getChildAt(i)).getTestElement();
            if (child instanceof RegexExtractor regexExtractor
                    && variableName.equals(regexExtractor.getRefName())) {
                return true;
            }
            if (child instanceof JSONPostProcessor jsonExtractor) {
                for (String refName : jsonExtractor.getRefNames().split(";")) {
                    if (variableName.equals(refName.trim())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<ThreadGroupChoice> recordedThreadGroups(GuiPackage gui) {
        List<ThreadGroupChoice> choices = new ArrayList<>();
        for (JMeterTreeNode node : gui.getTreeModel().getNodesOfType(AbstractThreadGroup.class)) {
            if (node.isEnabled() && hasRecordedSampler(node)) {
                choices.add(new ThreadGroupChoice(node, nodePath(node)));
            }
        }
        return choices;
    }

    private static boolean hasRecordedSampler(JMeterTreeNode threadGroupNode) {
        Enumeration<TreeNode> nodes = threadGroupNode.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            TreeNode candidate = nodes.nextElement();
            if (candidate instanceof JMeterTreeNode node
                    && node.getTestElement() instanceof HTTPSamplerBase sampler
                    && hasRecordingMetadata(sampler)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRecordingMetadata(HTTPSamplerBase sampler) {
        return StringUtilities.isNotEmpty(sampler.getPropertyAsString(
                RecordedHarExchangeResolver.RECORDING_EXCHANGE_ID))
                || StringUtilities.isNotEmpty(sampler.getPropertyAsString(
                        RecordedHarExchangeResolver.HAR_ENTRY_INDEX));
    }

    private static ThreadGroupChoice chooseThreadGroup(GuiPackage gui, List<ThreadGroupChoice> choices) {
        JMeterTreeNode currentThreadGroup = currentThreadGroup(gui);
        ThreadGroupChoice defaultChoice = choices.stream()
                .filter(choice -> choice.node() == currentThreadGroup)
                .findFirst()
                .orElse(choices.get(0));
        if (choices.size() == 1 || currentThreadGroup != null && defaultChoice.node() == currentThreadGroup) {
            return defaultChoice;
        }
        return (ThreadGroupChoice) JOptionPane.showInputDialog(
                gui.getMainFrame(),
                JMeterUtils.getResString("find_predefined_correlations_choose_group"),
                JMeterUtils.getResString(ActionNames.FIND_PREDEFINED_CORRELATIONS),
                JOptionPane.QUESTION_MESSAGE, null, choices.toArray(), defaultChoice);
    }

    private static JMeterTreeNode currentThreadGroup(GuiPackage gui) {
        JMeterTreeNode current = gui.getCurrentNode();
        while (current != null) {
            if (current.getTestElement() instanceof AbstractThreadGroup) {
                return current;
            }
            current = current.getParent() instanceof JMeterTreeNode parent ? parent : null;
        }
        return null;
    }

    private static String nodePath(JMeterTreeNode node) {
        List<String> names = new ArrayList<>();
        for (TreeNode pathNode : node.getPath()) {
            names.add(((JMeterTreeNode) pathNode).getName());
        }
        return String.join(" > ", names);
    }

    @Override
    public Set<String> getActionNames() {
        return COMMANDS;
    }

    @Override
    public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
        if (location != MENU_LOCATION.TOOLS) {
            return new JMenuItem[0];
        }
        JMenuItem menuItem = new JMenuItem(
                JMeterUtils.getResString(ActionNames.FIND_PREDEFINED_CORRELATIONS), KeyEvent.VK_UNDEFINED);
        menuItem.setName(ActionNames.FIND_PREDEFINED_CORRELATIONS);
        menuItem.setActionCommand(ActionNames.FIND_PREDEFINED_CORRELATIONS);
        menuItem.setAccelerator(null);
        menuItem.addActionListener(ActionRouter.getInstance());
        return new JMenuItem[] {menuItem};
    }

    @Override
    public JMenu[] getTopLevelMenus() {
        return new JMenu[0];
    }

    @Override
    public boolean localeChanged(MenuElement menu) {
        return false;
    }

    @Override
    public void localeChanged() {
        // NOOP
    }

    record ScanResult(
            List<HarPredefinedCorrelation> correlations,
            Map<Integer, JMeterTreeNode> nodesByEntryIndex,
            int unavailableCount) {
    }

    private record ApplyResult(int extractorCount, int replacementCount, int movedRequestCount,
            int skippedCount) {
    }

    private record ThreadGroupChoice(JMeterTreeNode node, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}
