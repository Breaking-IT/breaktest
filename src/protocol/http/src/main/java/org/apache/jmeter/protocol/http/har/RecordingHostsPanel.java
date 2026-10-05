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
import java.awt.Component;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import org.apache.jmeter.util.JMeterUtils;

/** Shared hostname review for HAR imports and live proxy recordings. */
public class RecordingHostsPanel extends JPanel {
    private static final String HOST_PROPERTY = "harHostname";
    private final JPanel hostsPanel = new JPanel();
    private final List<JCheckBox> hostCheckBoxes = new ArrayList<>();
    private final List<JCheckBox> groupCheckBoxes = new ArrayList<>();
    private final JButton toggleAllButton = new JButton(JMeterUtils.getResString("har_import_unselect_all"));
    private boolean allSelected = true;
    private List<HarEntry> entries = List.of();
    private List<String> hostnames = List.of();
    private final Runnable changed;

    public RecordingHostsPanel(Runnable changed) {
        super(new BorderLayout());
        this.changed = changed;
        add(buildHostsCard(), BorderLayout.CENTER);
    }

    public void setEntries(List<HarEntry> entries) {
        this.entries = List.copyOf(entries);
        hostnames = HarConverter.sortedHostnames(entries);
        rebuildHostsPanel();
    }
    private JPanel buildHostsCard() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel top = new JPanel(new BorderLayout());
        top.add(new JLabel(JMeterUtils.getResString("har_import_hosts_prompt")), BorderLayout.WEST);
        toggleAllButton.addActionListener(e -> toggleAll());
        top.add(toggleAllButton, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);

        hostsPanel.setLayout(new BoxLayout(hostsPanel, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(hostsPanel);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private void toggleAll() {
        allSelected = !allSelected;
        for (JCheckBox box : hostCheckBoxes) {
            box.setSelected(allSelected);
        }
        for (JCheckBox box : groupCheckBoxes) {
            box.setSelected(allSelected);
        }
        toggleAllButton.setText(allSelected
                ? JMeterUtils.getResString("har_import_unselect_all")
                : JMeterUtils.getResString("har_import_select_all"));
        changed.run();
    }

    private void rebuildHostsPanel() {
        hostsPanel.removeAll();
        hostCheckBoxes.clear();
        groupCheckBoxes.clear();
        allSelected = true;
        toggleAllButton.setText(JMeterUtils.getResString("har_import_unselect_all"));

        Map<String, Integer> counts = requestCounts();

        // Group hostnames by base domain, preserving the sorted order.
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String host : hostnames) {
            groups.computeIfAbsent(HarConverter.baseDomain(host), k -> new ArrayList<>()).add(host);
        }

        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            List<String> members = group.getValue();
            if (members.size() == 1) {
                String host = members.get(0);
                JCheckBox box = hostCheckBox(host, counts.getOrDefault(host, 0));
                hostsPanel.add(box);
            } else {
                int groupTotal = members.stream().mapToInt(h -> counts.getOrDefault(h, 0)).sum();
                JCheckBox parent = new JCheckBox(hostLabel("*." + group.getKey(), groupTotal), true);
                parent.setAlignmentX(Component.LEFT_ALIGNMENT);
                groupCheckBoxes.add(parent);
                List<JCheckBox> children = new ArrayList<>();
                for (String host : members) {
                    JCheckBox child = hostCheckBox(host, counts.getOrDefault(host, 0));
                    child.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
                    child.addActionListener(e ->
                            parent.setSelected(children.stream().allMatch(JCheckBox::isSelected)));
                    children.add(child);
                }
                parent.addActionListener(e -> {
                    for (JCheckBox child : children) {
                        child.setSelected(parent.isSelected());
                    }
                    changed.run();
                });
                hostsPanel.add(parent);
                for (JCheckBox child : children) {
                    hostsPanel.add(child);
                }
            }
        }
        hostsPanel.revalidate();
        hostsPanel.repaint();
    }

    private JCheckBox hostCheckBox(String host, int count) {
        JCheckBox box = new JCheckBox(hostLabel(host, count), true);
        box.putClientProperty(HOST_PROPERTY, host);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.addActionListener(e -> changed.run());
        hostCheckBoxes.add(box);
        return box;
    }

    private static String hostLabel(String host, int count) {
        return MessageFormat.format(JMeterUtils.getResString("har_import_host_requests"), host, count);
    }

    private Map<String, Integer> requestCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (HarEntry entry : entries) {
            String host = HarConverter.hostnameOf(entry.getUrl());
            if (host != null && !host.isEmpty()) {
                counts.merge(host, 1, Integer::sum);
            }
        }
        return counts;
    }

    public Set<String> selectedHostnames() {
        Set<String> selected = new LinkedHashSet<>();
        for (JCheckBox box : hostCheckBoxes) {
            if (box.isSelected()) {
                selected.add((String) box.getClientProperty(HOST_PROPERTY));
            }
        }
        return selected;
    }

}
