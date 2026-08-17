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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.apache.jmeter.util.JMeterUtils;

/** Selectable list of predefined correlation matches and their replacement locations. */
final class HarCorrelationMatchesPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final List<JCheckBox> checkBoxes = new ArrayList<>();
    private final List<HarPredefinedCorrelation> displayedCorrelations = new ArrayList<>();

    HarCorrelationMatchesPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }

    void setCorrelations(List<HarPredefinedCorrelation> correlations) {
        checkBoxes.clear();
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
            groupLabel.setFont(groupLabel.getFont().deriveFont(java.awt.Font.BOLD));
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
        for (HarPredefinedCorrelation.Replacement replacement : correlation.getReplacements()) {
            String location = replacement.getLocation().getDisplayName();
            if (!replacement.getLocationName().isEmpty()) {
                location += " " + replacement.getLocationName();
            }
            JLabel target = new JLabel(MessageFormat.format(
                    JMeterUtils.getResString("har_import_correlation_replacement"),
                    replacement.getRequestMethod(), compactUrl(replacement.getRequestUrl()), location));
            target.setBorder(BorderFactory.createEmptyBorder(2, 24, 0, 0));
            target.setAlignmentX(Component.LEFT_ALIGNMENT);
            matchPanel.add(target);
        }
        add(matchPanel);
    }

    List<HarPredefinedCorrelation> getSelectedCorrelations() {
        List<HarPredefinedCorrelation> selected = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isSelected()) {
                selected.add(displayedCorrelations.get(i));
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
