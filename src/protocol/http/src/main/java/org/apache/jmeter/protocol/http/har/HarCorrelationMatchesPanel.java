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

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.apache.jmeter.util.JMeterUtils;

/** Selectable list of predefined correlation matches and their replacement locations. */
final class HarCorrelationMatchesPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final List<JCheckBox> checkBoxes = new ArrayList<>();
    private final List<Map<Integer, JCheckBox>> requestCheckBoxes = new ArrayList<>();
    private final List<HarPredefinedCorrelation> displayedCorrelations = new ArrayList<>();

    HarCorrelationMatchesPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }

    void setCorrelations(List<HarPredefinedCorrelation> correlations) {
        checkBoxes.clear();
        requestCheckBoxes.clear();
        displayedCorrelations.clear();
        removeAll();
        if (correlations.isEmpty()) {
            add(new JLabel(JMeterUtils.getResString("har_import_correlations_none")));
        }
        Map<String, List<HarPredefinedCorrelation>> groups = new LinkedHashMap<>();
        for (HarPredefinedCorrelation correlation : correlations) {
            groups.computeIfAbsent(correlation.getRule().getGroup(), ignored -> new ArrayList<>())
                    .add(correlation);
        }
        for (Map.Entry<String, List<HarPredefinedCorrelation>> group : groups.entrySet()) {
            JLabel groupLabel = new JLabel(group.getKey());
            groupLabel.setFont(groupLabel.getFont().deriveFont(Font.BOLD));
            groupLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
            groupLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(groupLabel);
            for (HarPredefinedCorrelation correlation : group.getValue()) {
                addCorrelation(correlation);
            }
        }
        revalidate();
        repaint();
    }

    private void addCorrelation(HarPredefinedCorrelation correlation) {
        JPanel matchPanel = new JPanel();
        matchPanel.setLayout(new BoxLayout(matchPanel, BoxLayout.Y_AXIS));
        matchPanel.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
        JCheckBox checkBox = new JCheckBox(MessageFormat.format(
                JMeterUtils.getResString("har_import_correlation_match"),
                correlation.getRule().getName(), compactUrl(correlation.getSourceUrl()),
                correlation.getMatchNumber()), true);
        checkBox.setToolTipText(correlation.getRule().getExtractorType() + ": "
                + correlation.getRule().getExpression());
        checkBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        checkBoxes.add(checkBox);
        displayedCorrelations.add(correlation);
        matchPanel.add(checkBox);
        addValueMapping(matchPanel, correlation);
        Map<Integer, JCheckBox> requests = new LinkedHashMap<>();
        requestCheckBoxes.add(requests);
        checkBox.addActionListener(event -> requests.values().forEach(box -> box.setSelected(checkBox.isSelected())));
        Map<Integer, List<HarPredefinedCorrelation.Replacement>> targets = new LinkedHashMap<>();
        for (HarPredefinedCorrelation.Replacement replacement : correlation.getReplacements()) {
            targets.computeIfAbsent(replacement.getTargetEntryIndex(), ignored -> new ArrayList<>()).add(replacement);
        }
        for (var target : targets.entrySet()) {
            HarPredefinedCorrelation.Replacement first = target.getValue().get(0);
            String locations = target.getValue().stream().map(replacement ->
                    replacement.getLocation().getDisplayName()
                            + (replacement.getLocationName().isEmpty() ? "" : " " + replacement.getLocationName()))
                    .distinct().collect(Collectors.joining(", "));
            JCheckBox request = new JCheckBox(first.getRequestMethod() + " "
                    + compactUrl(first.getRequestUrl()) + " — " + locations, true);
            request.setToolTipText(first.getRequestMethod() + " " + first.getRequestUrl());
            request.setAlignmentX(Component.LEFT_ALIGNMENT);
            request.setBorder(BorderFactory.createEmptyBorder(2, 24, 0, 0));
            request.addActionListener(event -> checkBox.setSelected(
                    requests.values().stream().anyMatch(JCheckBox::isSelected)));
            requests.put(target.getKey(), request);
            matchPanel.add(request);
        }
        add(matchPanel);
    }

    private static void addValueMapping(JPanel panel, HarPredefinedCorrelation correlation) {
        String value = correlation.getExtractedValue().replace("\r", "\\r").replace("\n", "\\n");
        String reference = "${" + correlation.getVariableName() + "}";
        JTextField mapping = new JTextField(compactUrl(value) + " → " + reference);
        mapping.setEditable(false);
        mapping.setOpaque(false);
        mapping.setBorder(BorderFactory.createEmptyBorder(2, 24, 4, 0));
        mapping.setAlignmentX(Component.LEFT_ALIGNMENT);
        mapping.setMaximumSize(new Dimension(Integer.MAX_VALUE, mapping.getPreferredSize().height));
        mapping.setToolTipText(MessageFormat.format(
                JMeterUtils.getResString("har_import_correlation_value_tooltip"), value, reference));
        mapping.getAccessibleContext().setAccessibleName(JMeterUtils.getResString("har_import_correlation_value_mapping"));
        panel.add(mapping);
    }

    List<HarPredefinedCorrelation> getSelectedCorrelations() {
        List<HarPredefinedCorrelation> selected = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isSelected()) {
                HarPredefinedCorrelation correlation = displayedCorrelations.get(i);
                Map<Integer, JCheckBox> requests = requestCheckBoxes.get(i);
                List<HarPredefinedCorrelation.Replacement> replacements = correlation.getReplacements().stream()
                        .filter(replacement -> requests.get(replacement.getTargetEntryIndex()).isSelected())
                        .toList();
                if (!replacements.isEmpty()) {
                    selected.add(correlation.withReplacements(replacements));
                }
            }
        }
        return selected;
    }

    private static String compactUrl(String url) {
        int maxLength = 100;
        if (url == null || url.length() <= maxLength) {
            return url;
        }
        return url.substring(0, maxLength - 3) + "...";
    }
}
