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

package org.apache.jmeter.threads.gui;

import static org.apache.jmeter.util.JMeterUtils.labelFor;

import java.awt.BorderLayout;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.gui.LoopControlPanel;
import org.apache.jmeter.gui.JBooleanPropertyEditor;
import org.apache.jmeter.gui.JTextComponentBinding;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.util.InfoButton;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.AbstractThreadGroupSchema;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.ThreadGroupSchema;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.JEditableCheckBox;

import net.miginfocom.swing.MigLayout;

@TestElementMetadata(labelResource = "threadgroup")
public class ThreadGroupGui extends AbstractThreadGroupGui implements ItemListener {
    private static final long serialVersionUID = 240L;

    private LoopControlPanel loopPanel;

    private static final String THREAD_NAME = "Thread Field";

    private static final String RAMP_NAME = "Ramp Up Field";

    private final JTextField threadInput = new JTextField();

    private final JTextField rampInput = new JTextField();

    private final boolean showDelayedStart;

    private final JComboBox<String> pacingMode = new JComboBox<>(new String[] {
            AbstractThreadGroup.PACING_DISABLED,
            AbstractThreadGroup.PACING_FIXED,
            AbstractThreadGroup.PACING_RANDOM,
            AbstractThreadGroup.PACING_GAUSSIAN_RANDOM,
    });

    private final JTextField fixedPacing = new JTextField(12);

    private final JTextField pacingMin = new JTextField(12);

    private final JTextField pacingMax = new JTextField(12);

    private final JLabel fixedPacingLabel =
            new JLabel(JMeterUtils.getResString("thread_group_pacing_fixed"));

    private final JLabel pacingMinLabel =
            new JLabel(JMeterUtils.getResString("thread_group_pacing_min"));

    private final JLabel pacingMaxLabel =
            new JLabel(JMeterUtils.getResString("thread_group_pacing_max"));

    private final JLabel pacingRate = new JLabel();

    private final InfoButton pacingInfo = new InfoButton(
            JMeterUtils.getResString("thread_group_pacing_info_title"),
            JMeterUtils.getResString("thread_group_pacing_info"));

    private JPanel fixedPacingFieldPanel;

    private JPanel pacingMinFieldPanel;

    private JPanel pacingMaxFieldPanel;

    private JBooleanPropertyEditor delayedStart;

    private final JBooleanPropertyEditor scheduler =
            new JBooleanPropertyEditor(
                    ThreadGroupSchema.INSTANCE.getUseScheduler(),
                    "scheduler",
                    JMeterUtils::getResString);

    private final JTextField duration = new JTextField();
    private final JLabel durationLabel = labelFor(duration, "duration");

    private final JTextField delay = new JTextField(); // Relative start-up time
    private final JLabel delayLabel = labelFor(delay, "delay");

    private final JBooleanPropertyEditor sameUserBox =
            new JBooleanPropertyEditor(
                    AbstractThreadGroupSchema.INSTANCE.getSameUserOnNextIteration(),
                    "threadgroup_same_user",
                    JMeterUtils::getResString);

    public ThreadGroupGui() {
        this(true);
    }

    public ThreadGroupGui(boolean showDelayedStart) {
        super();
        this.showDelayedStart = showDelayedStart;
        init();
        initGui();
        if (showDelayedStart) {
            bindingGroup.add(delayedStart);
        }
        bindingGroup.addAll(
                Arrays.asList(
                        new JTextComponentBinding(threadInput, AbstractThreadGroupSchema.INSTANCE.getNumThreads()),
                        new JTextComponentBinding(rampInput, ThreadGroupSchema.INSTANCE.getRampTime()),
                        new JTextComponentBinding(duration, ThreadGroupSchema.INSTANCE.getDuration()),
                        new JTextComponentBinding(delay, ThreadGroupSchema.INSTANCE.getDelay()),
                        sameUserBox,
                        scheduler
                )
        );
    }

    @Override
    public TestElement makeTestElement() {
        return new ThreadGroup();
    }

    @Override
    public void assignDefaultValues(TestElement element) {
        super.assignDefaultValues(element);
        element.set(ThreadGroupSchema.INSTANCE.getNumThreads(), 1);
        element.set(ThreadGroupSchema.INSTANCE.getRampTime(), 1);
        if (showDelayedStart) {
            element.set(ThreadGroupSchema.INSTANCE.getDelayedStart(), true);
        }
        element.set(AbstractThreadGroupSchema.INSTANCE.getSameUserOnNextIteration(), true);
        ((AbstractThreadGroup) element).setSamplerController((LoopController) loopPanel.createTestElement());
    }

