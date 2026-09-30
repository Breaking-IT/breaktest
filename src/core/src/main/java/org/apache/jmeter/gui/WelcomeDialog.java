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

package org.apache.jmeter.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.LoadRecentProject;
import org.apache.jmeter.gui.settings.SettingsGroup;
import org.apache.jmeter.gui.settings.SettingsModel;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry points for creating or opening a test plan on a file-less GUI launch. */
public final class WelcomeDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(WelcomeDialog.class);
    public static final String SHOW_PROPERTY = "welcome.show";

    public static boolean shouldShow(String testFile) {
        return testFile == null && JMeterUtils.getPropDefault(SHOW_PROPERTY, true);
    }

    public WelcomeDialog(MainFrame owner) {
        super(owner, JMeterUtils.getResString("welcome_title"), true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(24, 24));
        content.setBorder(BorderFactory.createEmptyBorder(28, 28, 20, 28));
        JLabel title = new JLabel(JMeterUtils.getResString("welcome_title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
        content.add(title, BorderLayout.NORTH);

        JPanel choices = new JPanel(new BorderLayout(24, 12));
        JPanel recent = new JPanel(new BorderLayout(0, 12));
        choices.add(new JLabel(JMeterUtils.getResString("welcome_recent")), BorderLayout.NORTH);
        DefaultListModel<JMenuItem> recentFiles = new DefaultListModel<>();
        Map<JMenuItem, String> modifiedDates = new HashMap<>();
        DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(JMeterUtils.getLocale()).withZone(ZoneId.systemDefault());
        for (JMenuItem item : LoadRecentProject.getRecentFileItems()) {
            if (item.isVisible()) {
                recentFiles.addElement(item);
                long modified = new File(item.getToolTipText()).lastModified();
                modifiedDates.put(item, modified == 0 ? JMeterUtils.getResString("welcome_modified_unknown")
                        : dateFormat.format(Instant.ofEpochMilli(modified)));
            }
        }
        JList<JMenuItem> files = new JList<>(recentFiles);
        files.setVisibleRowCount(Math.max(1, recentFiles.size()));
        files.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Font dateFont = files.getFont().deriveFont(files.getFont().getSize2D() - 1);
        int dateWidth = modifiedDates.values().stream()
                .mapToInt(value -> files.getFontMetrics(dateFont).stringWidth(value))
                .max().orElse(0);
        Dimension dateColumnSize = new Dimension(Math.max(dateWidth,
                files.getFontMetrics(dateFont).stringWidth(JMeterUtils.getResString("welcome_modified"))),
                files.getFontMetrics(dateFont).getHeight());
        files.setCellRenderer(new DefaultListCellRenderer() {
            private final JPanel row = new JPanel(new BorderLayout(24, 0));
            private final JLabel modified = new JLabel();

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                String path = ((JMenuItem) value).getToolTipText();
                label.putClientProperty("html.disable", true);
                label.setText(path);
                label.setToolTipText(path);
                label.setBorder(BorderFactory.createEmptyBorder());
                modified.setText(modifiedDates.get(value));
                modified.setFont(dateFont);
                modified.setPreferredSize(dateColumnSize);
                modified.setForeground(isSelected ? list.getSelectionForeground()
                        : UIManager.getColor("Label.disabledForeground"));
                row.setBackground(label.getBackground());
                row.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
                row.add(label, BorderLayout.CENTER);
                row.add(modified, BorderLayout.EAST);
                row.setToolTipText(path);
                return row;
            }
        });
        Runnable openSelectedFile = () -> {
            JMenuItem item = files.getSelectedValue();
            if (item != null) {
                dispose();
                item.doClick();
            }
        };
        files.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = files.locationToIndex(event.getPoint());
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2
                        && index >= 0 && files.getCellBounds(index, index).contains(event.getPoint())) {
                    openSelectedFile.run();
                }
            }
        });
        files.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "openRecentFile");
        files.getActionMap().put("openRecentFile", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                openSelectedFile.run();
            }
        });
        if (recentFiles.isEmpty()) {
            recent.add(new JLabel(JMeterUtils.getResString("welcome_no_recent")), BorderLayout.CENTER);
        } else {
            files.setSelectedIndex(0);
            JScrollPane scroll = new JScrollPane(files);
            JPanel columns = new JPanel(new BorderLayout(24, 0));
            columns.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
            JLabel pathHeading = new JLabel(JMeterUtils.getResString("welcome_path"));
            pathHeading.setFont(dateFont);
            columns.add(pathHeading, BorderLayout.CENTER);
            JLabel modifiedHeading = new JLabel(JMeterUtils.getResString("welcome_modified"));
            modifiedHeading.setFont(dateFont);
            modifiedHeading.setPreferredSize(dateColumnSize);
            columns.add(modifiedHeading, BorderLayout.EAST);
            scroll.setColumnHeaderView(columns);
            Dimension listSize = scroll.getPreferredSize();
            // Reserve the scrollbar width in case the main window limits the dialog height.
            listSize.width += scroll.getVerticalScrollBar().getPreferredSize().width;
            scroll.setPreferredSize(listSize);
            recent.add(scroll, BorderLayout.CENTER);
            recent.add(new JLabel(JMeterUtils.getResString("welcome_recent_hint")), BorderLayout.SOUTH);
        }
        choices.add(recent, BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridLayout(0, 1, 0, 12));
        actions.add(actionButton("welcome_open", ActionNames.OPEN));
        actions.add(actionButton("welcome_har", ActionNames.HAR_IMPORT));
        actions.add(actionButton("welcome_recorder", ActionNames.WELCOME_RECORDER));
        JButton blank = new JButton(JMeterUtils.getResString("welcome_blank"));
        blank.addActionListener(event -> dispose());
        actions.add(blank);
        JPanel actionColumn = new JPanel(new BorderLayout());
        actionColumn.add(actions, BorderLayout.NORTH);
        choices.add(actionColumn, BorderLayout.EAST);
        content.add(choices, BorderLayout.CENTER);

        JCheckBox skip = createSkipCheckbox(SettingsModel::new, () -> JOptionPane.showMessageDialog(this,
                JMeterUtils.getResString("welcome_save_failed"), JMeterUtils.getResString("welcome_title"),
                JOptionPane.ERROR_MESSAGE));
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        footer.add(skip);
        content.add(footer, BorderLayout.SOUTH);
        setContentPane(content);
        pack();
        // Fit the paths and controls; use the main window and monitor only as size limits.
        Rectangle available = new Rectangle(owner.getGraphicsConfiguration().getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(owner.getGraphicsConfiguration());
        available.x += insets.left;
        available.y += insets.top;
        available.width -= insets.left + insets.right;
        available.height -= insets.top + insets.bottom;
        Rectangle bounds = dialogBounds(getSize(), owner.getBounds(), available,
                (owner.getExtendedState() & Frame.ICONIFIED) != 0);
        setBounds(bounds);
        setMinimumSize(new Dimension(Math.min(640, bounds.width), Math.min(400, bounds.height)));
    }

    static Rectangle dialogBounds(Dimension preferred, Rectangle owner, Rectangle available, boolean minimized) {
        Rectangle visibleOwner = owner.intersection(available);
        // A tiny or absent intersection is not a useful sizing or centering reference.
        Rectangle reference = minimized || visibleOwner.width < 640 || visibleOwner.height < 400
                ? available : visibleOwner;
        int minWidth = Math.min(640, available.width);
        int minHeight = Math.min(400, available.height);
        int width = Math.min(Math.max(minWidth, preferred.width),
                Math.max(minWidth, (int) (reference.width * 0.85)));
        int height = Math.min(Math.max(minHeight, preferred.height),
                Math.max(minHeight, (int) (reference.height * 0.85)));
        int x = Math.max(available.x, Math.min(reference.x + (reference.width - width) / 2,
                available.x + available.width - width));
        int y = Math.max(available.y, Math.min(reference.y + (reference.height - height) / 2,
                available.y + available.height - height));
        return new Rectangle(x, y, width, height);
    }

    @FunctionalInterface
    interface SettingsModelFactory {
        SettingsModel create() throws IOException;
    }

    static JCheckBox createSkipCheckbox(SettingsModelFactory modelFactory, Runnable reportFailure) {
        JCheckBox skip = new JCheckBox(JMeterUtils.getResString("welcome_skip"),
                !JMeterUtils.getPropDefault(SHOW_PROPERTY, true));
        skip.addActionListener(event -> {
            boolean selected = skip.isSelected();
            try {
                modelFactory.create().apply(SettingsGroup.Target.USER,
                        selected ? Map.of(SHOW_PROPERTY, "false") : Map.of(),
                        selected ? Set.of() : Set.of(SHOW_PROPERTY));
            } catch (IOException | RuntimeException ex) {
                skip.setSelected(!selected);
                LOG.warn("Unable to save welcome screen preference", ex);
                reportFailure.run();
            }
        });
        return skip;
    }

    private JButton actionButton(String label, String command) {
        JButton button = new JButton(JMeterUtils.getResString(label));
        button.addActionListener(event -> {
            dispose();
            ActionRouter.getInstance().actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, command));
        });
        return button;
    }
}