    /**
     * Modifies a given TestElement to mirror the data in the gui components.
     *
     * @see org.apache.jmeter.gui.JMeterGUIComponent#modifyTestElement(TestElement)
     */
    @Override
    public void modifyTestElement(TestElement tg) {
        super.modifyTestElement(tg);
        if (tg instanceof AbstractThreadGroup abstractThreadGroup) {
            abstractThreadGroup.setPacingMode((String) pacingMode.getSelectedItem());
            abstractThreadGroup.setFixedPacing(fixedPacing.getText());
            abstractThreadGroup.setPacingMin(pacingMin.getText());
            abstractThreadGroup.setPacingMax(pacingMax.getText());
            abstractThreadGroup.setSamplerController((LoopController) loopPanel.createTestElement());
        }
    }

    @Override
    public void configure(TestElement tg) {
        super.configure(tg);
        loopPanel.configure((TestElement) tg.getProperty(AbstractThreadGroup.MAIN_CONTROLLER).getObjectValue());
        if (tg instanceof AbstractThreadGroup abstractThreadGroup) {
            pacingMode.setSelectedItem(abstractThreadGroup.getPacingMode());
            fixedPacing.setText(abstractThreadGroup.getFixedPacing());
            pacingMin.setText(abstractThreadGroup.getPacingMin());
            pacingMax.setText(abstractThreadGroup.getPacingMax());
            updatePacingFields();
        }
        toggleSchedulerFields();
    }

    @Override
    public void itemStateChanged(ItemEvent ie) {
        // Method kept for backward compatibility
    }

    private void toggleSchedulerFields() {
        boolean enable = !scheduler.getValue().equals(JEditableCheckBox.Value.of(false));
        duration.setEnabled(enable);
        durationLabel.setEnabled(enable);
        delay.setEnabled(enable);
        delayLabel.setEnabled(enable);
    }

    private JPanel createControllerPanel() {
        loopPanel = new LoopControlPanel(false);
        LoopController looper = (LoopController) loopPanel.createTestElement();
        looper.setLoops(1);
        loopPanel.configure(looper);
        return loopPanel;
    }


    @Override
    public String getLabelResource() {
        return "threadgroup"; // $NON-NLS-1$
    }

    @Override
    public void clearGui(){
        super.clearGui();
        initGui();
    }

    // Initialise the gui field values
    private void initGui(){
        loopPanel.clearGui();
        pacingMode.setSelectedItem(AbstractThreadGroup.PACING_DISABLED);
        fixedPacing.setText("0"); // $NON-NLS-1$
        pacingMin.setText("0"); // $NON-NLS-1$
        pacingMax.setText("0"); // $NON-NLS-1$
        updatePacingFields();
    }

    private void init() { // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
        // THREAD PROPERTIES
        JPanel threadPropsPanel = new JPanel(new MigLayout("fillx, wrap 2, hidemode 3", "[][fill,grow]"));
        threadPropsPanel.setBorder(BorderFactory.createTitledBorder(
                JMeterUtils.getResString("thread_properties"))); // $NON-NLS-1$

        // NUMBER OF THREADS
        threadPropsPanel.add(labelFor(threadInput, "number_of_threads")); // $NON-NLS-1$
        threadInput.setName(THREAD_NAME);
        threadPropsPanel.add(threadInput);

        // RAMP-UP
        threadPropsPanel.add(labelFor(rampInput, "ramp_up"));
        rampInput.setName(RAMP_NAME);
        threadPropsPanel.add(rampInput);

        // LOOP COUNT
        LoopControlPanel loopController = (LoopControlPanel) createControllerPanel();
        threadPropsPanel.add(loopController.getLoopsLabel(), "split 2");
        threadPropsPanel.add(loopController.getInfinite(), "gapleft push");
        threadPropsPanel.add(loopController.getLoops());
        addPacingControls(threadPropsPanel);
        threadPropsPanel.add(sameUserBox, "span 2");
        if (showDelayedStart) {
            delayedStart = new JBooleanPropertyEditor(
                    ThreadGroupSchema.INSTANCE.getDelayedStart(),
                    "delayed_start",
                    JMeterUtils::getResString); // $NON-NLS-1$
            threadPropsPanel.add(delayedStart, "span 2");
        }
        scheduler.addPropertyChangeListener(
                JBooleanPropertyEditor.VALUE_PROPERTY, (ev) -> toggleSchedulerFields());

        threadPropsPanel.add(scheduler, "span 2");

        threadPropsPanel.add(durationLabel);
        threadPropsPanel.add(duration);
        threadPropsPanel.add(delayLabel);
        threadPropsPanel.add(delay);
        add(threadPropsPanel, BorderLayout.CENTER);
    }

    private void addPacingControls(JPanel threadPropsPanel) {
        JPanel pacingOptionPanel = new JPanel(new MigLayout("insets 0, fillx", "[][10][][6][]push"));
        pacingOptionPanel.add(pacingMode, "w pref!, growx 0");
        pacingOptionPanel.add(pacingRate);
        pacingOptionPanel.add(pacingInfo);
        pacingMode.addActionListener(e -> updatePacingFields());

        fixedPacingFieldPanel = createPacingFieldPanel(fixedPacing);
        pacingMinFieldPanel = createPacingFieldPanel(pacingMin);
        pacingMaxFieldPanel = createPacingFieldPanel(pacingMax);

        threadPropsPanel.add(new JLabel(JMeterUtils.getResString("thread_group_pacing_option")));
        threadPropsPanel.add(pacingOptionPanel, "growx");
        threadPropsPanel.add(fixedPacingLabel);
        threadPropsPanel.add(fixedPacingFieldPanel, "growx");
        threadPropsPanel.add(pacingMinLabel);
        threadPropsPanel.add(pacingMinFieldPanel, "growx");
        threadPropsPanel.add(pacingMaxLabel);
        threadPropsPanel.add(pacingMaxFieldPanel, "growx");

        addPacingDocumentListener(fixedPacing);
        addPacingDocumentListener(pacingMin);
        addPacingDocumentListener(pacingMax);
        updatePacingFields();
    }

    private static JPanel createPacingFieldPanel(JTextField field) {
        JPanel panel = new JPanel(new MigLayout("insets 0", "[pref!]"));
        panel.add(field, "w pref!, growx 0");
        return panel;
    }

    private void updatePacingFields() {
        String mode = (String) pacingMode.getSelectedItem();
        boolean fixed = AbstractThreadGroup.PACING_FIXED.equals(mode);
        boolean random = AbstractThreadGroup.PACING_RANDOM.equals(mode)
                || AbstractThreadGroup.PACING_GAUSSIAN_RANDOM.equals(mode);

        fixedPacingLabel.setVisible(fixed);
        fixedPacingFieldPanel.setVisible(fixed);
        pacingMinLabel.setVisible(random);
        pacingMinFieldPanel.setVisible(random);
        pacingMaxLabel.setVisible(random);
        pacingMaxFieldPanel.setVisible(random);
        pacingRate.setText(formatPacingRate(mode, fixedPacing.getText(), pacingMin.getText(), pacingMax.getText()));
        pacingRate.setVisible(!pacingRate.getText().isEmpty());
        pacingInfo.setVisible(!AbstractThreadGroup.PACING_DISABLED.equals(mode));
        revalidate();
        repaint();
    }

    private void addPacingDocumentListener(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updatePacingFields();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updatePacingFields();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updatePacingFields();
            }
        });
    }

    static String formatPacingRate(String mode, String fixedPacing, String pacingMin, String pacingMax) {
        Long pacing = switch (mode) {
            case AbstractThreadGroup.PACING_FIXED -> parsePositiveLong(fixedPacing);
            case AbstractThreadGroup.PACING_RANDOM, AbstractThreadGroup.PACING_GAUSSIAN_RANDOM ->
                    averagePacing(pacingMin, pacingMax);
            default -> null;
        };
        if (pacing == null || pacing <= 0) {
            return ""; // $NON-NLS-1$
        }
        double perMinute = 60_000d / pacing;
        String pattern = JMeterUtils.getResString("thread_group_pacing_rate");
        if (pattern.startsWith("[res_key=")) { // $NON-NLS-1$
            pattern = "{0} iterations/min"; // $NON-NLS-1$
        }
        return MessageFormat.format(pattern, formatPacingRateValue(perMinute));
    }

    private static String formatPacingRateValue(double perMinute) {
        return new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(perMinute);
    }

    private static Long averagePacing(String pacingMin, String pacingMax) {
        Long min = parsePositiveLong(pacingMin);
        Long max = parsePositiveLong(pacingMax);
        if (min == null || max == null || max < min) {
            return null;
        }
        return min + (max - min) / 2;
    }

    private static Long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
